package de.schwarzland.mavenup.ui

import de.schwarzland.mavenup.model.DependencyUpdate
import de.schwarzland.mavenup.model.VulnerabilityAdvisory
import de.schwarzland.mavenup.service.DependencyVersionService
import de.schwarzland.mavenup.service.MavenUpSettings
import de.schwarzland.mavenup.service.MAVEN_UP_SETTINGS_TOPIC
import de.schwarzland.mavenup.service.RefreshSnapshotCollector
import de.schwarzland.mavenup.service.PomUpdateService
import de.schwarzland.mavenup.service.PomNavigationService
import de.schwarzland.mavenup.service.VulnerabilityScanService
import de.schwarzland.mavenup.service.VersionAutoSelectionMode
import de.schwarzland.mavenup.service.VulnerabilityApiService
import de.schwarzland.mavenup.service.VulnerabilityMerger
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.actionSystem.impl.ActionButton
import com.intellij.ide.HelpTooltip
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DoNotAskOption
import com.intellij.openapi.ui.MessageDialogBuilder
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.content.ContentManager
import com.intellij.ui.content.ContentManagerEvent
import com.intellij.ui.content.ContentManagerListener
import com.intellij.ui.table.JBTable
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.AbstractTableCellEditor
import com.intellij.icons.AllIcons
import org.jetbrains.idea.maven.project.MavenImportListener
import org.jetbrains.idea.maven.project.MavenProject
import org.jetbrains.idea.maven.project.MavenProjectsManager
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.table.DefaultTableModel
import javax.swing.table.TableCellRenderer
import javax.swing.table.TableRowSorter


private val LOG = Logger.getInstance(MavenUpWindowFactory::class.java)


private const val TOOLWINDOW_MY_TOOL_WINDOW_RESET_VERSIONS_CONFIRM_TITLE = "toolwindow.MyToolWindow.resetVersions.confirm.title"

/**
 * -----------------------------------------------------------------------------------------------
 * Factory-Klasse zur Erstellung und Initialisierung des MavenUp Tool Windows in der IntelliJ-IDE.
 *
 * Diese Klasse registriert das Tool Window und bettet das [MyToolWindow]-Panel ein, welches die
 * Hauptoberfläche für die Maven-Abhängigkeitsverwaltung bereitstellt.
 */
class MavenUpWindowFactory : ToolWindowFactory {
    /**
     * Bestimmt, ob das Tool Window für das aktuelle Projekt verfügbar sein soll.
     * Es wird nur angezeigt, wenn Maven-Projekte im Projekt konfiguriert sind.
     */
    override fun shouldBeAvailable(project: Project): Boolean {
        return MavenProjectsManager.getInstance(project).hasProjects()
    }

    /**
     * Erstellt den Inhalt des Tool Windows und fügt ihn dem ContentManager hinzu.
     *
     * Beide Ansichten werden als eigenständige [Content]-Instanzen registriert. Die IDE stellt sie
     * dadurch als Tabs in der Kopfzeile des Tool Windows dar, sodass keine zusätzliche Tab-Zeile im
     * Inhaltsbereich nötig ist.
     */
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val myToolWindow = MyToolWindow(project)
        val contentFactory = ContentFactory.getInstance()

        val dependenciesContent = contentFactory.createContent(
            myToolWindow.getContent(),
            MyMessageBundle.message("toolwindow.MyToolWindow.tab.dependencies"),
            false
        ).apply {
            isCloseable = false
            description = MyMessageBundle.message("toolwindow.MyToolWindow.tab.dependencies.tooltip")
            setDisposer(myToolWindow)
        }

        val transitiveContent = contentFactory.createContent(
            myToolWindow.getTransitiveContent(),
            MyMessageBundle.message("toolwindow.MyToolWindow.tab.transitiveView"),
            false
        ).apply {
            isCloseable = false
            description = MyMessageBundle.message("toolwindow.MyToolWindow.tab.transitiveView.tooltip")
        }

        toolWindow.contentManager.addContent(dependenciesContent)
        toolWindow.contentManager.addContent(transitiveContent)
        myToolWindow.bindTabs(toolWindow.contentManager, dependenciesContent, transitiveContent)
    }

    /**
     * Die eigentliche Tool-Window-Komponente, die die Tabelle der Abhängigkeiten und die Aktions-Buttons verwaltet.
     *
     * Bewusste Ausnahme von der `LargeClass`-Regel: Diese Swing-basierte UI-Komponente bündelt
     * Tabellen-Setup, Toolbar-Aktionen, Filterlogik, Versionsauswahl sowie Update- und
     * Vulnerability-Prüfungen. Alle Bereiche teilen sich denselben veränderlichen UI- und
     * Datenzustand (u. a. [availableVersions], [selectedVersions], [dependencyToProperty],
     * [vulnerabilityAdvisories]) und greifen als `inner class` direkt auf [project] und die Services
     * zu. Eine Aufteilung würde diesen Zustand künstlich über Controller-Grenzen ziehen und ist
     * mit hohem Risiko für Verhalten und die bestehenden UI-Tests verbunden; daher wird die
     * Klassengröße hier bewusst in Kauf genommen.
     */
    @Suppress("LargeClass")
    internal inner class MyToolWindow(private val project: Project) : Disposable {
        private val vulnerabilityApiService = VulnerabilityApiService()
        private val vulnerabilityScanService = VulnerabilityScanService(project)
        private val dependencyVersionService = DependencyVersionService(project)
        private val refreshSnapshotCollector = RefreshSnapshotCollector(project)
        private val pomUpdateService = PomUpdateService(project)
        private val pomNavigationService = PomNavigationService(project)
        private val availableVersions = mutableMapOf<String, List<String>>()
        private val selectedVersions = mutableMapOf<String, String>()
        private val dependencyToProperty = mutableMapOf<String, String>()
        private val knownDependencies = mutableMapOf<String, String>() // key to current version
        private val knownTypes = mutableMapOf<String, String>()
        private val vulnerabilityAdvisories = mutableMapOf<String, List<VulnerabilityAdvisory>>()
        private val transitiveCoordinates = mutableSetOf<String>()
        private val transitiveDependenciesByDirect = mutableMapOf<String, Set<String>>()

        /**
         * Schlüssel (`groupId:artifactId`) der Einträge, deren Version nicht in der `pom.xml`
         * deklariert, sondern vom Parent-POM oder einem importierten BOM geerbt wird. Der
         * Renderer der Spalte **Current Version** hält eine lebende Referenz auf diese Menge,
         * und der Filter nach der Herkunft der Version wertet sie in [applyRowFilter] aus.
         */
        internal val inheritedVersionDependencies = mutableSetOf<String>()

        /**
         * Verfügbare Versionen der verwundbaren transitiven Koordinaten (`groupId:artifactId`), die
         * beim Vulnerability-Scan ermittelt werden. Bewusst getrennt von [availableVersions], damit die
         * New-Version-Spalte der transitiven Ansicht bei einer erneuten Versionssuche der Haupttabelle
         * (die [availableVersions] leert und neu füllt) nicht geleert wird.
         */
        private val transitiveAvailableVersions = mutableMapOf<String, List<String>>()

        /** Alternative Ansicht, die ausschließlich transitive, verwundbare Abhängigkeiten auflistet. */
        private val transitiveVulnerabilitiesView = TransitiveVulnerabilitiesView(project) { refreshToolbar() }

        /** Wurzelkomponente des Tabs **Transitive CVEs**: Aktionsleiste über der transitiven Ansicht. */
        private val transitiveContent = JBPanel<JBPanel<*>>(BorderLayout())

        /** Container für die Aktionsleiste des Tabs **Transitive CVEs**. */
        private val transitiveTopPanel = JBPanel<JBPanel<*>>(BorderLayout())

        /** ContentManager des Tool Windows; erst nach [bindTabs] gesetzt. */
        private var contentManager: ContentManager? = null

        /** Tab **Dependencies**; erst nach [bindTabs] gesetzt. */
        private var dependenciesTab: Content? = null

        /** Tab **Transitive CVEs**; erst nach [bindTabs] gesetzt. */
        private var transitiveTab: Content? = null

        /** `true`, solange der Tab der transitiven Sicherheitslücken-Ansicht ausgewählt ist. */
        private var showingTransitiveView = false

        /**
         * `true`, sobald mindestens eine erfolgreiche Vulnerability-Prüfung ("Scan for Vulnerabilities")
         * abgeschlossen wurde. Steuert die Aktivierung des Vulnerabilities-Filters.
         */
        private var vulnerabilityScanPerformed = false
        private var isUpdating = false
        private var isRefreshing = false
        private var refreshGeneration = 0

        /**
         * Zuletzt bekannter Wert der Auto-Selektionsstrategie.
         *
         * Dient dazu, bei einer Einstellungsänderung nur dann die "New Version"-Auswahl
         * neu zu berechnen, wenn sich die Strategie tatsächlich geändert hat.
         */
        private var lastVersionAutoSelectionMode =
            MavenUpSettings.getInstance().state.versionAutoSelectionMode

        /** Die Tabelle der Abhängigkeiten; wird im Property-Initializer von [content] zugewiesen. */
        private var table: JBTable

        /** Die Aktionsleiste des Tabs **Dependencies**; wird in [installToolbars] gesetzt. */
        private var actionToolbar: ActionToolbar? = null

        /** Die Aktionsleiste des Tabs **Transitive CVEs**; wird in [installToolbars] gesetzt. */
        private var transitiveActionToolbar: ActionToolbar? = null

        /** Die Aktionsgruppe der oberen Aktionsleiste; wird im Init-Block befüllt. */
        private val toolbarGroup = DefaultActionGroup()

        /** Eingabefeld für den Textfilter über GroupId, ArtifactId und Property. */
        internal val searchTextField = SearchTextField()

        /** Auswahlfeld für den Typfilter der Tabelle. */
        internal val typeFilterComboBox = ComboBox<String>()

        /** Auswahlfeld für den Filter nach anstehenden Änderungen (Ja/Nein/Alle). */
        internal val changesFilterComboBox = ComboBox(TriStateFilter.entries.toTypedArray())

        /**
         * Auswahlfeld für den Filter nach verfügbaren Updates (Ja/Nein/Alle).
         *
         * Nur aktiv, sobald eine erfolgreiche Versionssuche ("Refresh and Search for New Versions")
         * mindestens eine abrufbare Versionsliste geliefert hat (siehe [updateUpdatesFilterState]).
         */
        internal val updatesFilterComboBox = ComboBox(TriStateFilter.entries.toTypedArray())

        /**
         * Auswahlfeld für den Filter nach Sicherheitslücken
         * (Alle, verwundbar, selbst verwundbar, transitiv verwundbar, nicht verwundbar).
         */
        internal val vulnerabilitiesFilterComboBox = ComboBox(VulnerabilityFilter.entries.toTypedArray())

        /**
         * Auswahlfeld für den Filter nach der Herkunft der Version (Alle, geerbt, in der `pom.xml` deklariert).
         *
         * Nur aktiv, solange mindestens eine Zeile eine geerbte Version besitzt
         * (siehe [isVersionSourceFilterAvailable]).
         */
        internal val versionSourceFilterComboBox = ComboBox(TriStateFilter.entries.toTypedArray())

        /** Anzeigetext der Combobox-Option, die alle Typen zulässt. */
        private val allTypesFilterLabel =
            MyMessageBundle.message("toolwindow.MyToolWindow.filter.type.all")

        /** Container für die Aktionsleiste und die Filterzeile des Tabs **Dependencies**. */
        private val topPanel = JBPanel<JBPanel<*>>(BorderLayout())

        /** Aktionsleiste am Ende der Filterzeile zum Zurücksetzen aller Filter. */
        private var filterResetToolbar: ActionToolbar? = null

        /**
         * Row-Sorter der Tabelle, der sowohl das Filtern der Zeilen als auch das
         * spaltenweise Sortieren über die Kopfzeile übernimmt.
         */
        private var tableRowSorter: TableRowSorter<DefaultTableModel>

        private val content = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            val tableModel = object : DefaultTableModel() {
                override fun isCellEditable(row: Int, column: Int): Boolean = column == NEW_VERSION_COLUMN
            }.apply {
                addColumn(MyMessageBundle.message("toolwindow.MyToolWindow.table.header.groupId"))
                addColumn(MyMessageBundle.message("toolwindow.MyToolWindow.table.header.artifactId"))
                addColumn(MyMessageBundle.message("toolwindow.MyToolWindow.table.header.property"))
                addColumn(MyMessageBundle.message("toolwindow.MyToolWindow.table.header.type"))
                addColumn(MyMessageBundle.message("toolwindow.MyToolWindow.table.header.vulnerabilities"))
                addColumn(MyMessageBundle.message("toolwindow.MyToolWindow.table.header.currentVersion"))
                addColumn(MyMessageBundle.message("toolwindow.MyToolWindow.table.header.newVersion"))
            }

            table = object : JBTable(tableModel) {
                override fun getToolTipText(e: MouseEvent): String? {
                    val row = rowAtPoint(e.point)
                    val column = columnAtPoint(e.point)
                    if (row < 0 || column == VULNERABILITIES_COLUMN) return super.getToolTipText(e)
                    if (column == CURRENT_VERSION_COLUMN) {
                        val groupId = getValueAt(row, GROUP_ID_COLUMN) as? String ?: ""
                        val artifactId = getValueAt(row, ARTIFACT_ID_COLUMN) as? String ?: ""
                        inheritedVersionTooltip(inheritedVersionDependencies.contains("$groupId:$artifactId"))
                            ?.let { return it }
                    }
                    if (column == NEW_VERSION_COLUMN) {
                        @Suppress("UNCHECKED_CAST")
                        val versions = getValueAt(row, NEW_VERSION_COLUMN) as? List<String> ?: emptyList()
                        if (versions.isEmpty()) return null
                        val groupId = getValueAt(row, GROUP_ID_COLUMN) as? String ?: ""
                        val artifactId = getValueAt(row, ARTIFACT_ID_COLUMN) as? String ?: ""
                        val currentVersion = getValueAt(row, CURRENT_VERSION_COLUMN) as? String ?: ""
                        val newestVersion = versions.firstOrNull() ?: ""
                        val effectiveVersion = selectedVersions["$groupId:$artifactId"] ?: currentVersion
                        return versionStatusTooltip(currentVersion, effectiveVersion, newestVersion)
                    }
                    val settings = MavenUpSettings.getInstance()
                    return if (settings.state.jumpOnSingleClick)
                        MyMessageBundle.message("toolwindow.MyToolWindow.table.row.tooltip.singleClick")
                    else
                        MyMessageBundle.message("toolwindow.MyToolWindow.table.row.tooltip.doubleClick")
                }
            }
            table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
            table.tableHeader.reorderingAllowed = false
            applyRecommendedRowHeight(table)

            table.addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    if (e.isPopupTrigger) showContextMenu(e)
                }

                override fun mouseReleased(e: MouseEvent) {
                    if (e.isPopupTrigger) showContextMenu(e)
                }

                override fun mouseClicked(e: MouseEvent) {
                    val row = table.rowAtPoint(e.point)
                    val column = table.columnAtPoint(e.point)
                    if (row < 0) return
                    if (column == VULNERABILITIES_COLUMN && e.clickCount == 1) {
                        val cell = table.getValueAt(row, VULNERABILITIES_COLUMN) as? VulnerabilityCell
                        if (cell != null && cell.allAdvisories.isNotEmpty()) {
                            val coordinate = listOf(GROUP_ID_COLUMN, ARTIFACT_ID_COLUMN, CURRENT_VERSION_COLUMN)
                                .joinToString(":") { table.getValueAt(row, it).toString() }
                            VulnerabilityDetailDialog(
                                project,
                                cell.detailFindings(),
                                "$coordinate - ${MyMessageBundle.message(VULNERABILITY_DETAILS_TITLE)}",
                                cell.detailOrigins()
                            ).show()
                        }
                        return
                    }

                    val settings = MavenUpSettings.getInstance()
                    val requiredClickCount = if (settings.state.jumpOnSingleClick) 1 else 2

                    if (e.clickCount == requiredClickCount) {
                        val groupId = table.getValueAt(row, GROUP_ID_COLUMN) as? String ?: ""
                        val artifactId = table.getValueAt(row, ARTIFACT_ID_COLUMN) as? String ?: ""
                        val type = table.getValueAt(row, TYPE_COLUMN) as? String ?: "dependency"

                        pomNavigationService.navigateToDependency(groupId, artifactId, type)
                    }
                }

                private fun showContextMenu(e: MouseEvent) {
                    val row = table.rowAtPoint(e.point)
                    if (row < 0) return
                    if (!table.isRowSelected(row)) {
                        table.setRowSelectionInterval(row, row)
                    }
                    val column = table.columnAtPoint(e.point)
                    val groupId = table.getValueAt(row, GROUP_ID_COLUMN) as? String ?: ""
                    val artifactId = table.getValueAt(row, ARTIFACT_ID_COLUMN) as? String ?: ""
                    val property = table.getValueAt(row, PROPERTY_COLUMN) as? String ?: ""
                    val type = table.getValueAt(row, TYPE_COLUMN) as? String ?: "dependency"
                    val currentVersion = table.getValueAt(row, CURRENT_VERSION_COLUMN) as? String ?: ""
                    val vulnerabilityCell = table.getValueAt(row, VULNERABILITIES_COLUMN) as? VulnerabilityCell

                    // Build the menu through IntelliJ's ActionSystem. This is what gives
                    // native context menus their current spacing, rounded border and
                    // theme-aware (light-blue in Light theme) selection color.
                    val group = DefaultActionGroup()
                    fun addAction(label: String, enabled: Boolean = true, action: () -> Unit) {
                        group.add(object : AnAction(label) {
                            override fun getActionUpdateThread() = ActionUpdateThread.BGT
                            override fun update(e: AnActionEvent) {
                                e.presentation.isEnabled = enabled
                            }
                            override fun actionPerformed(e: AnActionEvent) = action()
                        })
                    }
                    val filterValue = when (column) {
                        GROUP_ID_COLUMN -> groupId
                        ARTIFACT_ID_COLUMN -> artifactId
                        PROPERTY_COLUMN -> property
                        else -> ""
                    }
                    if (filterValue.isNotBlank()) {
                        addAction(MyMessageBundle.message(
                            "toolwindow.MyToolWindow.contextMenu.filterBy", filterValue)) { filterBy(filterValue) }
                        group.addSeparator()
                    }
                    addAction(MyMessageBundle.message("toolwindow.MyToolWindow.contextMenu.navigateToPom")) {
                        pomNavigationService.navigateToDependency(groupId, artifactId, type)
                    }
                    val browserName = MavenUpSettings.getInstance().state.repositoryBrowser.displayName
                    addAction(MyMessageBundle.message(
                        TOOLWINDOW_MY_TOOL_WINDOW_CONTEXT_MENU_OPEN_IN_MVN_REPOSITORY, browserName)) {
                        openInMavenRepository(groupId, artifactId, currentVersion)
                    }
                    val dependencyKey = "$groupId:$artifactId"
                    val versionsAvailable = hasSelectableVersionsForDependency(dependencyKey)
                    group.addSeparator()
                    addAction(MyMessageBundle.message("toolwindow.MyToolWindow.contextMenu.selectHighestMajor"), versionsAvailable) {
                        selectHighestMajorVersionForDependency(dependencyKey)
                    }
                    addAction(MyMessageBundle.message("toolwindow.MyToolWindow.contextMenu.selectHighestMinor"), versionsAvailable) {
                        selectHighestMinorVersionForDependency(dependencyKey)
                    }
                    addAction(MyMessageBundle.message("toolwindow.MyToolWindow.contextMenu.selectRecommended"),
                        hasRecommendedVersionForDependency(dependencyKey)) {
                        selectRecommendedVersionForDependency(dependencyKey)
                    }
                    addAction(MyMessageBundle.message("toolwindow.MyToolWindow.contextMenu.resetToCurrent"),
                        isVersionResetEnabledForDependency(dependencyKey)) {
                        resetVersionForDependency(dependencyKey)
                    }
                    val hasVulnerabilities = vulnerabilityCell != null && vulnerabilityCell.allAdvisories.isNotEmpty()
                    group.addSeparator()
                    addAction(MyMessageBundle.message("toolwindow.MyToolWindow.contextMenu.showVulnerabilityDetails"), hasVulnerabilities) {
                        val cell = vulnerabilityCell ?: return@addAction
                        val coordinate = "$groupId:$artifactId:$currentVersion"
                        VulnerabilityDetailDialog(
                            project,
                            cell.detailFindings(),
                            "$coordinate - ${MyMessageBundle.message(VULNERABILITY_DETAILS_TITLE)}",
                            cell.detailOrigins()
                        ).show()
                    }
                    ActionManager.getInstance().createActionPopupMenu(
                        "MavenUp.DependencyTable", group
                    ).component.show(e.component, e.x, e.y)
                }
            })

            // Force commit editor when focus lost
            table.putClientProperty("terminateEditOnFocusLost", true)

            table.selectionModel.addListSelectionListener { event ->
                if (!event.valueIsAdjusting) {
                    refreshToolbar()
                }
            }

            transitiveVulnerabilitiesView.table.selectionModel.addListSelectionListener { event ->
                if (!event.valueIsAdjusting) {
                    refreshToolbar()
                }
            }

            tableRowSorter = object : TableRowSorter<DefaultTableModel>(tableModel) {
                /**
                 * Schaltet die Sortierung einer Spalte zyklisch weiter: aufsteigend →
                 * absteigend → unsortiert (Reihenfolge wie in der pom.xml).
                 *
                 * @param column Modellindex der angeklickten Spalte.
                 */
                override fun toggleSortOrder(column: Int) {
                    if (!isSortable(column)) return
                    val current = sortKeys.firstOrNull { it.column == column }?.sortOrder
                    val next = when (current) {
                        SortOrder.ASCENDING -> SortOrder.DESCENDING
                        SortOrder.DESCENDING -> SortOrder.UNSORTED
                        else -> SortOrder.ASCENDING
                    }
                    sortKeys = if (next == SortOrder.UNSORTED) {
                        emptyList()
                    } else {
                        listOf(SortKey(column, next))
                    }
                }
            }
            val textComparator = Comparator<Any?> { a, b ->
                String.CASE_INSENSITIVE_ORDER.compare(a?.toString().orEmpty(), b?.toString().orEmpty())
            }
            for (columnIndex in 0 until tableModel.columnCount) {
                when (columnIndex) {
                    CURRENT_VERSION_COLUMN, NEW_VERSION_COLUMN ->
                        tableRowSorter.setSortable(columnIndex, false)
                    VULNERABILITIES_COLUMN -> {
                        tableRowSorter.setSortable(columnIndex, true)
                        tableRowSorter.setComparator(columnIndex, vulnerabilityCellComparator)
                    }
                    else -> {
                        tableRowSorter.setSortable(columnIndex, true)
                        tableRowSorter.setComparator(columnIndex, textComparator)
                    }
                }
            }
            table.rowSorter = tableRowSorter
            installSortableHeaderRenderer(table)
            applyRowFilter()

            // Custom Renderer and Editor for the "New Version" column
            table.columnModel.getColumn(CURRENT_VERSION_COLUMN).cellRenderer =
                createCurrentVersionRenderer(inheritedVersionDependencies)
            table.columnModel.getColumn(NEW_VERSION_COLUMN).cellRenderer =
                TableCellRenderer { table, value, isSelected, _, row, _ ->
                    val groupId = table?.getValueAt(row, GROUP_ID_COLUMN) as? String ?: ""
                    val artifactId = table?.getValueAt(row, ARTIFACT_ID_COLUMN) as? String ?: ""
                    val key = "$groupId:$artifactId"

                    @Suppress("UNCHECKED_CAST")
                    val versions = value as? List<String> ?: emptyList()
                    if (versions.isEmpty()) return@TableCellRenderer JLabel("")

                    val selectedVersion = selectedVersions[key]
                    val currentVersion = table?.getValueAt(row, CURRENT_VERSION_COLUMN) as? String ?: ""
                    val newestVersion = versions.firstOrNull() ?: ""
                    val effectiveVersion = selectedVersion ?: currentVersion
                    val upToDate = isVersionUpToDate(effectiveVersion, newestVersion)
                    val hasChange = effectiveVersion != currentVersion && effectiveVersion.isNotEmpty()

                    val combo = ComboBox(versions.toTypedArray()).apply {
                        if (effectiveVersion.isNotEmpty()) {
                            selectedItem = effectiveVersion
                        }
                        if (hasChange) {
                            foreground = versionStatusColor(upToDate)
                        }

                        if (isSelected) {
                            background = table?.selectionBackground
                        }
                    }

                    createVersionPanel(
                        combo,
                        versionStatusText(upToDate),
                        if (hasChange) versionStatusColor(upToDate) else null,
                        versionStatusTooltip(currentVersion, effectiveVersion, newestVersion),
                        hasChange
                    )
                }

            table.columnModel.getColumn(NEW_VERSION_COLUMN).cellEditor = object : AbstractTableCellEditor() {
                private var currentComboBox: ComboBox<String>? = null
                private var currentKey: String? = null
                private var editorPanel: JPanel? = null

                override fun getTableCellEditorComponent(
                    table: JTable?, value: Any?, isSelected: Boolean, row: Int, column: Int
                ): Component {
                    val groupId = table?.getValueAt(row, GROUP_ID_COLUMN) as? String ?: ""
                    val artifactId = table?.getValueAt(row, ARTIFACT_ID_COLUMN) as? String ?: ""
                    currentKey = "$groupId:$artifactId"

                    @Suppress("UNCHECKED_CAST")
                    val versions = value as? List<String> ?: emptyList()
                    val combo = ComboBox(versions.toTypedArray())

                    val currentVersion = table?.getValueAt(row, CURRENT_VERSION_COLUMN) as? String ?: ""
                    val newestVersion = versions.firstOrNull() ?: ""

                    val selectedVersion = if (currentKey != null) selectedVersions[currentKey!!] else null
                    val effectiveVersion = selectedVersion ?: currentVersion
                    if (effectiveVersion.isNotEmpty()) {
                        combo.selectedItem = effectiveVersion
                    }

                    val upToDate = isVersionUpToDate(effectiveVersion, newestVersion)
                    val hasChange = effectiveVersion != currentVersion && effectiveVersion.isNotEmpty()
                    if (hasChange) {
                        combo.foreground = versionStatusColor(upToDate)
                    }

                    // Gemeinsamer Dropdown-Renderer: Farbe und Font werden nur im Anzeigefeld übernommen
                    // (nicht im Dropdown); aktuelle und empfohlene Version werden in der Liste markiert.
                    applyVersionDropdownRenderer(
                        combo,
                        currentVersion,
                        recommendedVersionForDependency(currentKey.orEmpty())
                    )

                    combo.addActionListener {
                        val selected = combo.selectedItem as? String
                        val key = currentKey
                        if (key != null && selected != null) {
                            synchronizePropertyVersions(key, selected)
                        }
                        updateUpdateButtonState()
                        updateEditorVisuals(combo, editorPanel, selected, currentVersion, newestVersion)
                        applyRowFilter()
                    }

                    currentComboBox = combo
                    val panel = createVersionPanel(
                        combo,
                        versionStatusText(upToDate),
                        if (hasChange) versionStatusColor(upToDate) else null,
                        versionStatusTooltip(currentVersion, effectiveVersion, newestVersion),
                        hasChange
                    )
                    editorPanel = panel
                    return panel
                }

                override fun getCellEditorValue(): Any? {
                    val selected = currentComboBox?.selectedItem as? String
                    if (currentKey != null && selected != null) {
                        synchronizePropertyVersions(currentKey!!, selected)
                    }
                    updateUpdateButtonState()
                    val groupId = currentKey?.substringBefore(":")
                    val artifactId = currentKey?.substringAfter(":")
                    return availableVersions["$groupId:$artifactId"]
                }
            }

            table.columnModel.getColumn(VULNERABILITIES_COLUMN).cellRenderer = vulnerabilityCellRenderer()

            /**
             * Liest die Abhängigkeiten der `pom.xml`-Dateien neu ein und baut die Tabelle auf.
             *
             * @param checkUpdates Wenn `true`, wird im Anschluss online nach neuen Versionen gesucht.
             * @param clearData Wenn `true`, werden verfügbare Versionen und Versionsauswahlen verworfen.
             * @param clearVulnerabilities Wenn `true`, werden Scan-Ergebnisse und transitive Daten verworfen.
             */
            fun refreshAction(checkUpdates: Boolean, clearData: Boolean, clearVulnerabilities: Boolean) {
                if (isUpdating) return

                val generation = ++refreshGeneration
                isRefreshing = true
                refreshToolbar()
                cancelActiveCellEditing()
                tableModel.setRowCount(0)
                if (clearData) {
                    availableVersions.clear()
                    selectedVersions.clear()
                }
                if (clearVulnerabilities) {
                    vulnerabilityAdvisories.clear()
                    transitiveCoordinates.clear()
                    transitiveDependenciesByDirect.clear()
                    transitiveAvailableVersions.clear()
                    vulnerabilityScanPerformed = false
                }
                dependencyToProperty.clear()
                knownDependencies.clear()
                knownTypes.clear()
                inheritedVersionDependencies.clear()
                updateUpdateButtonState()

                val managedDependencyType =
                    MyMessageBundle.message(TOOLWINDOW_MY_TOOL_WINDOW_TYPE_MANAGED_DEPENDENCY)
                ReadAction.nonBlocking<RefreshSnapshot> {
                    refreshSnapshotCollector.collectRefreshSnapshot(managedDependencyType)
                }.expireWith(this@MyToolWindow)
                    .finishOnUiThread(ModalityState.any()) { snapshot ->
                        if (generation != refreshGeneration) {
                            return@finishOnUiThread
                        }

                        dependencyToProperty.putAll(snapshot.dependencyProperties)
                        val declaredCoordinates = snapshot.rows
                            .filter { it.currentVersion.isNotEmpty() }
                            .mapTo(linkedSetOf()) { "${it.key}:${it.currentVersion}" }
                        snapshot.rows.forEach { row ->
                            knownDependencies[row.key] = row.currentVersion
                            knownTypes[row.key] = row.type
                            if (row.versionInherited) {
                                inheritedVersionDependencies.add(row.key)
                            }
                            tableModel.addRow(
                                arrayOf(
                                    row.groupId,
                                    row.artifactId,
                                    row.propertyName,
                                    row.type,
                                    buildVulnerabilityCell(
                                        "${row.key}:${row.currentVersion}",
                                        vulnerabilityAdvisories,
                                        transitiveDependenciesByDirect["${row.key}:${row.currentVersion}"].orEmpty(),
                                        declaredCoordinates
                                    ),
                                    row.currentVersion,
                                    availableVersions[row.key].orEmpty()
                                )
                            )
                        }
                        updateUpdateButtonState()
                        updateTypeFilterOptions()
                        updateUpdatesFilterState()
                        updateVulnerabilitiesFilterState()
                        updateVersionSourceFilterState()
                        updateTransitiveVulnerabilitiesView()
                        trimColumnWidthsToContent(table)

                        if (checkUpdates) {
                            performUpdateCheck {
                                refreshAction(false, false, false)
                            }
                        }
                        isRefreshing = false
                        refreshToolbar()
                    }
                    .submit(AppExecutorUtil.getAppExecutorService())
            }


            val updateAction = {
                if (!isUpdating && (selectedVersions.isNotEmpty() || transitiveVulnerabilitiesView.hasPendingUpdates())) {
                    val updates = collectSelectedUpdates()

                    if (updates.isNotEmpty()) {
                        val dialog = UpdateConfirmationDialog(project, updates)
                        if (dialog.showAndGet()) {
                            val shouldSyncMaven = dialog.isSyncMavenSelected()
                            MavenUpSettings.getInstance().state.syncMavenAfterUpdate = shouldSyncMaven
                            ProgressManager.getInstance().run(object : Task.Backgroundable(
                                project,
                                MyMessageBundle.message("toolwindow.MyToolWindow.update.progress"),
                                true
                            ) {
                                override fun run(indicator: ProgressIndicator) {
                                    val mavenManager = MavenProjectsManager.getInstance(project)
                                    val pomFiles = mavenManager.projects.map { it.file }
                                    mavenManager.projects.forEach { mavenProject ->
                                        pomUpdateService.applyUpdateToPom(mavenProject, updates)
                                    }

                                    if (shouldSyncMaven) {
                                        pomUpdateService.persistPomChanges(pomFiles)
                                        mavenManager.forceUpdateAllProjectsOrFindAllAvailablePomFiles()
                                    }

                                    ApplicationManager.getApplication().invokeLater {
                                        selectedVersions.clear()
                                        availableVersions.clear()
                                        transitiveVulnerabilitiesView.resetSelections()
                                        refreshAction(false, true, true)

                                        for (row in 0 until tableModel.rowCount) {
                                            tableModel.setValueAt("", row, CURRENT_VERSION_COLUMN)
                                            tableModel.setValueAt(emptyList<String>(), row, NEW_VERSION_COLUMN)
                                        }
                                    }
                                }
                            })
                        }
                    }
                }
            }

            val checkVulnerabilitiesAction = {
                if (!isUpdating) {
                    isUpdating = true
                    refreshToolbar()

                    performVulnerabilityCheck {
                        isUpdating = false
                        refreshAction(false, false, false)
                        refreshToolbar()
                    }
                }
            }

            refreshAction(isAutoVersionSearchEnabled(), true, true)

            add(JBScrollPane(table), BorderLayout.CENTER)
            transitiveContent.add(transitiveTopPanel, BorderLayout.NORTH)
            transitiveContent.add(transitiveVulnerabilitiesView, BorderLayout.CENTER)

            fun toolbarAction(
                messageKey: String,
                icon: Icon,
                isEnabled: () -> Boolean,
                shortLabelKey: String? = null,
                descriptionProvider: (() -> String)? = null,
                isMenuItem: Boolean = false,
                onPerform: () -> Unit
            ): AnAction {
                val label = MyMessageBundle.message(messageKey)
                return object : AnAction(label, label, icon) {
                    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
                    override fun update(e: AnActionEvent) {
                        e.presentation.isEnabled = isEnabled()
                        val fullText = descriptionProvider?.invoke() ?: label
                        if (isMenuItem) {
                            // In einem Untermenü wird immer der vollständige Text angezeigt.
                            e.presentation.text = label
                            e.presentation.description = fullText
                            return
                        }
                        val showText = isToolbarTextEnabled()
                        val shortLabel = shortLabelKey?.let { MyMessageBundle.message(it) } ?: label
                        // Der Tooltip muss in beiden Anzeigemodi identisch sein. Über CUSTOM_HELP_TOOLTIP
                        // wird der Tooltip komplett vorgegeben; sowohl ActionButton (Icon-Modus) als auch
                        // ActionButtonWithText (Text-Modus) übernehmen ihn unverändert und zeigen so stets
                        // nur den vollständigen (umbrechenden) Text – unabhängig von der Textbeschriftung.
                        e.presentation.text = shortLabel
                        e.presentation.description = fullText
                        e.presentation.putClientProperty(ActionButton.CUSTOM_HELP_TOOLTIP, HelpTooltip().withWrappingDescription(fullText))
                        e.presentation.putClientProperty(ActionUtil.SHOW_TEXT_IN_TOOLBAR, showText)
                    }

                    override fun actionPerformed(e: AnActionEvent) = onPerform()
                }
            }

            val openInRepositoryAction = object : AnAction() {
                override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
                override fun update(e: AnActionEvent) {
                    val fullLabel = currentOpenInRepositoryText()
                    val showText = isToolbarTextEnabled()
                    val shortLabel = MyMessageBundle.message("toolwindow.MyToolWindow.openInRepository.button.short")
                    // Identischer Tooltip in beiden Modi über CUSTOM_HELP_TOOLTIP (siehe toolbarAction);
                    // der volle Text inkl. Browser-Name wird stets als umbrechende Beschreibung gezeigt.
                    e.presentation.text = shortLabel
                    e.presentation.description = fullLabel
                    e.presentation.putClientProperty(ActionButton.CUSTOM_HELP_TOOLTIP, HelpTooltip().withWrappingDescription(fullLabel))
                    e.presentation.icon = AllIcons.General.Web
                    e.presentation.isEnabled = isOpenInRepositoryEnabled()
                    e.presentation.putClientProperty(ActionUtil.SHOW_TEXT_IN_TOOLBAR, showText)
                }

                override fun actionPerformed(e: AnActionEvent) = openInMavenRepositoryForSelectedRow()
            }

            // Die beiden "Select Highest"-Aktionen werden in einem aufklappbaren Untermenü gebündelt,
            // damit die Symbolleiste auch bei aktiven Textbeschriftungen kompakt bleibt. Beide wirken
            // ausschließlich auf die aktuell sichtbaren (nicht ausgefilterten) Zeilen.
            val versionActionsGroup = object : DefaultActionGroup(
                MyMessageBundle.message("toolwindow.MyToolWindow.versionActions.group.button"),
                true
            ) {
                override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
                override fun update(e: AnActionEvent) {
                    val showText = isToolbarTextEnabled()
                    val shortLabel = MyMessageBundle.message("toolwindow.MyToolWindow.versionActions.group.button.short")
                    val tooltip = MyMessageBundle.message("toolwindow.MyToolWindow.versionActions.group.tooltip")
                    e.presentation.isEnabled = isBulkVersionSelectionEnabledForCurrentView() ||
                        isRecommendedSelectionEnabledForCurrentView()
                    // Identischer Tooltip in beiden Modi über CUSTOM_HELP_TOOLTIP (siehe toolbarAction);
                    // der lange Tooltip wird stets als umbrechende Beschreibung gezeigt.
                    e.presentation.text = shortLabel
                    e.presentation.description = tooltip
                    e.presentation.putClientProperty(ActionButton.CUSTOM_HELP_TOOLTIP, HelpTooltip().withWrappingDescription(tooltip))
                    e.presentation.icon = VersionUpdateArrowIcon
                    e.presentation.putClientProperty(ActionUtil.SHOW_TEXT_IN_TOOLBAR, showText)
                }
            }.apply {
                templatePresentation.icon = VersionUpdateArrowIcon
                add(toolbarAction(
                    "toolwindow.MyToolWindow.selectHighestMajor.button",
                    AllIcons.Actions.Play_last,
                    { isBulkVersionSelectionEnabledForCurrentView() },
                    descriptionProvider = {
                        bulkSelectionActionDescription(
                            MyMessageBundle.message("toolwindow.MyToolWindow.selectHighestMajor.button")
                        )
                    },
                    isMenuItem = true
                ) {
                    if (showingTransitiveView) transitiveVulnerabilitiesView.selectHighestMajorVersionForAll()
                    else selectHighestMajorVersionForAll()
                })
                add(toolbarAction(
                    "toolwindow.MyToolWindow.selectHighestMinor.button",
                    AllIcons.Actions.Play_forward,
                    { isBulkVersionSelectionEnabledForCurrentView() },
                    descriptionProvider = {
                        bulkSelectionActionDescription(
                            MyMessageBundle.message("toolwindow.MyToolWindow.selectHighestMinor.button")
                        )
                    },
                    isMenuItem = true
                ) {
                    if (showingTransitiveView) transitiveVulnerabilitiesView.selectHighestMinorVersionForAll()
                    else selectHighestMinorVersionForAll()
                })
                add(toolbarAction(
                    "toolwindow.MyToolWindow.selectRecommended.button",
                    AllIcons.Actions.Checked,
                    { isRecommendedSelectionEnabledForCurrentView() },
                    descriptionProvider = {
                        bulkSelectionActionDescription(
                            MyMessageBundle.message("toolwindow.MyToolWindow.selectRecommended.button")
                        )
                    },
                    isMenuItem = true
                ) {
                    if (showingTransitiveView) transitiveVulnerabilitiesView.selectRecommendedVersionForAll()
                    else selectRecommendedVersionForAll()
                })
            }

            toolbarGroup.apply {
                add(toolbarAction(
                    "toolwindow.MyToolWindow.refresh.button",
                    AllIcons.Actions.Refresh,
                    { isRefreshEnabled() },
                    shortLabelKey = "toolwindow.MyToolWindow.refresh.button.short",
                    descriptionProvider = {
                        MyMessageBundle.message("toolwindow.MyToolWindow.refresh.tooltip")
                    }
                ) { refreshAction(true, true, true) })
                add(toolbarAction(
                    "toolwindow.MyToolWindow.checkVulnerabilities.button",
                    AllIcons.General.InspectionsEye,
                    { isCheckVulnerabilitiesEnabled() },
                    shortLabelKey = "toolwindow.MyToolWindow.checkVulnerabilities.button.short"
                ) { checkVulnerabilitiesAction() })
                add(toolbarAction(
                    "toolwindow.MyToolWindow.update.button",
                    AllIcons.Actions.Execute,
                    { isUpdateActionEnabled() },
                    shortLabelKey = "toolwindow.MyToolWindow.update.button.short"
                ) { updateAction() })
                addSeparator()
                add(versionActionsGroup)
                add(toolbarAction(
                    "toolwindow.MyToolWindow.resetVersions.button",
                    AllIcons.Actions.Undo,
                    { isResetVersionsEnabledForCurrentView() },
                    shortLabelKey = "toolwindow.MyToolWindow.resetVersions.button.short",
                    descriptionProvider = {
                        MyMessageBundle.message("toolwindow.MyToolWindow.resetVersions.tooltip")
                    }
                ) {
                    if (showingTransitiveView) confirmAndResetTransitiveSelections()
                    else confirmAndResetAllVersionsToCurrent()
                })
                addSeparator()
                add(openInRepositoryAction)
                add(toolbarAction(
                    "toolwindow.MyToolWindow.vulnerabilityDetails.button",
                    AllIcons.General.BalloonWarning,
                    { isVulnerabilityDetailsEnabled() },
                    shortLabelKey = "toolwindow.MyToolWindow.vulnerabilityDetails.button.short"
                ) { openVulnerabilityDetailsForSelectedRow() })
                add(Separator.getInstance())
                add(toolbarAction(
                    "toolwindow.MyToolWindow.settings.button",
                    AllIcons.General.Settings,
                    { true }
                ) { openSettings() })
            }
            installToolbars()
            topPanel.add(buildFilterPanel(), BorderLayout.SOUTH)
            add(topPanel, BorderLayout.NORTH)

            project.messageBus.connect(this@MyToolWindow).subscribe(MavenImportListener.TOPIC, object : MavenImportListener {
                override fun importFinished(
                    importedProjects: Collection<MavenProject>,
                    newModules: List<com.intellij.openapi.module.Module>
                ) {
                    ApplicationManager.getApplication().invokeLater {
                        availableVersions.clear()
                        selectedVersions.clear()
                        refreshAction(isAutoVersionSearchEnabled(), true, true)
                    }
                }
            })

            project.messageBus.connect(this@MyToolWindow).subscribe(MAVEN_UP_SETTINGS_TOPIC, Runnable {
                ApplicationManager.getApplication().invokeLater {
                    rebuildToolbar()
                    applySelectLatestVersionSettingIfChanged()
                }
            })
        }

        override fun dispose() = Unit

        /**
         * Verbindet die Komponente mit den Tabs des Tool Windows.
         *
         * Registriert einen [ContentManagerListener], der einen Tab-Wechsel in [showingTransitiveView]
         * überträgt, und setzt den Titel des transitiven Tabs initial.
         *
         * @param manager ContentManager des Tool Windows.
         * @param dependencies Tab mit der Hauptabhängigkeitstabelle.
         * @param transitive Tab mit der transitiven Sicherheitslücken-Ansicht.
         */
        internal fun bindTabs(manager: ContentManager, dependencies: Content, transitive: Content) {
            contentManager = manager
            dependenciesTab = dependencies
            transitiveTab = transitive
            manager.addContentManagerListener(object : ContentManagerListener {
                override fun selectionChanged(event: ContentManagerEvent) = applySelectedTab()
            })
            updateTransitiveTabTitle()
            applySelectedTab()
        }

        /**
         * Erzeugt eine Aktionsleiste über der gemeinsamen [toolbarGroup] für die angegebene Zielkomponente.
         *
         * Beide Tabs benötigen eine eigene Instanz, da eine Swing-Komponente nur einen Container haben kann.
         *
         * @param target Komponente, gegen die die Aktionen ihren Kontext auflösen.
         * @return Die neu erzeugte Aktionsleiste.
         */
        private fun createToolbar(target: JComponent): ActionToolbar =
            ActionManager.getInstance()
                .createActionToolbar("MavenUpToolWindow", toolbarGroup, true)
                .also { it.targetComponent = target }

        /**
         * Erzeugt die Aktionsleisten beider Tabs und hängt sie in ihre Container ein.
         *
         * Bereits vorhandene Aktionsleisten werden zuvor entfernt, damit ein geänderter Text-/Icon-Modus
         * wirksam wird. Muss auf dem Event Dispatch Thread laufen.
         */
        private fun installToolbars() {
            actionToolbar?.let { topPanel.remove(it.component) }
            val dependenciesBar = createToolbar(content)
            actionToolbar = dependenciesBar
            topPanel.add(dependenciesBar.component, BorderLayout.NORTH)
            topPanel.revalidate()
            topPanel.repaint()

            transitiveActionToolbar?.let { transitiveTopPanel.remove(it.component) }
            val transitiveBar = createToolbar(transitiveVulnerabilitiesView)
            transitiveActionToolbar = transitiveBar
            transitiveTopPanel.add(transitiveBar.component, BorderLayout.NORTH)
            transitiveTopPanel.revalidate()
            transitiveTopPanel.repaint()
        }

        /**
         * Fordert die Aktionsleisten beider Tabs auf, den Aktivierungszustand und die Beschriftungen
         * ihrer Aktionen neu zu berechnen.
         */
        private fun refreshToolbar() {
            actionToolbar?.updateActionsAsync()
            transitiveActionToolbar?.updateActionsAsync()
        }

        /**
         * Prüft, ob die Aktionsleisten gemäß den Einstellungen Text-Buttons statt reiner Icon-Buttons
         * anzeigen sollen.
         *
         * @return `true`, wenn Text-Buttons angezeigt werden sollen.
         */
        internal fun isToolbarTextEnabled(): Boolean =
            MavenUpSettings.getInstance().state.toolbarShowText

        /**
         * Liefert die obersten Aktionen der oberen Aktionsleiste (inklusive Untermenü-Gruppen und Trenner).
         *
         * Dient primär Tests, um den Aufbau der Aktionsleiste (z.B. das Sammelauswahl-Untermenü) zu prüfen.
         *
         * @return Die direkt in der Aktionsleiste registrierten Aktionen in ihrer Reihenfolge.
         */
        internal fun topToolbarActions(): Array<AnAction> = toolbarGroup.childActionsOrStubs

        /**
         * Erstellt die Filterzeile mit Typ-, Versionsherkunfts-, Updates-, Änderungs- und Vulnerabilities-Combobox
         * sowie Textfeld unterhalb der Aktionsleiste.
         *
         * @return Die konfigurierte Filter-Komponente.
         */
        private fun buildFilterPanel(): JComponent {
            val panel = JBPanel<JBPanel<*>>(BorderLayout())
            panel.border = BorderFactory.createEmptyBorder(2, 4, 2, 4)

            val filterControlsPanel = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 4, 0))

            filterControlsPanel.add(JLabel(MyMessageBundle.message("toolwindow.MyToolWindow.filter.type.label")))
            typeFilterComboBox.model = DefaultComboBoxModel(arrayOf(allTypesFilterLabel))
            typeFilterComboBox.toolTipText = MyMessageBundle.message("toolwindow.MyToolWindow.filter.type.tooltip")
            typeFilterComboBox.addActionListener { applyRowFilter() }
            filterControlsPanel.add(typeFilterComboBox)

            filterControlsPanel.add(JLabel(MyMessageBundle.message("toolwindow.MyToolWindow.filter.versionSource.label")))
            versionSourceFilterComboBox.model = DefaultComboBoxModel(TriStateFilter.entries.toTypedArray())
            versionSourceFilterComboBox.selectedItem = TriStateFilter.ALL
            versionSourceFilterComboBox.renderer = triStateFilterRenderer(VERSION_SOURCE_FILTER_LABELS)
            versionSourceFilterComboBox.toolTipText =
                MyMessageBundle.message("toolwindow.MyToolWindow.filter.versionSource.tooltip")
            versionSourceFilterComboBox.isEnabled = isVersionSourceFilterAvailable()
            versionSourceFilterComboBox.addActionListener { applyRowFilter() }
            filterControlsPanel.add(versionSourceFilterComboBox)

            filterControlsPanel.add(JLabel(MyMessageBundle.message("toolwindow.MyToolWindow.filter.updates.label")))
            updatesFilterComboBox.model = DefaultComboBoxModel(TriStateFilter.entries.toTypedArray())
            updatesFilterComboBox.selectedItem = TriStateFilter.ALL
            updatesFilterComboBox.renderer = triStateFilterRenderer(UPDATES_FILTER_LABELS)
            updatesFilterComboBox.toolTipText = MyMessageBundle.message("toolwindow.MyToolWindow.filter.updates.tooltip")
            updatesFilterComboBox.isEnabled = isUpdatesFilterAvailable()
            updatesFilterComboBox.addActionListener { applyRowFilter() }
            filterControlsPanel.add(updatesFilterComboBox)

            filterControlsPanel.add(JLabel(MyMessageBundle.message("toolwindow.MyToolWindow.filter.changes.label")))
            changesFilterComboBox.model = DefaultComboBoxModel(TriStateFilter.entries.toTypedArray())
            changesFilterComboBox.selectedItem = TriStateFilter.ALL
            changesFilterComboBox.renderer = triStateFilterRenderer(CHANGES_FILTER_LABELS)
            changesFilterComboBox.toolTipText = MyMessageBundle.message("toolwindow.MyToolWindow.filter.changes.tooltip")
            changesFilterComboBox.isEnabled = isChangesFilterAvailable()
            changesFilterComboBox.addActionListener { applyRowFilter() }
            filterControlsPanel.add(changesFilterComboBox)

            filterControlsPanel.add(JLabel(MyMessageBundle.message("toolwindow.MyToolWindow.filter.vulnerabilities.label")))
            vulnerabilitiesFilterComboBox.model = DefaultComboBoxModel(VulnerabilityFilter.entries.toTypedArray())
            vulnerabilitiesFilterComboBox.selectedItem = VulnerabilityFilter.ALL
            vulnerabilitiesFilterComboBox.renderer = vulnerabilityFilterRenderer()
            vulnerabilitiesFilterComboBox.toolTipText =
                MyMessageBundle.message("toolwindow.MyToolWindow.filter.vulnerabilities.tooltip")
            vulnerabilitiesFilterComboBox.isEnabled = isVulnerabilitiesFilterAvailable()
            vulnerabilitiesFilterComboBox.addActionListener { applyRowFilter() }
            filterControlsPanel.add(vulnerabilitiesFilterComboBox)

            panel.add(filterControlsPanel, BorderLayout.WEST)

            searchTextField.textEditor.emptyText.text =
                MyMessageBundle.message("toolwindow.MyToolWindow.filter.search.placeholder")
            searchTextField.toolTipText = MyMessageBundle.message("toolwindow.MyToolWindow.filter.search.tooltip")
            searchTextField.textEditor.toolTipText =
                MyMessageBundle.message("toolwindow.MyToolWindow.filter.search.tooltip")
            searchTextField.addDocumentListener(object : DocumentAdapter() {
                override fun textChanged(e: DocumentEvent) = applyRowFilter()
            })
            panel.add(searchTextField, BorderLayout.CENTER)

            panel.add(buildFilterResetToolbar(), BorderLayout.EAST)

            return panel
        }

        /**
         * Erstellt eine schmale Aktionsleiste mit einem einzelnen Icon-Button, der alle Filter
         * der Filterzeile zurücksetzt.
         *
         * Der Button wird am Ende der Filterzeile platziert und ist nur aktiv, solange mindestens
         * ein Filter aktiv ist (siehe [isResetFiltersEnabled]).
         *
         * @return Die Toolbar-Komponente mit der Reset-Aktion.
         */
        private fun buildFilterResetToolbar(): JComponent {
            val resetTitle = MyMessageBundle.message("toolwindow.MyToolWindow.filter.reset.button")
            val resetAction = object : AnAction(resetTitle, null, AllIcons.General.Reset) {
                override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = isResetFiltersEnabled()
                }

                override fun actionPerformed(e: AnActionEvent) = resetAllFilters()
            }
            val group = DefaultActionGroup().apply { add(resetAction) }
            val toolbar = ActionManager.getInstance()
                .createActionToolbar("MavenUpFilterReset", group, true)
            toolbar.targetComponent = searchTextField
            filterResetToolbar = toolbar
            return toolbar.component
        }

        /**
         * Prüft, ob aktuell mindestens ein Filter der Filterzeile aktiv ist.
         *
         * @return `true`, wenn Suchtext, Typ-, Änderungs-, Updates-, Vulnerabilities- oder
         *         Versionsherkunfts-Filter von ihrem Standardwert abweichen.
         */
        internal fun isResetFiltersEnabled(): Boolean {
            val searchActive = searchTextField.text.isNotEmpty()
            val typeActive = (typeFilterComboBox.selectedItem as? String ?: allTypesFilterLabel) != allTypesFilterLabel
            val changesActive = (changesFilterComboBox.selectedItem as? TriStateFilter ?: TriStateFilter.ALL) != TriStateFilter.ALL
            val updatesActive =
                (updatesFilterComboBox.selectedItem as? TriStateFilter ?: TriStateFilter.ALL) != TriStateFilter.ALL
            val vulnerabilitiesActive =
                (vulnerabilitiesFilterComboBox.selectedItem as? VulnerabilityFilter ?: VulnerabilityFilter.ALL) != VulnerabilityFilter.ALL
            val versionSourceActive =
                (versionSourceFilterComboBox.selectedItem as? TriStateFilter ?: TriStateFilter.ALL) != TriStateFilter.ALL
            return searchActive || typeActive || changesActive || updatesActive ||
                vulnerabilitiesActive || versionSourceActive
        }

        /**
         * Übernimmt den übergebenen Wert als alleinigen Textfilter der Filterzeile und
         * aktualisiert die Tabellenansicht.
         *
         * Ein eventuell bereits vorhandener Suchtext wird vollständig durch [value] ersetzt.
         * Das Setzen des Textes löst über den [DocumentListener][searchTextField] die
         * Neuberechnung des Filters aus; zur Sicherheit wird [applyRowFilter] zusätzlich
         * explizit aufgerufen.
         *
         * @param value Der zu setzende Filtertext (typischerweise eine GroupId, ArtifactId
         *              oder ein Property-Name aus dem Kontextmenü).
         */
        internal fun filterBy(value: String) {
            searchTextField.text = value
            applyRowFilter()
        }

        /**
         * Setzt alle Filter der Filterzeile auf ihren Standardwert zurück und aktualisiert die
         * Tabellenansicht.
         *
         * Zurückgesetzt werden Suchtext, Typ-, Änderungs-, Updates-, Vulnerabilities- und Versionsherkunfts-Filter.
         */
        internal fun resetAllFilters() {
            searchTextField.text = ""
            typeFilterComboBox.selectedItem = allTypesFilterLabel
            changesFilterComboBox.selectedItem = TriStateFilter.ALL
            updatesFilterComboBox.selectedItem = TriStateFilter.ALL
            vulnerabilitiesFilterComboBox.selectedItem = VulnerabilityFilter.ALL
            versionSourceFilterComboBox.selectedItem = TriStateFilter.ALL
            applyRowFilter()
        }

        /**
         * Prüft, ob nach dem letzten Scan mindestens eine transitive, verwundbare Abhängigkeit vorliegt.
         *
         * @return `true`, wenn eine transitive Koordinate mindestens eine Sicherheitswarnung besitzt.
         */
        internal fun hasTransitiveVulnerabilities(): Boolean = transitiveVulnerabilityCount() > 0

        /**
         * Zählt die transitiven Koordinaten, für die mindestens eine Sicherheitswarnung vorliegt.
         *
         * @return Anzahl der verwundbaren transitiven Abhängigkeiten.
         */
        internal fun transitiveVulnerabilityCount(): Int =
            transitiveCoordinates.count { vulnerabilityAdvisories[it]?.isNotEmpty() == true }

        /**
         * Wählt den Tab der Hauptabhängigkeitstabelle oder den der transitiven Sicherheitslücken-Ansicht.
         *
         * Sind die Tabs noch nicht über [bindTabs] verbunden (z.B. in Tests ohne Tool Window), wird nur
         * der interne Zustand geführt.
         *
         * @param visible `true`, um den transitiven Tab zu wählen, `false` für die Hauptabhängigkeitstabelle.
         */
        internal fun setTransitiveViewVisible(visible: Boolean) {
            val manager = contentManager
            val target = if (visible) transitiveTab else dependenciesTab
            if (manager != null && target != null) {
                manager.setSelectedContent(target)
                applySelectedTab()
                return
            }
            if (showingTransitiveView == visible) return
            showingTransitiveView = visible
            refreshToolbar()
        }

        /**
         * Übernimmt den aktuell ausgewählten Tab in den internen Zustand und aktualisiert die
         * Aktionsleisten, damit die Toolbar-Aktionen auf die sichtbare Tabelle wirken.
         */
        private fun applySelectedTab() {
            val transitive = transitiveTab ?: return
            val visible = contentManager?.selectedContent === transitive
            if (showingTransitiveView == visible) return
            showingTransitiveView = visible
            refreshToolbar()
        }

        /**
         * Schreibt die Anzahl der verwundbaren transitiven Koordinaten in den Titel des transitiven Tabs.
         *
         * Der Tab bleibt bewusst stets auswählbar; ohne Funde erklärt der Empty State der Tabelle, wie
         * Ergebnisse erzeugt werden.
         */
        private fun updateTransitiveTabTitle() {
            val count = transitiveVulnerabilityCount()
            transitiveTab?.displayName = if (count > 0) {
                MyMessageBundle.message("toolwindow.MyToolWindow.tab.transitiveView.withCount", count)
            } else {
                MyMessageBundle.message("toolwindow.MyToolWindow.tab.transitiveView")
            }
        }

        /**
         * Aktualisiert die transitive Sicherheitslücken-Ansicht mit den aktuellen Scan-Ergebnissen
         * und schreibt die Anzahl der Funde in den Tab-Titel.
         */
        internal fun updateTransitiveVulnerabilitiesView() {
            transitiveVulnerabilitiesView.update(
                vulnerabilityAdvisories,
                transitiveCoordinates,
                knownTypes,
                availableVersions + transitiveAvailableVersions
            )
            updateTransitiveTabTitle()
            refreshToolbar()
        }

        /**
         * Wendet die aktuellen Filter (Suchtext, Typ, Versionsherkunft, Änderungen, Updates, Sicherheitslücken)
         * auf die Tabelle an.
         *
         * Liest den Suchtext aus [searchTextField], den gewählten Typ aus [typeFilterComboBox],
         * die Filteroptionen aus [changesFilterComboBox], [updatesFilterComboBox], [vulnerabilitiesFilterComboBox]
         * und [versionSourceFilterComboBox] und setzt einen entsprechenden [RowFilter] auf den [tableRowSorter].
         */
        internal fun applyRowFilter() {
            val searchText = searchTextField.text
            val selectedType = typeFilterComboBox.selectedItem as? String ?: allTypesFilterLabel
            val typeFilter = if (selectedType == allTypesFilterLabel) "" else selectedType
            val changesFilter = changesFilterComboBox.selectedItem as? TriStateFilter ?: TriStateFilter.ALL
            val updatesFilter = updatesFilterComboBox.selectedItem as? TriStateFilter ?: TriStateFilter.ALL
            val vulnerabilitiesFilter =
                vulnerabilitiesFilterComboBox.selectedItem as? VulnerabilityFilter ?: VulnerabilityFilter.ALL
            val versionSourceFilter =
                versionSourceFilterComboBox.selectedItem as? TriStateFilter ?: TriStateFilter.ALL

            tableRowSorter.rowFilter = object : RowFilter<DefaultTableModel, Int>() {
                override fun include(entry: Entry<out DefaultTableModel, out Int>): Boolean {
                    val groupId = entry.getValue(GROUP_ID_COLUMN)?.toString().orEmpty()
                    val artifactId = entry.getValue(ARTIFACT_ID_COLUMN)?.toString().orEmpty()
                    val property = entry.getValue(PROPERTY_COLUMN)?.toString().orEmpty()
                    val type = entry.getValue(TYPE_COLUMN)?.toString().orEmpty()
                    val currentVersion = entry.getValue(CURRENT_VERSION_COLUMN)?.toString().orEmpty()
                    val cell = entry.getValue(VULNERABILITIES_COLUMN) as? VulnerabilityCell

                    val key = "$groupId:$artifactId"
                    val selectedVersion = selectedVersions[key]
                    val effectiveVersion = selectedVersion ?: currentVersion
                    val hasChange = effectiveVersion != currentVersion && effectiveVersion.isNotEmpty()
                    val newestVersion = availableVersions[key]?.firstOrNull().orEmpty()
                    val hasUpdate = hasNewerVersion(currentVersion, newestVersion)

                    return rowMatchesFilter(
                        FilterRow(
                            groupId = groupId,
                            artifactId = artifactId,
                            property = property,
                            type = type,
                            hasChange = hasChange,
                            hasUpdate = hasUpdate,
                            hasDirectVulnerabilities = cell?.hasDirectAdvisories == true,
                            hasTransitiveVulnerabilities = cell?.hasTransitiveAdvisories == true,
                            versionInherited = inheritedVersionDependencies.contains(key)
                        ),
                        FilterCriteria(
                            searchText = searchText,
                            typeFilter = typeFilter,
                            changesFilter = changesFilter,
                            updatesFilter = updatesFilter,
                            vulnerabilitiesFilter = vulnerabilitiesFilter,
                            versionSourceFilter = versionSourceFilter
                        )
                    )
                }
            }
            filterResetToolbar?.updateActionsAsync()
        }

        /**
         * Aktualisiert die Auswahlmöglichkeiten der Typ-Combobox anhand der aktuell in der
         * Tabelle vorhandenen Typen.
         *
         * Die bisherige Auswahl bleibt erhalten, sofern der Typ weiterhin existiert; andernfalls
         * wird auf "alle Typen" zurückgesetzt.
         */
        internal fun updateTypeFilterOptions() {
            val model = table.model as DefaultTableModel
            val types = (0 until model.rowCount)
                .mapNotNull { model.getValueAt(it, TYPE_COLUMN) as? String }
                .filter { it.isNotBlank() }
                .distinct()
                .sorted()
            val previouslySelected = typeFilterComboBox.selectedItem as? String ?: allTypesFilterLabel
            typeFilterComboBox.model =
                DefaultComboBoxModel((listOf(allTypesFilterLabel) + types).toTypedArray())
            typeFilterComboBox.selectedItem =
                if (types.contains(previouslySelected)) previouslySelected else allTypesFilterLabel
        }

        /**
         * Prüft, ob der Änderungs-Filter verwendet werden darf.
         *
         * Der Filter setzt voraus, dass für mindestens eine Abhängigkeit eine von der aktuellen
         * Version abweichende Version ausgewählt wurde.
         *
         * @return `true`, wenn mindestens eine anstehende Versionsänderung vorliegt.
         */
        internal fun isChangesFilterAvailable(): Boolean = hasSelectedUpdates()

        /**
         * Aktualisiert den Aktivierungszustand des Änderungs-Filters.
         *
         * Der Filter wird nur aktiviert, wenn mindestens eine abweichende Version ausgewählt wurde
         * (siehe [isChangesFilterAvailable]). Ist er nicht verfügbar, wird die Auswahl auf
         * [TriStateFilter.ALL] zurückgesetzt, damit keine unsichtbare Filterung aktiv bleibt.
         */
        internal fun updateChangesFilterState() {
            val available = isChangesFilterAvailable()
            changesFilterComboBox.isEnabled = available
            if (!available && changesFilterComboBox.selectedItem != TriStateFilter.ALL) {
                changesFilterComboBox.selectedItem = TriStateFilter.ALL
            }
        }

        /**
         * Prüft, ob der Updates-Filter verwendet werden darf.
         *
         * Der Filter setzt eine erfolgreiche Versionssuche ("Refresh and Search for New Versions") voraus,
         * die für mindestens ein Artefakt eine Versionsliste geliefert hat.
         *
         * @return `true`, wenn mindestens eine Abhängigkeit abrufbare Versionen besitzt.
         */
        internal fun isUpdatesFilterAvailable(): Boolean =
            availableVersions.values.any { it.isNotEmpty() }

        /**
         * Aktualisiert den Aktivierungszustand des Updates-Filters.
         *
         * Der Filter wird nur aktiviert, wenn eine erfolgreiche Versionssuche durchgeführt wurde
         * (siehe [isUpdatesFilterAvailable]). Ist er nicht verfügbar, wird die Auswahl auf
         * [TriStateFilter.ALL] zurückgesetzt, damit keine unsichtbare Filterung aktiv bleibt.
         */
        internal fun updateUpdatesFilterState() {
            val available = isUpdatesFilterAvailable()
            updatesFilterComboBox.isEnabled = available
            if (!available && updatesFilterComboBox.selectedItem != TriStateFilter.ALL) {
                updatesFilterComboBox.selectedItem = TriStateFilter.ALL
            }
        }

        /**
         * Prüft, ob der Vulnerabilities-Filter verwendet werden darf.
         *
         * Der Filter setzt eine erfolgreiche Sicherheitsprüfung ("Scan for Vulnerabilities") voraus.
         *
         * @return `true`, wenn mindestens eine Sicherheitsprüfung abgeschlossen wurde.
         */
        internal fun isVulnerabilitiesFilterAvailable(): Boolean = vulnerabilityScanPerformed

        /**
         * Aktualisiert den Aktivierungszustand des Vulnerabilities-Filters.
         *
         * Der Filter wird nur aktiviert, wenn eine erfolgreiche Sicherheitsprüfung durchgeführt wurde
         * (siehe [isVulnerabilitiesFilterAvailable]). Ist er nicht verfügbar, wird die Auswahl auf
         * [VulnerabilityFilter.ALL] zurückgesetzt, damit keine unsichtbare Filterung aktiv bleibt.
         */
        internal fun updateVulnerabilitiesFilterState() {
            val available = isVulnerabilitiesFilterAvailable()
            vulnerabilitiesFilterComboBox.isEnabled = available
            if (!available && vulnerabilitiesFilterComboBox.selectedItem != VulnerabilityFilter.ALL) {
                vulnerabilitiesFilterComboBox.selectedItem = VulnerabilityFilter.ALL
            }
        }

        /**
         * Prüft, ob der Filter nach der Herkunft der Version verwendet werden darf.
         *
         * Der Filter ist nur sinnvoll, wenn überhaupt eine Zeile ihre Version vom Parent-POM oder
         * einem importierten BOM erbt; andernfalls würde jede Option dieselbe Menge liefern.
         *
         * @return `true`, wenn mindestens eine Zeile eine geerbte Version besitzt.
         */
        internal fun isVersionSourceFilterAvailable(): Boolean = inheritedVersionDependencies.isNotEmpty()

        /**
         * Aktualisiert den Aktivierungszustand des Filters nach der Herkunft der Version.
         *
         * Ist der Filter nicht verfügbar (siehe [isVersionSourceFilterAvailable]), wird die Auswahl auf
         * [TriStateFilter.ALL] zurückgesetzt, damit keine unsichtbare Filterung aktiv bleibt.
         */
        internal fun updateVersionSourceFilterState() {
            val available = isVersionSourceFilterAvailable()
            versionSourceFilterComboBox.isEnabled = available
            if (!available && versionSourceFilterComboBox.selectedItem != TriStateFilter.ALL) {
                versionSourceFilterComboBox.selectedItem = TriStateFilter.ALL
            }
        }

        /**
         * Bricht eine aktive Zell-Bearbeitung der Haupttabelle ab, bevor deren Zeilen entfernt
         * oder neu aufgebaut werden.
         *
         * Ohne diesen Abbruch kann ein noch geöffneter Zell-Editor der Spalte "New Version" beim
         * späteren Layout (`columnMarginChanged` → `editingStopped`) versuchen, seinen Wert in eine
         * bereits entfernte Zeile zu schreiben, was eine [ArrayIndexOutOfBoundsException] im
         * `DefaultTableModel` auslöst.
         *
         * @return `true`, wenn eine laufende Bearbeitung abgebrochen wurde, sonst `false`.
         */
        internal fun cancelActiveCellEditing(): Boolean {
            if (table.isEditing) {
                table.cellEditor?.cancelCellEditing()
                return true
            }
            return false
        }

        /**
         * Baut die Aktionsleisten beider Tabs neu auf, damit ein geänderter Text-/Icon-Modus wirksam wird.
         * Muss auf dem Event Dispatch Thread laufen.
         */
        private fun rebuildToolbar() = installToolbars()

        /**
         * Wendet die Auto-Selektionsstrategie nur dann erneut an, wenn sich ihr Wert geändert hat.
         *
         * Dadurch setzt das Ändern anderer Einstellungen (z.B. Text-Buttons oder Maven-Sync) die
         * bereits getroffene **New Version**-Auswahl nicht mehr zurück.
         */
        internal fun applySelectLatestVersionSettingIfChanged() {
            val autoSelectionMode = MavenUpSettings.getInstance().state.versionAutoSelectionMode
            if (autoSelectionMode != lastVersionAutoSelectionMode) {
                lastVersionAutoSelectionMode = autoSelectionMode
                applySelectLatestVersionSetting()
            }
        }

        /**
         * Wendet die konfigurierte Auto-Selektionsstrategie auf alle bereits geladenen Abhängigkeiten an.
         *
         * Wird aufgerufen, wenn sich die Einstellung ändert, damit die "New Version"-Spalte sofort
         * die korrekte Auswahl widerspiegelt.
         */
        internal fun applySelectLatestVersionSetting() {
            if (availableVersions.isEmpty()) return
            val autoSelectionMode = MavenUpSettings.getInstance().state.versionAutoSelectionMode
            for ((key, versions) in availableVersions) {
                if (versions.isEmpty()) continue
                val currentVersion = knownDependencies[key] ?: ""
                if (autoSelectionMode != VersionAutoSelectionMode.DISABLED) {
                    val autoSelectedVersion = chooseAutoSelectedVersion(currentVersion, versions, autoSelectionMode)
                    if (autoSelectedVersion != currentVersion) {
                        selectedVersions[key] = autoSelectedVersion
                    } else {
                        selectedVersions.remove(key)
                    }
                } else {
                    selectedVersions[key] = currentVersion
                }
            }
            cancelActiveCellEditing()
            table.repaint()
            updateUpdateButtonState()
            applyRowFilter()
        }

        /**
         * Wählt für alle aktuell in der Tabelle sichtbaren Abhängigkeiten die höchste verfügbare Version
         * (über alle Major-Linien hinweg) aus.
         *
         * Ist ein Filter aktiv, werden ausgeblendete Einträge bewusst nicht verändert. Die neueste Version
         * steht jeweils an erster Stelle der von [de.schwarzland.mavenup.service.DependencyApiService.fetchVersions] gelieferten Liste.
         */
        internal fun selectHighestMajorVersionForAll() {
            applyBulkVersionSelection(visibleOnly = true) { _, _, versions -> versions.firstOrNull().orEmpty() }
        }

        /**
         * Wählt für alle aktuell in der Tabelle sichtbaren Abhängigkeiten die höchste Version innerhalb
         * derselben Major-Linie wie die aktuell verwendete Version aus.
         *
         * Ist ein Filter aktiv, werden ausgeblendete Einträge bewusst nicht verändert. Existiert keine
         * passende Version derselben Major-Linie, bleibt die aktuelle Version erhalten.
         */
        internal fun selectHighestMinorVersionForAll() {
            applyBulkVersionSelection(visibleOnly = true) { _, current, versions ->
                latestVersionWithinSameMajor(current, versions) ?: current
            }
        }

        /**
         * Wählt für alle aktuell sichtbaren Abhängigkeiten mit **eigenen** (nicht nur transitiven)
         * Sicherheitswarnungen die empfohlene Fix-Version aus.
         *
         * Abhängigkeiten ohne eigene Warnungen oder ohne auswählbare Empfehlung bleiben unverändert.
         * Ist ein Filter aktiv, werden ausgeblendete Einträge bewusst nicht verändert.
         */
        internal fun selectRecommendedVersionForAll() {
            applyBulkVersionSelection(visibleOnly = true) { key, current, _ ->
                recommendedVersionForDependency(key).ifEmpty { current }
            }
        }

        /**
         * Wählt für eine einzelne Abhängigkeit die höchste verfügbare Version (über alle Major-Linien
         * hinweg) aus.
         *
         * Wirkt ausschließlich auf die per [key] identifizierte Abhängigkeit und setzt eine Auswahl nur,
         * wenn deren verfügbare Versionen bereits abgerufen wurden. Verwenden mehrere Einträge dieselbe
         * Maven-Property, werden sie gemeinsam aktualisiert.
         *
         * @param key Der Schlüssel (`groupId:artifactId`) der Abhängigkeit.
         */
        internal fun selectHighestMajorVersionForDependency(key: String) {
            applySingleVersionSelection(key) { _, versions -> versions.firstOrNull().orEmpty() }
        }

        /**
         * Wählt für eine einzelne Abhängigkeit die empfohlene Fix-Version ihrer **eigenen**
         * Sicherheitswarnungen aus.
         *
         * Liegt keine auswählbare Empfehlung vor, bleibt die bestehende Auswahl unverändert.
         * Verwenden mehrere Einträge dieselbe Maven-Property, werden sie gemeinsam aktualisiert.
         *
         * @param key Der Schlüssel (`groupId:artifactId`) der Abhängigkeit.
         */
        internal fun selectRecommendedVersionForDependency(key: String) {
            val recommended = recommendedVersionForDependency(key)
            if (recommended.isEmpty()) return
            applySingleVersionSelection(key) { _, _ -> recommended }
        }

        /**
         * Ermittelt die auswählbare empfohlene Fix-Version einer direkten Abhängigkeit.
         *
         * Berücksichtigt ausschließlich Warnungen der Abhängigkeit selbst (Koordinate
         * `groupId:artifactId:version`), nicht die ihrer transitiven Kinder. Die über
         * [recommendedFixVersion] ermittelte Empfehlung wird anschließend über
         * [selectableRecommendedVersion] auf die tatsächlich abrufbaren Versionen abgebildet.
         *
         * @param key Der Schlüssel (`groupId:artifactId`) der Abhängigkeit.
         * @return Die auswählbare Empfehlung oder ein leerer String, wenn keine vorliegt.
         */
        internal fun recommendedVersionForDependency(key: String): String {
            if (vulnerabilityAdvisories.isEmpty()) return ""
            val currentVersion = knownDependencies[key] ?: return ""
            val advisories = vulnerabilityAdvisories["$key:$currentVersion"].orEmpty()
            if (advisories.isEmpty()) return ""
            return selectableRecommendedVersion(
                recommendedFixVersion(advisories, currentVersion),
                availableVersions[key].orEmpty()
            )
        }

        /**
         * Prüft, ob für die per [key] identifizierte Abhängigkeit eine empfohlene Fix-Version
         * ausgewählt werden kann.
         *
         * @param key Der Schlüssel (`groupId:artifactId`) der Abhängigkeit.
         * @return `true`, wenn keine Aktualisierung läuft und eine auswählbare Empfehlung vorliegt.
         */
        internal fun hasRecommendedVersionForDependency(key: String): Boolean =
            !isUpdating && recommendedVersionForDependency(key).isNotEmpty()

        /**
         * Prüft, ob mindestens eine direkte Abhängigkeit eine auswählbare empfohlene Fix-Version besitzt.
         *
         * @return `true`, wenn keine Aktualisierung läuft und wenigstens eine Abhängigkeit eigene
         *   Sicherheitswarnungen mit auswählbarer Fix-Version aufweist.
         */
        internal fun hasRecommendedVersions(): Boolean =
            !isUpdating && vulnerabilityAdvisories.isNotEmpty() &&
                knownDependencies.keys.any { recommendedVersionForDependency(it).isNotEmpty() }

        /**
         * Wählt für eine einzelne Abhängigkeit die höchste Version innerhalb derselben Major-Linie wie
         * die aktuell verwendete Version aus.
         *
         * Wirkt ausschließlich auf die per [key] identifizierte Abhängigkeit und setzt eine Auswahl nur,
         * wenn deren verfügbare Versionen bereits abgerufen wurden. Existiert keine passende Version
         * derselben Major-Linie, bleibt die aktuelle Version erhalten. Verwenden mehrere Einträge dieselbe
         * Maven-Property, werden sie gemeinsam aktualisiert.
         *
         * @param key Der Schlüssel (`groupId:artifactId`) der Abhängigkeit.
         */
        internal fun selectHighestMinorVersionForDependency(key: String) {
            applySingleVersionSelection(key) { current, versions ->
                latestVersionWithinSameMajor(current, versions) ?: current
            }
        }

        /**
         * Prüft, ob für die per [key] identifizierte Abhängigkeit eine höchste Major-/Minor-Version
         * ausgewählt werden kann.
         *
         * @param key Der Schlüssel (`groupId:artifactId`) der Abhängigkeit.
         * @return `true`, wenn keine Aktualisierung läuft und für die Abhängigkeit bereits verfügbare
         *   Versionen abgerufen wurden.
         */
        internal fun hasSelectableVersionsForDependency(key: String): Boolean =
            !isUpdating && availableVersions[key]?.isNotEmpty() == true

        /**
         * Setzt die per [key] identifizierte Abhängigkeit auf ihre aktuell verwendete Version zurück und
         * verwirft damit eine zuvor getroffene Auswahl.
         *
         * Wirkt ausschließlich auf die angeklickte Abhängigkeit. Verwenden mehrere Einträge dieselbe
         * Maven-Property, werden deren Auswahlen gemeinsam entfernt.
         *
         * @param key Der Schlüssel (`groupId:artifactId`) der Abhängigkeit.
         */
        internal fun resetVersionForDependency(key: String) {
            clearVersionSelection(key)
            cancelActiveCellEditing()
            table.repaint()
            updateUpdateButtonState()
            applyRowFilter()
        }

        /**
         * Prüft, ob für die per [key] identifizierte Abhängigkeit eine abweichende Version ausgewählt
         * wurde, die zurückgesetzt werden kann.
         *
         * @param key Der Schlüssel (`groupId:artifactId`) der Abhängigkeit.
         * @return `true`, wenn keine Aktualisierung läuft und die ausgewählte Version von der aktuell
         *   verwendeten Version abweicht.
         */
        internal fun isVersionResetEnabledForDependency(key: String): Boolean {
            if (isUpdating) return false
            val selected = selectedVersions[key] ?: return false
            return selected != (knownDependencies[key] ?: "")
        }

        /**
         * Wendet eine Auswahlstrategie auf eine einzelne Abhängigkeit an und aktualisiert die Tabelle.
         *
         * Sind für die Abhängigkeit noch keine Versionen abgerufen, geschieht nichts. Entspricht die
         * ermittelte Zielversion der aktuellen Version (oder ist leer), wird die Auswahl entfernt, sodass
         * keine Änderung angezeigt wird.
         *
         * @param key Der Schlüssel (`groupId:artifactId`) der Abhängigkeit.
         * @param chooser Funktion, die aus der aktuellen Version und den verfügbaren Versionen die Zielversion ermittelt.
         */
        private fun applySingleVersionSelection(key: String, chooser: (String, List<String>) -> String) {
            val versions = availableVersions[key] ?: return
            if (versions.isEmpty()) return
            val currentVersion = knownDependencies[key] ?: ""
            val chosen = chooser(currentVersion, versions)
            if (chosen.isNotEmpty() && chosen != currentVersion) {
                synchronizePropertyVersions(key, chosen)
            } else {
                clearVersionSelection(key)
            }
            cancelActiveCellEditing()
            table.repaint()
            updateUpdateButtonState()
            applyRowFilter()
        }

        /**
         * Entfernt die getroffene Versionsauswahl einer Abhängigkeit.
         *
         * Verwenden mehrere Einträge dieselbe Maven-Property, werden auch deren Auswahlen entfernt, damit
         * property-verknüpfte Abhängigkeiten konsistent bleiben.
         *
         * @param key Der Schlüssel (`groupId:artifactId`) der Abhängigkeit.
         */
        private fun clearVersionSelection(key: String) {
            selectedVersions.remove(key)
            val property = dependencyToProperty[key]
            if (property != null) {
                dependencyToProperty.forEach { (depKey, prop) ->
                    if (prop == property) {
                        selectedVersions.remove(depKey)
                    }
                }
            }
        }

        /**
         * Verwirft alle getroffenen Versionsauswahlen und setzt jede Abhängigkeit auf ihre aktuell
         * verwendete Version zurück.
         *
         * Diese Aktion wirkt bewusst global (auch auf ausgefilterte Einträge), damit ein Zurücksetzen
         * garantiert alle offenen Änderungen entfernt.
         */
        internal fun resetAllVersionsToCurrent() {
            applyBulkVersionSelection(visibleOnly = false) { _, current, _ -> current }
        }

        /**
         * Verwirft die Versionsauswahlen der aktuell sichtbaren (nicht ausgefilterten) Abhängigkeiten und
         * setzt diese auf ihre aktuell verwendete Version zurück.
         *
         * Ausgefilterte Einträge bleiben unverändert, sodass ein Zurücksetzen gezielt nur auf die durch den
         * aktiven Filter eingegrenzten Abhängigkeiten wirkt.
         */
        internal fun resetVisibleVersionsToCurrent() {
            applyBulkVersionSelection(visibleOnly = true) { _, current, _ -> current }
        }

        /**
         * Setzt Versionsauswahlen zurück und zeigt zuvor – abhängig vom aktiven Filter – einen
         * Bestätigungs- bzw. Auswahldialog an.
         *
         * Ist ein Filter aktiv (siehe [isResetFiltersEnabled]), wird ein Dialog angezeigt, in dem der
         * Benutzer wählt, ob das Zurücksetzen auf alle Abhängigkeiten oder nur auf die aktuell gefilterten
         * (sichtbaren) Abhängigkeiten wirkt. In diesem Fall wird die Option „Don't ask again" bewusst nicht
         * angeboten und [MavenUpSettings.State.confirmVersionReset] nicht ausgewertet.
         *
         * Ist kein Filter aktiv, bleibt das bisherige Verhalten erhalten: Ist die Einstellung
         * [MavenUpSettings.State.confirmVersionReset] aktiv, wird ein Ja/Nein-Dialog angezeigt. Über die
         * Option „Don't ask again" kann der Benutzer die Bestätigung dauerhaft deaktivieren; in diesem Fall
         * wird die Einstellung entsprechend gespeichert. Bricht der Benutzer ab, bleibt die aktuelle Auswahl
         * unverändert.
         */
        internal fun confirmAndResetAllVersionsToCurrent() {
            if (isResetFiltersEnabled()) {
                confirmAndResetWithActiveFilter()
                return
            }
            val settings = MavenUpSettings.getInstance()
            if (settings.state.confirmVersionReset) {
                val doNotAsk = object : DoNotAskOption.Adapter() {
                    override fun rememberChoice(isSelected: Boolean, exitCode: Int) {
                        if (isSelected && exitCode == Messages.YES) {
                            settings.state.confirmVersionReset = false
                        }
                    }
                }
                val confirmed = MessageDialogBuilder
                    .yesNo(
                        MyMessageBundle.message(TOOLWINDOW_MY_TOOL_WINDOW_RESET_VERSIONS_CONFIRM_TITLE),
                        MyMessageBundle.message("toolwindow.MyToolWindow.resetVersions.confirm.message")
                    )
                    .icon(Messages.getWarningIcon())
                    .doNotAsk(doNotAsk)
                    .ask(project)
                if (!confirmed) return
            }
            resetAllVersionsToCurrent()
        }

        /**
         * Zeigt bei aktivem Filter einen Auswahldialog an, ob das Zurücksetzen auf alle oder nur auf die
         * gefilterten (sichtbaren) Abhängigkeiten angewendet werden soll, und führt die gewählte Aktion aus.
         *
         * Der Dialog bietet drei Optionen: „Alle Abhängigkeiten", „Nur gefilterte" und „Abbrechen". Bricht
         * der Benutzer ab, bleibt die aktuelle Auswahl unverändert.
         */
        private fun confirmAndResetWithActiveFilter() {
            when (askResetScopeWithActiveFilter()) {
                0 -> resetAllVersionsToCurrent()
                1 -> resetVisibleVersionsToCurrent()
                else -> return
            }
        }

        /**
         * Zeigt den Auswahldialog für den Geltungsbereich eines Zurücksetzens bei aktivem Filter an.
         *
         * Der Dialog bietet drei Optionen: „Alle Abhängigkeiten", „Nur gefilterte" und „Abbrechen".
         *
         * @return `0` für alle, `1` für nur die gefilterten Abhängigkeiten, ein anderer Wert bei Abbruch.
         */
        private fun askResetScopeWithActiveFilter(): Int {
            val options = arrayOf(
                MyMessageBundle.message("toolwindow.MyToolWindow.resetVersions.filtered.option.all"),
                MyMessageBundle.message("toolwindow.MyToolWindow.resetVersions.filtered.option.filtered"),
                MyMessageBundle.message("toolwindow.MyToolWindow.resetVersions.filtered.option.cancel")
            )
            return Messages.showDialog(
                project,
                MyMessageBundle.message("toolwindow.MyToolWindow.resetVersions.filtered.message"),
                MyMessageBundle.message(TOOLWINDOW_MY_TOOL_WINDOW_RESET_VERSIONS_CONFIRM_TITLE),
                options,
                0,
                Messages.getWarningIcon()
            )
        }

        /**
         * Wendet eine Auswahlstrategie auf Abhängigkeiten an und aktualisiert die Tabelle.
         *
         * Für jede berücksichtigte Abhängigkeit wird die von [chooser] gelieferte Zielversion übernommen.
         * Entspricht sie der aktuellen Version (oder ist leer), wird der Eintrag aus [selectedVersions]
         * entfernt, sodass keine Änderung angezeigt wird.
         *
         * @param visibleOnly Wenn `true`, werden nur aktuell in der Tabelle sichtbare (nicht ausgefilterte)
         *   Abhängigkeiten berücksichtigt; ansonsten alle geladenen Abhängigkeiten.
         * @param chooser Funktion, die aus Schlüssel, aktueller Version und den verfügbaren Versionen die
         *   Zielversion ermittelt.
         */
        private fun applyBulkVersionSelection(
            visibleOnly: Boolean,
            chooser: (String, String, List<String>) -> String
        ) {
            if (availableVersions.isEmpty()) return
            val visibleKeys = if (visibleOnly) collectVisibleDependencyKeys() else null
            for ((key, versions) in availableVersions) {
                if (versions.isEmpty()) continue
                if (visibleKeys != null && key !in visibleKeys) continue
                val currentVersion = knownDependencies[key] ?: ""
                val chosen = chooser(key, currentVersion, versions)
                if (chosen.isNotEmpty() && chosen != currentVersion) {
                    selectedVersions[key] = chosen
                } else {
                    selectedVersions.remove(key)
                }
            }
            cancelActiveCellEditing()
            table.repaint()
            updateUpdateButtonState()
            applyRowFilter()
        }

        /**
         * Ermittelt die Schlüssel (`groupId:artifactId`) aller aktuell in der Tabelle sichtbaren Zeilen.
         *
         * Ausgefilterte Zeilen werden nicht berücksichtigt.
         *
         * @return Menge der sichtbaren Dependency-Schlüssel.
         */
        private fun collectVisibleDependencyKeys(): Set<String> {
            val model = table.model
            val keys = HashSet<String>()
            for (viewRow in 0 until table.rowCount) {
                val modelRow = table.convertRowIndexToModel(viewRow)
                val groupId = model.getValueAt(modelRow, GROUP_ID_COLUMN)?.toString().orEmpty()
                val artifactId = model.getValueAt(modelRow, ARTIFACT_ID_COLUMN)?.toString().orEmpty()
                keys.add("$groupId:$artifactId")
            }
            return keys
        }

        /**
         * Prüft, ob der aktive Zeilenfilter aktuell Einträge ausblendet.
         *
         * Berücksichtigt die jeweils sichtbare Ansicht: in der transitiven Ansicht deren eigenen Filter,
         * sonst den Filter der Haupttabelle.
         *
         * @return `true`, wenn weniger Zeilen sichtbar sind als das Tabellenmodell enthält.
         */
        internal fun isRowFilterHidingEntries(): Boolean {
            if (showingTransitiveView) return transitiveVulnerabilitiesView.isRowFilterHidingEntries()
            val model = table.model as? DefaultTableModel ?: return false
            return table.rowCount < model.rowCount
        }

        /**
         * Erzeugt die Tooltip-Beschreibung für die Sammelauswahl-Aktionen und ergänzt bei aktivem Filter
         * einen Hinweis, dass nur sichtbare Abhängigkeiten betroffen sind.
         *
         * @param baseLabel Die Basisbeschriftung der Aktion.
         * @return Die Beschriftung, ggf. um den Filterhinweis erweitert.
         */
        internal fun bulkSelectionActionDescription(baseLabel: String): String =
            if (isRowFilterHidingEntries()) {
                "$baseLabel \u2014 ${MyMessageBundle.message("toolwindow.MyToolWindow.bulkSelection.filterActiveHint")}"
            } else {
                baseLabel
            }

        /**
         * Prüft, ob die Sammelaktionen zur Versionsauswahl ausführbar sind.
         *
         * @return `true`, wenn keine Aktualisierung läuft und mindestens eine Abhängigkeit auswählbare Versionen besitzt.
         */
        internal fun isBulkVersionSelectionEnabled(): Boolean =
            !isUpdating && availableVersions.values.any { it.isNotEmpty() }

        /**
         * Prüft, ob das Zurücksetzen aller Versionsauswahlen ausführbar ist.
         *
         * @return `true`, wenn keine Aktualisierung läuft und mindestens eine abweichende Version ausgewählt ist.
         */
        internal fun isResetVersionsEnabled(): Boolean =
            !isUpdating && hasSelectedUpdates()

        /**
         * Prüft, ob nach einem automatischen Neuladen der Projektdaten sofort online nach neuen
         * Versionen gesucht werden soll.
         *
         * Die automatischen Auslöser sind der Aufbau des Tool-Window-Inhalts (erstes Öffnen nach dem
         * Projektstart) und jeder abgeschlossene Maven-Import bzw. -Resync. Ohne geöffnetes Tool Window
         * existiert dieser Inhalt nicht, sodass ohne Zutun des Anwenders keine Netzwerkabfragen erfolgen.
         *
         * @return `true`, wenn die Einstellung [MavenUpSettings.State.autoSearchVersions] aktiv ist.
         */
        internal fun isAutoVersionSearchEnabled(): Boolean =
            MavenUpSettings.getInstance().state.autoSearchVersions

        /**
         * Prüft, ob die kombinierte Aktualisierung samt Versionssuche ausführbar ist.
         *
         * Die Aktion lädt die Abhängigkeiten neu, verwirft alle bisherigen Ergebnisse und sucht
         * anschließend nach neuen Versionen. Da sie sämtliche Daten beider Ansichten zurücksetzt,
         * ist sie auch in der transitiven Sicherheitslücken-Ansicht verfügbar.
         *
         * @return `true`, wenn gerade keine Aktualisierung läuft.
         */
        internal fun isRefreshEnabled(): Boolean = !isUpdating

        /**
         * Prüft, ob die Sammelauswahl der höchsten Versionen für die aktuell sichtbare Ansicht ausführbar ist.
         *
         * In der transitiven Sicherheitslücken-Ansicht wirkt sie auf deren Zeilen, ansonsten auf die Haupttabelle.
         *
         * @return `true`, wenn die Sammelauswahl für die aktive Ansicht ausgeführt werden kann.
         */
        internal fun isBulkVersionSelectionEnabledForCurrentView(): Boolean =
            if (showingTransitiveView) {
                !isUpdating && transitiveVulnerabilitiesView.isBulkVersionSelectionEnabled()
            } else {
                isBulkVersionSelectionEnabled()
            }

        /**
         * Prüft, ob die Auswahl der empfohlenen Fix-Version für die aktuell sichtbare Ansicht ausführbar ist.
         *
         * In der transitiven Sicherheitslücken-Ansicht wirkt sie auf deren Koordinaten; in der Haupttabelle
         * auf Abhängigkeiten mit eigenen (nicht nur transitiven) Sicherheitswarnungen.
         *
         * @return `true`, wenn in der aktiven Ansicht mindestens eine empfohlene Fix-Version vorliegt.
         */
        internal fun isRecommendedSelectionEnabledForCurrentView(): Boolean =
            if (showingTransitiveView) {
                !isUpdating && transitiveVulnerabilitiesView.hasRecommendedVersions()
            } else {
                hasRecommendedVersions()
            }

        /**
         * Prüft, ob das Zurücksetzen der Versionsauswahlen für die aktuell sichtbare Ansicht ausführbar ist.
         *
         * In der transitiven Sicherheitslücken-Ansicht wirkt es auf deren Auswahlen, ansonsten auf die Haupttabelle.
         *
         * @return `true`, wenn das Zurücksetzen für die aktive Ansicht ausgeführt werden kann.
         */
        internal fun isResetVersionsEnabledForCurrentView(): Boolean =
            if (showingTransitiveView) {
                !isUpdating && transitiveVulnerabilitiesView.hasPendingUpdates()
            } else {
                isResetVersionsEnabled()
            }

        /**
         * Setzt die Versionsauswahlen der transitiven Sicherheitslücken-Ansicht zurück und zeigt bei
         * aktivierter Einstellung zuvor einen Bestätigungsdialog an.
         *
         * Ist in der transitiven Ansicht ein Filter aktiv, wird stattdessen abgefragt, ob alle oder nur die
         * gefilterten (sichtbaren) Koordinaten zurückgesetzt werden sollen.
         *
         * Ist [MavenUpSettings.State.confirmVersionReset] aktiv, wird ein Ja/Nein-Dialog angezeigt; über die
         * Option „Don't ask again" kann der Benutzer die Bestätigung dauerhaft deaktivieren. Bricht der
         * Benutzer ab, bleibt die aktuelle Auswahl unverändert.
         */
        internal fun confirmAndResetTransitiveSelections() {
            if (!transitiveVulnerabilitiesView.hasPendingUpdates()) return
            if (transitiveVulnerabilitiesView.filterPanel.isResetFiltersEnabled()) {
                when (askResetScopeWithActiveFilter()) {
                    0 -> transitiveVulnerabilitiesView.resetSelections()
                    1 -> transitiveVulnerabilitiesView.resetVisibleSelections()
                    else -> return
                }
                return
            }
            val settings = MavenUpSettings.getInstance()
            if (settings.state.confirmVersionReset) {
                val doNotAsk = object : DoNotAskOption.Adapter() {
                    override fun rememberChoice(isSelected: Boolean, exitCode: Int) {
                        if (isSelected && exitCode == Messages.YES) {
                            settings.state.confirmVersionReset = false
                        }
                    }
                }
                val confirmed = MessageDialogBuilder
                    .yesNo(
                        MyMessageBundle.message(TOOLWINDOW_MY_TOOL_WINDOW_RESET_VERSIONS_CONFIRM_TITLE),
                        MyMessageBundle.message("toolwindow.MyToolWindow.resetVersions.confirm.message")
                    )
                    .icon(Messages.getWarningIcon())
                    .doNotAsk(doNotAsk)
                    .ask(project)
                if (!confirmed) return
            }
            transitiveVulnerabilitiesView.resetSelections()
        }

        /**
         * Synchronisiert die Version-Auswahl über alle Einträge, die dieselbe Maven-Property verwenden.
         *
         * @param key Der Schlüssel (`groupId:artifactId`) der geänderten Dependency.
         * @param selected Die neu ausgewählte Version.
         */
        private fun synchronizePropertyVersions(key: String, selected: String) {
            selectedVersions[key] = selected
            val property = dependencyToProperty[key]
            if (property != null) {
                dependencyToProperty.forEach { (depKey, prop) ->
                    if (prop == property) {
                        selectedVersions[depKey] = selected
                    }
                }
            }
        }

        /**
         * Aktualisiert die visuelle Darstellung (Farbe, Font, Status-Glyph, Tooltip) des Editor-Panels
         * sofort nach einer Versionsauswahl, ohne dass der Fokus die Zelle verlassen muss.
         *
         * @param combo Die ComboBox im Editor.
         * @param panel Das umgebende JPanel mit Status-Glyph und ComboBox.
         * @param selected Die aktuell gewählte Version.
         * @param currentVersion Die im Projekt verwendete Version.
         * @param newestVersion Die höchste bekannte Version.
         */
        private fun updateEditorVisuals(
            combo: ComboBox<String>,
            panel: JPanel?,
            selected: String?,
            currentVersion: String,
            newestVersion: String
        ) {
            val newUpToDate = isVersionUpToDate(selected ?: "", newestVersion)
            val newHasChange = selected != currentVersion && !selected.isNullOrEmpty()
            val newColor = if (newHasChange) versionStatusColor(newUpToDate) else null
            val newFontStyle = if (newHasChange) Font.BOLD else Font.PLAIN
            combo.foreground = newColor
            combo.font = combo.font.deriveFont(newFontStyle)
            combo.repaint()
            if (panel != null) {
                val statusLabel = panel.getComponent(0) as? JLabel
                statusLabel?.text = versionStatusText(newUpToDate)
                statusLabel?.foreground = newColor
                panel.toolTipText = versionStatusTooltip(currentVersion, selected ?: currentVersion, newestVersion)
                panel.repaint()
            }
        }

        private fun updateUpdateButtonState() {
            refreshToolbar()
            updateChangesFilterState()
        }

        /**
         * Prüft, ob mindestens eine Abhängigkeit mit einer von der aktuellen Version abweichenden
         * neuen Version ausgewählt wurde.
         *
         * @return `true`, wenn mindestens ein anwendbares Update ausgewählt ist.
         */
        internal fun hasSelectedUpdates(): Boolean {
            return knownDependencies.any { (key, currentVersion) ->
                val newVersion = selectedVersions[key]
                newVersion != null && newVersion != currentVersion
            } || transitiveVulnerabilitiesView.hasPendingUpdates()
        }

        /**
         * Prüft, ob die Update-Aktion ausführbar ist (ausgewählte Updates und keine laufende Operation).
         *
         * @return `true`, wenn die Update-Aktion aktiviert sein soll.
         */
        internal fun isUpdateActionEnabled(): Boolean = hasSelectedUpdates() && !isUpdating

        /**
         * Prüft, ob die Vulnerability-Prüfung derzeit gestartet werden darf.
         *
         * @return `true`, wenn keine laufende Aktualisierung oder Prüfung aktiv ist.
         */
        internal fun isCheckVulnerabilitiesEnabled(): Boolean =
            canCheckVulnerabilities(isRefreshing, isUpdating)

        /**
         * Prüft, ob für die aktuell selektierte Zeile die Repository-Browser-Aktion verfügbar ist.
         *
         * Wirkt je nach aktiver Ansicht auf die Haupttabelle oder die transitive Sicherheitslücken-Ansicht.
         *
         * @return `true`, wenn eine Zeile selektiert ist.
         */
        internal fun isOpenInRepositoryEnabled(): Boolean =
            if (showingTransitiveView) transitiveVulnerabilitiesView.hasSelectedRow()
            else table.selectedRow >= 0

        /**
         * Prüft, ob für die aktuell selektierte Zeile Vulnerability-Details angezeigt werden können.
         *
         * Wirkt je nach aktiver Ansicht auf die Haupttabelle oder die transitive Sicherheitslücken-Ansicht.
         *
         * @return `true`, wenn die selektierte Zeile mindestens eine Sicherheitswarnung besitzt.
         */
        internal fun isVulnerabilityDetailsEnabled(): Boolean {
            if (isUpdating) return false
            if (showingTransitiveView) return transitiveVulnerabilitiesView.selectedRowHasVulnerabilities()
            val row = table.selectedRow
            val cell = if (row >= 0) table.getValueAt(row, VULNERABILITIES_COLUMN) as? VulnerabilityCell else null
            return cell?.allAdvisories?.isNotEmpty() == true
        }

        /**
         * Liefert die aktuelle Beschriftung der Repository-Browser-Aktion inklusive des
         * konfigurierten Browser-Namens.
         *
         * @return Der lokalisierte Aktionstext mit eingesetztem Browser-Namen.
         */
        internal fun currentOpenInRepositoryText(): String {
            val browserName = MavenUpSettings.getInstance().state.repositoryBrowser.displayName
            return MyMessageBundle.message(
                TOOLWINDOW_MY_TOOL_WINDOW_CONTEXT_MENU_OPEN_IN_MVN_REPOSITORY, browserName
            )
        }

        /**
         * Öffnet die aktuell selektierte Abhängigkeit im konfigurierten Repository-Browser.
         *
         * Wirkt je nach aktiver Ansicht auf die Haupttabelle oder die transitive Sicherheitslücken-Ansicht.
         */
        internal fun openInMavenRepositoryForSelectedRow() {
            if (showingTransitiveView) {
                transitiveVulnerabilitiesView.openSelectedInRepository()
                return
            }
            val row = table.selectedRow
            if (row < 0) return
            val groupId = table.getValueAt(row, GROUP_ID_COLUMN)?.toString().orEmpty()
            val artifactId = table.getValueAt(row, ARTIFACT_ID_COLUMN)?.toString().orEmpty()
            val currentVersion = table.getValueAt(row, CURRENT_VERSION_COLUMN)?.toString().orEmpty()
            openInMavenRepository(groupId, artifactId, currentVersion)
        }

        /**
         * Öffnet den Vulnerability-Details-Dialog für die aktuell selektierte Abhängigkeit.
         *
         * Wirkt je nach aktiver Ansicht auf die Haupttabelle oder die transitive Sicherheitslücken-Ansicht.
         */
        internal fun openVulnerabilityDetailsForSelectedRow() {
            if (showingTransitiveView) {
                transitiveVulnerabilitiesView.openSelectedVulnerabilityDetails()
                return
            }
            val row = table.selectedRow
            if (row < 0) return
            val cell = table.getValueAt(row, VULNERABILITIES_COLUMN) as? VulnerabilityCell
            val findings = cell?.detailFindings() ?: emptyMap()
            val groupId = table.getValueAt(row, GROUP_ID_COLUMN)?.toString().orEmpty()
            val artifactId = table.getValueAt(row, ARTIFACT_ID_COLUMN)?.toString().orEmpty()
            if (findings.isNotEmpty()) {
                VulnerabilityDetailDialog(
                    project,
                    findings,
                    "$groupId:$artifactId - ${MyMessageBundle.message(VULNERABILITY_DETAILS_TITLE)}",
                    cell?.detailOrigins().orEmpty()
                ).show()
            }
        }

        /**
         * Sammelt alle in der UI ausgewählten Updates, für die eine neue Version gewählt wurde.
         *
         * Enthält sowohl die Auswahlen der Haupttabelle als auch die in der transitiven Ansicht
         * gepinnten Versionen (als verwaltete Abhängigkeiten).
         */
        internal fun collectSelectedUpdates(): List<DependencyUpdate> {
            val mainUpdates = selectedVersions.mapNotNull { (key, newVersion) ->
                val currentVersion = knownDependencies[key] ?: return@mapNotNull null
                val type = knownTypes[key] ?: return@mapNotNull null
                if (newVersion == currentVersion) return@mapNotNull null

                DependencyUpdate(
                    key.substringBefore(":"),
                    key.substringAfter(":"),
                    type,
                    currentVersion,
                    newVersion
                )
            }
            return mainUpdates + transitiveVulnerabilitiesView.collectPendingUpdates()
        }

        /**
         * Orchestriert den Prozess der Versionssuche und aktualisiert die Aktionsleiste.
         *
         * @param refreshAfterCheck Callback, der nach Abschluss der Prüfung ausgeführt wird.
         */
        private fun performUpdateCheck(
            refreshAfterCheck: () -> Unit
        ) {
            isUpdating = true
            refreshToolbar()

            availableVersions.clear()
            selectedVersions.clear()
            checkForUpdates {
                ApplicationManager.getApplication().invokeLater {
                    isUpdating = false
                    refreshAfterCheck()
                    refreshToolbar()
                }
            }
        }

        /**
         * Führt den Vulnerability-Scan für die erfassten Abhängigkeiten durch.
         * Nutzt OSV und optional den Sonatype OSS Index.
         */
        private fun performVulnerabilityCheck(onFinished: () -> Unit) {
            ProgressManager.getInstance().run(object : Task.Backgroundable(
                project,
                MyMessageBundle.message("toolwindow.MyToolWindow.checkVulnerabilities.progress"),
                true
            ) {
                override fun run(indicator: ProgressIndicator) {
                    val directDependencies = knownDependencies.entries
                        .filter { it.value.isNotEmpty() }
                        .map { (key, version) -> Triple(key.substringBefore(":"), key.substringAfter(":"), version) }
                    val scanTargets = vulnerabilityScanService.collectVulnerabilityScanTargets(directDependencies)
                    val dependencies = scanTargets.dependencies
                    LOG.info("Starting vulnerability check for ${dependencies.size} dependencies/plugins.")

                    val osvResults = vulnerabilityApiService.fetchVulnerabilityAdvisories(dependencies.toList(), indicator)
                    val ossIndexScan = vulnerabilityScanService.resolveOssIndexResults(dependencies.toList(), indicator)
                    val results = VulnerabilityMerger.merge(osvResults, ossIndexScan.advisories)
                    val vulnerableEntries = results.values.count { it.isNotEmpty() }
                    LOG.info(
                        "Finished vulnerability check. " +
                            "Results for ${results.size} entries, $vulnerableEntries entries with known vulnerabilities."
                    )

                    val transitiveVersions = fetchVulnerableTransitiveVersions(scanTargets, results, indicator)

                    ApplicationManager.getApplication().invokeLater {
                        transitiveAvailableVersions.clear()
                        transitiveAvailableVersions.putAll(transitiveVersions)
                        applyVulnerabilityResults(results, scanTargets)
                        ossIndexScan.errorMessage?.let(::showOssIndexError)
                        onFinished()
                    }
                }
            })
        }

        /**
         * Ermittelt für die verwundbaren transitiven Koordinaten des Scans die verfügbaren Versionen.
         *
         * Beschränkt die (netzwerklastige) Versionsabfrage auf transitive Koordinaten mit mindestens
         * einer Sicherheitswarnung, sodass die New-Version-Spalte der transitiven Ansicht bereits nach
         * dem Scan – ohne separate Versionssuche – auswählbare Versionen anbietet.
         *
         * @param scanTargets Die Scan-Ziele mit allen transitiven Koordinaten.
         * @param results Die zusammengeführten Scan-Ergebnisse je Koordinate.
         * @param indicator Der Fortschrittsindikator der laufenden Hintergrundaufgabe.
         * @return Zuordnung von `groupId:artifactId` zu den verfügbaren Versionen.
         */
        private fun fetchVulnerableTransitiveVersions(
            scanTargets: VulnerabilityScanTargets,
            results: Map<String, List<VulnerabilityAdvisory>>,
            indicator: ProgressIndicator
        ): Map<String, List<String>> {
            val coordinates = scanTargets.transitiveCoordinates
                .filter { results[it]?.isNotEmpty() == true }
                .mapNotNull { coordinate ->
                    val parts = coordinate.split(":")
                    if (parts.size < 3) null
                    else "${parts[0]}:${parts[1]}" to parts.drop(2).joinToString(":")
                }
                .toMap()
            if (coordinates.isEmpty()) return emptyMap()
            return dependencyVersionService.fetchAvailableVersions(coordinates, indicator)
        }

        /**
         * Übernimmt die zusammengeführten Scan-Ergebnisse in den Zustand des Tool-Windows.
         */
        private fun applyVulnerabilityResults(
            results: Map<String, List<VulnerabilityAdvisory>>,
            scanTargets: VulnerabilityScanTargets
        ) {
            vulnerabilityAdvisories.clear()
            vulnerabilityAdvisories.putAll(results)
            transitiveCoordinates.clear()
            transitiveCoordinates.addAll(scanTargets.transitiveCoordinates)
            transitiveDependenciesByDirect.clear()
            transitiveDependenciesByDirect.putAll(scanTargets.transitiveDependenciesByDirect)
            vulnerabilityScanPerformed = true
            updateTransitiveVulnerabilitiesView()
        }

        /**
         * Zeigt die qualifizierte OSS-Index-Fehlermeldung an und bietet einen direkten Sprung
         * in die Plugin-Einstellungen.
         */
        private fun showOssIndexError(errorMessage: String) {
            val choice = Messages.showDialog(
                project,
                errorMessage,
                MyMessageBundle.message("vulnerability.ossIndex.error.title"),
                arrayOf(
                    MyMessageBundle.message("vulnerability.ossIndex.error.openSettings"),
                    MyMessageBundle.message("button.close")
                ),
                0,
                Messages.getWarningIcon()
            )
            if (choice == 0) {
                openSettings()
            }
        }

        /**
         * Startet die Hintergrundaufgabe zur Suche nach verfügbaren neuen Versionen.
         */
        private fun checkForUpdates(onFinished: () -> Unit) {
            ProgressManager.getInstance().run(object : Task.Backgroundable(
                project,
                MyMessageBundle.message("toolwindow.MyToolWindow.checkUpdates.progress"),
                true
            ) {
                override fun run(indicator: ProgressIndicator) {
                    val result = dependencyVersionService.searchVersions(
                        knownDependencies,
                        dependencyToProperty,
                        indicator
                    )

                    ApplicationManager.getApplication().invokeLater {
                        availableVersions.putAll(result.availableVersions)
                        selectedVersions.putAll(result.selectedVersions)
                        onFinished()
                    }
                }
            })
        }

        /**
         * Liefert die UI-Komponente des Tabs **Dependencies**.
         */
        fun getContent(): JBPanel<JBPanel<*>> = content

        /**
         * Liefert die UI-Komponente des Tabs **Transitive CVEs**.
         */
        fun getTransitiveContent(): JBPanel<JBPanel<*>> = transitiveContent

        /**
         * Öffnet die Plugin-Einstellungen.
         */
        private fun openSettings() {
            com.intellij.openapi.options.ShowSettingsUtil.getInstance()
                .showSettingsDialog(project, MavenUpConfigurable::class.java)
        }

        /**
         * Öffnet die Repository-Browser-Seite der angegebenen Abhängigkeit im Standard-Browser.
         * Der verwendete Repository-Browser wird aus den Plugin-Einstellungen gelesen.
         */
        private fun openInMavenRepository(groupId: String, artifactId: String, version: String) {
            val browser = MavenUpSettings.getInstance().state.repositoryBrowser
            BrowserUtil.browse(buildMavenRepositoryUrl(groupId, artifactId, version, browser))
        }
    }
}
