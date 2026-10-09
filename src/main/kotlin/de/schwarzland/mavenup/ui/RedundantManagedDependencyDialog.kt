package de.schwarzland.mavenup.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.JBColor
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.HTMLEditorKitBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import de.schwarzland.mavenup.model.RedundancyReason
import de.schwarzland.mavenup.model.RedundantManagedDependencyRecommendation
import org.apache.maven.artifact.versioning.ComparableVersion
import java.awt.Component
import java.awt.Graphics
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.ListSelectionModel
import javax.swing.RowSorter
import javax.swing.SortOrder
import javax.swing.border.AbstractBorder
import javax.swing.table.DefaultTableModel
import javax.swing.table.TableRowSorter

private const val COLUMN_SELECT = 0
private const val COLUMN_MANAGED_DEPENDENCY = 1
private const val COLUMN_CURRENT_VERSION = 2
private const val COLUMN_REASON = 3
private const val COLUMN_PROVIDED_VERSION = 4
private const val COLUMN_PROJECT = 5

/**
 * Dialog zur interaktiven Prüfung und Übernahme redundanter Einträge in `<dependencyManagement>`.
 *
 * @param project Das aktuelle IntelliJ-Projekt.
 * @property recommendations Liste der erkannten redundanten verwalteten Abhängigkeiten.
 * @property scopeDescription Erklärung des Suchumfangs.
 * @property onApply Optionaler Callback für ausgewählte Empfehlungen und Filterwechsel.
 */
class RedundantManagedDependencyDialog(
    project: Project,
    private val recommendations: List<RedundantManagedDependencyRecommendation>,
    private val scopeDescription: String = MyMessageBundle.message("redundant.managed.dependency.scope.global"),
    private val onApply: ((List<RedundantManagedDependencyRecommendation>, Boolean) -> Unit)? = null
) : DialogWrapper(project) {

    private val selectionStates = BooleanArray(recommendations.size) { true }
    private var showAllPendingChanges = false
    private lateinit var dialogPanel: DialogPanel
    private lateinit var tableModel: DefaultTableModel
    private lateinit var table: JBTable
    private val detailEditor = JEditorPane().apply {
        isEditable = false
        contentType = "text/html"
        editorKit = HTMLEditorKitBuilder().withWordWrapViewFactory().build()
        border = JBUI.Borders.empty(8)
    }

    init {
        title = MyMessageBundle.message("redundant.managed.dependency.dialog.title")
        isResizable = true
        setOKButtonText(MyMessageBundle.message("redundant.managed.dependency.dialog.apply"))
        init()
        updateDetailPanel(0)
        updateOkActionState()
    }

    /**
     * Liefert die vom Anwender ausgewählten redundanten Empfehlungen.
     */
    fun getSelectedRecommendations(): List<RedundantManagedDependencyRecommendation> =
        recommendations.filterIndexed { index, _ -> selectionStates.getOrElse(index) { false } }

    public override fun createCenterPanel(): JComponent {
        val recommendationsPanel = panel {
            row {
                cell(JBScrollPane(buildTable())).align(Align.FILL)
            }.resizableRow()
            row {
                button(MyMessageBundle.message("redundant.managed.dependency.dialog.selectAll")) {
                    setAllSelected(true)
                }
                button(MyMessageBundle.message("redundant.managed.dependency.dialog.deselectAll")) {
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
            setAndLoadSplitterProportionKey("MavenUp.RedundantManagedDependencyDialog.splitter")
        }

        return panel {
            row {
                label(MyMessageBundle.message("redundant.managed.dependency.dialog.explanation"))
            }
            row {
                comment(scopeDescription)
            }
            row {
                cell(splitter).align(Align.FILL)
            }.resizableRow()
            row {
                checkBox(MyMessageBundle.message("redundant.managed.dependency.dialog.showPending"))
                    .bindSelected(::showAllPendingChanges)
                    .comment(MyMessageBundle.message("redundant.managed.dependency.dialog.showPending.comment"))
            }
            row {
                comment(MyMessageBundle.message("managed.dependency.removal.coverage.limitations"))
            }
        }.also {
            dialogPanel = it
            it.preferredSize = JBUI.size(880, 560)
        }
    }

    private fun configureDivider(splitter: JBSplitter) {
        val divider = splitter.divider ?: return
        val border = RedundantDividerBorder()
        divider.border = border
        divider.addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent?) {
                border.hovered = true
                divider.repaint()
            }

            override fun mouseExited(e: MouseEvent?) {
                border.hovered = false
                divider.repaint()
            }
        })
    }

    private fun buildTable(): JBTable {
        val columnNames = arrayOf(
            MyMessageBundle.message("redundant.managed.dependency.dialog.table.header.select"),
            MyMessageBundle.message("redundant.managed.dependency.dialog.table.header.managedDependency"),
            MyMessageBundle.message("redundant.managed.dependency.dialog.table.header.currentVersion"),
            MyMessageBundle.message("redundant.managed.dependency.dialog.table.header.reason"),
            MyMessageBundle.message("redundant.managed.dependency.dialog.table.header.providedVersion"),
            MyMessageBundle.message("redundant.managed.dependency.dialog.table.header.project")
        )

        tableModel = object : DefaultTableModel(columnNames, 0) {
            override fun getColumnClass(columnIndex: Int): Class<*> =
                if (columnIndex == COLUMN_SELECT) Boolean::class.javaObjectType else String::class.java

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
            tableModel.addRow(
                arrayOf<Any>(
                    selectionStates[index],
                    "${rec.groupId}:${rec.artifactId}",
                    rec.currentVersion,
                    reasonLabel(rec.reason),
                    rec.providedVersion ?: "-",
                    rec.sourceProjectId
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

    private fun buildRowSorter(model: DefaultTableModel): TableRowSorter<DefaultTableModel> =
        TableRowSorter(model).apply {
            setSortable(COLUMN_SELECT, false)
            setComparator(COLUMN_SELECT) { o1, o2 ->
                (o1 as? Boolean ?: false).compareTo(o2 as? Boolean ?: false)
            }
            setComparator(COLUMN_CURRENT_VERSION) { o1, o2 ->
                ComparableVersion(o1?.toString().orEmpty()).compareTo(ComparableVersion(o2?.toString().orEmpty()))
            }
            setComparator(COLUMN_PROVIDED_VERSION) { o1, o2 ->
                ComparableVersion(o1?.toString().orEmpty()).compareTo(ComparableVersion(o2?.toString().orEmpty()))
            }
            for (col in listOf(COLUMN_MANAGED_DEPENDENCY, COLUMN_REASON, COLUMN_PROJECT)) {
                setComparator(col) { o1, o2 ->
                    StringUtil.naturalCompare(o1?.toString().orEmpty(), o2?.toString().orEmpty())
                }
            }
            sortKeys = listOf(RowSorter.SortKey(COLUMN_MANAGED_DEPENDENCY, SortOrder.ASCENDING))
        }

    private fun buildDetailPanel(): JComponent = panel {
        row {
            label(MyMessageBundle.message("redundant.managed.dependency.dialog.detail.title")).bold()
        }
        row {
            cell(JBScrollPane(detailEditor)).align(Align.FILL)
        }.resizableRow()
    }.apply {
        minimumSize = JBUI.size(0, 80)
    }

    private fun updateDetailPanel(modelRow: Int) {
        if (modelRow !in recommendations.indices) {
            detailEditor.text = ""
            return
        }

        val rec = recommendations[modelRow]
        val reasonTitle = StringUtil.escapeXmlEntities(
            MyMessageBundle.message("redundant.managed.dependency.dialog.detail.reason")
        )
        val reasonDetail = StringUtil.escapeXmlEntities(rec.reasonDetail)
        val sourcePom = rec.sourcePomPath.takeIf { it.isNotBlank() }?.let {
            "<p><b>${StringUtil.escapeXmlEntities(
                MyMessageBundle.message("redundant.managed.dependency.dialog.detail.sourcePom")
            )}</b> ${StringUtil.escapeXmlEntities(it)}</p>"
        }.orEmpty()

        val paths = if (rec.consumers.isEmpty()) {
            StringUtil.escapeXmlEntities(
                MyMessageBundle.message("redundant.managed.dependency.dialog.detail.consumers.empty")
            )
        } else {
            rec.consumers.joinToString("<br/>") { consumer ->
                StringUtil.escapeXmlEntities(
                    "${consumer.groupId}:${consumer.artifactId} -> ${consumer.pathDescription}"
                )
            }
        }
        val pathsTitle = StringUtil.escapeXmlEntities(
            MyMessageBundle.message("redundant.managed.dependency.dialog.detail.consumers.title")
        )

        detailEditor.text = "<html><body><p><b>$reasonTitle</b> $reasonDetail</p>" +
            "$sourcePom<p><b>$pathsTitle</b></p>$paths</body></html>"
        detailEditor.caretPosition = 0
    }

    internal fun setAllSelected(selected: Boolean) {
        for (i in selectionStates.indices) {
            selectionStates[i] = selected
            tableModel.setValueAt(selected, i, COLUMN_SELECT)
        }
        updateOkActionState()
    }

    private fun updateOkActionState() {
        isOKActionEnabled = selectionStates.any { it }
    }

    override fun doOKAction() {
        dialogPanel.apply()
        val selected = getSelectedRecommendations()
        onApply?.invoke(selected, showAllPendingChanges)
        super.doOKAction()
    }

    private fun reasonLabel(reason: RedundancyReason): String = when (reason) {
        RedundancyReason.PARENT_MANAGED -> MyMessageBundle.message("redundant.managed.dependency.reason.parentManaged")
        RedundancyReason.DIRECT_DEPENDENCY_MATCH -> MyMessageBundle.message("redundant.managed.dependency.reason.directMatch")
        RedundancyReason.TRANSITIVE_MATCH -> MyMessageBundle.message("redundant.managed.dependency.reason.transitiveMatch")
        RedundancyReason.UNUSED -> MyMessageBundle.message("redundant.managed.dependency.reason.unused")
    }

    private class RedundantDividerBorder : AbstractBorder() {
        var hovered = false

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
