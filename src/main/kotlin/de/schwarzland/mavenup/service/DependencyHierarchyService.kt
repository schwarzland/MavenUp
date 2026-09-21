package de.schwarzland.mavenup.service

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import de.schwarzland.mavenup.model.DependencyHierarchyNode
import de.schwarzland.mavenup.model.DependencyHierarchyNodeType
import org.jetbrains.idea.maven.model.MavenArtifactNode
import org.jetbrains.idea.maven.project.MavenProject
import org.jetbrains.idea.maven.project.MavenProjectsManager

/**
 * Ermittelt die Einbindungs- und Management-Hierarchie für Managed Dependencies und Managed Plugins.
 *
 * Analysiert die Maven-Projekte im Workspace, deren `<parent>`-Hierarchie, `<dependencyManagement>`-
 * bzw. `<pluginManagement>`-Deklarationen, BOM-Imports und den aufgelösten Abhängigkeitsbaum,
 * um alle Pfade darzustellen, über die ein Artefakt eingebunden oder verwaltet wird.
 *
 * @property project Das IntelliJ-Projekt, dessen Maven-Modell ausgewertet wird.
 */
class DependencyHierarchyService(private val project: Project) {

    /**
     * Erstellt den vollständigen Hierarchie-Baum für die angegebene Koordinate.
     *
     * @param targetGroupId Die Group-ID der Managed Dependency oder des Plugins.
     * @param targetArtifactId Die Artefakt-ID der Managed Dependency oder des Plugins.
     * @param isPlugin `true`, wenn es sich um ein Plugin aus `<pluginManagement>` handelt.
     * @return Der Wurzelknoten der Hierarchie ([DependencyHierarchyNode]).
     */
    fun buildHierarchy(
        targetGroupId: String,
        targetArtifactId: String,
        isPlugin: Boolean = false
    ): DependencyHierarchyNode {
        val rootNode = DependencyHierarchyNode(
            type = DependencyHierarchyNodeType.ROOT,
            groupId = targetGroupId,
            artifactId = targetArtifactId,
            isManaged = true
        )

        val mavenProjects = MavenProjectsManager.getInstance(project).projects.toList()
        for (mavenProject in mavenProjects) {
            val projectNode = buildProjectHierarchy(mavenProject, targetGroupId, targetArtifactId, isPlugin)
            if (projectNode.children.isNotEmpty()) {
                rootNode.children.add(projectNode)
            }
        }

        return rootNode
    }

    /**
     * Ermittelt die Hierarchie-Knoten für ein einzelnes Maven-Projekt.
     *
     * @param mavenProject Das zu untersuchende Maven-Projekt.
     * @param targetGroupId Die gesuchte Group-ID.
     * @param targetArtifactId Die gesuchte Artefakt-ID.
     * @param isPlugin `true` für Plugins, `false` für Dependencies.
     * @return Der Projekt-Hierarchieknoten mit allen gefundenen Unterknoten.
     */
    internal fun buildProjectHierarchy(
        mavenProject: MavenProject,
        targetGroupId: String,
        targetArtifactId: String,
        isPlugin: Boolean
    ): DependencyHierarchyNode {
        val projectNode = DependencyHierarchyNode(
            type = DependencyHierarchyNodeType.PROJECT,
            groupId = mavenProject.mavenId.groupId ?: "",
            artifactId = mavenProject.mavenId.artifactId ?: mavenProject.displayName,
            version = mavenProject.mavenId.version,
            pomFile = mavenProject.file
        )

        val effectiveProperties =
            mavenProject.properties.entries.associate { (k, v) -> k.toString() to v.toString() }

        val psiFile = ApplicationManager.getApplication().runReadAction<XmlFile?> {
            PsiManager.getInstance(project).findFile(mavenProject.file) as? XmlFile
        }

        var rootTag: XmlTag? = null
        if (psiFile != null) {
            ApplicationManager.getApplication().runReadAction {
                rootTag = psiFile.document?.rootTag

                if (isPlugin) {
                    collectPluginManagementAndDirect(
                        rootTag,
                        mavenProject.file,
                        targetGroupId,
                        targetArtifactId,
                        effectiveProperties,
                        projectNode
                    )
                } else {
                    collectDependencyManagementAndDirect(
                        rootTag,
                        mavenProject.file,
                        targetGroupId,
                        targetArtifactId,
                        effectiveProperties,
                        projectNode
                    )
                }
            }
        }

        if (!isPlugin) {
            ApplicationManager.getApplication().runReadAction {
                collectTransitiveDependencyPaths(
                    mavenProject,
                    targetGroupId,
                    targetArtifactId,
                    projectNode,
                    rootTag
                )
            }
        }

        return projectNode
    }

    /**
     * Sammelt Dependency-Management, Parent-POM, BOM-Imports und direkte Deklarationen aus dem XML.
     *
     * @param rootTag Das Root-Tag der `pom.xml`.
     * @param pomFile Die VirtualFile der `pom.xml`.
     * @param targetGroupId Die gesuchte Group-ID.
     * @param targetArtifactId Die gesuchte Artefakt-ID.
     * @param effectiveProperties Die effektiven Maven-Properties zur Auflösung von Platzhaltern.
     * @param projectNode Der übergeordnete Projekt-Knoten.
     */
    internal fun collectDependencyManagementAndDirect(
        rootTag: XmlTag?,
        pomFile: VirtualFile,
        targetGroupId: String,
        targetArtifactId: String,
        effectiveProperties: Map<String, String>,
        projectNode: DependencyHierarchyNode
    ) {
        if (rootTag == null) return

        collectParentPomNode(rootTag, pomFile, effectiveProperties, projectNode)
        collectDependencyManagementNodes(rootTag, pomFile, targetGroupId, targetArtifactId, effectiveProperties, projectNode)
        collectDirectDependencyNodes(rootTag, pomFile, targetGroupId, targetArtifactId, effectiveProperties, projectNode)
    }

    private fun collectParentPomNode(
        rootTag: XmlTag,
        pomFile: VirtualFile,
        effectiveProperties: Map<String, String>,
        projectNode: DependencyHierarchyNode
    ) {
        val parentTag = rootTag.findFirstSubTag("parent") ?: return
        val g = parentTag.findFirstSubTag("groupId")?.value?.text?.trim().orEmpty()
        val a = parentTag.findFirstSubTag("artifactId")?.value?.text?.trim().orEmpty()
        val v = parentTag.findFirstSubTag("version")?.value?.text?.trim().orEmpty()
        val rawVersion = parentTag.findFirstSubTag("version")?.value?.trimmedText
        val propName = extractPropertyName(rawVersion)
        val resolvedVersion = resolvePropertyPlaceholder(v, effectiveProperties)

        projectNode.children.add(
            DependencyHierarchyNode(
                type = DependencyHierarchyNodeType.PARENT_POM,
                groupId = g,
                artifactId = a,
                version = resolvedVersion.ifBlank { null },
                rawVersion = rawVersion,
                propertyName = propName,
                pomFile = pomFile,
                xmlTag = parentTag
            )
        )
    }

    private fun collectDependencyManagementNodes(
        rootTag: XmlTag,
        pomFile: VirtualFile,
        targetGroupId: String,
        targetArtifactId: String,
        effectiveProperties: Map<String, String>,
        projectNode: DependencyHierarchyNode
    ) {
        val dmTag = rootTag.findFirstSubTag("dependencyManagement")
        val dmDepsTag = dmTag?.findFirstSubTag("dependencies")
        dmDepsTag?.findSubTags("dependency")?.forEach { depTag ->
            val g = depTag.findFirstSubTag("groupId")?.value?.text?.trim().orEmpty()
            val a = depTag.findFirstSubTag("artifactId")?.value?.text?.trim().orEmpty()
            val v = depTag.findFirstSubTag("version")?.value?.text?.trim().orEmpty()
            val rawVersion = depTag.findFirstSubTag("version")?.value?.trimmedText
            val scope = depTag.findFirstSubTag("scope")?.value?.text?.trim()
            val type = depTag.findFirstSubTag("type")?.value?.text?.trim()
            val propName = extractPropertyName(rawVersion)
            val resolvedVersion = resolvePropertyPlaceholder(v, effectiveProperties)

            if (type.equals("pom", ignoreCase = true) && scope.equals("import", ignoreCase = true)) {
                projectNode.children.add(
                    DependencyHierarchyNode(
                        type = DependencyHierarchyNodeType.BOM_IMPORT,
                        groupId = g,
                        artifactId = a,
                        version = resolvedVersion.ifBlank { null },
                        rawVersion = rawVersion,
                        propertyName = propName,
                        scope = scope,
                        isManaged = true,
                        pomFile = pomFile,
                        xmlTag = depTag
                    )
                )
            } else if (g == targetGroupId && a == targetArtifactId) {
                projectNode.children.add(
                    DependencyHierarchyNode(
                        type = DependencyHierarchyNodeType.DEPENDENCY_MANAGEMENT,
                        groupId = g,
                        artifactId = a,
                        version = resolvedVersion.ifBlank { null },
                        rawVersion = rawVersion,
                        propertyName = propName,
                        scope = scope,
                        isManaged = true,
                        pomFile = pomFile,
                        xmlTag = depTag
                    )
                )
            }
        }
    }

    private fun collectDirectDependencyNodes(
        rootTag: XmlTag,
        pomFile: VirtualFile,
        targetGroupId: String,
        targetArtifactId: String,
        effectiveProperties: Map<String, String>,
        projectNode: DependencyHierarchyNode
    ) {
        val depsTag = rootTag.findFirstSubTag("dependencies")
        depsTag?.findSubTags("dependency")?.forEach { depTag ->
            val g = depTag.findFirstSubTag("groupId")?.value?.text?.trim().orEmpty()
            val a = depTag.findFirstSubTag("artifactId")?.value?.text?.trim().orEmpty()
            if (g == targetGroupId && a == targetArtifactId) {
                val v = depTag.findFirstSubTag("version")?.value?.text?.trim().orEmpty()
                val rawVersion = depTag.findFirstSubTag("version")?.value?.trimmedText
                val scope = depTag.findFirstSubTag("scope")?.value?.text?.trim()
                val propName = extractPropertyName(rawVersion)
                val resolvedVersion = resolvePropertyPlaceholder(v, effectiveProperties)
                projectNode.children.add(
                    DependencyHierarchyNode(
                        type = DependencyHierarchyNodeType.DIRECT_DEPENDENCY,
                        groupId = g,
                        artifactId = a,
                        version = resolvedVersion.ifBlank { null },
                        rawVersion = rawVersion,
                        propertyName = propName,
                        scope = scope,
                        isManaged = v.isBlank(),
                        pomFile = pomFile,
                        xmlTag = depTag
                    )
                )
            }
        }
    }

    /**
     * Sammelt Plugin-Management, Parent-POM und direkte Plugin-Deklarationen aus dem XML.
     *
     * @param rootTag Das Root-Tag der `pom.xml`.
     * @param pomFile Die VirtualFile der `pom.xml`.
     * @param targetGroupId Die gesuchte Group-ID.
     * @param targetArtifactId Die gesuchte Artefakt-ID.
     * @param effectiveProperties Die effektiven Maven-Properties zur Auflösung von Platzhaltern.
     * @param projectNode Der übergeordnete Projekt-Knoten.
     */
    internal fun collectPluginManagementAndDirect(
        rootTag: XmlTag?,
        pomFile: VirtualFile,
        targetGroupId: String,
        targetArtifactId: String,
        effectiveProperties: Map<String, String>,
        projectNode: DependencyHierarchyNode
    ) {
        if (rootTag == null) return

        // 1. Parent-POM erfassen
        val parentTag = rootTag.findFirstSubTag("parent")
        if (parentTag != null) {
            val g = parentTag.findFirstSubTag("groupId")?.value?.text?.trim().orEmpty()
            val a = parentTag.findFirstSubTag("artifactId")?.value?.text?.trim().orEmpty()
            val v = parentTag.findFirstSubTag("version")?.value?.text?.trim().orEmpty()
            val rawVersion = parentTag.findFirstSubTag("version")?.value?.trimmedText
            val propName = extractPropertyName(rawVersion)
            val resolvedVersion = resolvePropertyPlaceholder(v, effectiveProperties)

            projectNode.children.add(
                DependencyHierarchyNode(
                    type = DependencyHierarchyNodeType.PARENT_POM,
                    groupId = g,
                    artifactId = a,
                    version = resolvedVersion.ifBlank { null },
                    rawVersion = rawVersion,
                    propertyName = propName,
                    pomFile = pomFile,
                    xmlTag = parentTag
                )
            )
        }

        val buildTag = rootTag.findFirstSubTag("build")

        // 2. PluginManagement erfassen
        val pmTag = buildTag?.findFirstSubTag("pluginManagement")
        val pmPluginsTag = pmTag?.findFirstSubTag("plugins")
        pmPluginsTag?.findSubTags("plugin")?.forEach { pluginTag ->
            var g = pluginTag.findFirstSubTag("groupId")?.value?.text?.trim().orEmpty()
            if (g.isEmpty()) g = "org.apache.maven.plugins"
            val a = pluginTag.findFirstSubTag("artifactId")?.value?.text?.trim().orEmpty()
            val v = pluginTag.findFirstSubTag("version")?.value?.text?.trim().orEmpty()
            val rawVersion = pluginTag.findFirstSubTag("version")?.value?.trimmedText
            val propName = extractPropertyName(rawVersion)
            val resolvedVersion = resolvePropertyPlaceholder(v, effectiveProperties)

            if (g == targetGroupId && a == targetArtifactId) {
                projectNode.children.add(
                    DependencyHierarchyNode(
                        type = DependencyHierarchyNodeType.PLUGIN_MANAGEMENT,
                        groupId = g,
                        artifactId = a,
                        version = resolvedVersion.ifBlank { null },
                        rawVersion = rawVersion,
                        propertyName = propName,
                        isManaged = true,
                        pomFile = pomFile,
                        xmlTag = pluginTag
                    )
                )
            }
        }

        // 3. Direkte Plugins erfassen
        val pluginsTag = buildTag?.findFirstSubTag("plugins")
        pluginsTag?.findSubTags("plugin")?.forEach { pluginTag ->
            var g = pluginTag.findFirstSubTag("groupId")?.value?.text?.trim().orEmpty()
            if (g.isEmpty()) g = "org.apache.maven.plugins"
            val a = pluginTag.findFirstSubTag("artifactId")?.value?.text?.trim().orEmpty()
            if (g == targetGroupId && a == targetArtifactId) {
                val v = pluginTag.findFirstSubTag("version")?.value?.text?.trim().orEmpty()
                val rawVersion = pluginTag.findFirstSubTag("version")?.value?.trimmedText
                val propName = extractPropertyName(rawVersion)
                val resolvedVersion = resolvePropertyPlaceholder(v, effectiveProperties)
                projectNode.children.add(
                    DependencyHierarchyNode(
                        type = DependencyHierarchyNodeType.DIRECT_PLUGIN,
                        groupId = g,
                        artifactId = a,
                        version = resolvedVersion.ifBlank { null },
                        rawVersion = rawVersion,
                        propertyName = propName,
                        isManaged = v.isBlank(),
                        pomFile = pomFile,
                        xmlTag = pluginTag
                    )
                )
            }
        }
    }

    /**
     * Ermittelt transitive Abhängigkeitspfade aus dem aufgelösten Abhängigkeitsbaum des Projekts.
     *
     * @param mavenProject Das Maven-Projekt mit dem aufgelösten Abhängigkeitsbaum.
     * @param targetGroupId Die gesuchte Group-ID.
     * @param targetArtifactId Die gesuchte Artefakt-ID.
     * @param projectNode Der übergeordnete Projekt-Knoten.
     * @param rootTag Das optionale Root-Tag der `pom.xml` zur Zuordnung von XML-Tags.
     */
    @JvmOverloads
    internal fun collectTransitiveDependencyPaths(
        mavenProject: MavenProject,
        targetGroupId: String,
        targetArtifactId: String,
        projectNode: DependencyHierarchyNode,
        rootTag: XmlTag? = null
    ) {
        val paths = mutableListOf<List<MavenArtifactNode>>()
        for (rootNode in mavenProject.dependencyTree) {
            findPathsToTarget(rootNode, targetGroupId, targetArtifactId, emptyList(), mutableSetOf(), paths)
        }

        for (path in paths) {
            if (path.size == 1) {
                val directNode = path.first()
                val g = directNode.artifact.groupId
                val a = directNode.artifact.artifactId
                val directTag = rootTag?.findFirstSubTag("dependencies")?.findSubTags("dependency")?.find { tag ->
                    val depG = tag.findFirstSubTag("groupId")?.value?.text?.trim().orEmpty()
                    val depA = tag.findFirstSubTag("artifactId")?.value?.text?.trim().orEmpty()
                    depG == g && depA == a
                }
                val rawVersion = directTag?.findFirstSubTag("version")?.value?.trimmedText
                val propName = extractPropertyName(rawVersion)
                val isManaged = directTag?.findFirstSubTag("version")?.value?.text?.trim().isNullOrBlank()

                var directDep = projectNode.children.firstOrNull {
                    it.type == DependencyHierarchyNodeType.DIRECT_DEPENDENCY && it.groupId == g && it.artifactId == a
                }
                if (directDep == null) {
                    directDep = DependencyHierarchyNode(
                        type = DependencyHierarchyNodeType.DIRECT_DEPENDENCY,
                        groupId = g,
                        artifactId = a,
                        version = directNode.artifact.version,
                        rawVersion = rawVersion,
                        propertyName = propName,
                        scope = directTag?.findFirstSubTag("scope")?.value?.text?.trim() ?: directNode.artifact.scope,
                        isManaged = isManaged,
                        pomFile = mavenProject.file,
                        xmlTag = directTag
                    ).also { projectNode.children.add(it) }
                }

                attachTransitiveChildren(directDep, directNode, mavenProject.file, mutableSetOf(directNode))
                continue
            }

            attachPathToHierarchy(projectNode, path, mavenProject.file, rootTag)
        }
    }

    /**
     * Durchsucht den Teilbaum rekursiv nach Pfaden zur Zielkoordinate.
     *
     * @param currentNode Der aktuelle Knoten im Abhängigkeitsbaum.
     * @param targetGroupId Die gesuchte Group-ID.
     * @param targetArtifactId Die gesuchte Artefakt-ID.
     * @param currentPath Der bisherige Pfad von der Wurzel.
     * @param visited Menge bereits besuchter Knoten zur Vermeidung von Zyklen.
     * @param result Die Ergebnisliste aller gefundenen Pfade.
     */
    internal fun findPathsToTarget(
        currentNode: MavenArtifactNode,
        targetGroupId: String,
        targetArtifactId: String,
        currentPath: List<MavenArtifactNode>,
        visited: MutableSet<MavenArtifactNode>,
        result: MutableList<List<MavenArtifactNode>>
    ) {
        val newPath = currentPath + currentNode
        val art = currentNode.artifact
        if (art.groupId == targetGroupId && art.artifactId == targetArtifactId) {
            result.add(newPath)
            return
        }

        if (!visited.add(currentNode)) return

        for (child in currentNode.dependencies) {
            findPathsToTarget(child, targetGroupId, targetArtifactId, newPath, visited, result)
        }
    }

    /**
     * Fügt einen transitiven Pfad in die Hierarchie ein und verschmilzt gemeinsame Präfixe.
     *
     * @param projectNode Der übergeordnete Projekt-Knoten.
     * @param path Der gefundene Abhängigkeitspfad von der direkten Abhängigkeit bis zum Ziel.
     * @param pomFile Die `pom.xml` des Projekts.
     * @param rootTag Das optionale Root-Tag der `pom.xml` zur Zuordnung von XML-Tags.
     */
    @JvmOverloads
    internal fun attachPathToHierarchy(
        projectNode: DependencyHierarchyNode,
        path: List<MavenArtifactNode>,
        pomFile: VirtualFile,
        rootTag: XmlTag? = null
    ) {
        if (path.isEmpty()) return

        val rootArtifactNode = path.first()
        var currentParent = findOrCreateDirectDependencyNode(projectNode, rootArtifactNode, pomFile, rootTag)

        for (i in 1 until path.size) {
            val node = path[i]
            val g = node.artifact.groupId
            val a = node.artifact.artifactId
            val isTarget = (i == path.size - 1)

            val nextNode = currentParent.children.firstOrNull {
                it.type == DependencyHierarchyNodeType.TRANSITIVE_DEPENDENCY && it.groupId == g && it.artifactId == a
            } ?: DependencyHierarchyNode(
                type = DependencyHierarchyNodeType.TRANSITIVE_DEPENDENCY,
                groupId = g,
                artifactId = a,
                version = node.artifact.version,
                scope = node.artifact.scope,
                isManaged = isTarget,
                pomFile = pomFile
            ).also { currentParent.children.add(it) }

            if (isTarget) {
                attachTransitiveChildren(nextNode, node, pomFile, mutableSetOf(node))
            }

            currentParent = nextNode
        }
    }

    private fun findOrCreateDirectDependencyNode(
        projectNode: DependencyHierarchyNode,
        rootArtifactNode: MavenArtifactNode,
        pomFile: VirtualFile,
        rootTag: XmlTag?
    ): DependencyHierarchyNode {
        val rootG = rootArtifactNode.artifact.groupId
        val rootA = rootArtifactNode.artifact.artifactId
        return projectNode.children.firstOrNull {
            it.type == DependencyHierarchyNodeType.DIRECT_DEPENDENCY && it.groupId == rootG && it.artifactId == rootA
        } ?: run {
            val directTag = rootTag?.findFirstSubTag("dependencies")?.findSubTags("dependency")?.find { tag ->
                val depG = tag.findFirstSubTag("groupId")?.value?.text?.trim().orEmpty()
                val depA = tag.findFirstSubTag("artifactId")?.value?.text?.trim().orEmpty()
                depG == rootG && depA == rootA
            }
            val rawVersion = directTag?.findFirstSubTag("version")?.value?.trimmedText
            val propName = extractPropertyName(rawVersion)
            DependencyHierarchyNode(
                type = DependencyHierarchyNodeType.DIRECT_DEPENDENCY,
                groupId = rootG,
                artifactId = rootA,
                version = rootArtifactNode.artifact.version,
                rawVersion = rawVersion,
                propertyName = propName,
                scope = directTag?.findFirstSubTag("scope")?.value?.text?.trim() ?: rootArtifactNode.artifact.scope,
                pomFile = pomFile,
                xmlTag = directTag
            ).also { projectNode.children.add(it) }
        }
    }

    /**
     * Fügt alle transitiven Unterabhängigkeiten eines Artefaktknotens rekursiv in den Hierarchieknoten ein.
     *
     * @param parentNode Der übergeordnete Hierarchieknoten.
     * @param artifactNode Der Maven-Artefaktknoten mit seinen Unterabhängigkeiten.
     * @param pomFile Die `pom.xml` des Projekts zur Navigation.
     * @param visited Menge bereits besuchter Artefaktknoten zur Vermeidung von Zyklen.
     */
    internal fun attachTransitiveChildren(
        parentNode: DependencyHierarchyNode,
        artifactNode: MavenArtifactNode,
        pomFile: VirtualFile,
        visited: MutableSet<MavenArtifactNode>
    ) {
        for (childArtifactNode in artifactNode.dependencies) {
            if (childArtifactNode in visited) continue
            val childArt = childArtifactNode.artifact
            val childG = childArt.groupId
            val childA = childArt.artifactId

            val childNode = parentNode.children.firstOrNull {
                it.type == DependencyHierarchyNodeType.TRANSITIVE_DEPENDENCY && it.groupId == childG && it.artifactId == childA
            } ?: DependencyHierarchyNode(
                type = DependencyHierarchyNodeType.TRANSITIVE_DEPENDENCY,
                groupId = childG,
                artifactId = childA,
                version = childArt.version,
                scope = childArt.scope,
                isManaged = false,
                pomFile = pomFile
            ).also { parentNode.children.add(it) }

            visited.add(childArtifactNode)
            attachTransitiveChildren(childNode, childArtifactNode, pomFile, visited)
            visited.remove(childArtifactNode)
        }
    }

    /**
     * Extrahiert den Namen einer Maven-Property aus einem Platzhalter der Form `${property.name}`.
     *
     * @param text Der zu prüfende Text.
     * @return Der Property-Name oder `null`.
     */
    private fun extractPropertyName(text: String?): String? {
        if (text == null || !text.startsWith("\${") || !text.endsWith("}")) return null
        return text.substring(2, text.length - 1).trim().ifEmpty { null }
    }

    /**
     * Löst einen Versionswert auf, der als Maven-Property-Platzhalter vorliegen kann.
     *
     * @param value Der möglicherweise als Platzhalter vorliegende Wert.
     * @param properties Die effektiven Maven-Properties.
     * @return Die aufgelöste Version oder der ursprüngliche Wert.
     */
    private fun resolvePropertyPlaceholder(value: String, properties: Map<String, String>): String {
        if (!value.startsWith("\${") || !value.endsWith("}")) return value
        val propName = value.substring(2, value.length - 1).trim()
        return properties[propName]?.takeIf { it.isNotBlank() } ?: value
    }
}
