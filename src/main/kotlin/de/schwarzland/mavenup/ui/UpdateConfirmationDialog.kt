package de.schwarzland.mavenup.ui

import de.schwarzland.mavenup.model.DependencyUpdate
import de.schwarzland.mavenup.service.MavenUpSettings
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import java.awt.BorderLayout
import javax.swing.BorderFactory
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.ListSelectionModel
import javax.swing.SortOrder
import javax.swing.table.DefaultTableModel
import javax.swing.table.TableRowSorter

/**
 * Dialog zur Bestätigung der ausgewählten Updates. Zeigt eine Tabelle mit den
 * durchzuführenden Änderungen (Gruppe, Artefakt, Version alt/neu).
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

    init {
        title = MyMessageBundle.message("toolwindow.MyToolWindow.update.confirm.title")
        init()
    }

    /**
     * Gibt zurück, ob der Benutzer die Option "Sync Maven Changes" ausgewählt hat.
     */
    fun isSyncMavenSelected(): Boolean = syncMavenCheckbox.isSelected

    /**
     * Erstellt den zentralen Bereich des Dialogs mit der Update-Übersichtstabelle und der Sync-Option.
     */
    override fun createCenterPanel(): JComponent {
        val panel = JBPanel<JBPanel<*>>(BorderLayout())
        panel.preferredSize = java.awt.Dimension(900, 500)

        val topPanel = JBPanel<JBPanel<*>>(BorderLayout())
        topPanel.add(
            JLabel(MyMessageBundle.message("toolwindow.MyToolWindow.update.confirm.message")),
            BorderLayout.NORTH
        )
        topPanel.border = BorderFactory.createEmptyBorder(0, 0, 10, 0)
        panel.add(topPanel, BorderLayout.NORTH)

        panel.add(JBScrollPane(buildTable()), BorderLayout.CENTER)

        val bottomPanel = JBPanel<JBPanel<*>>(BorderLayout())
        bottomPanel.border = BorderFactory.createEmptyBorder(10, 0, 0, 0)
        bottomPanel.add(syncMavenCheckbox, BorderLayout.WEST)
        panel.add(bottomPanel, BorderLayout.SOUTH)

        return panel
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
                    update.type,
                    update.oldVersion,
                    update.newVersion
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
        }
        return table
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
