package de.schwarzland.mavenup.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.TreeSpeedSearch
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.treeStructure.Tree
import de.schwarzland.mavenup.model.DependencyHierarchyNode
import de.schwarzland.mavenup.model.DependencyHierarchyNodeType
import de.schwarzland.mavenup.model.VulnerabilityAdvisory
import de.schwarzland.mavenup.model.VulnerabilitySeverity
import de.schwarzland.mavenup.service.DependencyHierarchyService
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JTree
import javax.swing.KeyStroke
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

/**
 * Einbettbares Seitenpanel zur Anzeige des Einbindungs- und Management-Hierarchiebaums für
 * Dependencies, Managed Dependencies, Plugins, Managed Plugins sowie transitive Abhängigkeiten.
 *
 * Stellt in einem interaktiven [Tree] dar, über welche Wege und Eltern-Hierarchien
 * eine Komponente im Projekt eingebunden wird, welche direkten und transitiven
 * Abhängigkeiten dazwischen liegen und welche Versionen bzw. Properties greifen.
 *
 * Beinhaltet eine eigene Toolbar mit Aktionen zum Auf-/Zuklappen aller Knoten, zur
 * Navigation in die `pom.xml` (`Navigate to pom.xml`), zum Anspringen der Komponente in der
 * passenden Tabellenansicht (`Select in Dependencies` oder `Select in Transitive CVEs`) und zum
 * Ein- oder Ausblenden der Group-IDs sowie zum Schließen des Seitenpanels.
 *
 * Die Mindestbreite bleibt bei null, damit der umgebende Splitter die vom Nutzer gewählte Breite
 * auch nach dem Neuaufbau des Inhalts bei einer geänderten Tabellenselektion beibehält.
 *
 * Ein Rechtsklick auf einen Baumknoten öffnet das entsprechende Kontextmenü;
 * zusätzlich kann per `Enter` oder `F4` direkt zur Deklaration in der `pom.xml` gesprungen werden.
 *
 * @property project Das zugehörige IntelliJ-Projekt.
 * @property isDependencyInTable Optionales Prädikat zur Prüfung, ob eine Koordinate in einer Tabellenansicht existiert.
 * @property tableNavigationLabelProvider Optionaler Provider für die Beschriftung der Zieltabellenaktion.
 * @property onNavigateToTable Optionaler Callback zur Navigation in die passende Tabellenansicht.
 * @property onClose Optionaler Callback beim Schließen des Seitenpanels.
 * @property vulnerabilityAdvisoriesProvider Optionaler Provider für bekannte Sicherheitswarnungen zur Kennzeichnung
 *           vulnerabler transitiver Abhängigkeiten.
 */
class DependencyHierarchyPanel(
    private val project: Project,
    private val isDependencyInTable: ((groupId: String, artifactId: String) -> Boolean)? = null,
    private val tableNavigationLabelProvider: ((groupId: String, artifactId: String) -> String)? = null,
    private val onNavigateToTable: ((groupId: String, artifactId: String) -> Boolean)? = null,
    private val onClose: (() -> Unit)? = null,
    private val vulnerabilityAdvisoriesProvider: (() -> Map<String, List<VulnerabilityAdvisory>>)? = null
) : JBPanel<JBPanel<*>>(BorderLayout()) {

    private val hierarchyNavigation = DependencyHierarchyNavigation(project)
    private val expansionPolicy = DependencyHierarchyExpansionPolicy { groupId, artifactId ->
        isDependencyInTable?.invoke(groupId, artifactId) ?: false
    }

    init {
        minimumSize = Dimension(0, 0)
    }

    /** Group-ID der aktuell angezeigten Zielkomponente. */
    var currentGroupId: String? = null
        private set

    /** Artefakt-ID der aktuell angezeigten Zielkomponente. */
    var currentArtifactId: String? = null
        private set

    /** `true`, wenn die aktuell angezeigte Komponente ein Plugin ist. */
    var currentIsPlugin: Boolean = false
        private set

    /** Der aktuelle Hierarchiebaum. */
    var tree: Tree? = null
        private set

    /** Die Aktionsleiste des Hierarchiepanels. */
    var toolbar: ActionToolbar? = null
        private set

    /** `true`, wenn die Group-IDs im Hierarchiebaum angezeigt werden. */
    var groupIdsVisible: Boolean = true
        private set

    /**
     * Baut die Hierarchie für die übergebene Komponente auf und aktualisiert die Ansicht.
     *
     * @param groupId Group-ID der anzuzeigenden Komponente.
     * @param artifactId Artefakt-ID der anzuzeigenden Komponente.
     * @param isPlugin `true` für Plugins aus `<pluginManagement>`, sonst `false`.
     */
    fun showHierarchy(groupId: String, artifactId: String, isPlugin: Boolean = false) {
        currentGroupId = groupId
        currentArtifactId = artifactId
        currentIsPlugin = isPlugin

        val hierarchyService = DependencyHierarchyService(project)
        val rootData = hierarchyService.buildHierarchy(groupId, artifactId, isPlugin)
        val advisories = vulnerabilityAdvisoriesProvider?.invoke().orEmpty()

        removeAll()

        if (rootData.children.isEmpty()) {
            val emptyTree = createEmptyTree()
            val hierarchyToolbar = createToolbar(emptyTree)
            this.toolbar = hierarchyToolbar
            this.tree = emptyTree

            val emptyPanel = panel {
                row {
                    cell(hierarchyToolbar.component)
                }
                row {
                    cell(JBLabel(MyMessageBundle.message("dependency.hierarchy.dialog.empty", "$groupId:$artifactId")).apply {
                        foreground = JBColor.GRAY
                    })
                }
            }
            add(emptyPanel, BorderLayout.CENTER)
            revalidate()
            repaint()
            return
        }

        val newTree = createHierarchyTree(rootData, groupId, artifactId, advisories)
        val hierarchyToolbar = createToolbar(newTree)
        this.toolbar = hierarchyToolbar
        this.tree = newTree

        expansionPolicy.applyInitialExpansion(newTree)

        val contentPanel = panel {
            row {
                cell(hierarchyToolbar.component)
            }
            row {
                label(MyMessageBundle.message("dependency.hierarchy.dialog.header", "$groupId:$artifactId"))
                    .bold()
            }
            row {
                cell(JBScrollPane(newTree))
                    .align(Align.FILL)
            }.resizableRow()
        }

        add(contentPanel, BorderLayout.CENTER)
        revalidate()
        repaint()
    }

    private fun createEmptyTree(): Tree {
        val emptyTree = Tree(DefaultTreeModel(DefaultMutableTreeNode()))
        if (onClose != null) {
            emptyTree.registerKeyboardAction(
                { onClose.invoke() },
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                WHEN_FOCUSED
            )
        }
        return emptyTree
    }

    private fun createHierarchyTree(
        rootData: DependencyHierarchyNode,
        groupId: String,
        artifactId: String,
        vulnerabilityAdvisories: Map<String, List<VulnerabilityAdvisory>> = emptyMap()
    ): Tree {
        val treeModel = DependencyHierarchyTreeModelBuilder().build(rootData)
        val newTree = Tree(treeModel).apply {
            isRootVisible = true
            showsRootHandles = true
            cellRenderer = DependencyHierarchyTreeCellRenderer(
                groupId,
                artifactId,
                vulnerabilityAdvisories,
                isDependencyInTable,
                groupIdsVisible
            )
            toolTipText = MyMessageBundle.message("dependency.hierarchy.dialog.tree.tooltip")
        }

        TreeSpeedSearch.installOn(newTree, false) { path ->
            val node = (path.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? DependencyHierarchyNode
            node?.let { "${it.groupId}:${it.artifactId} ${it.version.orEmpty()}" } ?: path.lastPathComponent.toString()
        }

        newTree.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (e.isPopupTrigger) {
                    showContextMenu(newTree, e)
                }
            }

            override fun mouseReleased(e: MouseEvent) {
                if (e.isPopupTrigger) {
                    showContextMenu(newTree, e)
                }
            }
        })

        newTree.registerKeyboardAction(
            { navigateToSelectedNode(newTree) },
            KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0),
            WHEN_FOCUSED
        )
        newTree.registerKeyboardAction(
            { navigateToSelectedNode(newTree) },
            KeyStroke.getKeyStroke(KeyEvent.VK_F4, 0),
            WHEN_FOCUSED
        )

        if (onClose != null) {
            newTree.registerKeyboardAction(
                { onClose.invoke() },
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                WHEN_FOCUSED
            )
        }

        return newTree
    }

    /**
     * Zeigt einen informativen Empty State im Hierarchiepanel an, wenn für die aktuelle
     * Tabellenselektion keine Hierarchie existiert oder keine Zeile ausgewählt ist.
     *
     * @param message Optionaler individueller Hinweistext.
     */
    fun showEmpty(message: String? = null) {
        currentGroupId = null
        currentArtifactId = null
        currentIsPlugin = false

        removeAll()

        val emptyTree = Tree(DefaultTreeModel(DefaultMutableTreeNode()))
        if (onClose != null) {
            emptyTree.registerKeyboardAction(
                { onClose.invoke() },
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                WHEN_FOCUSED
            )
        }
        val hierarchyToolbar = createToolbar(emptyTree)
        this.toolbar = hierarchyToolbar
        this.tree = emptyTree

        val emptyLabelText = message ?: MyMessageBundle.message("dependency.hierarchy.empty.noSelection")
        val emptyPanel = panel {
            row {
                cell(hierarchyToolbar.component)
            }
            row {
                cell(JBLabel(emptyLabelText).apply {
                    foreground = JBColor.GRAY
                })
            }
        }
        add(emptyPanel, BorderLayout.CENTER)
        revalidate()
        repaint()
    }

    /**
     * Prüft, ob für den aktuell ausgewählten Knoten eine Navigation in die pom.xml möglich ist.
     *
     * @param targetTree Der Baum mit der aktuellen Selektion.
     * @return `true`, wenn die Auswahl zu einer `pom.xml`-Stelle oder einer passenden
     *         Maven-Navigation springen kann.
     */
    internal fun canNavigateToSelectedNode(targetTree: JTree): Boolean {
        val selectedPath = targetTree.selectionPath ?: return false
        val treeNode = selectedPath.lastPathComponent as? DefaultMutableTreeNode ?: return false
        val node = treeNode.userObject as? DependencyHierarchyNode ?: return false
        return hierarchyNavigation.isNodeInPom(node)
    }

    /**
     * Prüft, ob für den aktuell ausgewählten Knoten eine Navigation in eine Tabellenansicht möglich ist.
     *
     * @param targetTree Der Baum mit der aktuellen Selektion.
     * @return `true`, wenn die Auswahl gültige Koordinaten für Group-ID und Artefakt-ID besitzt
     *         und in einer Tabellenansicht enthalten ist.
     */
    internal fun canNavigateToTable(targetTree: JTree): Boolean {
        val selectedPath = targetTree.selectionPath ?: return false
        val treeNode = selectedPath.lastPathComponent as? DefaultMutableTreeNode ?: return false
        val node = treeNode.userObject as? DependencyHierarchyNode ?: return false
        if (node.type == DependencyHierarchyNodeType.PROJECT || node.groupId.isBlank() || node.artifactId.isBlank()) {
            return false
        }
        return isDependencyInTable?.invoke(node.groupId, node.artifactId) ?: (onNavigateToTable != null)
    }

    /**
     * Erstellt die Toolbar-Aktionen des Hierarchie-Panels.
     *
     * @param targetTree Der zugehörige Baum.
     * @return Die Toolbar mit den Hierarchieaktionen und dem Schließen-Button.
     */
    internal fun createToolbar(targetTree: JTree): ActionToolbar =
        ActionManager.getInstance().createActionToolbar(
            "MavenUp.DependencyHierarchyPanel",
            DefaultActionGroup().apply {
                add(object : AnAction(
                    MyMessageBundle.message("dependency.hierarchy.toolbar.expandAll"),
                    null,
                    AllIcons.Actions.Expandall
                ) {
                    override fun getActionUpdateThread() = ActionUpdateThread.EDT
                    override fun actionPerformed(event: AnActionEvent) {
                        expandAllNodes(targetTree)
                    }
                })
                add(object : AnAction(
                    MyMessageBundle.message("dependency.hierarchy.toolbar.collapseAll"),
                    null,
                    AllIcons.Actions.Collapseall
                ) {
                    override fun getActionUpdateThread() = ActionUpdateThread.EDT
                    override fun actionPerformed(event: AnActionEvent) {
                        collapseAllNodes(targetTree)
                    }
                })
                add(object : ToggleAction(
                    MyMessageBundle.message("dependency.hierarchy.toolbar.groupIds"),
                    MyMessageBundle.message("dependency.hierarchy.toolbar.groupIds.tooltip"),
                    AllIcons.Actions.ShowAsTree
                ) {
                    override fun getActionUpdateThread() = ActionUpdateThread.EDT
                    override fun isSelected(event: AnActionEvent): Boolean = groupIdsVisible
                    override fun setSelected(event: AnActionEvent, state: Boolean) {
                        setGroupIdsVisible(targetTree, state)
                    }
                })
                add(Separator.getInstance())
                add(object : AnAction(
                    MyMessageBundle.message("toolwindow.MyToolWindow.contextMenu.navigateToPom"),
                    null,
                    AllIcons.General.Locate
                ) {
                    override fun getActionUpdateThread() = ActionUpdateThread.EDT
                    override fun update(event: AnActionEvent) {
                        event.presentation.isEnabled = canNavigateToSelectedNode(targetTree)
                    }
                    override fun actionPerformed(event: AnActionEvent) {
                        if (canNavigateToSelectedNode(targetTree)) {
                            navigateToSelectedNode(targetTree)
                        }
                    }
                })
                add(object : AnAction(
                    tableNavigationActionLabel(targetTree),
                    null,
                    SELECT_IN_TABLE_ICON
                ) {
                    override fun getActionUpdateThread() = ActionUpdateThread.EDT
                    override fun update(event: AnActionEvent) {
                        event.presentation.text = tableNavigationActionLabel(targetTree)
                        event.presentation.isEnabled = canNavigateToTable(targetTree)
                    }
                    override fun actionPerformed(event: AnActionEvent) {
                        if (canNavigateToTable(targetTree)) {
                            navigateToTableForSelectedNode(targetTree)
                        }
                    }
                })
                if (onClose != null) {
                    add(Separator.getInstance())
                    add(object : AnAction(
                        MyMessageBundle.message("button.close"),
                        null,
                        AllIcons.Actions.Cancel
                    ) {
                        override fun getActionUpdateThread() = ActionUpdateThread.EDT
                        override fun actionPerformed(event: AnActionEvent) {
                            onClose.invoke()
                        }
                    })
                }
            },
            true
        ).apply {
            // Der Baum selbst wird im Empty State (siehe [showEmpty] und der leere Zweig von
            // [showHierarchy]) nicht in die Komponentenhierarchie eingehängt und wäre daher nie
            // "showing". Die ActionManagerImpl verweigert dann jede Toolbar-Aktion (auch das
            // Schließen) mit der Warnung "target component is not showing". Das Panel selbst ist
            // dagegen immer sichtbar, solange die Split-View geöffnet ist.
            targetComponent = this@DependencyHierarchyPanel
        }

    /**
     * Schaltet die Darstellung der Group-IDs im angegebenen Hierarchiebaum.
     *
     * @param targetTree Der Hierarchiebaum, dessen Renderer aktualisiert werden soll.
     * @param visible `true`, um Group-IDs anzuzeigen, sonst `false`.
     */
    internal fun setGroupIdsVisible(targetTree: JTree, visible: Boolean) {
        groupIdsVisible = visible
        (targetTree.cellRenderer as? DependencyHierarchyTreeCellRenderer)?.groupIdsVisible = visible
        targetTree.repaint()
    }

    /**
     * Erstellt die Aktionsgruppe für das Kontextmenü des Hierarchiebaums.
     *
     * @param targetTree Der zugehörige Baum.
     * @return Die Aktionsgruppe mit den Navigationsaktionen.
     */
    internal fun createContextMenuGroup(targetTree: JTree): DefaultActionGroup =
        DefaultActionGroup().apply {
            add(object : AnAction(MyMessageBundle.message("toolwindow.MyToolWindow.contextMenu.navigateToPom")) {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun update(event: AnActionEvent) {
                    event.presentation.isEnabled = canNavigateToSelectedNode(targetTree)
                }
                override fun actionPerformed(event: AnActionEvent) {
                    if (canNavigateToSelectedNode(targetTree)) {
                        navigateToSelectedNode(targetTree)
                    }
                }
            })
            add(object : AnAction(tableNavigationActionLabel(targetTree)) {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun update(event: AnActionEvent) {
                    event.presentation.text = tableNavigationActionLabel(targetTree)
                    event.presentation.isEnabled = canNavigateToTable(targetTree)
                }
                override fun actionPerformed(event: AnActionEvent) {
                    if (canNavigateToTable(targetTree)) {
                        navigateToTableForSelectedNode(targetTree)
                    }
                }
            })
        }

    /**
     * Zeigt das Kontextmenü für den angeklickten Baumknoten an.
     *
     * @param targetTree Der zugehörige Baum.
     * @param e Das auslösende Maus-Ereignis.
     */
    internal fun showContextMenu(targetTree: JTree, e: MouseEvent) {
        val path = targetTree.getPathForLocation(e.x, e.y) ?: return
        if (!targetTree.isPathSelected(path)) {
            targetTree.selectionPath = path
        }
        val group = createContextMenuGroup(targetTree)
        ActionManager.getInstance().createActionPopupMenu(
            "MavenUp.DependencyHierarchyTree", group
        ).component.show(e.component, e.x, e.y)
    }

    /**
     * Klappt alle Knoten des Baums vollständig auf.
     *
     * @param targetTree Der zu expandierende Baum.
     */
    internal fun expandAllNodes(targetTree: JTree) {
        var row = 0
        while (row < targetTree.rowCount) {
            targetTree.expandRow(row)
            row++
        }
    }

    /**
     * Klappt alle Knoten des Baums vollständig zu.
     *
     * @param targetTree Der zu kollabierende Baum.
     */
    internal fun collapseAllNodes(targetTree: JTree) {
        for (row in targetTree.rowCount - 1 downTo 0) {
            targetTree.collapseRow(row)
        }
    }

    /**
     * Ermittelt die lokalisierte Beschriftung der Aktion für die Zieltabellen-Navigation.
     *
     * @param targetTree Der Baum mit der aktuellen Selektion.
     * @return Die Beschriftung für die tatsächliche Zieltabellenansicht.
     */
     internal fun tableNavigationActionLabel(targetTree: JTree): String {
         val defaultLabel = MyMessageBundle.message("dependency.hierarchy.action.navigateToDependencies")
         val selectedPath = targetTree.selectionPath ?: return defaultLabel
         val treeNode = selectedPath.lastPathComponent as? DefaultMutableTreeNode ?: return defaultLabel
         val node = treeNode.userObject as? DependencyHierarchyNode ?: return defaultLabel
         if (node.groupId.isBlank() || node.artifactId.isBlank()) return defaultLabel

         return tableNavigationLabelProvider?.invoke(node.groupId, node.artifactId) ?: defaultLabel
     }

    /**
     * Springt in der passenden Tabellenansicht zur ausgewählten Komponente.
     *
     * @param targetTree Der Baum mit der aktuellen Selektion.
     * @return `true`, wenn die Navigation ausgeführt wurde, sonst `false`.
     */
    internal fun navigateToTableForSelectedNode(targetTree: JTree): Boolean {
        val selectedPath = targetTree.selectionPath ?: return false
        val treeNode = selectedPath.lastPathComponent as? DefaultMutableTreeNode ?: return false
        val node = treeNode.userObject as? DependencyHierarchyNode ?: return false
        if (node.groupId.isBlank() || node.artifactId.isBlank()) return false

        return onNavigateToTable?.invoke(node.groupId, node.artifactId) ?: true
    }

    /**
     * Springt zur `pom.xml`-Definition des aktuell ausgewählten Baumknotens.
     *
     * @param targetTree Der Baum mit der aktuellen Selektion.
     */
    internal fun navigateToSelectedNode(targetTree: JTree) {
        val selectedPath = targetTree.selectionPath ?: return
        val treeNode = selectedPath.lastPathComponent as? DefaultMutableTreeNode ?: return
        val node = treeNode.userObject as? DependencyHierarchyNode ?: return

        hierarchyNavigation.navigateToNode(node)
    }

    companion object {
        private val SELECT_IN_TABLE_ICON = IconLoader.getIcon("/icons/selectInTable.svg", DependencyHierarchyPanel::class.java)
    }
}

/**
 * Zell-Renderer für den [DependencyHierarchyPanel]-Hierarchiebaum.
 *
 * Stellt Knoten typabhängig mit passendem Icon, einem vorangestellten Typ-Präfix
 * (z. B. `[Dependency Management]`, `DD`), Koordinaten und Version dar.
 * Die Ziel-Abhängigkeit wird zur schnellen Orientierung farblich hervorgehoben.
 * Vulnerable transitive Abhängigkeiten werden nach einem Sicherheits-Scan mit einem Warn-Icon
 * und einem kompakten Hinweis zu Schweregrad und Anzahl der Befunde gesondert gekennzeichnet.
 *
 * @param targetGroupId Group-ID der Zielkomponente zur farblichen Hervorhebung.
 * @param targetArtifactId Artefakt-ID der Zielkomponente zur farblichen Hervorhebung.
 * @param vulnerabilityAdvisories Zuordnung aller bekannten Koordinaten zu ihren Warnungen.
 * @param isDependencyInTable Prüft, ob eine Koordinate in der Haupttabelle oder der Tabelle
 *        transitiver CVEs vorkommt.
 * @param groupIdsVisible `true`, wenn Group-IDs vor den Artefakt-IDs angezeigt werden.
 */
class DependencyHierarchyTreeCellRenderer(
    private val targetGroupId: String? = null,
    private val targetArtifactId: String? = null,
    private val vulnerabilityAdvisories: Map<String, List<VulnerabilityAdvisory>> = emptyMap(),
    private val isDependencyInTable: ((groupId: String, artifactId: String) -> Boolean)? = null,
    var groupIdsVisible: Boolean = true
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
            renderHierarchyNode(userObject)
        } else if (userObject != null) {
            append(userObject.toString(), SimpleTextAttributes.REGULAR_ATTRIBUTES)
        }
    }

    /**
     * Formatiert einen Hierarchieknoten entsprechend seiner Relevanz für die Tabellenansichten.
     *
     * @param node Der darzustellende Hierarchieknoten.
     */
    private fun renderHierarchyNode(node: DependencyHierarchyNode) {
        val advisories = findAdvisories(node)
        val isVulnerableTransitive = advisories.isNotEmpty() &&
            node.type == DependencyHierarchyNodeType.TRANSITIVE_DEPENDENCY
        val isUnlistedTransitive = isUnlistedTransitiveDependency(node, isVulnerableTransitive)

        icon = when {
            isVulnerableTransitive -> AllIcons.General.BalloonWarning
            isUnlistedTransitive -> IconLoader.getDisabledIcon(nodeIcon(node.type))
            else -> nodeIcon(node.type)
        }

        val prefix = nodePrefix(node.type)
        if (prefix.isNotBlank()) {
            append("$prefix ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }

        val isTarget = isTargetDependency(node)
        val (coordAttributes, versionAttributes) = determineAttributes(
            isTarget,
            isVulnerableTransitive,
            isUnlistedTransitive
        )

        val coordinate = if (groupIdsVisible) {
            "${node.groupId}:${node.artifactId}"
        } else {
            node.artifactId
        }
        append(coordinate, coordAttributes)

        if (!node.version.isNullOrBlank()) {
            append(":${node.version}", versionAttributes)
        }

        val details = formatNodeDetails(node, advisories)
        if (details.isNotBlank()) {
            val detailsAttributes = if (isVulnerableTransitive) {
                SimpleTextAttributes(SimpleTextAttributes.STYLE_ITALIC, VULNERABLE_TEXT_COLOR)
            } else {
                SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES
            }
            append(" ($details)", detailsAttributes)
        }

        updateNodeTooltip(isVulnerableTransitive, advisories)
    }

    /**
     * Ermittelt die Textattribute für Ziel-, Sicherheits- und Kontextknoten.
     *
     * @param isTarget Gibt an, ob der Knoten die ausgewählte Zielkomponente darstellt.
     * @param isVulnerable Gibt an, ob der Knoten einen bekannten Sicherheitsbefund besitzt.
     * @param isUnlistedTransitive Gibt an, ob der Knoten nur als transitive Kontextinformation vorliegt.
     * @return Die Attribute für Koordinate und Version.
     */
    private fun determineAttributes(
        isTarget: Boolean,
        isVulnerable: Boolean,
        isUnlistedTransitive: Boolean
    ): Pair<SimpleTextAttributes, SimpleTextAttributes> = when {
        isTarget -> Pair(
            SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, TARGET_DEPENDENCY_COLOR),
            SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, TARGET_DEPENDENCY_COLOR)
        )
        isVulnerable -> Pair(
            SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, VULNERABLE_TEXT_COLOR),
            SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, VULNERABLE_TEXT_COLOR)
        )
        isUnlistedTransitive -> Pair(
            SimpleTextAttributes.GRAYED_ATTRIBUTES,
            SimpleTextAttributes.GRAYED_ATTRIBUTES
        )
        else -> Pair(
            SimpleTextAttributes.REGULAR_ATTRIBUTES,
            SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES
        )
    }

    /**
     * Setzt den kompakten Hinweis für vulnerable transitive Abhängigkeiten.
     *
     * Einzelne Advisory-Details werden bewusst nicht in den Tooltip aufgenommen, damit dieser
     * auch bei vielen oder umfangreichen Befunden übersichtlich bleibt.
     *
     * @param isVulnerableTransitive Gibt an, ob der Knoten eine verwundbare transitive Abhängigkeit darstellt.
     * @param advisories Die für den Knoten ermittelten Sicherheitswarnungen.
     */
    private fun updateNodeTooltip(isVulnerableTransitive: Boolean, advisories: List<VulnerabilityAdvisory>) {
        if (isVulnerableTransitive) {
            val severity = worstSeverity(advisories)
            toolTipText = MyMessageBundle.message(
                "dependency.hierarchy.node.vulnerable.tooltip",
                if (severity != VulnerabilitySeverity.UNKNOWN) severity.name else "-",
                advisories.size
            )
        } else {
            toolTipText = MyMessageBundle.message("dependency.hierarchy.dialog.tree.tooltip")
        }
    }

    /**
     * Ermittelt die passenden Sicherheitswarnungen für einen Hierarchieknoten.
     *
     * @param node Der zu prüfende Knoten.
     * @return Liste aller zutreffenden [VulnerabilityAdvisory]-Warnungen.
     */
    internal fun findAdvisories(node: DependencyHierarchyNode): List<VulnerabilityAdvisory> {
        if (node.groupId.isBlank() || node.artifactId.isBlank() || vulnerabilityAdvisories.isEmpty()) {
            return emptyList()
        }
        val version = node.version
        if (!version.isNullOrBlank()) {
            val exact = vulnerabilityAdvisories["${node.groupId}:${node.artifactId}:$version"]
            if (exact != null) return exact
        }
        return vulnerabilityAdvisories.entries
            .filter { it.key.startsWith("${node.groupId}:${node.artifactId}:") }
            .flatMap { it.value }
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

    /**
     * Prüft, ob ein transitiver Knoten ausschließlich als Kontext dient und deshalb zurückhaltend
     * dargestellt werden soll.
     *
     * Sicherheitsbefunde und Knoten ohne verfügbares Tabellen-Prädikat behalten ihre reguläre
     * Darstellung, damit keine relevante Information oder bestehende Einbettung abgeschwächt wird.
     *
     * @param node Der zu prüfende Hierarchieknoten.
     * @param isVulnerableTransitive Gibt an, ob der Knoten einen bekannten Sicherheitsbefund besitzt.
     * @return `true`, wenn der Knoten transitiv, nicht verwundbar und in keiner Tabelle vorhanden ist.
     */
    internal fun isUnlistedTransitiveDependency(
        node: DependencyHierarchyNode,
        isVulnerableTransitive: Boolean
    ): Boolean =
        node.type == DependencyHierarchyNodeType.TRANSITIVE_DEPENDENCY &&
            !isVulnerableTransitive &&
            isDependencyInTable?.invoke(node.groupId, node.artifactId) == false

    /**
     * Liefert das zur Art des Hierarchieknotens passende Icon.
     *
     * @param type Der Typ des Hierarchieknotens.
     * @return Das passende IntelliJ-Icon.
     */
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

    /**
     * Liefert das kompakte, lokalisierte Präfix für einen Hierarchieknotentyp.
     *
     * @param type Der Typ des Hierarchieknotens.
     * @return Das anzuzeigende Präfix.
     */
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

    /**
     * Baut die ergänzenden Detailinformationen eines Hierarchieknotens auf.
     *
     * @param node Der zu formatierende Hierarchieknoten.
     * @param advisories Die bekannten Sicherheitswarnungen des Knotens.
     * @return Die durch Kommata getrennten Detailinformationen.
     */
    internal fun formatNodeDetails(
        node: DependencyHierarchyNode,
        advisories: List<VulnerabilityAdvisory> = emptyList()
    ): String {
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
        if (advisories.isNotEmpty() && node.type == DependencyHierarchyNodeType.TRANSITIVE_DEPENDENCY) {
            val severity = worstSeverity(advisories)
            if (severity != VulnerabilitySeverity.UNKNOWN) {
                detailsList.add(MyMessageBundle.message("dependency.hierarchy.node.vulnerableWithSeverity", severity.name, advisories.size))
            } else {
                detailsList.add(MyMessageBundle.message("dependency.hierarchy.node.vulnerable", advisories.size))
            }
        }
        return detailsList.joinToString(", ")
    }

    companion object {
        private val TARGET_DEPENDENCY_COLOR = JBColor(Color(0x00, 0x55, 0xAA), Color(0x58, 0x9D, 0xF6))
        private val VULNERABLE_TEXT_COLOR = JBColor(Color(0xC7, 0x22, 0x22), Color(0xFF, 0x6B, 0x68))
    }
}
