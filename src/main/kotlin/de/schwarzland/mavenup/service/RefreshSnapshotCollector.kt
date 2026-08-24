package de.schwarzland.mavenup.service

import de.schwarzland.mavenup.ui.MANAGED_PLUGIN
import de.schwarzland.mavenup.ui.PARENT_TYPE
import de.schwarzland.mavenup.ui.RefreshRow
import de.schwarzland.mavenup.ui.RefreshSnapshot
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiManager
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import org.jetbrains.idea.maven.project.MavenProjectsManager

/**
 * Sammelt einen Schnappschuss der im Projekt deklarierten Maven-Abhängigkeiten, Plugins und
 * ihrer Versions-Properties aus den `pom.xml`-Dateien.
 *
 * Der Collector liest ausschließlich über PSI/das Maven-Modell und hält keinen veränderlichen
 * UI-Zustand; die Ergebnisse werden als [RefreshSnapshot] zurückgegeben.
 *
 * @property project Das Projekt, dessen Maven-Projekte ausgewertet werden.
 */
internal class RefreshSnapshotCollector(private val project: Project) {

    /**
     * Löst einen Versionswert auf, der als Maven-Property-Platzhalter der Form
     * `${property.name}` vorliegen kann. Wird häufig für Einträge im
     * `dependencyManagement` benötigt, deren Version über eine Property (z. B.
     * `${netty-bom.version}`) definiert ist und die nicht im aufgelösten
     * Abhängigkeitsbaum auftauchen.
     *
     * Ist [value] kein Platzhalter oder die Property in [properties] nicht (mit
     * nicht-leerem Wert) vorhanden, wird [value] unverändert zurückgegeben.
     *
     * @param value Der möglicherweise als Platzhalter vorliegende Versionswert.
     * @param properties Die effektiven Maven-Properties (Property-Name -> Wert).
     * @return Die aufgelöste Version oder der ursprüngliche Wert.
     */
    internal fun resolveVersionPlaceholder(value: String, properties: Map<String, String>): String {
        if (!value.startsWith("\${") || !value.endsWith("}")) return value
        val propertyName = value.substring(2, value.length - 1)
        return properties[propertyName]?.takeIf { it.isNotBlank() } ?: value
    }

    /**
     * Durchsucht die XML-Tags nach Abhängigkeiten und extrahiert deren Koordinaten sowie
     * mögliche Platzhalter (Properties).
     *
     * @param parentTag Das Tag, unterhalb dessen gesucht wird (oder dessen Wrapper).
     * @param wrapperTagName Name des umschließenden Tags (z. B. `dependencies`).
     * @param itemTagName Name der Einzel-Tags (z. B. `dependency`).
     * @param targetMap Ziel-Map für Koordinate (`groupId:artifactId`) -> Versionstext.
     * @param propertyTargetMap Ziel-Map für erkannte Maven-Property-Platzhalter.
     */
    internal fun collectDependenciesAndProperties(
        parentTag: XmlTag?,
        wrapperTagName: String,
        itemTagName: String,
        targetMap: MutableMap<String, String>,
        propertyTargetMap: MutableMap<String, String>
    ) {
        val wrapperTag = parentTag?.findFirstSubTag(wrapperTagName) ?: parentTag
        wrapperTag?.findSubTags(itemTagName)?.forEach { tag ->
            val g = tag.findFirstSubTag("groupId")?.value?.text?.trim().orEmpty()
            if (g.isEmpty()) return@forEach
            val a = tag.findFirstSubTag("artifactId")?.value?.text ?: ""
            val v = tag.findFirstSubTag("version")?.value?.text ?: ""
            val key = "$g:$a"
            targetMap[key] = v

            val versionText = tag.findFirstSubTag("version")?.value?.trimmedText
            if (versionText != null && versionText.startsWith("\${") && versionText.endsWith("}")) {
                propertyTargetMap[key] = versionText.substring(2, versionText.length - 1)
            }
        }
    }

    /**
     * Liest die Parent-Dependency aus dem `<parent>`-Tag der `pom.xml` und erzeugt eine [RefreshRow].
     * Gibt `null` zurück, wenn kein Parent-Tag vorhanden ist oder groupId/artifactId leer sind.
     *
     * @param rootTag Das Root-Tag der `pom.xml`.
     * @param propertyTargetMap Ziel-Map für erkannte Maven-Property-Platzhalter.
     * @return Eine [RefreshRow] mit Typ [PARENT_TYPE] oder `null`.
     */
    internal fun collectParentDependency(
        rootTag: XmlTag?,
        propertyTargetMap: MutableMap<String, String>
    ): RefreshRow? {
        val parentTag = rootTag?.findFirstSubTag("parent") ?: return null
        val g = parentTag.findFirstSubTag("groupId")?.value?.text?.trim().orEmpty()
        if (g.isEmpty()) return null
        val a = parentTag.findFirstSubTag("artifactId")?.value?.text?.trim().orEmpty()
        if (a.isEmpty()) return null
        val v = parentTag.findFirstSubTag("version")?.value?.text?.trim().orEmpty()
        val key = "$g:$a"

        val versionText = parentTag.findFirstSubTag("version")?.value?.trimmedText
        var propertyName = ""
        if (versionText != null && versionText.startsWith("\${") && versionText.endsWith("}")) {
            propertyName = versionText.substring(2, versionText.length - 1)
            propertyTargetMap[key] = propertyName
        }

        return RefreshRow(
            groupId = g,
            artifactId = a,
            propertyName = propertyName,
            type = PARENT_TYPE,
            currentVersion = v
        )
    }

    /**
     * Erstellt einen Schnappschuss der aktuellen Abhängigkeiten im Projekt.
     * Muss außerhalb des Event Dispatch Threads aufgerufen werden.
     *
     * @param managedDependencyType Der anzuzeigende Typ für verwaltete Abhängigkeiten.
     * @return Der zusammengestellte [RefreshSnapshot].
     */
    internal fun collectRefreshSnapshot(managedDependencyType: String): RefreshSnapshot {
        check(!ApplicationManager.getApplication().isDispatchThread) {
            "Refresh data must not be collected on the Event Dispatch Thread"
        }

        val rows = mutableListOf<RefreshRow>()
        val properties = mutableMapOf<String, String>()
        MavenProjectsManager.getInstance(project).projects.forEach { mavenProject ->
            val psiFile = PsiManager.getInstance(project).findFile(mavenProject.file) as? XmlFile
            val localDependencies = mutableMapOf<String, String>()
            val managedDependencies = mutableMapOf<String, String>()
            val localPlugins = mutableMapOf<String, String>()
            val managedPlugins = mutableMapOf<String, String>()

            if (psiFile != null) {
                val documentElement = psiFile.document?.rootTag

                // Parent-Dependency erfassen
                collectParentDependency(documentElement, properties)?.let { parentRow ->
                    rows.add(parentRow)
                }

                collectDependenciesAndProperties(
                    documentElement,
                    "dependencies",
                    "dependency",
                    localDependencies,
                    properties
                )
                val dependencyManagement = documentElement?.findFirstSubTag("dependencyManagement")
                collectDependenciesAndProperties(
                    dependencyManagement,
                    "dependencies",
                    "dependency",
                    managedDependencies,
                    properties
                )
                val buildTag = documentElement?.findFirstSubTag("build")
                collectDependenciesAndProperties(buildTag, "plugins", "plugin", localPlugins, properties)
                val pluginManagement = buildTag?.findFirstSubTag("pluginManagement")
                collectDependenciesAndProperties(
                    pluginManagement,
                    "plugins",
                    "plugin",
                    managedPlugins,
                    properties
                )
            }

            val effectiveProperties =
                mavenProject.properties.entries.associate { (k, v) -> k.toString() to v.toString() }
            val resolvedDependencies =
                mavenProject.dependencyTree.associateBy { "${it.artifact.groupId}:${it.artifact.artifactId}" }
            val seenLocalDependencyKeys = mutableSetOf<String>()
            val seenManagedDependencyKeys = mutableSetOf<String>()
            localDependencies.forEach { (key, value) ->
                if (!seenLocalDependencyKeys.add(key)) return@forEach
                rows.add(
                    RefreshRow(
                        groupId = key.substringBefore(":"),
                        artifactId = key.substringAfter(":"),
                        propertyName = properties[key].orEmpty(),
                        type = "dependency",
                        currentVersion = resolvedDependencies[key]?.artifact?.version
                            ?: resolveVersionPlaceholder(value, effectiveProperties)
                    )
                )
            }
            managedDependencies.forEach { (key, value) ->
                if (!seenManagedDependencyKeys.add(key)) return@forEach
                rows.add(
                    RefreshRow(
                        groupId = key.substringBefore(":"),
                        artifactId = key.substringAfter(":"),
                        propertyName = properties[key].orEmpty(),
                        type = managedDependencyType,
                        currentVersion = resolvedDependencies[key]?.artifact?.version
                            ?: resolveVersionPlaceholder(value, effectiveProperties)
                    )
                )
            }

            val resolvedPlugins = mavenProject.plugins.associateBy { "${it.groupId}:${it.artifactId}" }
            val seenLocalPluginKeys = mutableSetOf<String>()
            val seenManagedPluginKeys = mutableSetOf<String>()
            localPlugins.forEach { (key, value) ->
                if (!seenLocalPluginKeys.add(key)) return@forEach
                rows.add(
                    RefreshRow(
                        groupId = key.substringBefore(":"),
                        artifactId = key.substringAfter(":"),
                        propertyName = properties[key].orEmpty(),
                        type = "plugin",
                        currentVersion = resolvedPlugins[key]?.version
                            ?: resolveVersionPlaceholder(value, effectiveProperties)
                    )
                )
            }
            managedPlugins.forEach { (key, value) ->
                if (!seenManagedPluginKeys.add(key)) return@forEach
                rows.add(
                    RefreshRow(
                        groupId = key.substringBefore(":"),
                        artifactId = key.substringAfter(":"),
                        propertyName = properties[key].orEmpty(),
                        type = MANAGED_PLUGIN,
                        currentVersion = resolvedPlugins[key]?.version
                            ?: resolveVersionPlaceholder(value, effectiveProperties)
                    )
                )
            }
        }

        return RefreshSnapshot(rows, properties)
    }
}
