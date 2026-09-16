package de.schwarzland.mavenup.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.JBColor
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
import java.awt.Color
import java.awt.Dimension
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JTree
import javax.swing.KeyStroke
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
 * Ein Rechtsklick öffnet ein Kontextmenü zur Navigation in die `pom.xml` (`Navigate to pom.xml`),
 * zusätzlich kann per `Enter` oder `F4` direkt zur Deklaration gesprungen werden.
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
    public override fun createCenterPanel(): JComponent {
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
            cellRenderer = DependencyHierarchyTreeCellRenderer(groupId, artifactId)
            toolTipText = MyMessageBundle.message("dependency.hierarchy.dialog.tree.tooltip")
        }

        TreeSpeedSearch.installOn(tree, false) { path ->
            val node = (path.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? DependencyHierarchyNode
            node?.let { "${it.groupId}:${it.artifactId} ${it.version.orEmpty()}" } ?: path.lastPathComponent.toString()
        }

        val hierarchyToolbar = createToolbar(tree)
        expandAllNodes(tree)

        tree.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (e.isPopupTrigger) {
                    showContextMenu(tree, e)
                }
            }

            override fun mouseReleased(e: MouseEvent) {
                if (e.isPopupTrigger) {
                    showContextMenu(tree, e)
                }
            }
        })

        tree.registerKeyboardAction(
            { navigateToSelectedNode(tree) },
            KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0),
            JComponent.WHEN_FOCUSED
        )
        tree.registerKeyboardAction(
            { navigateToSelectedNode(tree) },
            KeyStroke.getKeyStroke(KeyEvent.VK_F4, 0),
            JComponent.WHEN_FOCUSED
        )

        return panel {
            row {
                cell(hierarchyToolbar.component)
            }
            row {
                label(MyMessageBundle.message("dependency.hierarchy.dialog.header", "$groupId:$artifactId"))
                    .bold()
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
     * Erstellt die Toolbar-Aktionen des Hierarchie-Dialogs.
     *
     * @param tree Der zugehörige Baum.
     * @return Die Toolbar mit den allgemeinen Baumaktionen.
     */
    internal fun createToolbar(tree: JTree): ActionToolbar =
        ActionManager.getInstance().createActionToolbar(
            "MavenUp.DependencyHierarchyDialog",
            DefaultActionGroup().apply {
                add(object : AnAction(
                    MyMessageBundle.message("dependency.hierarchy.toolbar.expandAll"),
                    null,
                    AllIcons.Actions.Expandall
                ) {
                    override fun getActionUpdateThread() = ActionUpdateThread.EDT
                    override fun actionPerformed(event: AnActionEvent) {
                        expandAllNodes(tree)
                    }
                })
                add(object : AnAction(
                    MyMessageBundle.message("dependency.hierarchy.toolbar.collapseAll"),
                    null,
                    AllIcons.Actions.Collapseall
                ) {
                    override fun getActionUpdateThread() = ActionUpdateThread.EDT
                    override fun actionPerformed(event: AnActionEvent) {
                        collapseAllNodes(tree)
                    }
                })
                add(Separator.getInstance())
                add(object : AnAction(
                    MyMessageBundle.message("toolwindow.MyToolWindow.contextMenu.navigateToPom"),
                    null,
                    AllIcons.Actions.Find
                ) {
                    override fun getActionUpdateThread() = ActionUpdateThread.EDT
                    override fun actionPerformed(event: AnActionEvent) {
                        navigateToSelectedNode(tree)
                    }
                })
            },
            true
        )

    /**
     * Erstellt die Aktionsgruppe für das Kontextmenü des Hierarchiebaums.
     *
     * @param tree Der zugehörige Baum.
     * @return Die Aktionsgruppe mit der Navigationsaktion.
     */
    internal fun createContextMenuGroup(tree: JTree): DefaultActionGroup =
        DefaultActionGroup().apply {
            add(object : AnAction(MyMessageBundle.message("toolwindow.MyToolWindow.contextMenu.navigateToPom")) {
                override fun getActionUpdateThread() = ActionUpdateThread.BGT
                override fun actionPerformed(event: AnActionEvent) {
                    navigateToSelectedNode(tree)
                }
            })
        }

    /**
     * Zeigt das Kontextmenü für den angeklickten Baumknoten an.
     *
     * @param tree Der zugehörige Baum.
     * @param e Das auslösende Maus-Ereignis.
     */
    internal fun showContextMenu(tree: JTree, e: MouseEvent) {
        val path = tree.getPathForLocation(e.x, e.y) ?: return
        if (!tree.isPathSelected(path)) {
            tree.selectionPath = path
        }
        val group = createContextMenuGroup(tree)
        ActionManager.getInstance().createActionPopupMenu(
            "MavenUp.DependencyHierarchyTree", group
        ).component.show(e.component, e.x, e.y)
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
     * Klappt alle Knoten des Baums vollständig zu.
     *
     * @param tree Der zu kollabierende Baum.
     */
    internal fun collapseAllNodes(tree: JTree) {
        for (row in tree.rowCount - 1 downTo 0) {
            tree.collapseRow(row)
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

        if (node.xmlTag != null && node.pomFile != null && node.xmlTag.isValid) {
            openInEditor(node.pomFile, node.xmlTag)
        } else if (node.type == DependencyHierarchyNodeType.PROJECT && node.pomFile != null) {
            val descriptor = OpenFileDescriptor(project, node.pomFile)
            FileEditorManager.getInstance(project).openTextEditor(descriptor, true)
        } else if (node.groupId.isNotBlank() && node.artifactId.isNotBlank()) {
            val navType = resolveNavType(node.type)
            if (node.pomFile != null) {
                val targetTag = ApplicationManager.getApplication().runReadAction<XmlTag?> {
                    val psiFile = PsiManager.getInstance(project).findFile(node.pomFile) as? XmlFile
                    findTargetTag(psiFile?.document?.rootTag, node)
                }
                if (targetTag != null) {
                    openInEditor(node.pomFile, targetTag)
                    return
                }
            }
            PomNavigationService(project).navigateToDependency(node.groupId, node.artifactId, navType)
        } else if (node.pomFile != null) {
            val descriptor = OpenFileDescriptor(project, node.pomFile)
            FileEditorManager.getInstance(project).openTextEditor(descriptor, true)
        }
    }

    private fun resolveNavType(type: DependencyHierarchyNodeType): String = when (type) {
        DependencyHierarchyNodeType.PARENT_POM -> PARENT_TYPE
        DependencyHierarchyNodeType.PLUGIN_MANAGEMENT,
        DependencyHierarchyNodeType.DIRECT_PLUGIN -> "plugin"
        DependencyHierarchyNodeType.ROOT -> if (isPlugin) "plugin" else "dependency"
        else -> "dependency"
    }

    private fun findTargetTag(rootTag: XmlTag?, node: DependencyHierarchyNode): XmlTag? {
        val navService = PomNavigationService(project)
        return when (node.type) {
            DependencyHierarchyNodeType.PARENT_POM ->
                navService.findParent(rootTag, node.groupId, node.artifactId)
            DependencyHierarchyNodeType.DIRECT_PLUGIN ->
                navService.findPlugin(rootTag, node.groupId, node.artifactId, isManaged = false)
            DependencyHierarchyNodeType.PLUGIN_MANAGEMENT ->
                navService.findPlugin(rootTag, node.groupId, node.artifactId, isManaged = true)
            DependencyHierarchyNodeType.DEPENDENCY_MANAGEMENT,
            DependencyHierarchyNodeType.BOM_IMPORT ->
                navService.findDependency(rootTag, node.groupId, node.artifactId, isManaged = true)
            DependencyHierarchyNodeType.ROOT ->
                if (isPlugin) {
                    navService.findPlugin(rootTag, node.groupId, node.artifactId, isManaged = false)
                        ?: navService.findPlugin(rootTag, node.groupId, node.artifactId, isManaged = true)
                } else {
                    navService.findDependency(rootTag, node.groupId, node.artifactId, isManaged = false)
                }
            else ->
                navService.findDependency(rootTag, node.groupId, node.artifactId, isManaged = false)
        }
    }

    /**
     * Öffnet die angegebene Datei im Editor an der Position des XML-Tags.
     *
     * @param pomFile Die zu öffnende Datei.
     * @param targetTag Das Ziel-XML-Tag.
     */
    private fun openInEditor(pomFile: VirtualFile, targetTag: XmlTag) {
        val offset = ApplicationManager.getApplication().runReadAction<Int> {
            if (targetTag.isValid) targetTag.textOffset else 0
        }
        ApplicationManager.getApplication().invokeLater {
            val descriptor = OpenFileDescriptor(project, pomFile, offset)
            FileEditorManager.getInstance(project).openTextEditor(descriptor, true)
        }
    }
}

/** Farbe zur Hervorhebung der Ziel-Abhängigkeit im Hierarchiebaum (Light-/Dark-Mode). */
internal val TARGET_DEPENDENCY_COLOR = JBColor(Color(10, 95, 185), Color(88, 157, 246))

/**
 * Renderer für die Knoten des Abhängigkeitshierarchie-Baums mit Icons und Formatierungen.
 *
 * Hebt die Ziel-Abhängigkeit bzw. das Ziel-Plugin im Baum farblich hervor ([TARGET_DEPENDENCY_COLOR]).
 *
 * @param targetGroupId Group-ID der Zielkomponente zur farblichen Hervorhebung.
 * @param targetArtifactId Artefakt-ID der Zielkomponente zur farblichen Hervorhebung.
 */
class DependencyHierarchyTreeCellRenderer(
    private val targetGroupId: String? = null,
    private val targetArtifactId: String? = null
) : ColoredTreeCellRenderer() {

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

            val isTarget = isTargetDependency(userObject)
            val coordAttributes = if (isTarget) {
                SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, TARGET_DEPENDENCY_COLOR)
            } else {
                SimpleTextAttributes.REGULAR_ATTRIBUTES
            }
            val versionAttributes = if (isTarget) {
                SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, TARGET_DEPENDENCY_COLOR)
            } else {
                SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES
            }

            append("${userObject.groupId}:${userObject.artifactId}", coordAttributes)

            if (!userObject.version.isNullOrBlank()) {
                append(":${userObject.version}", versionAttributes)
            }

            val details = formatNodeDetails(userObject)
            if (details.isNotBlank()) {
                append(" ($details)", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
            }
        } else if (userObject != null) {
            append(userObject.toString(), SimpleTextAttributes.REGULAR_ATTRIBUTES)
        }
    }

    /**
     * Prüft, ob der angegebene Knoten die Ziel-Abhängigkeit bzw. das Ziel-Plugin repräsentiert.
     *
     * @param node Der zu prüfende Hierarchieknoten.
     * @return `true`, wenn der Knoten nicht vom Typ [DependencyHierarchyNodeType.PROJECT] ist
     *         und seine Koordinaten mit der Zielkomponente übereinstimmen.
     */
    internal fun isTargetDependency(node: DependencyHierarchyNode): Boolean {
        if (targetGroupId.isNullOrBlank() || targetArtifactId.isNullOrBlank()) return false
        if (node.type == DependencyHierarchyNodeType.PROJECT) return false
        return node.groupId == targetGroupId && node.artifactId == targetArtifactId
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
