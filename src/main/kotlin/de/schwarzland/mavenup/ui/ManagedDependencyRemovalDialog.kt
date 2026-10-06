package de.schwarzland.mavenup.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.JBSplitter
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.HTMLEditorKitBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import de.schwarzland.mavenup.model.ManagedDependencyRemovalRecommendation
import java.awt.Component
import java.awt.Graphics
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.ListSelectionModel
import javax.swing.SortOrder
import javax.swing.border.AbstractBorder
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
 * Ein vertikaler Splitter speichert die vom Anwender gewählte Aufteilung zwischen
 * Tabelle und vollständig scrollbar dargestellten Details.
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
    private val detailEditor = JEditorPane().apply {
        isEditable = false
        contentType = "text/html"
        editorKit = HTMLEditorKitBuilder().withWordWrapViewFactory().build()
        border = JBUI.Borders.empty(8)
    }

    init {
        title = MyMessageBundle.message("managed.dependency.removal.dialog.title")
        isResizable = true
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
     * Erstellt den zentralen Bereich mit UI DSL v2 und einem vertikalen Splitter.
     * Die Aufteilung startet bei 65 Prozent Tabellenhöhe und wird IDE-weit gespeichert.
     *
     * @return Die Hauptkomponente des Dialogs.
     */
    public override fun createCenterPanel(): JComponent {
        val recommendationsPanel = panel {
            row {
                cell(JBScrollPane(buildTable())).align(Align.FILL)
            }.resizableRow()
            row {
                button(MyMessageBundle.message("managed.dependency.removal.dialog.selectAll")) {
                    setAllSelected(true)
                }
                button(MyMessageBundle.message("managed.dependency.removal.dialog.deselectAll")) {
                    setAllSelected(false)
                }
            }
        }.apply {
            minimumSize = JBUI.size(0, 120)
        }
        val splitter = JBSplitter(true, 0.65f, 0.15f, 0.85f).apply {
            firstComponent = recommendationsPanel
            secondComponent = buildDetailPanel()
            configureDivider(this)
            setAndLoadSplitterProportionKey("MavenUp.ManagedDependencyRemovalDialog.splitter")
        }
        return panel {
            row {
                text(StringUtil.escapeXmlEntities(
                    MyMessageBundle.message("managed.dependency.removal.dialog.explanation")
                )).align(Align.FILL)
            }
            row {
                cell(splitter).align(Align.FILL)
            }.resizableRow()
        }.apply {
            preferredSize = JBUI.size(900, 520)
            minimumSize = JBUI.size(650, 350)
        }
    }

    /**
     * Markiert die native Ziehfläche mit einer themeabhängigen Linie und einem mittigen Griff.
     * Die Mausbehandlung des Splitters bleibt erhalten; Hover hebt ausschließlich den Griff hervor.
     *
     * @param splitter Der vertikal ausgerichtete Master-Detail-Splitter.
     */
    private fun configureDivider(splitter: JBSplitter) {
        val gripBorder = CleanupDividerBorder()
        splitter.dividerWidth = JBUI.scale(10)
        splitter.divider.apply {
            border = gripBorder
            accessibleContext.accessibleName =
                MyMessageBundle.message("managed.dependency.removal.dialog.divider")
            addMouseListener(object : MouseAdapter() {
                /** Hebt den Griff beim Betreten der gesamten Ziehfläche hervor. */
                override fun mouseEntered(event: MouseEvent) {
                    gripBorder.hovered = true
                    repaint()
                }

                /** Stellt beim Verlassen die normale Griffdarstellung wieder her. */
                override fun mouseExited(event: MouseEvent) {
                    gripBorder.hovered = false
                    repaint()
                }
            })
        }
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
            /** Liefert den Datentyp für Checkboxen beziehungsweise Textspalten. */
            override fun getColumnClass(columnIndex: Int): Class<*> =
                if (columnIndex == COLUMN_SELECT) java.lang.Boolean::class.javaObjectType else String::class.java

            /** Erlaubt ausschließlich Änderungen an der Auswahlspalte. */
            override fun isCellEditable(row: Int, column: Int): Boolean = column == COLUMN_SELECT

            /** Synchronisiert die Checkbox-Auswahl mit dem Dialogzustand. */
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
     * Erstellt den Detailbereich mit schlichter Überschrift und einem gemeinsamen
     * Scrollbereich für umbrechende Erklärungen und Konsumenten-Pfade.
     *
     * @return Der Detailbereich ohne feste Höhe.
     */
    private fun buildDetailPanel(): JComponent = panel {
        row {
            label(MyMessageBundle.message("managed.dependency.removal.dialog.detail.title")).bold()
        }
        row {
            cell(JBScrollPane(detailEditor).apply {
                horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
                minimumSize = JBUI.size(0, 60)
            }).align(Align.FILL)
        }.resizableRow()
    }.apply {
        minimumSize = JBUI.size(0, 100)
    }

    /**
     * Aktualisiert die HTML-Details anhand der ausgewählten Modellzeile.
     * Maskiert alle Inhalte und setzt die Scrollposition beim Wechsel an den Anfang.
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

        val paths = if (rec.consumers.isEmpty()) {
            StringUtil.escapeXmlEntities(
                MyMessageBundle.message("managed.dependency.removal.dialog.detail.consumers.empty")
            )
        } else {
            rec.consumers.joinToString("<br/>") { consumer ->
                StringUtil.escapeXmlEntities(
                    "${consumer.groupId}:${consumer.artifactId} -> ${consumer.pathDescription}"
                )
            }
        }
        val pathsTitle = StringUtil.escapeXmlEntities(
            MyMessageBundle.message("managed.dependency.removal.dialog.detail.consumers.title")
        )
        detailEditor.text = "<html><body>${StringUtil.escapeXmlEntities(explanation)}" +
            "<p><b>$pathsTitle</b></p>$paths</body></html>"
        detailEditor.caretPosition = 0
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
            /** Wechselt zyklisch zwischen aufsteigender, absteigender und ursprünglicher Reihenfolge. */
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

    /**
     * Zeichnet eine dauerhaft sichtbare Trennlinie mit drei mittigen Griffpunkten direkt
     * auf dem nativen Splitter-Divider, ohne zusätzliche Mausereignisse abzufangen.
     */
    private class CleanupDividerBorder : AbstractBorder() {
        var hovered = false

        /** Zeichnet Linie und Griff DPI-skaliert mit Farben des jeweils aktiven IDE-Themes. */
        override fun paintBorder(component: Component, graphics: Graphics, x: Int, y: Int, width: Int, height: Int) {
            val painter = graphics.create()
            try {
                val centerX = x + width / 2
                val centerY = y + height / 2
                val gripGap = JBUI.scale(14)
                painter.color = JBColor.namedColor("Separator.separatorColor", UIUtil.getBoundsColor())
                painter.drawLine(x, centerY, centerX - gripGap, centerY)
                painter.drawLine(centerX + gripGap, centerY, x + width - 1, centerY)
                painter.color = if (hovered) {
                    JBColor.namedColor("Component.focusColor", UIUtil.getLabelForeground())
                } else {
                    UIUtil.getLabelForeground()
                }
                val dotSize = JBUI.scale(2)
                val spacing = JBUI.scale(5)
                for (offset in -1..1) {
                    painter.fillRect(centerX + offset * spacing - dotSize / 2, centerY - dotSize / 2, dotSize, dotSize)
                }
            } finally {
                painter.dispose()
            }
        }
    }
}
