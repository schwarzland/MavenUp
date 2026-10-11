package de.schwarzland.mavenup.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.table.JBTable
import de.schwarzland.mavenup.model.DependencyUpdate
import de.schwarzland.mavenup.model.TargetPomMode
import de.schwarzland.mavenup.service.MavenUpSettings
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
 * durchzuführenden Änderungen (Gruppe, Artefakt, Version alt/neu) sowie Optionen zur
 * Maven-Synchronisation und zur Ziel-POM-Auswahl für transitive Pins.
 *
 * @param project Das aktuelle Projekt, an das der Dialog gebunden wird.
 * @property updates Die Liste der zu bestätigenden Abhängigkeits-Updates.
 */
class UpdateConfirmationDialog(
    project: Project,
    private val updates: List<DependencyUpdate>
) : DialogWrapper(project) {
    private val syncMavenCheckbox: JCheckBox = JCheckBox(
        MyMessageBundle.message("toolwindow.MyToolWindow.update.confirm.syncMaven")
    ).apply {
        isSelected = MavenUpSettings.getInstance().state.syncMavenAfterUpdate
    }

    private val isMultiModule = MavenProjectsManager.getInstance(project).projects.size > 1
    private val hasTransitiveUpdates = updates.any { it.transitive }

    internal val targetPomComboBox: ComboBox<TargetPomMode> = ComboBox(TargetPomMode.values()).apply {
        selectedItem = TargetPomMode.ROOT_POM
        renderer = SimpleListCellRenderer.create("") { mode ->
            when (mode) {
                TargetPomMode.ROOT_POM -> MyMessageBundle.message("toolwindow.MyToolWindow.update.confirm.targetPom.root")
                TargetPomMode.AFFECTED_MODULES -> MyMessageBundle.message("toolwindow.MyToolWindow.update.confirm.targetPom.affected")
                TargetPomMode.ALL_MODULES -> MyMessageBundle.message("toolwindow.MyToolWindow.update.confirm.targetPom.all")
                null -> ""
            }
        }
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
     * Erstellt den zentralen Bereich des Dialogs mit der Update-Übersichtstabelle, der Ziel-POM-Auswahl
     * und der Sync-Option unter Verwendung der Kotlin UI DSL v2.
     */
    override fun createCenterPanel(): JComponent {
        return panel {
            row {
                label(MyMessageBundle.message("toolwindow.MyToolWindow.update.confirm.message"))
            }
            row {
                cell(JBScrollPane(buildTable())).align(Align.FILL).resizableColumn()
            }.resizableRow()
            if (hasTransitiveUpdates && isMultiModule) {
                row(MyMessageBundle.message("toolwindow.MyToolWindow.update.confirm.targetPom.label")) {
                    cell(targetPomComboBox)
                }
            }
            row {
                cell(syncMavenCheckbox)
            }
        }.also {
            it.preferredSize = Dimension(900, 500)
        }
    }

    /**
     * Erstellt die schreibgeschützte Update-Übersichtstabelle mit Einzelselektion.
     * Die Spalten "Group Id", "Artifact Id" und "Type" sind wie in den übrigen Plugin-Tabellen
     * sortierbar; die beiden Versionsspalten bleiben in der Reihenfolge der Auswahl.
     *
     * @return Die konfigurierte, nicht editierbare Tabelle mit allen anstehenden Updates.
     */
    internal fun buildTable(): JBTable {
        val tableModel = object : DefaultTableModel() {
            override fun isCellEditable(row: Int, column: Int): Boolean = false
        }.apply {
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
     * Erstellt den [TableRowSorter] für die Bestätigungstabelle. Die Textspalten "Group Id",
     * "Artifact Id" und "Type" werden alphabetisch ohne Beachtung der Groß-/Kleinschreibung
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
        /** Spaltenindex der Spalte mit der aktuellen Version; ab hier wird nicht mehr sortiert. */
        internal const val CONFIRM_CURRENT_VERSION_COLUMN = 3
    }
}
