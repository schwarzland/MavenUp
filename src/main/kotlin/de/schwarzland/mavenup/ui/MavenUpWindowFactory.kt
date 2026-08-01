package de.schwarzland.mavenup.ui

import de.schwarzland.mavenup.model.DependencyUpdate
import de.schwarzland.mavenup.model.VulnerabilityAdvisory
import de.schwarzland.mavenup.model.VulnerabilitySeverity
import de.schwarzland.mavenup.service.DependencyApiService
import de.schwarzland.mavenup.service.MavenRepositoryBrowser
import de.schwarzland.mavenup.service.MavenUpSettings
import de.schwarzland.mavenup.service.OssIndexApiService
import de.schwarzland.mavenup.service.OssIndexCredentialService
import de.schwarzland.mavenup.service.VulnerabilityApiService
import de.schwarzland.mavenup.service.VulnerabilityMerger
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
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
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.psi.PsiManager
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
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
import org.jetbrains.idea.maven.model.MavenArtifactNode
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import javax.swing.table.DefaultTableModel
import javax.swing.table.TableCellRenderer


private const val MANAGED_PLUGIN = "managed plugin"
private const val TOOLWINDOW_MY_TOOL_WINDOW_TYPE_MANAGED_DEPENDENCY = "toolwindow.MyToolWindow.type.managedDependency"
private const val GROUP_ID_COLUMN = 0
private const val ARTIFACT_ID_COLUMN = 1
private const val TYPE_COLUMN = 3
private const val CURRENT_VERSION_COLUMN = 4
private const val VULNERABILITIES_COLUMN = 5
private const val NEW_VERSION_COLUMN = 6
private val LOG = Logger.getInstance(MavenUpWindowFactory::class.java)

/**
 * Erstellt die URL zur Repository-Browser-Seite für eine gegebene Abhängigkeit und Version.
 * Verwendet den konfigurierten [MavenRepositoryBrowser]; standard ist [MavenRepositoryBrowser.MVN_REPOSITORY].
 */
internal fun buildMavenRepositoryUrl(
    groupId: String,
    artifactId: String,
    version: String,
    browser: MavenRepositoryBrowser = MavenRepositoryBrowser.MVN_REPOSITORY
): String = browser.urlFor(groupId, artifactId, version)

/**
 * Repräsentiert eine einzelne Zeile in der Abhängigkeitstabelle.
 */
internal data class RefreshRow(
    val groupId: String,
    val artifactId: String,
    val propertyName: String,
    val type: String,
    val currentVersion: String
) {
    val key: String = "$groupId:$artifactId"
}

/**
 * Enthält einen Schnappschuss aller relevanten Projektdaten für die Anzeige im Tool Window.
 */
internal data class RefreshSnapshot(
    val rows: List<RefreshRow>,
    val dependencyProperties: Map<String, String>
)

/**
 * Modell für die Darstellung von Sicherheitslücken in einer Tabellenzelle.
 * Kapselt die Zuordnung von Koordinaten zu Warnungen und unterscheidet zwischen direkten und transitiven Funden.
 */
internal data class VulnerabilityCell(
    val advisoriesByCoordinate: Map<String, List<VulnerabilityAdvisory>>,
    val transitiveCoordinates: Set<String>
) {
    val allAdvisories: List<VulnerabilityAdvisory>
        get() = advisoriesByCoordinate.values.flatten()

    val transitiveAdvisoryCount: Int
        get() = advisoriesByCoordinate
            .filterKeys { it in transitiveCoordinates }
            .values
            .sumOf { it.size }

    fun detailFindings(): Map<String, List<VulnerabilityAdvisory>> =
        advisoriesByCoordinate.mapKeys { (coordinate, _) ->
            if (coordinate in transitiveCoordinates) "$coordinate (transitive)" else coordinate
        }
}

/**
 * Erstellt ein [VulnerabilityCell]-Objekt für eine spezifische Abhängigkeit.
 * Kombiniert die Warnungen für die direkte Abhängigkeit mit denen ihrer transitiven Kinder.
 */
internal fun buildVulnerabilityCell(
    directCoordinate: String,
    vulnerabilityAdvisories: Map<String, List<VulnerabilityAdvisory>>,
    transitiveCoordinates: Set<String>
): VulnerabilityCell {
    val findings = linkedMapOf<String, List<VulnerabilityAdvisory>>()
    vulnerabilityAdvisories[directCoordinate]
        ?.takeIf { it.isNotEmpty() }
        ?.let { findings[directCoordinate] = it }
    transitiveCoordinates.sorted().forEach { coordinate ->
        vulnerabilityAdvisories[coordinate]
            ?.takeIf { it.isNotEmpty() }
            ?.let { findings[coordinate] = it }
    }
    return VulnerabilityCell(findings, transitiveCoordinates)
}

/**
 * Erstellt einen kurzen Zusammenfassungstext für die Anzeige in der Tabellenzelle.
 * Zeigt die Anzahl der Warnungen, den schlimmsten Schweregrad und (falls vorhanden) transitive Funde an.
 */
internal fun vulnerabilitySummary(cell: VulnerabilityCell): String {
    val advisories = cell.allAdvisories
    if (advisories.isEmpty()) return ""
    val severity = worstSeverity(advisories)
    val attributes = buildList {
        if (cell.transitiveAdvisoryCount > 0) add("${cell.transitiveAdvisoryCount} transitive")
        if (severity != VulnerabilitySeverity.UNKNOWN) add(severity.name)
    }
    return if (attributes.isEmpty()) {
        advisories.size.toString()
    } else {
        "${advisories.size} (${attributes.joinToString()})"
    }
}

private fun worstSeverity(advisories: List<VulnerabilityAdvisory>): VulnerabilitySeverity =
    advisories.maxByOrNull { it.severity.rank }?.severity ?: VulnerabilitySeverity.UNKNOWN

private data class VulnerabilityScanTargets(
    val dependencies: Set<Triple<String, String, String>>,
    val transitiveCoordinates: Set<String>,
    val transitiveDependenciesByDirect: Map<String, Set<String>>
)

private fun artifactNodeCoordinate(node: MavenArtifactNode): Triple<String, String, String>? {
    val artifact = node.artifact
    val version = artifact.version.orEmpty()
    return if (artifact.groupId.isNotEmpty() && artifact.artifactId.isNotEmpty() && version.isNotEmpty()) {
        Triple(artifact.groupId, artifact.artifactId, version)
    } else {
        null
    }
}

private fun coordinateString(coordinate: Triple<String, String, String>): String =
    "${coordinate.first}:${coordinate.second}:${coordinate.third}"

/**
 * Prüft, ob eine Sicherheitsprüfung aktuell durchgeführt werden kann.
 */
internal fun canCheckVulnerabilities(isRefreshing: Boolean, isUpdating: Boolean): Boolean {
    return !isRefreshing && !isUpdating
}


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
     * Dialog zur Bestätigung der ausgewählten Updates. Zeigt eine Tabelle mit den
     * durchzuführenden Änderungen (Gruppe, Artefakt, Version alt/neu).
     */
    class UpdateConfirmationDialog(
        project: Project,
        private val updates: List<DependencyUpdate>
    ) : DialogWrapper(project) {
        init {
            title = MyMessageBundle.message("toolwindow.MyToolWindow.update.confirm.title")
            init()
        }

        /**
         * Erstellt den zentralen Bereich des Dialogs mit der Update-Übersichtstabelle.
         */
        override fun createCenterPanel(): JComponent {
            val panel = JBPanel<JBPanel<*>>(BorderLayout())
            panel.preferredSize = java.awt.Dimension(600, 400)
            panel.add(
                JLabel(MyMessageBundle.message("toolwindow.MyToolWindow.update.confirm.message")),
                BorderLayout.NORTH
            )

            val tableModel = DefaultTableModel().apply {
                addColumn(MyMessageBundle.message("toolwindow.MyToolWindow.table.header.groupId"))
                addColumn(MyMessageBundle.message("toolwindow.MyToolWindow.table.header.artifactId"))
                addColumn(MyMessageBundle.message("toolwindow.MyToolWindow.table.header.type"))
                addColumn(MyMessageBundle.message("toolwindow.MyToolWindow.table.header.currentVersion"))
                addColumn(MyMessageBundle.message("toolwindow.MyToolWindow.table.header.newVersion"))
            }

            updates.forEach { update ->
                tableModel.addRow(
                    arrayOf(
                        update.groupId,
                        update.artifactId,
                        update.type,
                        update.oldVersion,
                        update.newVersion
                    )
                )
            }

            val table = JBTable(tableModel)
            panel.add(JBScrollPane(table), BorderLayout.CENTER)
            return panel
        }

    }

    /**
     * Die eigentliche Tool-Window-Komponente, die die Tabelle der Abhängigkeiten und die Aktions-Buttons verwaltet.
     */
    internal inner class MyToolWindow(private val project: Project) : Disposable {
        private val vulnerabilityApiService = VulnerabilityApiService()
        private val ossIndexApiService = OssIndexApiService()
        private val ossIndexCredentialService = OssIndexCredentialService()
        private val dependencyApiService = DependencyApiService(project)
        private val availableVersions = mutableMapOf<String, List<String>>()
        private val selectedVersions = mutableMapOf<String, String>()
        private val dependencyToProperty = mutableMapOf<String, String>()
        private val knownDependencies = mutableMapOf<String, String>() // key to current version
        private val knownTypes = mutableMapOf<String, String>()
        private val vulnerabilityAdvisories = mutableMapOf<String, List<VulnerabilityAdvisory>>()
        private val transitiveCoordinates = mutableSetOf<String>()
        private val transitiveDependenciesByDirect = mutableMapOf<String, Set<String>>()
        private var isUpdating = false
        private var isRefreshing = false
        private var refreshGeneration = 0

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

            val table = object : JBTable(tableModel) {
                override fun getToolTipText(e: MouseEvent): String? {
                    val row = rowAtPoint(e.point)
                    val column = columnAtPoint(e.point)
                    if (row < 0 || column == VULNERABILITIES_COLUMN) return super.getToolTipText(e)
                    val settings = MavenUpSettings.getInstance(project)
                    return if (settings.state.jumpOnSingleClick)
                        MyMessageBundle.message("toolwindow.MyToolWindow.table.row.tooltip.singleClick")
                    else
                        MyMessageBundle.message("toolwindow.MyToolWindow.table.row.tooltip.doubleClick")
                }
            }

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
                                "$coordinate - ${MyMessageBundle.message("vulnerability.details.title")}"
                            ).show()
                        }
                        return
                    }

                    val settings = MavenUpSettings.getInstance(project)
                    val requiredClickCount = if (settings.state.jumpOnSingleClick) 1 else 2

                    if (e.clickCount == requiredClickCount) {
                        val groupId = table.getValueAt(row, GROUP_ID_COLUMN) as? String ?: ""
                        val artifactId = table.getValueAt(row, ARTIFACT_ID_COLUMN) as? String ?: ""
                        val type = table.getValueAt(row, TYPE_COLUMN) as? String ?: "dependency"

                        navigateToDependency(groupId, artifactId, type)
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

                    val popup = JPopupMenu()
                    popup.add(JMenuItem(MyMessageBundle.message("toolwindow.MyToolWindow.contextMenu.navigateToPom")).apply {
                        addActionListener { navigateToDependency(groupId, artifactId, type) }
                    })
                    popup.add(JMenuItem(MyMessageBundle.message("toolwindow.MyToolWindow.contextMenu.openInMvnRepository")).apply {
                        addActionListener { openInMavenRepository(groupId, artifactId, currentVersion) }
                    })
                    popup.show(e.component, e.x, e.y)
                }
            })

            val refreshButton = JButton(MyMessageBundle.message("toolwindow.MyToolWindow.refresh.button"))
            val checkUpdatesButton = JButton(MyMessageBundle.message("toolwindow.MyToolWindow.checkUpdates.button"))
            val checkVulnerabilitiesButton =
                JButton(MyMessageBundle.message("toolwindow.MyToolWindow.checkVulnerabilities.button"))
            val vulnerabilityDetailsButton =
                JButton(MyMessageBundle.message("toolwindow.MyToolWindow.vulnerabilityDetails.button")).apply {
                    isEnabled = false
                }
            val updateButton = JButton(MyMessageBundle.message("toolwindow.MyToolWindow.update.button"))
            fun updateVulnerabilityCheckButtonState() {
                checkVulnerabilitiesButton.isEnabled = canCheckVulnerabilities(isRefreshing, isUpdating)
            }
            val settingsButton = JButton(AllIcons.General.Settings).apply {
                toolTipText = MyMessageBundle.message("toolwindow.MyToolWindow.settings.button")
                isBorderPainted = false
                isContentAreaFilled = false
                isFocusPainted = false
                preferredSize = java.awt.Dimension(20, 20)
            }

            // Force commit editor when focus lost
            table.putClientProperty("terminateEditOnFocusLost", true)

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

                    ComboBox(versions.toTypedArray()).apply {
                        if (selectedVersion != null) {
                            selectedItem = selectedVersion
                        }

                        val currentVersion = table?.getValueAt(row, CURRENT_VERSION_COLUMN) as? String ?: ""
                        val newestVersion = versions.firstOrNull() ?: ""
                        if (currentVersion == newestVersion && currentVersion.isNotEmpty()) {
                            foreground = com.intellij.ui.JBColor.BLUE
                        }

                        if (isSelected) {
                            background = table?.selectionBackground
                            if (currentVersion != newestVersion) {
                                foreground = table?.selectionForeground
                            }
                        }
                    }
                }

            table.columnModel.getColumn(NEW_VERSION_COLUMN).cellEditor = object : AbstractTableCellEditor() {
                private var currentComboBox: ComboBox<String>? = null
                private var currentKey: String? = null

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
                    if (currentVersion == newestVersion && currentVersion.isNotEmpty()) {
                        combo.foreground = com.intellij.ui.JBColor.BLUE
                    }

                    val selectedVersion = if (currentKey != null) selectedVersions[currentKey!!] else null
                    if (selectedVersion != null) {
                        combo.selectedItem = selectedVersion
                    }

                    combo.addActionListener {
                        val selected = combo.selectedItem as? String
                        val key = currentKey
                        if (key != null && selected != null) {
                            selectedVersions[key] = selected

                            // Synchronize other dependencies using the same property
                            val property = dependencyToProperty[key]
                            if (property != null) {
                                dependencyToProperty.forEach { (depKey, prop) ->
                                    if (prop == property) {
                                        selectedVersions[depKey] = selected
                                    }
                                }
                            }
                        }
                        updateUpdateButtonState(updateButton)
                    }

                    currentComboBox = combo
                    return combo
                }

                override fun getCellEditorValue(): Any? {
                    val selected = currentComboBox?.selectedItem as? String
                    if (currentKey != null && selected != null) {
                        selectedVersions[currentKey!!] = selected

                        // Synchronize other dependencies using the same property
                        val property = dependencyToProperty[currentKey!!]
                        if (property != null) {
                            dependencyToProperty.forEach { (key, prop) ->
                                if (prop == property) {
                                    selectedVersions[key] = selected
                                }
                            }
                        }
                    }
                    updateUpdateButtonState(updateButton)
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
                            toolTipText = MyMessageBundle.message("vulnerability.details.title")
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

            fun refreshAction(checkUpdates: Boolean, clearNewVersions: Boolean) {
                if (isUpdating) return

                val generation = ++refreshGeneration
                isRefreshing = true
                updateVulnerabilityCheckButtonState()
                tableModel.setRowCount(0)
                if (clearNewVersions) {
                    availableVersions.clear()
                    selectedVersions.clear()
                }
                dependencyToProperty.clear()
                knownDependencies.clear()
                knownTypes.clear()
                updateUpdateButtonState(updateButton)

                val managedDependencyType =
                    MyMessageBundle.message(TOOLWINDOW_MY_TOOL_WINDOW_TYPE_MANAGED_DEPENDENCY)
                ReadAction.nonBlocking<RefreshSnapshot> {
                    collectRefreshSnapshot(managedDependencyType)
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
                        updateUpdateButtonState(updateButton)

                        if (checkUpdates) {
                            performUpdateCheck(
                                refreshButton,
                                checkUpdatesButton,
                                checkVulnerabilitiesButton,
                                updateButton
                            ) {
                                refreshAction(false, false)
                            }
                        }
                        isRefreshing = false
                        updateVulnerabilityCheckButtonState()
                    }
                    .submit(AppExecutorUtil.getAppExecutorService())
            }


            val updateAction = {
                if (!isUpdating && selectedVersions.isNotEmpty()) {
                    val updates = collectSelectedUpdates()

                    if (updates.isNotEmpty()) {
                        val dialog = UpdateConfirmationDialog(project, updates)
                        if (dialog.showAndGet()) {
                            ProgressManager.getInstance().run(object : Task.Backgroundable(
                                project,
                                MyMessageBundle.message("toolwindow.MyToolWindow.update.progress"),
                                true
                            ) {
                                override fun run(indicator: ProgressIndicator) {
                                    MavenProjectsManager.getInstance(project).projects.forEach { mavenProject ->
                                        applyUpdateToPom(mavenProject, updates)
                                    }

                                    ApplicationManager.getApplication().invokeLater {
                                        selectedVersions.clear()
                                        availableVersions.clear()
                                        refreshAction(false, true)

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
                    refreshButton.isEnabled = false
                    checkUpdatesButton.isEnabled = false
                    checkVulnerabilitiesButton.isEnabled = false
                    vulnerabilityDetailsButton.isEnabled = false
                    updateButton.isEnabled = false

                    performVulnerabilityCheck {
                        isUpdating = false
                        refreshButton.isEnabled = true
                        checkUpdatesButton.isEnabled = true
                        updateVulnerabilityCheckButtonState()
                        refreshAction(false, false)
                        vulnerabilityDetailsButton.isEnabled = vulnerabilityAdvisories.values.any { it.isNotEmpty() }
                        updateUpdateButtonState(updateButton)
                    }
                }
            }

            refreshAction(false, true)

            add(JBScrollPane(table), BorderLayout.CENTER)

            val buttonPanel = JPanel(BorderLayout()).apply {
                val leftButtonPanel = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
                    add(refreshButton.apply {
                        addActionListener { refreshAction(false, true) }
                    })
                    add(checkUpdatesButton.apply {
                        addActionListener { refreshAction(true, true) }
                    })
                    add(checkVulnerabilitiesButton.apply {
                        addActionListener { checkVulnerabilitiesAction() }
                    })
                    add(vulnerabilityDetailsButton.apply {
                        addActionListener { showAllVulnerabilityDetails() }
                    })
                    add(updateButton.apply {
                        addActionListener { updateAction() }
                    })
                }
                add(leftButtonPanel, BorderLayout.WEST)
                add(settingsButton.apply {
                    addActionListener { openSettings() }
                }, BorderLayout.EAST)
            }

            add(buttonPanel, BorderLayout.SOUTH)

            project.messageBus.connect(this@MyToolWindow).subscribe(MavenImportListener.TOPIC, object : MavenImportListener {
                override fun importFinished(
                    importedProjects: Collection<MavenProject>,
                    newModules: List<com.intellij.openapi.module.Module>
                ) {
                    ApplicationManager.getApplication().invokeLater {
                        availableVersions.clear()
                        selectedVersions.clear()
                        refreshAction(false, true)
                    }
                }
            })
        }

        override fun dispose() = Unit

        /**
         * Aktualisiert den Status des Update-Buttons basierend darauf, ob Änderungen ausgewählt wurden.
         */
        private fun updateUpdateButtonState(updateButton: JButton) {
            var hasUpdate = false
            knownDependencies.forEach { (key, currentVersion) ->
                val newVersion = selectedVersions[key]
                if (newVersion != null && newVersion != currentVersion) {
                    hasUpdate = true
                }
            }
            updateButton.isEnabled = hasUpdate && !isUpdating
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

        @Suppress("unused")
        private fun collectDependenciesAndProperties(
            parentTag: XmlTag?,
            wrapperTagName: String,
            itemTagName: String,
            targetMap: MutableMap<String, String>
        ) {
            collectDependenciesAndProperties(
                parentTag,
                wrapperTagName,
                itemTagName,
                targetMap,
                dependencyToProperty
            )
        }

        /**
         * Durchsucht die XML-Tags nach Abhängigkeiten und extrahiert deren Koordinaten sowie
         * mögliche Platzhalter (Properties).
         */
        private fun collectDependenciesAndProperties(
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
         * Erstellt einen Schnappschuss der aktuellen Abhängigkeiten im Projekt.
         * Muss außerhalb des Event Dispatch Threads aufgerufen werden.
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

                val resolvedDependencies =
                    mavenProject.dependencyTree.associateBy { "${it.artifact.groupId}:${it.artifact.artifactId}" }
                (localDependencies.keys + managedDependencies.keys).forEach { key ->
                    rows.add(
                        RefreshRow(
                            groupId = key.substringBefore(":"),
                            artifactId = key.substringAfter(":"),
                            propertyName = properties[key].orEmpty(),
                            type = if (managedDependencies.containsKey(key)) managedDependencyType else "dependency",
                            currentVersion = resolvedDependencies[key]?.artifact?.version
                                ?: managedDependencies[key]
                                ?: localDependencies[key]
                                ?: ""
                        )
                    )
                }

                val resolvedPlugins = mavenProject.plugins.associateBy { "${it.groupId}:${it.artifactId}" }
                (localPlugins.keys + managedPlugins.keys).forEach { key ->
                    rows.add(
                        RefreshRow(
                            groupId = key.substringBefore(":"),
                            artifactId = key.substringAfter(":"),
                            propertyName = properties[key].orEmpty(),
                            type = if (managedPlugins.containsKey(key)) MANAGED_PLUGIN else "plugin",
                            currentVersion = resolvedPlugins[key]?.version
                                ?: managedPlugins[key]
                                ?: localPlugins[key]
                                ?: ""
                        )
                    )
                }
            }

            return RefreshSnapshot(rows, properties)
        }

        /**
         * Orchestriert den Prozess der Update-Prüfung und aktualisiert die UI-Komponenten.
         */
        private fun performUpdateCheck(
            refreshButton: JButton,
            checkUpdatesButton: JButton,
            checkVulnerabilitiesButton: JButton,
            updateButton: JButton,
            refreshAfterCheck: () -> Unit
        ) {
            isUpdating = true
            refreshButton.isEnabled = false
            checkUpdatesButton.isEnabled = false
            checkVulnerabilitiesButton.isEnabled = false
            updateButton.isEnabled = false

            availableVersions.clear()
            selectedVersions.clear()
            checkForUpdates {
                ApplicationManager.getApplication().invokeLater {
                    isUpdating = false
                    refreshButton.isEnabled = true
                    checkUpdatesButton.isEnabled = true
                    checkVulnerabilitiesButton.isEnabled =
                        canCheckVulnerabilities(isRefreshing, isUpdating)
                    refreshAfterCheck()
                    updateUpdateButtonState(updateButton)
                }
            }
        }

        /**
         * Wendet die ausgewählten Updates auf die `pom.xml` des Projekts an.
         */
        private fun applyUpdateToPom(
            mavenProject: MavenProject,
            updates: List<DependencyUpdate>
        ) {
            val pomFile = mavenProject.file
            val psiFile = ApplicationManager.getApplication().runReadAction<XmlFile?> {
                PsiManager.getInstance(project).findFile(pomFile) as? XmlFile
            } ?: return

            WriteCommandAction.runWriteCommandAction(project) {
                val documentElement = psiFile.document?.rootTag ?: return@runWriteCommandAction
                val propertiesTag = documentElement.findFirstSubTag("properties")

                updates.forEach { update ->
                    val managedDependencyType = MyMessageBundle.message(
                        TOOLWINDOW_MY_TOOL_WINDOW_TYPE_MANAGED_DEPENDENCY
                    )
                    if (update.type == "dependency" || update.type == managedDependencyType) {
                        updateDependencies(documentElement, update, propertiesTag)
                    } else if (update.type == "plugin" || update.type == MANAGED_PLUGIN) {
                        updatePlugins(documentElement, update, propertiesTag)
                    }
                }
            }
        }

        private fun updateDependencies(
            documentElement: XmlTag,
            update: DependencyUpdate,
            propertiesTag: XmlTag?
        ) {
            val dependenciesTag = documentElement.findFirstSubTag("dependencies")
            val dependencyManagementTag = documentElement.findFirstSubTag("dependencyManagement")
            val dmDependenciesTag = dependencyManagementTag?.findFirstSubTag("dependencies")

            // Search in <dependencies>
            dependenciesTag?.findSubTags("dependency")?.forEach { depTag ->
                updateIfMatch(depTag, update, propertiesTag)
            }
            // Search in <dependencyManagement><dependencies>
            dmDependenciesTag?.findSubTags("dependency")?.forEach { depTag ->
                updateIfMatch(depTag, update, propertiesTag)
            }
        }

        private fun updatePlugins(
            documentElement: XmlTag,
            update: DependencyUpdate,
            propertiesTag: XmlTag?
        ) {
            val buildTag = documentElement.findFirstSubTag("build")
            val pluginsTag = buildTag?.findFirstSubTag("plugins")
            val pluginManagementTag = buildTag?.findFirstSubTag("pluginManagement")
            val pmPluginsTag = pluginManagementTag?.findFirstSubTag("plugins")

            // Search in <build><plugins>
            pluginsTag?.findSubTags("plugin")?.forEach { pluginTag ->
                updateIfMatch(pluginTag, update, propertiesTag)
            }
            // Search in <build><pluginManagement><plugins>
            pmPluginsTag?.findSubTags("plugin")?.forEach { pluginTag ->
                updateIfMatch(pluginTag, update, propertiesTag)
            }
        }

        private fun updateIfMatch(
            tag: XmlTag,
            update: DependencyUpdate,
            propertiesTag: XmlTag?
        ) {
            val g = tag.findFirstSubTag("groupId")?.value?.text
            val a = tag.findFirstSubTag("artifactId")?.value?.text

            if (g == update.groupId && a == update.artifactId) {
                updateXmlTagVersion(tag, update.newVersion, propertiesTag)
            }
        }

        /**
         * Führt die Sicherheitsprüfung für die erfassten Abhängigkeiten durch.
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
                    val scanTargets = collectVulnerabilityScanTargets(directDependencies)
                    val dependencies = scanTargets.dependencies
                    LOG.info("Starting vulnerability check for ${dependencies.size} dependencies/plugins.")

                    val osvResults = vulnerabilityApiService.fetchVulnerabilityAdvisories(dependencies.toList(), indicator)
                    val settings = MavenUpSettings.getInstance(project).state
                    var ossIndexError: String? = null
                    val ossIndexResults = if (settings.ossIndexEnabled && !indicator.isCanceled) {
                        try {
                            val credentials = ossIndexCredentialService.retrieve()
                            val username = settings.ossIndexUsername.ifBlank {
                                credentials?.userName.orEmpty()
                            }
                            val token = credentials?.getPasswordAsString().orEmpty()
                            if (username.isBlank() || token.isBlank()) {
                                LOG.warn("Skipping OSS Index vulnerability check because credentials are incomplete.")
                                ossIndexError = MyMessageBundle.message(
                                    "vulnerability.ossIndex.credentialsMissing"
                                )
                                emptyMap()
                            } else {
                                ossIndexApiService.fetchVulnerabilityAdvisories(
                                    dependencies.toList(),
                                    username,
                                    token,
                                    indicator
                                )
                            }
                        } catch (exception: Exception) {
                            LOG.warn("OSS Index vulnerability check failed", exception)
                            ossIndexError = exception.message ?: exception.javaClass.simpleName
                            emptyMap()
                        }
                    } else {
                        emptyMap()
                    }
                    val results = VulnerabilityMerger.merge(osvResults, ossIndexResults)
                    val vulnerableEntries = results.values.count { it.isNotEmpty() }
                    LOG.info(
                        "Finished vulnerability check. " +
                            "Results for ${results.size} entries, $vulnerableEntries entries with known vulnerabilities."
                    )

                    ApplicationManager.getApplication().invokeLater {
                        vulnerabilityAdvisories.clear()
                        vulnerabilityAdvisories.putAll(results)
                        transitiveCoordinates.clear()
                        transitiveCoordinates.addAll(scanTargets.transitiveCoordinates)
                        transitiveDependenciesByDirect.clear()
                        transitiveDependenciesByDirect.putAll(scanTargets.transitiveDependenciesByDirect)
                        ossIndexError?.let {
                            Messages.showWarningDialog(
                                project,
                                it,
                                MyMessageBundle.message("vulnerability.ossIndex.error.title")
                            )
                        }
                        onFinished()
                    }
                }
            })
        }

        /**
         * Sammelt alle direkten und (falls konfiguriert) transitiven Abhängigkeiten für den Scan.
         */
        private fun collectVulnerabilityScanTargets(
            directDependencies: List<Triple<String, String, String>>
        ): VulnerabilityScanTargets {
            val dependencies = LinkedHashSet(directDependencies)
            if (!MavenUpSettings.getInstance(project).state.checkTransitiveDependencies) {
                return VulnerabilityScanTargets(dependencies, emptySet(), emptyMap())
            }

            val transitiveCoordinates = linkedSetOf<String>()
            val transitiveDependenciesByDirect = linkedMapOf<String, Set<String>>()
            collectResolvedDependencyRelations(MavenProjectsManager.getInstance(project).projects)
                .forEach { (directDependency, transitiveDependencies) ->
                    dependencies.add(directDependency)
                    dependencies.addAll(transitiveDependencies)
                    transitiveDependenciesByDirect[coordinateString(directDependency)] =
                        transitiveDependencies.mapTo(linkedSetOf(), ::coordinateString)
                    transitiveDependencies
                        .filterNotTo(linkedSetOf()) { it in directDependencies }
                        .mapTo(transitiveCoordinates, ::coordinateString)
                }
            return VulnerabilityScanTargets(
                dependencies,
                transitiveCoordinates,
                transitiveDependenciesByDirect
            )
        }

        /**
         * Ermittelt die Beziehungen zwischen direkten und transitiven Abhängigkeiten aus dem Maven-Modell.
         */
        internal fun collectResolvedDependencyRelations(
            projects: Collection<MavenProject>
        ): Map<Triple<String, String, String>, Set<Triple<String, String, String>>> {
            val dependenciesByDirect = linkedMapOf<Triple<String, String, String>, MutableSet<Triple<String, String, String>>>()

            fun collectTransitive(node: MavenArtifactNode, target: MutableSet<Triple<String, String, String>>) {
                node.dependencies.forEach { dependency ->
                    artifactNodeCoordinate(dependency)?.let(target::add)
                    collectTransitive(dependency, target)
                }
            }

            projects.forEach { project ->
                project.dependencyTree.forEach { root ->
                    val directDependency = artifactNodeCoordinate(root) ?: return@forEach
                    val transitiveDependencies = dependenciesByDirect.getOrPut(directDependency) { linkedSetOf() }
                    collectTransitive(root, transitiveDependencies)
                }
            }
            return dependenciesByDirect
        }

        /**
         * Öffnet den Detail-Dialog für alle gefundenen Sicherheitslücken.
         */
        private fun showAllVulnerabilityDetails() {
            val findings = vulnerabilityAdvisories
                .filterValues { it.isNotEmpty() }
                .mapKeys { (coordinate, _) ->
                    if (coordinate in transitiveCoordinates) "$coordinate (transitive)" else coordinate
                }
            if (findings.isNotEmpty()) VulnerabilityDetailDialog(project, findings).show()
        }

        /**
         * Bestimmt die Hintergrundfarbe für ein Tabellenfeld basierend auf dem Schweregrad der Sicherheitslücke.
         */
        private fun vulnerabilityColor(
            severity: VulnerabilitySeverity,
            defaultColor: java.awt.Color
        ): java.awt.Color = when (severity) {
            VulnerabilitySeverity.CRITICAL -> com.intellij.ui.JBColor(0xFFB3B3, 0x6E2C2C)
            VulnerabilitySeverity.HIGH -> com.intellij.ui.JBColor(0xFFD5A3, 0x714A21)
            VulnerabilitySeverity.MEDIUM -> com.intellij.ui.JBColor(0xFFF0A6, 0x665A21)
            VulnerabilitySeverity.LOW -> com.intellij.ui.JBColor(0xC7E3FF, 0x294E6B)
            VulnerabilitySeverity.UNKNOWN -> defaultColor
        }

        /**
         * Startet die Hintergrundaufgabe zur Prüfung auf verfügbare Versions-Updates.
         */
        private fun checkForUpdates(onFinished: () -> Unit) {
            ProgressManager.getInstance().run(object : Task.Backgroundable(
                project,
                MyMessageBundle.message("toolwindow.MyToolWindow.checkUpdates.progress"),
                true
            ) {
                override fun run(indicator: ProgressIndicator) {
                    val projects = MavenProjectsManager.getInstance(project).projects
                    projects.forEach { mavenProject ->
                        processProjectUpdates(mavenProject, indicator)
                    }

                    postProcessPropertyUpdates()

                    ApplicationManager.getApplication().invokeLater {
                        onFinished()
                    }
                }
            })
        }

        private fun processProjectUpdates(mavenProject: MavenProject, indicator: ProgressIndicator) {
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
                checkArtifactUpdate(groupId, artifactId, version, indicator)
            }
        }

        private fun collectFromPsi(mavenProject: MavenProject, allKeysWithVersions: MutableMap<String, String>) {
            val psiFile = ApplicationManager.getApplication().runReadAction<XmlFile?> {
                PsiManager.getInstance(project).findFile(mavenProject.file) as? XmlFile
            } ?: return

            ApplicationManager.getApplication().runReadAction {
                val rootTag = psiFile.document?.rootTag ?: return@runReadAction

                // dependencies
                collectTags(rootTag.findFirstSubTag("dependencies"), "dependency", allKeysWithVersions)

                // dependencyManagement
                val dmTag = rootTag.findFirstSubTag("dependencyManagement")
                collectTags(dmTag?.findFirstSubTag("dependencies"), "dependency", allKeysWithVersions)

                // build/plugins
                val buildTag = rootTag.findFirstSubTag("build")
                collectTags(buildTag?.findFirstSubTag("plugins"), "plugin", allKeysWithVersions)

                // build/pluginManagement
                val pmTag = buildTag?.findFirstSubTag("pluginManagement")
                collectTags(pmTag?.findFirstSubTag("plugins"), "plugin", allKeysWithVersions)
            }
        }

        private fun collectTags(parentTag: XmlTag?, tagName: String, allKeysWithVersions: MutableMap<String, String>) {
            parentTag?.findSubTags(tagName)?.forEach { tag ->
                val g = tag.findFirstSubTag("groupId")?.value?.text ?: ""
                val a = tag.findFirstSubTag("artifactId")?.value?.text ?: ""
                val v = tag.findFirstSubTag("version")?.value?.text ?: ""
                if (g.isNotEmpty() && a.isNotEmpty() && !allKeysWithVersions.containsKey("$g:$a")) {
                    allKeysWithVersions["$g:$a"] = v
                }
            }
        }

        /**
         * Berechnet Schnittmengen von verfügbaren Versionen, wenn mehrere Abhängigkeiten dieselbe Property nutzen.
         */
        private fun postProcessPropertyUpdates() {
            val propertyToDependencies: Map<String, List<String>> = dependencyToProperty
                .entries
                .groupBy({ it.value }, { it.key })

            propertyToDependencies.forEach { (_, depKeys) ->
                if (depKeys.size > 1) {
                    intersectVersions(depKeys)
                }
            }
        }

        private fun intersectVersions(depKeys: List<String>) {
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
                if (sortedCommonVersions.isNotEmpty() && MavenUpSettings.getInstance(project).state.selectLatestVersion) {
                    selectedVersions[depKey] = sortedCommonVersions.first()
                } else if (sortedCommonVersions.isNotEmpty() && !MavenUpSettings.getInstance(project).state.selectLatestVersion) {
                    selectedVersions[depKey] = knownDependencies[depKey] ?: ""
                }
            }
        }

        private fun checkArtifactUpdate(
            groupId: String?,
            artifactId: String?,
            currentVersion: String?,
            indicator: ProgressIndicator
        ) {
            indicator.text2 = "$groupId:$artifactId"

            if (groupId != null && artifactId != null) {
                val version = currentVersion ?: ""
                val versions = fetchVersions(groupId, artifactId, version)
                if (versions.isNotEmpty()) {
                    val key = "$groupId:$artifactId"
                    availableVersions[key] = versions
                    // Pre-select the newest version if it's different from the current one and setting is enabled
                    if (versions.first() != version && MavenUpSettings.getInstance(project).state.selectLatestVersion) {
                        selectedVersions[key] = versions.first()
                    } else if (!MavenUpSettings.getInstance(project).state.selectLatestVersion) {
                        selectedVersions[key] = version
                    }
                }
            }
        }

        /**
         * Aktualisiert die Versionsnummer in einem XML-Tag. Berücksichtigt dabei, ob die Version
         * direkt oder über eine Maven-Property definiert ist.
         */
        internal fun updateXmlTagVersion(tag: XmlTag, newVersion: String, propertiesTag: XmlTag?) {
            val versionTag = tag.findFirstSubTag("version")
            if (versionTag != null) {
                // Use versionTag.value.trimmedText for recognition
                val versionContent = versionTag.value.trimmedText

                if (versionContent.startsWith("\${") && versionContent.endsWith("}")) {
                    val propertyName = versionContent.substring(2, versionContent.length - 1)
                    val propertyTag = propertiesTag?.findFirstSubTag(propertyName)
                    if (propertyTag != null) {
                        propertyTag.value.text = newVersion
                        return // Property updated, don't overwrite version tag
                    }
                }
                // Fallback or direct update: overwrite version tag
                versionTag.value.text = newVersion
            } else {
                val newTag = tag.createChildTag("version", null, newVersion, false)
                tag.addSubTag(newTag, false)
            }
        }

        private fun fetchVersions(groupId: String, artifactId: String, currentVersion: String): List<String> {
            return dependencyApiService.fetchVersions(groupId, artifactId, currentVersion)
        }

        /**
         * Liefert die UI-Komponente des Tool Windows zurück.
         */
        fun getContent(): JBPanel<JBPanel<*>> = content

        private fun findTag(parentTag: XmlTag?, tagName: String, groupId: String, artifactId: String): XmlTag? {
            return parentTag?.findSubTags(tagName)?.find { tag ->
                val g = tag.findFirstSubTag("groupId")?.value?.text
                val a = tag.findFirstSubTag("artifactId")?.value?.text
                g == groupId && a == artifactId
            }
        }

        /**
         * Sucht nach einer Abhängigkeit in der `pom.xml`.
         * Unterstützt sowohl direkte Abhängigkeiten als auch Einträge im `dependencyManagement`.
         */
        internal fun findDependency(
            rootTag: XmlTag?,
            groupId: String,
            artifactId: String,
            isManaged: Boolean
        ): XmlTag? {
            if (!isManaged) {
                val dependenciesTag = rootTag?.findFirstSubTag("dependencies")
                val localDep = findTag(dependenciesTag, "dependency", groupId, artifactId)
                if (localDep != null) return localDep
            }

            val dmTag = rootTag?.findFirstSubTag("dependencyManagement")
            val dmDepsTag = dmTag?.findFirstSubTag("dependencies")
            return findTag(dmDepsTag, "dependency", groupId, artifactId)
        }

        private fun findPlugin(rootTag: XmlTag?, groupId: String, artifactId: String, isManaged: Boolean): XmlTag? {
            val buildTag = rootTag?.findFirstSubTag("build")
            if (!isManaged) {
                val pluginsTag = buildTag?.findFirstSubTag("plugins")
                val localPlugin = findTag(pluginsTag, "plugin", groupId, artifactId)
                if (localPlugin != null) return localPlugin
            }

            val pmTag = buildTag?.findFirstSubTag("pluginManagement")
            val pmPluginsTag = pmTag?.findFirstSubTag("plugins")
            return findTag(pmPluginsTag, "plugin", groupId, artifactId)
        }

        /**
         * Öffnet den Editor und springt zur Definition der angegebenen Abhängigkeit in der `pom.xml`.
         */
        private fun navigateToDependency(groupId: String, artifactId: String, type: String = "dependency") {
            ProgressManager.getInstance().run(object : Task.Backgroundable(
                project,
                MyMessageBundle.message("toolwindow.MyToolWindow.refresh.button"),
                false
            ) {
                override fun run(indicator: ProgressIndicator) {
                    for (mavenProject in MavenProjectsManager.getInstance(project).projects) {
                        val pomFile = mavenProject.file
                        val psiFile = ApplicationManager.getApplication().runReadAction<XmlFile?> {
                            PsiManager.getInstance(project).findFile(pomFile) as? XmlFile
                        } ?: continue

                        val targetTag = ApplicationManager.getApplication().runReadAction<XmlTag?> {
                            val rootTag = psiFile.document?.rootTag
                            val managedDepType =
                                MyMessageBundle.message(TOOLWINDOW_MY_TOOL_WINDOW_TYPE_MANAGED_DEPENDENCY)
                            val isManaged = type == managedDepType || type == MANAGED_PLUGIN

                            if (type == "dependency" || type == managedDepType) {
                                findDependency(rootTag, groupId, artifactId, isManaged)
                            } else {
                                findPlugin(rootTag, groupId, artifactId, isManaged)
                            }
                        }

                        if (targetTag != null) {
                            ApplicationManager.getApplication().invokeLater {
                                val descriptor = OpenFileDescriptor(project, pomFile, targetTag.textOffset)
                                FileEditorManager.getInstance(project).openTextEditor(descriptor, true)
                            }
                            return
                        }
                    }
                }
            })
        }

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
            val browser = MavenUpSettings.getInstance(project).state.repositoryBrowser
            BrowserUtil.browse(buildMavenRepositoryUrl(groupId, artifactId, version, browser))
        }
    }
}
