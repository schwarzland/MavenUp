package de.schwarzland.mavenup.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import de.schwarzland.mavenup.model.ManagedDependencyRemovalRecommendation
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.BorderFactory
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.ListSelectionModel
import javax.swing.SortOrder
import javax.swing.table.DefaultTableModel
import javax.swing.table.TableRowSorter

/**
 * Spaltenindex für die Auswahl-Checkbox.
 */
private const val COLUMN_SELECT = 0

/**
 * Spaltenindex für die verwaltete Abhängigkeit (`groupId:artifactId`).
 */
private const val COLUMN_MANAGED_DEP = 1

/**
 * Spaltenindex für die aktuell gepinnte Version der verwalteten Abhängigkeit.
 */
private const val COLUMN_CURRENT_VERSION = 2

/**
 * Spaltenindex für die auslösende Komponente (Parent-POM oder Dependency).
 */
private const val COLUMN_TRIGGER = 3

/**
 * Spaltenindex für die Zielversion der auslösenden Komponente.
 */
private const val COLUMN_TARGET_VERSION = 4

/**
 * Spaltenindex für die durch die Zielversion transitiv bereitgestellte Version.
 */
private const val COLUMN_PROVIDED_VERSION = 5

/**
 * Dialog zur interaktiven Prüfung und Übernahme von Empfehlungen zur Bereinigung
 * redundanter Einträge in `<dependencyManagement>`.
 *
 * Zeigt die erkannten Empfehlungen in einer Tabelle mit Auswahl-Checkboxen sowie
 * detaillierte Erklärungen und Pfadangaben zur jeweils selektierten Zeile an.
 *
 * @param project Das aktuelle IntelliJ-Projekt.
 * @property recommendations Die Liste der zur Bereinigung vorgeschlagenen Empfehlungen.
 * @property onApply Optionaler Callback, der bei Bestätigung mit den ausgewählten Empfehlungen aufgerufen wird.
 */
class ManagedDependencyRemovalDialog(
    project: Project,
    private val recommendations: List<ManagedDependencyRemovalRecommendation>,
    private val onApply: ((List<ManagedDependencyRemovalRecommendation>) -> Unit)? = null
) : DialogWrapper(project) {

    private val selectionStates = BooleanArray(recommendations.size) { true }
    private lateinit var tableModel: DefaultTableModel
    private lateinit var table: JBTable
    private val explanationLabel = JBLabel().apply {
        isAllowAutoWrapping = true
    }
    private val consumersListModel = DefaultListModel<String>()
    private val consumersList = JBList(consumersListModel)

    init {
        title = MyMessageBundle.message("managed.dependency.removal.dialog.title")
        setOKButtonText(MyMessageBundle.message("managed.dependency.removal.dialog.apply"))
        init()
        updateDetailPanel(0)
    }

    /**
     * Liefert die Liste aller vom Anwender ausgewählten Empfehlungen.
     *
     * @return Liste der ausgewählten [ManagedDependencyRemovalRecommendation].
     */
    fun getSelectedRecommendations(): List<ManagedDependencyRemovalRecommendation> {
        val result = mutableListOf<ManagedDependencyRemovalRecommendation>()
        for (i in recommendations.indices) {
            if (selectionStates[i]) {
                result.add(recommendations[i])
            }
        }
        return result
    }

    /**
     * Erstellt den zentralen Bereich des Dialogs.
     *
     * @return Die Hauptkomponente des Dialogs.
     */
    override fun createCenterPanel(): JComponent {
        val rootPanel = JBPanel<JBPanel<*>>(BorderLayout(0, 10))
        rootPanel.preferredSize = Dimension(900, 520)

        val headerLabel = JLabel(MyMessageBundle.message("managed.dependency.removal.dialog.explanation"))
        rootPanel.add(headerLabel, BorderLayout.NORTH)

        val centerSplit = JBPanel<JBPanel<*>>(BorderLayout(0, 8))
        centerSplit.add(JBScrollPane(buildTable()), BorderLayout.CENTER)

        val buttonBar = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 5, 0))
        val selectAllBtn = JButton(MyMessageBundle.message("managed.dependency.removal.dialog.selectAll")).apply {
            addActionListener { setAllSelected(true) }
        }
        val deselectAllBtn = JButton(MyMessageBundle.message("managed.dependency.removal.dialog.deselectAll")).apply {
            addActionListener { setAllSelected(false) }
        }
        buttonBar.add(selectAllBtn)
        buttonBar.add(deselectAllBtn)
        centerSplit.add(buttonBar, BorderLayout.SOUTH)

        rootPanel.add(centerSplit, BorderLayout.CENTER)
        rootPanel.add(buildDetailPanel(), BorderLayout.SOUTH)

        return rootPanel
    }

    /**
     * Baut die Tabelle mit den Empfehlungen auf.
     *
     * @return Die konfigurierte [JBTable].
     */
    internal fun buildTable(): JBTable {
        val columnNames = arrayOf(
            MyMessageBundle.message("managed.dependency.removal.dialog.table.header.select"),
            MyMessageBundle.message("managed.dependency.removal.dialog.table.header.managedDependency"),
            MyMessageBundle.message("managed.dependency.removal.dialog.table.header.currentVersion"),
            MyMessageBundle.message("managed.dependency.removal.dialog.table.header.trigger"),
            MyMessageBundle.message("managed.dependency.removal.dialog.table.header.targetVersion"),
            MyMessageBundle.message("managed.dependency.removal.dialog.table.header.providedVersion")
        )

        tableModel = object : DefaultTableModel(columnNames, 0) {
            override fun getColumnClass(columnIndex: Int): Class<*> =
                if (columnIndex == COLUMN_SELECT) java.lang.Boolean::class.javaObjectType else String::class.java

            override fun isCellEditable(row: Int, column: Int): Boolean = column == COLUMN_SELECT

            override fun setValueAt(aValue: Any?, row: Int, column: Int) {
                if (column == COLUMN_SELECT && aValue is Boolean) {
                    selectionStates[row] = aValue
                    super.setValueAt(aValue, row, column)
                    updateOkActionState()
                } else {
                    super.setValueAt(aValue, row, column)
                }
            }
        }

        recommendations.forEachIndexed { index, rec ->
            val triggerLabel = "${rec.triggerGroupId}:${rec.triggerArtifactId} (${rec.triggerType})"
            tableModel.addRow(
                arrayOf<Any>(
                    selectionStates[index],
                    "${rec.managedGroupId}:${rec.managedArtifactId}",
                    rec.managedCurrentVersion,
                    triggerLabel,
                    rec.triggerTargetVersion,
                    rec.transitiveVersionInTarget
                )
            )
        }

        table = JBTable(tableModel).apply {
            autoResizeMode = JBTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS
            setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
            tableHeader.reorderingAllowed = false
            rowSorter = buildRowSorter(tableModel)
            installSortableHeaderRenderer(this)
            trimColumnWidthsToContent(this)
            applyRecommendedRowHeight(this)

            selectionModel.addListSelectionListener {
                val selectedRow = selectedRow
                if (selectedRow >= 0) {
                    val modelRow = convertRowIndexToModel(selectedRow)
                    updateDetailPanel(modelRow)
                }
            }
        }

        if (recommendations.isNotEmpty()) {
            table.setRowSelectionInterval(0, 0)
        }

        return table
    }

    /**
     * Erstellt das untere Detail-Panel für Erklärungen und Konsumenten-Pfade.
     *
     * @return Das Detail-[JBPanel].
     */
    private fun buildDetailPanel(): JBPanel<*> {
        val detailPanel = JBPanel<JBPanel<*>>(BorderLayout(0, 6))
        detailPanel.border = BorderFactory.createTitledBorder(
            BorderFactory.createEtchedBorder(),
            MyMessageBundle.message("managed.dependency.removal.dialog.detail.title")
        )
        detailPanel.preferredSize = Dimension(900, 160)

        detailPanel.add(explanationLabel, BorderLayout.NORTH)

        val consumersPanel = JBPanel<JBPanel<*>>(BorderLayout(0, 4))
        consumersPanel.add(
            JLabel(MyMessageBundle.message("managed.dependency.removal.dialog.detail.consumers.title")),
            BorderLayout.NORTH
        )
        consumersPanel.add(JBScrollPane(consumersList), BorderLayout.CENTER)

        detailPanel.add(consumersPanel, BorderLayout.CENTER)
        return detailPanel
    }

    /**
     * Aktualisiert das Detail-Panel anhand der ausgewählten Modellzeile.
     *
     * @param modelRow Der Zeilenindex im Datenmodell.
     */
    internal fun updateDetailPanel(modelRow: Int) {
        if (modelRow !in recommendations.indices) return
        val rec = recommendations[modelRow]

        val explanation = if (rec.triggerType == "parent") {
            MyMessageBundle.message(
                "managed.dependency.removal.dialog.detail.explanation.parent",
                rec.managedGroupId,
                rec.managedArtifactId,
                rec.managedCurrentVersion,
                rec.triggerGroupId,
                rec.triggerArtifactId,
                rec.triggerTargetVersion,
                rec.transitiveVersionInTarget
            )
        } else {
            MyMessageBundle.message(
                "managed.dependency.removal.dialog.detail.explanation.dependency",
                rec.managedGroupId,
                rec.managedArtifactId,
                rec.managedCurrentVersion,
                rec.triggerGroupId,
                rec.triggerArtifactId,
                rec.triggerTargetVersion,
                rec.transitiveVersionInTarget
            )
        }

        explanationLabel.text = "<html>$explanation</html>"

        consumersListModel.clear()
        if (rec.consumers.isEmpty()) {
            consumersListModel.addElement("No consumer paths recorded.")
        } else {
            rec.consumers.forEach { consumer ->
                consumersListModel.addElement("${consumer.groupId}:${consumer.artifactId} -> ${consumer.pathDescription}")
            }
        }
    }

    /**
     * Setzt alle Auswahlen auf den angegebenen Wert.
     *
     * @param selected `true` zum Auswählen aller, `false` zum Abwählen aller.
     */
    internal fun setAllSelected(selected: Boolean) {
        for (i in selectionStates.indices) {
            selectionStates[i] = selected
            tableModel.setValueAt(selected, i, COLUMN_SELECT)
        }
        updateOkActionState()
    }

    /**
     * Aktualisiert die Aktivierung des OK-Buttons.
     */
    private fun updateOkActionState() {
        isOKActionEnabled = selectionStates.any { it }
    }

    /**
     * Erstellt den [TableRowSorter] für die Empfehlungstabelle.
     *
     * @param model Das Tabellenmodell.
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
            val sortable = columnIndex > COLUMN_SELECT
            sorter.setSortable(columnIndex, sortable)
            if (sortable) {
                sorter.setComparator(columnIndex, cellTextComparator)
            }
        }
        return sorter
    }

    /**
     * Führt die Bestätigungsaktion aus und übergibt die ausgewählten Empfehlungen an den Callback.
     */
    public override fun doOKAction() {
        onApply?.invoke(getSelectedRecommendations())
        super.doOKAction()
    }
}
