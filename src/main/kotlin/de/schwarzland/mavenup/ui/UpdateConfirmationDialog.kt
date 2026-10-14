package de.schwarzland.mavenup.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.table.JBTable
import de.schwarzland.mavenup.model.DependencyUpdate
import de.schwarzland.mavenup.model.TargetPomMode
import de.schwarzland.mavenup.service.MavenUpSettings
import de.schwarzland.mavenup.service.PomUpdateService
import org.jetbrains.idea.maven.project.MavenProjectsManager
import java.awt.Dimension
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.ListSelectionModel
import javax.swing.SortOrder
import javax.swing.table.DefaultTableModel
import javax.swing.table.TableRowSorter

/**
 * Dialog zur Bestätigung der ausgewählten Updates. Zeigt eine Tabelle mit den
 * durchzuführenden Änderungen (Gruppe, Artefakt, Modul, Typ und Version alt/neu) sowie Optionen zur
 * Maven-Synchronisation und zur Ziel-POM-Auswahl für transitive Pins ohne bereits festgelegte POM-Datei.
 * Die Auswahl bleibt sichtbar und ist nur bei transitiven Updates in Multi-Modul-Projekten aktiviert.
 *
 * @param project Das aktuelle Projekt, an das der Dialog gebunden wird.
 * @property updates Die Liste der zu bestätigenden Abhängigkeits-Updates.
 */
class UpdateConfirmationDialog(
    project: Project,
    private val updates: List<DependencyUpdate>
) : DialogWrapper(project) {
    private val mavenProjectsManager = MavenProjectsManager.getInstance(project)
    private val pomUpdateService = PomUpdateService(project)

    private val syncMavenCheckbox: JCheckBox = JCheckBox(
        MyMessageBundle.message("toolwindow.MyToolWindow.update.confirm.syncMaven")
    ).apply {
        isSelected = MavenUpSettings.getInstance().state.syncMavenAfterUpdate
    }

    private val isMultiModule = mavenProjectsManager.projects.size > 1
    private val hasTransitiveUpdates = updates.any { it.transitive }
    private val targetPomAffectedCount = updates.count { it.transitive && it.pomFile == null }
    private var confirmationTable: JBTable? = null

    internal val targetPomComboBox: ComboBox<TargetPomMode> = ComboBox(TargetPomMode.entries.toTypedArray()).apply {
        selectedItem = TargetPomMode.ROOT_POM
        isEnabled = hasTransitiveUpdates && isMultiModule
        renderer = SimpleListCellRenderer.create<TargetPomMode>("") { mode ->
            when (mode) {
                TargetPomMode.ROOT_POM -> MyMessageBundle.message("toolwindow.MyToolWindow.update.confirm.targetPom.root")
                TargetPomMode.AFFECTED_MODULES -> MyMessageBundle.message("toolwindow.MyToolWindow.update.confirm.targetPom.affected")
                TargetPomMode.ALL_MODULES -> MyMessageBundle.message("toolwindow.MyToolWindow.update.confirm.targetPom.all")
                null -> ""
            }
        }
        addActionListener { updateTargetModuleColumn() }
    }

    init {
        title = MyMessageBundle.message("toolwindow.MyToolWindow.update.confirm.title")
        init()
    }

    /**
     * Gibt zurück, ob der Benutzer die Option "Sync Maven Changes" ausgewählt hat.
     */
    fun isSyncMavenSelected(): Boolean = syncMavenCheckbox.isSelected

    /**
     * Liefert den ausgewählten Ziel-POM-Modus für transitive Pins (Standard: [TargetPomMode.ROOT_POM]).
     */
    fun getSelectedTargetPomMode(): TargetPomMode =
        targetPomComboBox.selectedItem as? TargetPomMode ?: TargetPomMode.ROOT_POM

    /**
     * Setzt den ausgewählten Ziel-POM-Modus für transitive Pins.
     *
     * @param mode Der zu wählende Ziel-POM-Modus.
     */
    fun setSelectedTargetPomMode(mode: TargetPomMode) {
        targetPomComboBox.selectedItem = mode
    }

    /**
     * Erstellt den zentralen Dialogbereich.
     */
    override fun createCenterPanel(): JComponent = buildCenterPanel()

    /**
     * Erstellt den zentralen Bereich mit Übersichtstabelle, stets sichtbarer (gegebenenfalls deaktivierter)
     * Ziel-POM-Auswahl samt Anzahl betroffener transitiver Pins und Sync-Option unter Verwendung der Kotlin UI DSL v2.
     *
     * Die bevorzugte Größe startet großzügig, die Mindestgröße bleibt bewusst kleiner, damit sich der
     * Dialog nach Bedarf auch schmaler ziehen lässt.
     *
     * @return Der konfigurierte zentrale Dialogbereich.
     */
    internal fun buildCenterPanel(): JComponent {
        val overviewScrollPane = JBScrollPane(buildTable()).apply {
            minimumSize = CONFIRM_DIALOG_MIN_SIZE
        }

        return panel {
            row {
                label(MyMessageBundle.message("toolwindow.MyToolWindow.update.confirm.message"))
            }
            row {
                cell(overviewScrollPane).align(Align.FILL).resizableColumn()
            }.resizableRow()
            row(MyMessageBundle.message("toolwindow.MyToolWindow.update.confirm.targetPom.label")) {
                cell(targetPomComboBox)
            }
            row {
                comment(
                    MyMessageBundle.message(
                        "toolwindow.MyToolWindow.update.confirm.targetPom.affectedCount",
                        targetPomAffectedCount
                    )
                )
            }
            row {
                cell(syncMavenCheckbox)
            }
        }.also {
            it.preferredSize = CONFIRM_DIALOG_PREFERRED_SIZE
            it.minimumSize = CONFIRM_DIALOG_MIN_SIZE
        }
    }

    /**
     * Erstellt die schreibgeschützte Update-Übersichtstabelle mit Einzelselektion.
     * Die Spalten "Group Id", "Artifact Id", "Module" und "Type" sind wie in den übrigen Plugin-Tabellen
     * sortierbar. Die Modul-Spalte steht immer an dritter Stelle; die Versionsspalten bleiben unsortiert.
     *
     * @return Die konfigurierte, nicht editierbare Tabelle mit allen anstehenden Updates.
     */
    internal fun buildTable(): JBTable {
        val tableModel = object : DefaultTableModel() {
            override fun isCellEditable(row: Int, column: Int): Boolean = false
        }.apply {
            addColumn(MyMessageBundle.message("toolwindow.MyToolWindow.table.header.groupId"))
            addColumn(MyMessageBundle.message("toolwindow.MyToolWindow.table.header.artifactId"))
            addColumn(MyMessageBundle.message("toolwindow.MyToolWindow.table.header.module"))
            addColumn(MyMessageBundle.message("toolwindow.MyToolWindow.table.header.type"))
            addColumn(MyMessageBundle.message("toolwindow.MyToolWindow.table.header.currentVersion"))
            addColumn(MyMessageBundle.message("toolwindow.MyToolWindow.table.header.newVersion"))
        }

        updates.forEach { update ->
            tableModel.addRow(
                arrayOf(
                    update.groupId,
                    update.artifactId,
                    targetModuleLabel(update, getSelectedTargetPomMode()),
                    typeLabel(update),
                    update.oldVersion,
                    if (update.removeFromPom) {
                        if (MavenUpSettings.getInstance().state.commentOutManagedEntriesOnRemoval) {
                            MyMessageBundle.message("toolwindow.MyToolWindow.version.willCommentOut")
                        } else {
                            MyMessageBundle.message("toolwindow.MyToolWindow.version.willRemove")
                        }
                    } else {
                        update.newVersion
                    }
                )
            )
        }

        val table = JBTable(tableModel).apply {
            autoResizeMode = JBTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS
            setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
            tableHeader.reorderingAllowed = false
            rowSorter = buildRowSorter(tableModel)
            installSortableHeaderRenderer(this)
            trimColumnWidthsToContent(this)
            applyRecommendedRowHeight(this)
        }
        confirmationTable = table
        return table
    }

    /**
     * Ermittelt den in der Spalte "Type" anzuzeigenden Text eines Updates.
     *
     * Updates aus bislang ausschließlich transitiv aufgelösten Abhängigkeiten werden als
     * `transitive -> <Zieltyp>` gekennzeichnet, damit erkennbar ist, dass die Abhängigkeit durch das
     * Update erstmals in der `pom.xml` (im `dependencyManagement`) gepinnt wird.
     *
     * @param update Das anzuzeigende Update.
     * @return Der Anzeigetext für die Typ-Spalte.
     */
    internal fun typeLabel(update: DependencyUpdate): String =
        if (update.transitive) {
            MyMessageBundle.message("toolwindow.MyToolWindow.update.confirm.type.transitive", update.type)
        } else {
            update.type
        }

    /**
     * Liefert den Namen des Zielmoduls für die Bestätigungstabelle.
     *
     * Entspricht für bekannte Maven-Module der Logik der Haupttabelle
     * (`displayName`, alternativ Maven-`artifactId`). Ist eine POM-Datei vorhanden, aber keinem
     * Maven-Projekt zugeordnet, wird ihr Pfad anstelle der Artefakt-ID angezeigt.
     *
     * @param update Das anzuzeigende Update.
     * @return Anzeigename des Zielmoduls oder als Fallback POM-Pfad beziehungsweise Artefakt-ID.
     */
    internal fun targetModuleLabel(update: DependencyUpdate): String {
        return targetModuleLabel(update.pomFile, update.artifactId)
    }

    /**
     * Liefert die Modulnamen der POM-Dateien, auf die ein Update gemäß dem gewählten Modus angewendet wird.
     *
     * Für transitive Pins wird dieselbe Auflösung wie beim anschließenden Update verwendet. Im Modus
     * „All modules“ werden alle Maven-Projekte angezeigt; bekannte Projektbezeichnungen werden genutzt,
     * sonst der POM-Pfad. Ohne auflösbare POM bleibt die Artefakt-ID der Rückfallwert.
     *
     * @param update Das anzuzeigende Update.
     * @param mode Der ausgewählte Ziel-POM-Modus.
     * @return Anzeigename oder kommagetrennte Liste der Zielmodule.
     */
    private fun targetModuleLabel(update: DependencyUpdate, mode: TargetPomMode): String {
        if (!update.transitive || update.pomFile != null) return targetModuleLabel(update)

        val targetPomFiles = when (mode) {
            TargetPomMode.ALL_MODULES -> mavenProjectsManager.projects.map { it.file }
            TargetPomMode.ROOT_POM, TargetPomMode.AFFECTED_MODULES ->
                pomUpdateService.resolveUpdatesForTargetPomMode(listOf(update), mode)
                    .mapNotNull { it.pomFile }
        }
        val labels = targetPomFiles.distinctBy { it.path }
            .map { targetModuleLabel(it, update.artifactId) }
        return labels.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: update.artifactId
    }

    /**
     * Ermittelt den Namen eines Maven-Moduls anhand seiner POM-Datei.
     *
     * @param pomFile Die POM-Datei des Moduls oder `null`, wenn kein Ziel zugeordnet wurde.
     * @param fallback Der Rückgabewert, falls keine POM-Datei angegeben ist.
     * @return Anzeigename, Maven-Artifact-ID oder POM-Pfad als Rückfallwert.
     */
    private fun targetModuleLabel(pomFile: VirtualFile?, fallback: String): String {
        val pomPath = pomFile?.path ?: return fallback
        val mavenProject = mavenProjectsManager.projects.firstOrNull { it.file.path == pomPath }
        return mavenProject?.displayName?.takeIf { it.isNotBlank() }
            ?: mavenProject?.mavenId?.artifactId?.takeIf { it.isNotBlank() }
            ?: pomPath
    }

    /**
     * Aktualisiert die Modulspalte entsprechend dem aktuell ausgewählten Ziel-POM-Modus.
     */
    private fun updateTargetModuleColumn() {
        val model = confirmationTable?.model as? DefaultTableModel ?: return
        updates.forEachIndexed { rowIndex, update ->
            model.setValueAt(targetModuleLabel(update, getSelectedTargetPomMode()), rowIndex, MODULE_COLUMN)
        }
    }

    /**
     * Erstellt den [TableRowSorter] für die Bestätigungstabelle. Die Textspalten "Group Id",
     * "Artifact Id", "Module" und "Type" werden alphabetisch ohne Beachtung der Groß-/Kleinschreibung
     * sortiert und durchlaufen bei Klick den Zyklus aufsteigend → absteigend → unsortiert.
     * Die Versionsspalten sind – wie im Hauptfenster – nicht sortierbar.
     *
     * @param model Das Tabellenmodell, auf dem sortiert wird.
     * @return Der konfigurierte Sorter.
     */
    internal fun buildRowSorter(model: DefaultTableModel): TableRowSorter<DefaultTableModel> {
        val sorter = object : TableRowSorter<DefaultTableModel>(model) {
            override fun toggleSortOrder(column: Int) {
                if (!isSortable(column)) return
                val current = sortKeys.firstOrNull { it.column == column }?.sortOrder
                val next = when (current) {
                    SortOrder.ASCENDING -> SortOrder.DESCENDING
                    SortOrder.DESCENDING -> SortOrder.UNSORTED
                    else -> SortOrder.ASCENDING
                }
                sortKeys = if (next == SortOrder.UNSORTED) emptyList() else listOf(SortKey(column, next))
            }
        }
        for (columnIndex in 0 until model.columnCount) {
            val sortable = columnIndex < CONFIRM_CURRENT_VERSION_COLUMN
            sorter.setSortable(columnIndex, sortable)
            if (sortable) {
                sorter.setComparator(columnIndex, cellTextComparator)
            }
        }
        return sorter
    }

    companion object {
        /** Bevorzugte Startgröße des Dialogs für eine vollständige Erstansicht. */
        private val CONFIRM_DIALOG_PREFERRED_SIZE = Dimension(900, 500)

        /** Bewusst kleinere Mindestgröße, damit der Dialog nach dem Öffnen verkleinert werden kann. */
        internal val CONFIRM_DIALOG_MIN_SIZE = Dimension(520, 320)

        /** Index der Modulspalte in der Übersichtstabelle. */
        private const val MODULE_COLUMN = 2

        /** Spaltenindex der Spalte mit der aktuellen Version; ab hier wird nicht mehr sortiert. */
        internal const val CONFIRM_CURRENT_VERSION_COLUMN = 4
    }
}
