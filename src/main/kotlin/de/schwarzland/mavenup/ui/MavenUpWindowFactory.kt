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
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
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
import com.intellij.psi.PsiManager
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.table.JBTable
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.AbstractTableCellEditor
import com.intellij.icons.AllIcons
import org.apache.maven.artifact.versioning.ComparableVersion
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
     */
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val myToolWindow = MyToolWindow(project)

        val content = ContentFactory
            .getInstance()
            .createContent(myToolWindow.getContent(), null, false)
        content.setDisposer(myToolWindow)

        toolWindow.contentManager.addContent(content)
    }

    /**
     * Die eigentliche Tool-Window-Komponente, die die Tabelle der Abhängigkeiten und die Aktions-Buttons verwaltet.
     */
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

        /** Die obere Aktionsleiste des Tool-Windows; wird im Init-Block initialisiert. */
        private var actionToolbar: ActionToolbar? = null

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
         * Nur aktiv, sobald eine erfolgreiche Versionssuche ("Search for New Versions") mindestens eine
         * abrufbare Versionsliste geliefert hat (siehe [updateUpdatesFilterState]).
         */
        internal val updatesFilterComboBox = ComboBox(TriStateFilter.entries.toTypedArray())

        /** Auswahlfeld für den Filter nach Sicherheitslücken (Ja/Nein/Alle). */
        internal val vulnerabilitiesFilterComboBox = ComboBox(TriStateFilter.entries.toTypedArray())

        /** Anzeigetext der Combobox-Option, die alle Typen zulässt. */
        private val allTypesFilterLabel =
            MyMessageBundle.message("toolwindow.MyToolWindow.filter.type.all")

        /** Container für Aktionsleiste und Filterzeile im Nordbereich. */
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
                addColumn(MyMessageBundle.message("toolwindow.MyToolWindow.table.header.currentVersion"))
                addColumn(MyMessageBundle.message("toolwindow.MyToolWindow.table.header.vulnerabilities"))
                addColumn(MyMessageBundle.message("toolwindow.MyToolWindow.table.header.newVersion"))
            }

            table = object : JBTable(tableModel) {
                override fun getToolTipText(e: MouseEvent): String? {
                    val row = rowAtPoint(e.point)
                    val column = columnAtPoint(e.point)
                    if (row < 0 || column == VULNERABILITIES_COLUMN) return super.getToolTipText(e)
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
                                "$coordinate - ${MyMessageBundle.message(VULNERABILITY_DETAILS_TITLE)}"
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
                    val groupId = table.getValueAt(row, GROUP_ID_COLUMN) as? String ?: ""
                    val artifactId = table.getValueAt(row, ARTIFACT_ID_COLUMN) as? String ?: ""
                    val type = table.getValueAt(row, TYPE_COLUMN) as? String ?: "dependency"
                    val currentVersion = table.getValueAt(row, CURRENT_VERSION_COLUMN) as? String ?: ""
                    val vulnerabilityCell = table.getValueAt(row, VULNERABILITIES_COLUMN) as? VulnerabilityCell

                    val popup = JPopupMenu()
                    popup.add(JMenuItem(MyMessageBundle.message("toolwindow.MyToolWindow.contextMenu.navigateToPom")).apply {
                        addActionListener { pomNavigationService.navigateToDependency(groupId, artifactId, type) }
                    })
                    val browserName = MavenUpSettings.getInstance().state.repositoryBrowser.displayName
                    popup.add(JMenuItem(MyMessageBundle.message(
                        TOOLWINDOW_MY_TOOL_WINDOW_CONTEXT_MENU_OPEN_IN_MVN_REPOSITORY, browserName)).apply {
                        addActionListener { openInMavenRepository(groupId, artifactId, currentVersion) }
                    })
                    if (vulnerabilityCell != null && vulnerabilityCell.allAdvisories.isNotEmpty()) {
                        popup.addSeparator()
                        popup.add(JMenuItem(MyMessageBundle.message("toolwindow.MyToolWindow.contextMenu.showVulnerabilityDetails")).apply {
                            addActionListener {
                                val coordinate = "$groupId:$artifactId:$currentVersion"
                                VulnerabilityDetailDialog(
                                    project,
                                    vulnerabilityCell.detailFindings(),
                                    "$coordinate - ${MyMessageBundle.message(VULNERABILITY_DETAILS_TITLE)}"
                                ).show()
                            }
                        })
                    }
                    popup.show(e.component, e.x, e.y)
                }
            })

            // Force commit editor when focus lost
            table.putClientProperty("terminateEditOnFocusLost", true)

            table.selectionModel.addListSelectionListener { event ->
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
                    CURRENT_VERSION_COLUMN, VULNERABILITIES_COLUMN, NEW_VERSION_COLUMN ->
                        tableRowSorter.setSortable(columnIndex, false)
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

                    // Custom-Renderer, der Farbe und Font nur im Anzeigefeld übernimmt (nicht im Dropdown)
                    // und die aktuelle Version in der Dropdown-Liste hervorhebt.
                    combo.setRenderer { _, value, index, _, _ ->
                        JLabel(value ?: "").apply {
                            if (index == -1) {
                                foreground = combo.foreground
                                font = combo.font
                            } else if (value != null && value == currentVersion) {
                                text = versionDropdownItemText(value, currentVersion)
                                font = font.deriveFont(Font.BOLD)
                            }
                        }
                    }

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

            table.columnModel.getColumn(VULNERABILITIES_COLUMN).cellRenderer =
                TableCellRenderer { currentTable, value, isSelected, _, _, _ ->
                    val cell = value as? VulnerabilityCell ?: VulnerabilityCell(emptyMap(), emptySet())
                    val advisories = cell.allAdvisories
                    JLabel(vulnerabilitySummary(cell)).apply {
                        isOpaque = true
                        border = BorderFactory.createEmptyBorder(0, 6, 0, 6)
                        if (advisories.isNotEmpty()) {
                            icon = AllIcons.Ide.Link
                            horizontalTextPosition = JLabel.TRAILING
                            toolTipText = MyMessageBundle.message(VULNERABILITY_DETAILS_TITLE)
                        } else {
                            toolTipText = null
                        }
                        background = if (isSelected) {
                            currentTable.selectionBackground
                        } else {
                            vulnerabilityColor(worstSeverity(advisories), currentTable.background)
                        }
                        foreground = if (isSelected) currentTable.selectionForeground else currentTable.foreground
                    }
                }

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
                    vulnerabilityScanPerformed = false
                }
                dependencyToProperty.clear()
                knownDependencies.clear()
                knownTypes.clear()
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
                        snapshot.rows.forEach { row ->
                            knownDependencies[row.key] = row.currentVersion
                            knownTypes[row.key] = row.type
                            tableModel.addRow(
                                arrayOf(
                                    row.groupId,
                                    row.artifactId,
                                    row.propertyName,
                                    row.type,
                                    row.currentVersion,
                                    buildVulnerabilityCell(
                                        "${row.key}:${row.currentVersion}",
                                        vulnerabilityAdvisories,
                                        transitiveDependenciesByDirect["${row.key}:${row.currentVersion}"].orEmpty()
                                    ),
                                    availableVersions[row.key].orEmpty()
                                )
                            )
                        }
                        updateUpdateButtonState()
                        updateTypeFilterOptions()
                        updateUpdatesFilterState()
                        updateVulnerabilitiesFilterState()

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
                if (!isUpdating && selectedVersions.isNotEmpty()) {
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

            refreshAction(false, true, true)

            add(JBScrollPane(table), BorderLayout.CENTER)

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
                    e.presentation.isEnabled = isBulkVersionSelectionEnabled()
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
                    { isBulkVersionSelectionEnabled() },
                    descriptionProvider = {
                        bulkSelectionActionDescription(
                            MyMessageBundle.message("toolwindow.MyToolWindow.selectHighestMajor.button")
                        )
                    },
                    isMenuItem = true
                ) { selectHighestMajorVersionForAll() })
                add(toolbarAction(
                    "toolwindow.MyToolWindow.selectHighestMinor.button",
                    AllIcons.Actions.Play_forward,
                    { isBulkVersionSelectionEnabled() },
                    descriptionProvider = {
                        bulkSelectionActionDescription(
                            MyMessageBundle.message("toolwindow.MyToolWindow.selectHighestMinor.button")
                        )
                    },
                    isMenuItem = true
                ) { selectHighestMinorVersionForAll() })
            }

            toolbarGroup.apply {
                add(toolbarAction(
                    "toolwindow.MyToolWindow.refresh.button",
                    AllIcons.Actions.Refresh,
                    { !isUpdating }
                ) { refreshAction(false, true, true) })
                add(toolbarAction(
                    "toolwindow.MyToolWindow.checkUpdates.button",
                    AllIcons.Actions.Find,
                    { !isUpdating },
                    shortLabelKey = "toolwindow.MyToolWindow.checkUpdates.button.short"
                ) { refreshAction(true, true, false) })
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
                    { isResetVersionsEnabled() },
                    shortLabelKey = "toolwindow.MyToolWindow.resetVersions.button.short",
                    descriptionProvider = {
                        MyMessageBundle.message("toolwindow.MyToolWindow.resetVersions.tooltip")
                    }
                ) { confirmAndResetAllVersionsToCurrent() })
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
            val toolbar = ActionManager.getInstance()
                .createActionToolbar("MavenUpToolWindow", toolbarGroup, true)
            toolbar.targetComponent = table
            actionToolbar = toolbar

            topPanel.add(toolbar.component, BorderLayout.NORTH)
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
                        refreshAction(false, true, true)
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
         * Fordert die obere Aktionsleiste auf, den Aktivierungszustand und die Beschriftungen
         * ihrer Aktionen neu zu berechnen.
         */
        private fun refreshToolbar() {
            actionToolbar?.updateActionsAsync()
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
         * Erzeugt einen Renderer, der die [TriStateFilter]-Werte einer Filter-Combobox mit
         * kontextspezifischen, selbsterklärenden Texten anzeigt.
         *
         * @param labels Die Message-Bundle-Schlüssel der Optionstexte des jeweiligen Filters.
         * @return Ein [ListCellRenderer] für die Filter-Combobox.
         */
        private fun triStateFilterRenderer(labels: TriStateFilterLabels): ListCellRenderer<in TriStateFilter> =
            object : DefaultListCellRenderer() {
                override fun getListCellRendererComponent(
                    list: JList<*>?,
                    value: Any?,
                    index: Int,
                    isSelected: Boolean,
                    cellHasFocus: Boolean
                ): Component {
                    val text = (value as? TriStateFilter)?.let { triStateFilterOptionLabel(it, labels) }
                        ?: value?.toString()
                    return super.getListCellRendererComponent(list, text, index, isSelected, cellHasFocus)
                }
            }

        /**
         * Erstellt die Filterzeile mit Typ-, Updates-, Änderungs- und Vulnerabilities-Combobox sowie Textfeld unterhalb der Aktionsleiste.
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
            vulnerabilitiesFilterComboBox.model = DefaultComboBoxModel(TriStateFilter.entries.toTypedArray())
            vulnerabilitiesFilterComboBox.selectedItem = TriStateFilter.ALL
            vulnerabilitiesFilterComboBox.renderer = triStateFilterRenderer(VULNERABILITIES_FILTER_LABELS)
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
         * @return `true`, wenn Suchtext, Typ-, Änderungs-, Updates- oder Vulnerabilities-Filter von ihrem
         *         Standardwert abweichen.
         */
        internal fun isResetFiltersEnabled(): Boolean {
            val searchActive = searchTextField.text.isNotEmpty()
            val typeActive = (typeFilterComboBox.selectedItem as? String ?: allTypesFilterLabel) != allTypesFilterLabel
            val changesActive = (changesFilterComboBox.selectedItem as? TriStateFilter ?: TriStateFilter.ALL) != TriStateFilter.ALL
            val updatesActive =
                (updatesFilterComboBox.selectedItem as? TriStateFilter ?: TriStateFilter.ALL) != TriStateFilter.ALL
            val vulnerabilitiesActive =
                (vulnerabilitiesFilterComboBox.selectedItem as? TriStateFilter ?: TriStateFilter.ALL) != TriStateFilter.ALL
            return searchActive || typeActive || changesActive || updatesActive || vulnerabilitiesActive
        }

        /**
         * Setzt alle Filter der Filterzeile auf ihren Standardwert zurück und aktualisiert die
         * Tabellenansicht.
         *
         * Zurückgesetzt werden Suchtext, Typ-, Änderungs-, Updates- und Vulnerabilities-Filter.
         */
        internal fun resetAllFilters() {
            searchTextField.text = ""
            typeFilterComboBox.selectedItem = allTypesFilterLabel
            changesFilterComboBox.selectedItem = TriStateFilter.ALL
            updatesFilterComboBox.selectedItem = TriStateFilter.ALL
            vulnerabilitiesFilterComboBox.selectedItem = TriStateFilter.ALL
            applyRowFilter()
        }

        /**
         * Installiert einen Kopfzeilen-Renderer, der die Sortierbarkeit der Spalten sichtbar macht.
         *
         * Sortierbare Spalten erhalten ein rechtsbündiges Icon: einen gedämpften Doppelpfeil im
         * unsortierten Zustand sowie einen Auf-/Ab-Pfeil bei aktiver Sortierung. Nicht sortierbare
         * Spalten bleiben ohne Icon. Der ursprüngliche Renderer wird für das Look-and-Feel-konforme
         * Aussehen der Kopfzeile weiterverwendet.
         *
         * @param table Die Tabelle, deren Kopfzeile den Sortier-Indikator anzeigen soll.
         */
        private fun installSortableHeaderRenderer(table: JBTable) {
            val originalRenderer = table.tableHeader.defaultRenderer
            table.tableHeader.defaultRenderer = TableCellRenderer { tbl, value, isSelected, hasFocus, row, column ->
                val component = originalRenderer.getTableCellRendererComponent(tbl, value, isSelected, hasFocus, row, column)
                if (component is JLabel) {
                    val modelColumn = tbl.convertColumnIndexToModel(column)
                    val sorter = tbl.rowSorter
                    val sortable = sorter is TableRowSorter<*> && sorter.isSortable(modelColumn)
                    val sortOrder = sorter?.sortKeys?.firstOrNull { it.column == modelColumn }?.sortOrder
                    component.icon = sortableHeaderIcon(sortable, sortOrder)
                    component.horizontalTextPosition = SwingConstants.LEADING
                }
                component
            }
        }

        /**
         * Wendet die aktuellen Filter (Suchtext, Typ, Änderungen, Sicherheitslücken) auf die Tabelle an.
         *
         * Liest den Suchtext aus [searchTextField], den gewählten Typ aus [typeFilterComboBox],
         * die Filteroptionen aus [changesFilterComboBox], [updatesFilterComboBox] und [vulnerabilitiesFilterComboBox]
         * und setzt einen entsprechenden [RowFilter] auf den [tableRowSorter].
         */
        internal fun applyRowFilter() {
            val searchText = searchTextField.text
            val selectedType = typeFilterComboBox.selectedItem as? String ?: allTypesFilterLabel
            val typeFilter = if (selectedType == allTypesFilterLabel) "" else selectedType
            val changesFilter = changesFilterComboBox.selectedItem as? TriStateFilter ?: TriStateFilter.ALL
            val updatesFilter = updatesFilterComboBox.selectedItem as? TriStateFilter ?: TriStateFilter.ALL
            val vulnerabilitiesFilter = vulnerabilitiesFilterComboBox.selectedItem as? TriStateFilter ?: TriStateFilter.ALL

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
                    val hasVulnerabilities = cell != null && cell.allAdvisories.isNotEmpty()

                    return rowMatchesFilter(
                        FilterRow(
                            groupId = groupId,
                            artifactId = artifactId,
                            property = property,
                            type = type,
                            hasChange = hasChange,
                            hasUpdate = hasUpdate,
                            hasVulnerabilities = hasVulnerabilities
                        ),
                        FilterCriteria(
                            searchText = searchText,
                            typeFilter = typeFilter,
                            changesFilter = changesFilter,
                            updatesFilter = updatesFilter,
                            vulnerabilitiesFilter = vulnerabilitiesFilter
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
         * Der Filter setzt eine erfolgreiche Versionssuche ("Search for New Versions") voraus, die für
         * mindestens ein Artefakt eine Versionsliste geliefert hat.
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
         * [TriStateFilter.ALL] zurückgesetzt, damit keine unsichtbare Filterung aktiv bleibt.
         */
        internal fun updateVulnerabilitiesFilterState() {
            val available = isVulnerabilitiesFilterAvailable()
            vulnerabilitiesFilterComboBox.isEnabled = available
            if (!available && vulnerabilitiesFilterComboBox.selectedItem != TriStateFilter.ALL) {
                vulnerabilitiesFilterComboBox.selectedItem = TriStateFilter.ALL
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
         * Baut die obere Aktionsleiste neu auf, damit ein geänderter Text-/Icon-Modus wirksam wird.
         * Ersetzt die bestehende Aktionsleiste im Nordbereich durch eine neu erstellte Instanz.
         * Muss auf dem Event Dispatch Thread laufen.
         */
        private fun rebuildToolbar() {
            actionToolbar?.let { topPanel.remove(it.component) }
            val toolbar = ActionManager.getInstance()
                .createActionToolbar("MavenUpToolWindow", toolbarGroup, true)
            toolbar.targetComponent = table
            actionToolbar = toolbar
            topPanel.add(toolbar.component, BorderLayout.NORTH)
            topPanel.revalidate()
            topPanel.repaint()
        }

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
            applyBulkVersionSelection(visibleOnly = true) { _, versions -> versions.firstOrNull().orEmpty() }
        }

        /**
         * Wählt für alle aktuell in der Tabelle sichtbaren Abhängigkeiten die höchste Version innerhalb
         * derselben Major-Linie wie die aktuell verwendete Version aus.
         *
         * Ist ein Filter aktiv, werden ausgeblendete Einträge bewusst nicht verändert. Existiert keine
         * passende Version derselben Major-Linie, bleibt die aktuelle Version erhalten.
         */
        internal fun selectHighestMinorVersionForAll() {
            applyBulkVersionSelection(visibleOnly = true) { current, versions ->
                latestVersionWithinSameMajor(current, versions) ?: current
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
            applyBulkVersionSelection(visibleOnly = false) { current, _ -> current }
        }

        /**
         * Setzt alle Versionsauswahlen zurück und zeigt zuvor – sofern konfiguriert – einen
         * Bestätigungsdialog an.
         *
         * Ist die Einstellung [MavenUpSettings.State.confirmVersionReset] aktiv, wird ein Ja/Nein-Dialog
         * angezeigt. Über die Option „Don't ask again" kann der Benutzer die Bestätigung dauerhaft
         * deaktivieren; in diesem Fall wird die Einstellung entsprechend gespeichert. Bricht der Benutzer
         * ab, bleibt die aktuelle Auswahl unverändert.
         */
        internal fun confirmAndResetAllVersionsToCurrent() {
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
                        MyMessageBundle.message("toolwindow.MyToolWindow.resetVersions.confirm.title"),
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
         * Wendet eine Auswahlstrategie auf Abhängigkeiten an und aktualisiert die Tabelle.
         *
         * Für jede berücksichtigte Abhängigkeit wird die von [chooser] gelieferte Zielversion übernommen.
         * Entspricht sie der aktuellen Version (oder ist leer), wird der Eintrag aus [selectedVersions]
         * entfernt, sodass keine Änderung angezeigt wird.
         *
         * @param visibleOnly Wenn `true`, werden nur aktuell in der Tabelle sichtbare (nicht ausgefilterte)
         *   Abhängigkeiten berücksichtigt; ansonsten alle geladenen Abhängigkeiten.
         * @param chooser Funktion, die aus der aktuellen Version und den verfügbaren Versionen die Zielversion ermittelt.
         */
        private fun applyBulkVersionSelection(visibleOnly: Boolean, chooser: (String, List<String>) -> String) {
            if (availableVersions.isEmpty()) return
            val visibleKeys = if (visibleOnly) collectVisibleDependencyKeys() else null
            for ((key, versions) in availableVersions) {
                if (versions.isEmpty()) continue
                if (visibleKeys != null && key !in visibleKeys) continue
                val currentVersion = knownDependencies[key] ?: ""
                val chosen = chooser(currentVersion, versions)
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
         * @return `true`, wenn weniger Zeilen sichtbar sind als das Tabellenmodell enthält.
         */
        internal fun isRowFilterHidingEntries(): Boolean {
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
            }
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
         * @return `true`, wenn eine Zeile selektiert ist.
         */
        internal fun isOpenInRepositoryEnabled(): Boolean =
            table.selectedRow >= 0

        /**
         * Prüft, ob für die aktuell selektierte Zeile Vulnerability-Details angezeigt werden können.
         *
         * @return `true`, wenn die selektierte Zeile mindestens eine Sicherheitswarnung besitzt.
         */
        internal fun isVulnerabilityDetailsEnabled(): Boolean {
            val row = table.selectedRow
            val cell = if (row >= 0) table.getValueAt(row, VULNERABILITIES_COLUMN) as? VulnerabilityCell else null
            return !isUpdating && cell?.allAdvisories?.isNotEmpty() == true
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
         */
        internal fun openInMavenRepositoryForSelectedRow() {
            val row = table.selectedRow
            if (row < 0) return
            val groupId = table.getValueAt(row, GROUP_ID_COLUMN)?.toString().orEmpty()
            val artifactId = table.getValueAt(row, ARTIFACT_ID_COLUMN)?.toString().orEmpty()
            val currentVersion = table.getValueAt(row, CURRENT_VERSION_COLUMN)?.toString().orEmpty()
            openInMavenRepository(groupId, artifactId, currentVersion)
        }

        /**
         * Öffnet den Vulnerability-Details-Dialog für die aktuell selektierte Abhängigkeit.
         */
        internal fun openVulnerabilityDetailsForSelectedRow() {
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
                    "$groupId:$artifactId - ${MyMessageBundle.message(VULNERABILITY_DETAILS_TITLE)}"
                ).show()
            }
        }

        /**
         * Sammelt alle in der UI ausgewählten Updates, für die eine neue Version gewählt wurde.
         */
        internal fun collectSelectedUpdates(): List<DependencyUpdate> {
            return selectedVersions.mapNotNull { (key, newVersion) ->
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

                    ApplicationManager.getApplication().invokeLater {
                        applyVulnerabilityResults(results, scanTargets)
                        ossIndexScan.errorMessage?.let(::showOssIndexError)
                        onFinished()
                    }
                }
            })
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
         * Liefert die UI-Komponente des Tool Windows zurück.
         */
        fun getContent(): JBPanel<JBPanel<*>> = content

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
