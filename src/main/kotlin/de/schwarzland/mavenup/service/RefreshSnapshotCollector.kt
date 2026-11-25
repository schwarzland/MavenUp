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
import com.intellij.openapi.vfs.VirtualFile
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
     * Bestimmt die angezeigte und für den Update-Check verwendete Version.
     *
     * Deklarierte `*-SNAPSHOT`-Versionen haben Vorrang vor der von Maven aufgelösten Version,
     * damit sie nicht durch zeitgestempelte Snapshot-Versionen ersetzt werden. Für andere
     * Versionen wird die Maven-Auflösung bevorzugt, etwa bei Versionsbereichen.
     *
     * @param declaredVersion Die in der `pom.xml` deklarierte Version, ggf. als Property.
     * @param resolvedVersion Die vom Maven-Modell aufgelöste Version oder `null`.
     * @param effectiveProperties Die effektiven Maven-Properties zur Platzhalterauflösung.
     * @return Die deklarierte Snapshot-Version oder die Maven-Modellversion, sofern vorhanden.
     */
    internal fun resolveCurrentVersion(
        declaredVersion: String,
        resolvedVersion: String?,
        effectiveProperties: Map<String, String>
    ): String {
        val declaration = declaredVersion.trim()
        if (declaration.isEmpty()) return resolvedVersion.orEmpty()

        val resolvedDeclaration = resolveVersionPlaceholder(declaration, effectiveProperties)
        return resolvedDeclaration.takeIf { it.endsWith("-SNAPSHOT", ignoreCase = true) }
            ?: resolvedVersion
            ?: resolvedDeclaration
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
     * Erstellt Tabellenzeilen aus genau einem Maven-Deklarationskontext.
     *
     * Die Property-Zuordnung stammt ausschließlich aus [propertyNames] dieses Kontexts; gleichlautende
     * Koordinaten in anderen Abschnitten oder POM-Dateien beeinflussen die Zeile nicht.
     *
     * @param declarations Deklarierte Versionen nach Koordinate.
     * @param propertyNames Property-Namen nach Koordinate aus demselben Deklarationskontext.
     * @param type Anzuzeigender Eintragstyp.
     * @param effectiveProperties Effektive Maven-Properties zur Auflösung von Platzhaltern.
     * @param resolvedVersions Von Maven aufgelöste Versionen nach Koordinate.
     * @param pomFile Die zugehörige `pom.xml`-Datei, oder `null`.
     * @param moduleName Name des Maven-Moduls.
     * @return Die erzeugten Tabellenzeilen.
     */
    internal fun createRefreshRows(
        declarations: Map<String, String>,
        propertyNames: Map<String, String>,
        type: String,
        effectiveProperties: Map<String, String>,
        resolvedVersions: Map<String, String?> = emptyMap(),
        pomFile: VirtualFile? = null,
        moduleName: String = ""
    ): List<RefreshRow> = declarations.map { (key, value) ->
        RefreshRow(
            groupId = key.substringBefore(":"),
            artifactId = key.substringAfter(":"),
            propertyName = propertyNames[key].orEmpty(),
            type = type,
            currentVersion = resolveCurrentVersion(value, resolvedVersions[key], effectiveProperties),
            versionInherited = value.isBlank(),
            pomFile = pomFile,
            moduleName = moduleName
        )
    }

    /**
     * Liefert nur eindeutige Property-Zuordnungen für Koordinaten, deren jede erfasste Zeile dieselbe
     * nicht-leere Property verwendet.
     *
     * @param rows Erfasste Zeilen aus allen Maven-Deklarationskontexten.
     * @return Eindeutige Koordinate-zu-Property-Zuordnungen.
     */
    internal fun collectPropertyMappings(rows: List<RefreshRow>): Map<String, String> =
        rows.groupBy { it.key }.mapNotNull { (key, matchingRows) ->
            val propertyNames = matchingRows.map { it.propertyName }
            propertyNames.distinct().singleOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let { key to it }
        }.toMap()

    /**
     * Liest die Parent-Dependency aus dem `<parent>`-Tag der `pom.xml` und erzeugt eine [RefreshRow].
     * Gibt `null` zurück, wenn kein Parent-Tag vorhanden ist oder groupId/artifactId leer sind.
     *
     * Ist die Parent-Version als Maven-Property-Platzhalter (`${property}`) definiert, wird sie – analog
     * zu den übrigen Abhängigkeiten – über [effectiveProperties] zu einer konkreten Version aufgelöst.
     * Andernfalls würde der unaufgelöste Platzhalter in den Vulnerability-Scan und die Anzeige gelangen und
     * dort niemals gegen eine reale Koordinate matchen.
     *
     * @param rootTag Das Root-Tag der `pom.xml`.
     * @param propertyTargetMap Ziel-Map für erkannte Maven-Property-Platzhalter.
     * @param effectiveProperties Die effektiven Maven-Properties zur Auflösung von Versions-Platzhaltern.
     * @param pomFile Die zugehörige `pom.xml`-Datei (`VirtualFile`), oder `null`.
     * @param moduleName Der Name des Maven-Moduls, oder ein leerer String.
     * @return Eine [RefreshRow] mit Typ [PARENT_TYPE] oder `null`.
     */
    internal fun collectParentDependency(
        rootTag: XmlTag?,
        propertyTargetMap: MutableMap<String, String>,
        effectiveProperties: Map<String, String> = emptyMap(),
        pomFile: VirtualFile? = null,
        moduleName: String = ""
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
            currentVersion = resolveVersionPlaceholder(v, effectiveProperties),
            versionInherited = v.isBlank(),
            pomFile = pomFile,
            moduleName = moduleName
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
        MavenProjectsManager.getInstance(project).projects.forEach { mavenProject ->
            val psiFile = PsiManager.getInstance(project).findFile(mavenProject.file) as? XmlFile
            val localDependencies = mutableMapOf<String, String>()
            val managedDependencies = mutableMapOf<String, String>()
            val localPlugins = mutableMapOf<String, String>()
            val managedPlugins = mutableMapOf<String, String>()
            val parentProperties = mutableMapOf<String, String>()
            val localDependencyProperties = mutableMapOf<String, String>()
            val managedDependencyProperties = mutableMapOf<String, String>()
            val localPluginProperties = mutableMapOf<String, String>()
            val managedPluginProperties = mutableMapOf<String, String>()

            val moduleName = mavenProject.displayName.takeIf { it.isNotBlank() }
                ?: mavenProject.mavenId.artifactId.orEmpty()

            val effectiveProperties =
                mavenProject.properties.entries.associate { (k, v) -> k.toString() to v.toString() }

            if (psiFile != null) {
                val documentElement = psiFile.document?.rootTag

                // Parent-Dependency erfassen
                collectParentDependency(documentElement, parentProperties, effectiveProperties, mavenProject.file, moduleName)?.let { parentRow ->
                    rows.add(parentRow)
                }

                collectDependenciesAndProperties(
                    documentElement,
                    "dependencies",
                    "dependency",
                    localDependencies,
                    localDependencyProperties
                )
                val dependencyManagement = documentElement?.findFirstSubTag("dependencyManagement")
                collectDependenciesAndProperties(
                    dependencyManagement,
                    "dependencies",
                    "dependency",
                    managedDependencies,
                    managedDependencyProperties
                )
                val buildTag = documentElement?.findFirstSubTag("build")
                collectDependenciesAndProperties(buildTag, "plugins", "plugin", localPlugins, localPluginProperties)
                val pluginManagement = buildTag?.findFirstSubTag("pluginManagement")
                collectDependenciesAndProperties(
                    pluginManagement,
                    "plugins",
                    "plugin",
                    managedPlugins,
                    managedPluginProperties
                )
            }

            val resolvedDependencies =
                mavenProject.dependencyTree.associateBy { "${it.artifact.groupId}:${it.artifact.artifactId}" }
            val resolvedDependencyVersions = resolvedDependencies.mapValues { it.value.artifact.version }
            rows.addAll(
                createRefreshRows(
                    localDependencies,
                    localDependencyProperties,
                    "dependency",
                    effectiveProperties,
                    resolvedDependencyVersions,
                    mavenProject.file,
                    moduleName
                )
            )
            rows.addAll(
                createRefreshRows(
                    managedDependencies,
                    managedDependencyProperties,
                    managedDependencyType,
                    effectiveProperties,
                    resolvedDependencyVersions,
                    mavenProject.file,
                    moduleName
                )
            )

            val resolvedPlugins = mavenProject.plugins.associateBy { "${it.groupId}:${it.artifactId}" }
            val resolvedPluginVersions = resolvedPlugins.mapValues { it.value.version }
            rows.addAll(
                createRefreshRows(
                    localPlugins,
                    localPluginProperties,
                    "plugin",
                    effectiveProperties,
                    resolvedPluginVersions,
                    mavenProject.file,
                    moduleName
                )
            )
            rows.addAll(
                createRefreshRows(
                    managedPlugins,
                    managedPluginProperties,
                    MANAGED_PLUGIN,
                    effectiveProperties,
                    resolvedPluginVersions,
                    mavenProject.file,
                    moduleName
                )
            )
        }

        return RefreshSnapshot(rows, collectPropertyMappings(rows))
    }
}
