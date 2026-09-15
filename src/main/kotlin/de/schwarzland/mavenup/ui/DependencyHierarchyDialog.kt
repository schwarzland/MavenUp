package de.schwarzland.mavenup.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.xml.XmlTag
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.TreeSpeedSearch
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.treeStructure.Tree
import de.schwarzland.mavenup.model.DependencyHierarchyNode
import de.schwarzland.mavenup.model.DependencyHierarchyNodeType
import de.schwarzland.mavenup.service.DependencyHierarchyService
import de.schwarzland.mavenup.service.PomNavigationService
import java.awt.Dimension
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JTree
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

/**
 * Ein modaler Dialog zur Anzeige des Einbindungs- und Management-Hierarchiebaums für
 * Managed Dependencies und Managed Plugins.
 *
 * Stellt in einem interaktiven [Tree] dar, über welche Wege und Eltern-Hierarchien
 * eine verwaltete Abhängigkeit bzw. ein Plugin im Projekt eingebunden wird, welche direkten
 * und transitiven Abhängigkeiten dazwischen liegen und welche Versionen bzw. Properties greifen.
 *
 * Ein Doppelklick auf einen Knoten springt per PSI-Navigation direkt zur Deklaration in der `pom.xml`.
 *
 * @property project Das zugehörige IntelliJ-Projekt.
 * @property groupId Group-ID der anzuzeigenden Komponente.
 * @property artifactId Artefakt-ID der anzuzeigenden Komponente.
 * @property isPlugin `true`, wenn es sich um ein Plugin aus `<pluginManagement>` handelt.
 */
class DependencyHierarchyDialog(
    private val project: Project,
    private val groupId: String,
    private val artifactId: String,
    private val isPlugin: Boolean = false
) : DialogWrapper(project, true) {

    init {
        title = MyMessageBundle.message("dependency.hierarchy.dialog.title", "$groupId:$artifactId")
        setOKButtonText(MyMessageBundle.message("button.close"))
        init()
    }

    /**
     * Liefert ausschließlich die Schließen-Aktion, da der Dialog rein informativ ist.
     */
    override fun createActions(): Array<Action> = arrayOf(okAction)

    /**
     * Erstellt den Haupt-Inhaltsbereich des Dialogs mittels Kotlin UI DSL v2.
     */
    override fun createCenterPanel(): JComponent {
        val hierarchyService = DependencyHierarchyService(project)
        val rootData = hierarchyService.buildHierarchy(groupId, artifactId, isPlugin)

        if (rootData.children.isEmpty()) {
            return panel {
                row {
                    cell(JBLabel(MyMessageBundle.message("dependency.hierarchy.dialog.empty", "$groupId:$artifactId")))
                }
            }.apply {
                preferredSize = Dimension(600, 300)
            }
        }

        val treeModel = buildTreeModel(rootData)
        val tree = Tree(treeModel).apply {
            isRootVisible = true
            showsRootHandles = true
            cellRenderer = DependencyHierarchyTreeCellRenderer()
        }

        TreeSpeedSearch.installOn(tree, false) { path ->
            val node = (path.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? DependencyHierarchyNode
            node?.let { "${it.groupId}:${it.artifactId} ${it.version.orEmpty()}" } ?: path.lastPathComponent.toString()
        }

        expandAllNodes(tree)

        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2 && SwingUtilities.isLeftMouseButton(e)) {
                    navigateToSelectedNode(tree)
                }
            }
        })

        tree.registerKeyboardAction(
            { navigateToSelectedNode(tree) },
            KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0),
            JComponent.WHEN_FOCUSED
        )

        return panel {
            row {
                label(MyMessageBundle.message("dependency.hierarchy.dialog.header", "$groupId:$artifactId"))
                    .bold()
            }
            row {
                comment(MyMessageBundle.message("dependency.hierarchy.dialog.hint"))
            }
            row {
                cell(JBScrollPane(tree))
                    .align(Align.FILL)
            }.resizableRow()
        }.apply {
            preferredSize = Dimension(850, 520)
        }
    }

    /**
     * Baut das Swing-[DefaultTreeModel] aus dem Hierarchieknoten-Datenmodell auf.
     *
     * @param rootData Der Wurzelknoten der Hierarchie.
     * @return Das initialisierte Baummodell.
     */
    internal fun buildTreeModel(rootData: DependencyHierarchyNode): DefaultTreeModel {
        val rootTreeNode = DefaultMutableTreeNode(rootData)
        populateTreeNodes(rootTreeNode, rootData)
        return DefaultTreeModel(rootTreeNode)
    }

    /**
     * Befüllt die Kindknoten rekursiv im Swing-Baum.
     *
     * @param parentTreeNode Der übergeordnete Swing-Baumknoten.
     * @param parentData Das zugehörige Datenmodell.
     */
    private fun populateTreeNodes(parentTreeNode: DefaultMutableTreeNode, parentData: DependencyHierarchyNode) {
        for (childData in parentData.children) {
            val childTreeNode = DefaultMutableTreeNode(childData)
            parentTreeNode.add(childTreeNode)
            populateTreeNodes(childTreeNode, childData)
        }
    }

    /**
     * Klappt alle Knoten des Baums vollständig auf.
     *
     * @param tree Der zu expandierende Baum.
     */
    internal fun expandAllNodes(tree: JTree) {
        var row = 0
        while (row < tree.rowCount) {
            tree.expandRow(row)
            row++
        }
    }

    /**
     * Springt zur `pom.xml`-Definition des aktuell ausgewählten Baumknotens.
     *
     * @param tree Der Baum mit der aktuellen Selektion.
     */
    internal fun navigateToSelectedNode(tree: JTree) {
        val selectedPath = tree.selectionPath ?: return
        val treeNode = selectedPath.lastPathComponent as? DefaultMutableTreeNode ?: return
        val node = treeNode.userObject as? DependencyHierarchyNode ?: return

        if (node.xmlTag != null && node.pomFile != null) {
            openInEditor(node.pomFile, node.xmlTag)
        } else if (node.pomFile != null) {
            val descriptor = OpenFileDescriptor(project, node.pomFile)
            FileEditorManager.getInstance(project).openTextEditor(descriptor, true)
        } else if (node.groupId.isNotBlank() && node.artifactId.isNotBlank()) {
            val navType = when (node.type) {
                DependencyHierarchyNodeType.PARENT_POM -> PARENT_TYPE
                DependencyHierarchyNodeType.PLUGIN_MANAGEMENT,
                DependencyHierarchyNodeType.DIRECT_PLUGIN -> "plugin"
                else -> "dependency"
            }
            PomNavigationService(project).navigateToDependency(node.groupId, node.artifactId, navType)
        }
    }

    /**
     * Öffnet die angegebene Datei im Editor an der Position des XML-Tags.
     *
     * @param pomFile Die zu öffnende Datei.
     * @param targetTag Das Ziel-XML-Tag.
     */
    private fun openInEditor(pomFile: VirtualFile, targetTag: XmlTag) {
        val offset = ApplicationManager.getApplication().runReadAction<Int> { targetTag.textOffset }
        ApplicationManager.getApplication().invokeLater {
            val descriptor = OpenFileDescriptor(project, pomFile, offset)
            FileEditorManager.getInstance(project).openTextEditor(descriptor, true)
        }
    }
}

/**
 * Renderer für die Knoten des Abhängigkeitshierarchie-Baums mit Icons und Formatierungen.
 */
class DependencyHierarchyTreeCellRenderer : ColoredTreeCellRenderer() {

    override fun customizeCellRenderer(
        tree: JTree,
        value: Any?,
        selected: Boolean,
        expanded: Boolean,
        leaf: Boolean,
        row: Int,
        hasFocus: Boolean
    ) {
        val userObject = (value as? DefaultMutableTreeNode)?.userObject
        if (userObject is DependencyHierarchyNode) {
            icon = nodeIcon(userObject.type)

            val prefix = nodePrefix(userObject.type)
            if (prefix.isNotBlank()) {
                append("$prefix ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }

            append("${userObject.groupId}:${userObject.artifactId}", SimpleTextAttributes.REGULAR_ATTRIBUTES)

            if (!userObject.version.isNullOrBlank()) {
                append(":${userObject.version}", SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
            }

            val details = formatNodeDetails(userObject)
            if (details.isNotBlank()) {
                append(" ($details)", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
            }
        } else if (userObject != null) {
            append(userObject.toString(), SimpleTextAttributes.REGULAR_ATTRIBUTES)
        }
    }

    private fun nodeIcon(type: DependencyHierarchyNodeType): javax.swing.Icon = when (type) {
        DependencyHierarchyNodeType.ROOT -> AllIcons.Nodes.PpLib
        DependencyHierarchyNodeType.PROJECT -> AllIcons.Nodes.Module
        DependencyHierarchyNodeType.PARENT_POM -> AllIcons.Nodes.Folder
        DependencyHierarchyNodeType.BOM_IMPORT -> AllIcons.Nodes.Artifact
        DependencyHierarchyNodeType.DEPENDENCY_MANAGEMENT -> AllIcons.Nodes.Property
        DependencyHierarchyNodeType.PLUGIN_MANAGEMENT -> AllIcons.Nodes.Plugin
        DependencyHierarchyNodeType.DIRECT_DEPENDENCY -> AllIcons.Nodes.PpLib
        DependencyHierarchyNodeType.DIRECT_PLUGIN -> AllIcons.Nodes.Plugin
        DependencyHierarchyNodeType.TRANSITIVE_DEPENDENCY -> AllIcons.Nodes.Related
    }

    private fun nodePrefix(type: DependencyHierarchyNodeType): String = when (type) {
        DependencyHierarchyNodeType.ROOT -> MyMessageBundle.message("dependency.hierarchy.node.root")
        DependencyHierarchyNodeType.PROJECT -> MyMessageBundle.message("dependency.hierarchy.node.project")
        DependencyHierarchyNodeType.PARENT_POM -> MyMessageBundle.message("dependency.hierarchy.node.parentPom")
        DependencyHierarchyNodeType.BOM_IMPORT -> MyMessageBundle.message("dependency.hierarchy.node.bomImport")
        DependencyHierarchyNodeType.DEPENDENCY_MANAGEMENT ->
            MyMessageBundle.message("dependency.hierarchy.node.dependencyManagement")
        DependencyHierarchyNodeType.PLUGIN_MANAGEMENT ->
            MyMessageBundle.message("dependency.hierarchy.node.pluginManagement")
        DependencyHierarchyNodeType.DIRECT_DEPENDENCY ->
            MyMessageBundle.message("dependency.hierarchy.node.directDependency")
        DependencyHierarchyNodeType.DIRECT_PLUGIN ->
            MyMessageBundle.message("dependency.hierarchy.node.directPlugin")
        DependencyHierarchyNodeType.TRANSITIVE_DEPENDENCY ->
            MyMessageBundle.message("dependency.hierarchy.node.transitiveDependency")
    }

    private fun formatNodeDetails(node: DependencyHierarchyNode): String {
        val detailsList = mutableListOf<String>()
        if (!node.propertyName.isNullOrBlank()) {
            detailsList.add(MyMessageBundle.message("dependency.hierarchy.node.property", node.propertyName))
        }
        if (!node.scope.isNullOrBlank() && node.scope != "compile") {
            detailsList.add(MyMessageBundle.message("dependency.hierarchy.node.scope", node.scope))
        }
        if (node.isManaged && node.type == DependencyHierarchyNodeType.TRANSITIVE_DEPENDENCY) {
            detailsList.add(MyMessageBundle.message("dependency.hierarchy.node.managedMarker"))
        }
        return detailsList.joinToString(", ")
    }
}
