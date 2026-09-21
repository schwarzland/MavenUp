package de.schwarzland.mavenup.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import de.schwarzland.mavenup.model.DependencyHierarchyNode
import de.schwarzland.mavenup.model.DependencyHierarchyNodeType
import de.schwarzland.mavenup.service.PomNavigationService

/**
 * Kapselt die Navigation von Knoten des Abhängigkeitshierarchiebaums zu ihren `pom.xml`-Deklarationen.
 *
 * @property project Das zugehörige IntelliJ-Projekt.
 */
internal class DependencyHierarchyNavigation(
    private val project: Project
) {

    /**
     * Prüft, ob der übergebene Hierarchieknoten in einer `pom.xml` des Projekts deklariert ist.
     *
     * Modulknoten ([DependencyHierarchyNodeType.PROJECT]) unterstützen keine Navigation.
     * Transitive Abhängigkeiten ([DependencyHierarchyNodeType.TRANSITIVE_DEPENDENCY]) können angesprungen werden,
     * wenn sie als verwaltete Abhängigkeit im `<dependencyManagement>` vorhanden sind.
     *
     * @param node Der zu prüfende Hierarchieknoten.
     * @return `true`, wenn eine passende Deklaration in einer `pom.xml` existiert.
     */
    internal fun isNodeInPom(node: DependencyHierarchyNode): Boolean {
        if (node.type == DependencyHierarchyNodeType.PROJECT) return false
        if (node.xmlTag != null && node.xmlTag.isValid) return true
        if (node.groupId.isBlank() || node.artifactId.isBlank()) return false
        return isDeclaredInProjectPoms(node)
    }

    /**
     * Springt zur `pom.xml`-Definition des übergebenen Baumknotens.
     *
     * @param node Der zu öffnende Hierarchieknoten.
     */
    internal fun navigateToNode(node: DependencyHierarchyNode) {
        if (!isNodeInPom(node)) return

        when {
            node.xmlTag != null && node.pomFile != null && node.xmlTag.isValid -> {
                openInEditor(node.pomFile, node.xmlTag)
            }
            node.groupId.isNotBlank() && node.artifactId.isNotBlank() -> {
                val pomFile = node.pomFile
                if (pomFile != null) {
                    val targetTag = ApplicationManager.getApplication().runReadAction<XmlTag?> {
                        val psiFile = PsiManager.getInstance(project).findFile(pomFile) as? XmlFile
                        findTargetTag(psiFile?.document?.rootTag, node)
                    }
                    if (targetTag != null) {
                        openInEditor(pomFile, targetTag)
                        return
                    }
                }
                PomNavigationService(project).navigateToDependency(
                    node.groupId,
                    node.artifactId,
                    resolveNavType(node.type)
                )
            }
        }
    }

    /**
     * Prüft zuerst die angegebene POM und danach alle Maven-Projekt-POMs auf die Deklaration.
     *
     * @param node Der zu suchende Hierarchieknoten.
     * @return `true`, wenn eine passende Deklaration gefunden wurde.
     */
    private fun isDeclaredInProjectPoms(node: DependencyHierarchyNode): Boolean {
        val pomFile = node.pomFile
        if (pomFile != null) {
            val targetTag = ApplicationManager.getApplication().runReadAction<XmlTag?> {
                val psiFile = PsiManager.getInstance(project).findFile(pomFile) as? XmlFile
                findTargetTag(psiFile?.document?.rootTag, node)
            }
            if (targetTag != null && targetTag.isValid) return true
        }
        return isDeclaredInAnyMavenProjectPom(node)
    }

    /**
     * Prüft alle zum Projekt gehörenden Maven-POMs auf die Deklaration.
     *
     * @param node Der zu suchende Hierarchieknoten.
     * @return `true`, wenn eine passende Deklaration gefunden wurde.
     */
    private fun isDeclaredInAnyMavenProjectPom(node: DependencyHierarchyNode): Boolean =
        ApplicationManager.getApplication().runReadAction<Boolean> {
            val mavenProjects = org.jetbrains.idea.maven.project.MavenProjectsManager.getInstance(project).projects.toList()
            mavenProjects.any { mavenProject ->
                val psiFile = PsiManager.getInstance(project).findFile(mavenProject.file) as? XmlFile
                val targetTag = findTargetTag(psiFile?.document?.rootTag, node)
                targetTag != null && targetTag.isValid
            }
        }

    /**
     * Ordnet einen Hierarchieknotentyp dem Typ für die POM-Navigation zu.
     *
     * @param nodeType Der Typ des Hierarchieknotens.
     * @return Der von [PomNavigationService] erwartete Navigationstyp.
     */
    private fun resolveNavType(nodeType: DependencyHierarchyNodeType): String = when (nodeType) {
        DependencyHierarchyNodeType.DEPENDENCY_MANAGEMENT -> "managed dependency"
        DependencyHierarchyNodeType.PLUGIN_MANAGEMENT -> "managed plugin"
        DependencyHierarchyNodeType.DIRECT_PLUGIN -> "plugin"
        DependencyHierarchyNodeType.TRANSITIVE_DEPENDENCY -> "managed dependency"
        else -> "dependency"
    }

    /**
     * Öffnet eine XML-Deklaration im Editor.
     *
     * @param pomFile Die zu öffnende POM-Datei.
     * @param xmlTag Das anzusteuernde XML-Tag.
     */
    private fun openInEditor(pomFile: VirtualFile, xmlTag: XmlTag) {
        val offset = ApplicationManager.getApplication().runReadAction<Int> {
            xmlTag.textOffset
        }
        OpenFileDescriptor(project, pomFile, offset).navigate(true)
        FileEditorManager.getInstance(project).openFile(pomFile, true)
    }

    /**
     * Sucht rekursiv nach dem XML-Tag, das den Hierarchieknoten deklariert.
     *
     * @param rootTag Das aktuelle XML-Tag der Suche.
     * @param node Der zu suchende Hierarchieknoten.
     * @return Das passende XML-Tag oder `null`, wenn keines gefunden wurde.
     */
    private fun findTargetTag(rootTag: XmlTag?, node: DependencyHierarchyNode): XmlTag? {
        if (rootTag == null) return null
        if (node.type == DependencyHierarchyNodeType.PARENT_POM && rootTag.name == "parent") {
            val groupId = rootTag.findFirstSubTag("groupId")?.value?.trimmedText.orEmpty()
            val artifactId = rootTag.findFirstSubTag("artifactId")?.value?.trimmedText.orEmpty()
            if (groupId == node.groupId && artifactId == node.artifactId) return rootTag
        }
        val tagName = when (node.type) {
            DependencyHierarchyNodeType.PARENT_POM -> "parent"
            DependencyHierarchyNodeType.PLUGIN_MANAGEMENT, DependencyHierarchyNodeType.DIRECT_PLUGIN -> "plugin"
            else -> "dependency"
        }
        for (tag in rootTag.findSubTags(tagName)) {
            val groupId = tag.findFirstSubTag("groupId")?.value?.trimmedText.orEmpty()
            val artifactId = tag.findFirstSubTag("artifactId")?.value?.trimmedText.orEmpty()
            if (groupId == node.groupId && artifactId == node.artifactId) return tag
        }
        for (subTag in rootTag.subTags) {
            val found = findTargetTag(subTag, node)
            if (found != null) return found
        }
        return null
    }
}
