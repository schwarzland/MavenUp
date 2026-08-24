package de.schwarzland.mavenup.service

import de.schwarzland.mavenup.ui.chooseAutoSelectedVersion
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiManager
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import org.apache.maven.artifact.versioning.ComparableVersion
import org.jetbrains.idea.maven.project.MavenProject
import org.jetbrains.idea.maven.project.MavenProjectsManager

/**
 * Ergebnis einer Versionssuche: die verfügbaren Versionen je Abhängigkeit und die daraus
 * abgeleitete Vorauswahl.
 *
 * @property availableVersions Zuordnung von Abhängigkeitsschlüssel (`groupId:artifactId`) zu den verfügbaren Versionen.
 * @property selectedVersions Zuordnung von Abhängigkeitsschlüssel zur vorausgewählten Zielversion.
 */
internal data class VersionSearchResult(
    val availableVersions: Map<String, List<String>>,
    val selectedVersions: Map<String, String>
)

/**
 * Ermittelt die verfügbaren Versionen aller im Projekt deklarierten Abhängigkeiten und Plugins und
 * leitet daraus – abhängig von der konfigurierten Auto-Selektionsstrategie – eine Vorauswahl ab.
 *
 * Der Service hält keinen veränderlichen UI-Zustand; sämtliche Ergebnisse werden als
 * [VersionSearchResult] zurückgegeben.
 *
 * @property project Das Projekt, dessen Maven-Modell und `pom.xml`-Dateien ausgewertet werden.
 * @property fetchVersions Ruft die verfügbaren Versionen eines Artefakts ab (injizierbar für Tests).
 * @property refreshSnapshotCollector Löst Property-Platzhalter in `pom.xml`-Versionen auf (injizierbar für Tests).
 */
internal class DependencyVersionService(
    private val project: Project,
    private val fetchVersions: (groupId: String, artifactId: String, currentVersion: String) -> List<String> =
        DependencyApiService(project)::fetchVersions,
    private val refreshSnapshotCollector: RefreshSnapshotCollector = RefreshSnapshotCollector(project)
) {

    /**
     * Führt die Versionssuche für alle Maven-Projekte durch.
     *
     * @param currentVersions Zuordnung von Abhängigkeitsschlüssel zur aktuell verwendeten Version.
     * @param dependencyToProperty Zuordnung von Abhängigkeitsschlüssel zum Namen der genutzten Version-Property.
     * @param indicator Der Fortschrittsindikator der laufenden Hintergrundaufgabe.
     * @return Das [VersionSearchResult] mit verfügbaren Versionen und Vorauswahl.
     */
    internal fun searchVersions(
        currentVersions: Map<String, String>,
        dependencyToProperty: Map<String, String>,
        indicator: ProgressIndicator
    ): VersionSearchResult {
        val availableVersions = mutableMapOf<String, List<String>>()
        val selectedVersions = mutableMapOf<String, String>()

        MavenProjectsManager.getInstance(project).projects.forEach { mavenProject ->
            processProjectUpdates(mavenProject, indicator, availableVersions, selectedVersions)
        }

        postProcessPropertyUpdates(dependencyToProperty, currentVersions, availableVersions, selectedVersions)

        return VersionSearchResult(availableVersions, selectedVersions)
    }

    /**
     * Verarbeitet alle Abhängigkeiten und Plugins eines einzelnen Maven-Projekts
     * und fragt deren verfügbare Updates ab.
     */
    private fun processProjectUpdates(
        mavenProject: MavenProject,
        indicator: ProgressIndicator,
        availableVersions: MutableMap<String, List<String>>,
        selectedVersions: MutableMap<String, String>
    ) {
        val allKeysWithVersions = mutableMapOf<String, String>()

        // Collect from dependency tree
        mavenProject.dependencyTree.forEach { node ->
            val dep = node.artifact
            allKeysWithVersions["${dep.groupId}:${dep.artifactId}"] = dep.version ?: ""
        }

        // Collect from plugins
        mavenProject.plugins.forEach { plugin ->
            allKeysWithVersions["${plugin.groupId}:${plugin.artifactId}"] = plugin.version ?: ""
        }

        // Collect from PSI for managed or unused dependencies/plugins
        collectFromPsi(mavenProject, allKeysWithVersions)

        allKeysWithVersions.forEach { (key, version) ->
            if (indicator.isCanceled) return
            val groupId = key.substringBefore(":")
            val artifactId = key.substringAfter(":")
            checkArtifactUpdate(groupId, artifactId, version, indicator, availableVersions, selectedVersions)
        }
    }

    /**
     * Liest Abhängigkeiten und Plugins direkt aus der PSI-Struktur der `pom.xml`,
     * um auch nicht aufgelöste oder verwaltete Einträge zu erfassen.
     */
    private fun collectFromPsi(mavenProject: MavenProject, allKeysWithVersions: MutableMap<String, String>) {
        val psiFile = ApplicationManager.getApplication().runReadAction<XmlFile?> {
            PsiManager.getInstance(project).findFile(mavenProject.file) as? XmlFile
        } ?: return

        val effectiveProperties =
            mavenProject.properties.entries.associate { (k, v) -> k.toString() to v.toString() }

        ApplicationManager.getApplication().runReadAction {
            val rootTag = psiFile.document?.rootTag ?: return@runReadAction

            // parent
            val parentTag = rootTag.findFirstSubTag("parent")
            if (parentTag != null) {
                val g = parentTag.findFirstSubTag("groupId")?.value?.text ?: ""
                val a = parentTag.findFirstSubTag("artifactId")?.value?.text ?: ""
                val v = parentTag.findFirstSubTag("version")?.value?.text ?: ""
                if (g.isNotEmpty() && a.isNotEmpty() && !allKeysWithVersions.containsKey("$g:$a")) {
                    allKeysWithVersions["$g:$a"] = refreshSnapshotCollector.resolveVersionPlaceholder(v, effectiveProperties)
                }
            }

            // dependencies
            collectTags(rootTag.findFirstSubTag("dependencies"), "dependency", allKeysWithVersions, effectiveProperties)

            // dependencyManagement
            val dmTag = rootTag.findFirstSubTag("dependencyManagement")
            collectTags(dmTag?.findFirstSubTag("dependencies"), "dependency", allKeysWithVersions, effectiveProperties)

            // build/plugins
            val buildTag = rootTag.findFirstSubTag("build")
            collectTags(buildTag?.findFirstSubTag("plugins"), "plugin", allKeysWithVersions, effectiveProperties)

            // build/pluginManagement
            val pmTag = buildTag?.findFirstSubTag("pluginManagement")
            collectTags(pmTag?.findFirstSubTag("plugins"), "plugin", allKeysWithVersions, effectiveProperties)
        }
    }

    /**
     * Extrahiert groupId, artifactId und Version aus den Kind-Tags eines XML-Parent-Tags
     * und fügt neue Einträge der Ziel-Map hinzu (bereits vorhandene Schlüssel werden nicht überschrieben).
     *
     * Property-basierte Versionen (z. B. `${netty-bom.version}`) werden über [effectiveProperties]
     * aufgelöst, damit der anschließende Update-Check gegen die tatsächliche Version filtert und
     * nicht gegen den rohen Platzhalter.
     */
    private fun collectTags(
        parentTag: XmlTag?,
        tagName: String,
        allKeysWithVersions: MutableMap<String, String>,
        effectiveProperties: Map<String, String>
    ) {
        parentTag?.findSubTags(tagName)?.forEach { tag ->
            val g = tag.findFirstSubTag("groupId")?.value?.text ?: ""
            val a = tag.findFirstSubTag("artifactId")?.value?.text ?: ""
            val v = tag.findFirstSubTag("version")?.value?.text ?: ""
            if (g.isNotEmpty() && a.isNotEmpty() && !allKeysWithVersions.containsKey("$g:$a")) {
                allKeysWithVersions["$g:$a"] = refreshSnapshotCollector.resolveVersionPlaceholder(v, effectiveProperties)
            }
        }
    }

    /**
     * Berechnet für alle Abhängigkeiten, die dieselbe Maven-Property verwenden,
     * die Schnittmenge der verfügbaren Versionen, damit die Property konsistent aktualisiert wird.
     */
    internal fun postProcessPropertyUpdates(
        dependencyToProperty: Map<String, String>,
        currentVersions: Map<String, String>,
        availableVersions: MutableMap<String, List<String>>,
        selectedVersions: MutableMap<String, String>
    ) {
        val propertyToDependencies: Map<String, List<String>> = dependencyToProperty
            .entries
            .groupBy({ it.value }, { it.key })

        propertyToDependencies.forEach { (_, depKeys) ->
            if (depKeys.size > 1) {
                intersectVersions(depKeys, currentVersions, availableVersions, selectedVersions)
            }
        }
    }

    /**
     * Reduziert die verfügbaren Versionen für eine Gruppe von Abhängigkeiten auf deren gemeinsame
     * Schnittmenge und wählt bei aktivierter Einstellung automatisch die konfigurierte Zielversion vor.
     */
    internal fun intersectVersions(
        depKeys: List<String>,
        currentVersions: Map<String, String>,
        availableVersions: MutableMap<String, List<String>>,
        selectedVersions: MutableMap<String, String>
    ) {
        var commonVersions: List<String>? = null
        depKeys.forEach { depKey ->
            val versions = availableVersions[depKey] ?: emptyList()
            commonVersions = if (commonVersions == null) {
                versions
            } else {
                commonVersions.intersect(versions.toSet()).toList()
            }
        }

        val sortedCommonVersions = commonVersions?.sortedWith { v1, v2 ->
            ComparableVersion(v2).compareTo(ComparableVersion(v1))
        } ?: emptyList()

        depKeys.forEach { depKey ->
            availableVersions[depKey] = sortedCommonVersions
            if (sortedCommonVersions.isNotEmpty() &&
                MavenUpSettings.getInstance().state.versionAutoSelectionMode != VersionAutoSelectionMode.DISABLED
            ) {
                val currentVersion = currentVersions[depKey] ?: ""
                val autoSelectedVersion = chooseAutoSelectedVersion(
                    currentVersion,
                    sortedCommonVersions,
                    MavenUpSettings.getInstance().state.versionAutoSelectionMode
                )
                if (autoSelectedVersion != currentVersion) {
                    selectedVersions[depKey] = autoSelectedVersion
                } else {
                    selectedVersions.remove(depKey)
                }
            } else if (sortedCommonVersions.isNotEmpty()) {
                selectedVersions[depKey] = currentVersions[depKey] ?: ""
            }
        }
    }

    /**
     * Ruft die verfügbaren Versionen für ein einzelnes Artefakt ab und speichert
     * die Ergebnisse in [availableVersions] sowie die Vorauswahl in [selectedVersions].
     */
    internal fun checkArtifactUpdate(
        groupId: String?,
        artifactId: String?,
        currentVersion: String?,
        indicator: ProgressIndicator,
        availableVersions: MutableMap<String, List<String>>,
        selectedVersions: MutableMap<String, String>
    ) {
        indicator.text2 = "$groupId:$artifactId"

        if (groupId != null && artifactId != null) {
            val version = currentVersion ?: ""
            val versions = fetchVersions(groupId, artifactId, version)
            if (versions.isNotEmpty()) {
                val key = "$groupId:$artifactId"
                availableVersions[key] = versions
                if (MavenUpSettings.getInstance().state.versionAutoSelectionMode != VersionAutoSelectionMode.DISABLED) {
                    val autoSelectedVersion = chooseAutoSelectedVersion(
                        version,
                        versions,
                        MavenUpSettings.getInstance().state.versionAutoSelectionMode
                    )
                    if (autoSelectedVersion != version) {
                        selectedVersions[key] = autoSelectedVersion
                    } else {
                        selectedVersions.remove(key)
                    }
                } else {
                    selectedVersions[key] = version
                }
            }
        }
    }
}
