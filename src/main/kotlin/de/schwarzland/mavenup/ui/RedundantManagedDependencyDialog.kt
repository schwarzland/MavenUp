package de.schwarzland.mavenup.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.MAX_LINE_LENGTH_WORD_WRAP
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.HTMLEditorKitBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import de.schwarzland.mavenup.model.RedundancyReason
import de.schwarzland.mavenup.model.RedundantManagedDependencyRecommendation
import org.apache.maven.artifact.versioning.ComparableVersion
import java.awt.Color
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
        setOKButtonText(MyMessageBundle.message("redundant.managed.dependency.dialog.stageRemoval"))
        init()
        updateOkActionState()
    }

    /**
     * Liefert die vom Anwender ausgewählten redundanten Empfehlungen.
     */
    fun getSelectedRecommendations(): List<RedundantManagedDependencyRecommendation> =
        recommendations.filterIndexed { index, _ -> selectionStates.getOrElse(index) { false } }

    /**
     * Erstellt die größenveränderliche Übersicht mit einem unabhängig vom Detailtext schrumpfbaren Layout.
     */
    public override fun createCenterPanel(): JComponent {
        val recommendationsPanel = panel {
            row {
                cell(JBScrollPane(buildTable()).apply {
                    preferredSize = JBUI.size(0, 120)
                }).align(Align.FILL).resizableColumn()
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
                text(
                    MyMessageBundle.message("redundant.managed.dependency.dialog.explanation"),
                    maxLineLength = MAX_LINE_LENGTH_WORD_WRAP
                ).align(Align.FILL)
            }
            row {
                comment(scopeDescription)
            }
            row {
                cell(splitter).align(Align.FILL).resizableColumn()
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
            it.preferredSize = JBUI.size(1024, 680)
        }
    }

    /** Markiert den nativen Trenner mit einer themenabhängigen Griffleiste und Hover-Rückmeldung. */
    private fun configureDivider(splitter: JBSplitter) {
        val divider = splitter.divider ?: return
        val border = RedundantDividerBorder()
        divider.border = border
        divider.addMouseListener(object : MouseAdapter() {
            /** Hebt die Griffleiste beim Eintritt des Mauszeigers hervor. */
            override fun mouseEntered(e: MouseEvent?) {
                border.hovered = true
                divider.repaint()
            }

            /** Entfernt die Hervorhebung beim Verlassen der Griffleiste. */
            override fun mouseExited(e: MouseEvent?) {
                border.hovered = false
                divider.repaint()
            }
        })
    }

    /** Erstellt die sortierbare Empfehlungstabelle und synchronisiert Auswahl und Detailansicht. */
    private fun buildTable(): JBTable {
        val showProjectColumn = recommendations.map { it.sourceProjectId }.distinct().size > 1
        val columnNames = mutableListOf(
            MyMessageBundle.message("redundant.managed.dependency.dialog.table.header.select"),
            MyMessageBundle.message("redundant.managed.dependency.dialog.table.header.managedDependency"),
            MyMessageBundle.message("redundant.managed.dependency.dialog.table.header.currentVersion"),
            MyMessageBundle.message("redundant.managed.dependency.dialog.table.header.reason"),
            MyMessageBundle.message("redundant.managed.dependency.dialog.table.header.providedVersion")
        )
        if (showProjectColumn) {
            columnNames.add(MyMessageBundle.message("redundant.managed.dependency.dialog.table.header.project"))
        }

        tableModel = object : DefaultTableModel(columnNames.toTypedArray(), 0) {
            /** Liefert den Checkbox-Typ für die Auswahlspalte und Texttypen für die übrigen Spalten. */
            override fun getColumnClass(columnIndex: Int): Class<*> =
                if (columnIndex == COLUMN_SELECT) Boolean::class.javaObjectType else String::class.java

            /** Erlaubt Änderungen ausschließlich in der Auswahlspalte. */
            override fun isCellEditable(row: Int, column: Int): Boolean = column == COLUMN_SELECT

            /** Übernimmt Checkbox-Änderungen in den Auswahlzustand und aktualisiert die Übernahmeaktion. */
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
            val row = mutableListOf<Any>(
                selectionStates[index],
                "${rec.groupId}:${rec.artifactId}",
                rec.currentVersion,
                reasonLabel(rec),
                rec.providedVersion ?: "-"
            )
            if (showProjectColumn) {
                row.add(rec.sourceProjectId)
            }
            tableModel.addRow(row.toTypedArray())
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
            updateDetailPanel(table.convertRowIndexToModel(0))
        }

        return table
    }

    /** Sortiert Koordinaten natürlich und Versionsspalten nach der Maven-Versionsordnung. */
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
            val textColumns = listOf(COLUMN_MANAGED_DEPENDENCY, COLUMN_REASON) +
                if (model.columnCount > COLUMN_PROJECT) listOf(COLUMN_PROJECT) else emptyList()
            for (col in textColumns) {
                setComparator(col) { o1, o2 ->
                    StringUtil.naturalCompare(o1?.toString().orEmpty(), o2?.toString().orEmpty())
                }
            }
            sortKeys = listOf(RowSorter.SortKey(COLUMN_MANAGED_DEPENDENCY, SortOrder.ASCENDING))
        }

    /**
     * Erstellt den Detailbereich mit weichem Zeilenumbruch ohne horizontale Scrollleiste.
     */
    private fun buildDetailPanel(): JComponent = panel {
        row {
            label(MyMessageBundle.message("redundant.managed.dependency.dialog.detail.title")).bold()
        }
        row {
            cell(JBScrollPane(detailEditor).apply {
                horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
                preferredSize = JBUI.size(0, 80)
            }).align(Align.FILL).resizableColumn()
        }.resizableRow()
    }.apply {
        minimumSize = JBUI.size(0, 80)
    }

    /** Zeigt HTML-maskierte Nachweise und Quellpfade zur Modellzeile und setzt die Leseposition zurück. */
    private fun updateDetailPanel(modelRow: Int) {
        if (modelRow !in recommendations.indices) {
            detailEditor.text = ""
            return
        }

        val rec = recommendations[modelRow]
        val targetCoordinate = "${rec.groupId}:${rec.artifactId}"
        val colorHex = ColorUtil.toHtmlColor(AFFECTED_DEPENDENCY_COLOR)

        val reasonTitle = StringUtil.escapeXmlEntities(
            MyMessageBundle.message("redundant.managed.dependency.dialog.detail.reason")
        )
        val reasonDetail = formatReasonDetail(rec.reasonDetail, targetCoordinate, colorHex)
        val sourcePom = rec.sourcePomPath.takeIf { it.isNotBlank() }?.let {
            "<p><b>${StringUtil.escapeXmlEntities(
                MyMessageBundle.message("redundant.managed.dependency.dialog.detail.sourcePom")
            )}</b><br/>${StringUtil.escapeXmlEntities(it)}</p>"
        }.orEmpty()

        val paths = if (rec.consumers.isEmpty()) {
            StringUtil.escapeXmlEntities(
                MyMessageBundle.message("redundant.managed.dependency.dialog.detail.consumers.empty")
            )
        } else {
            rec.consumers.joinToString(separator = "", prefix = "<ul>", postfix = "</ul>") { consumer ->
                "<li>${formatConsumerPath(consumer.pathDescription, targetCoordinate, colorHex)}</li>"
            }
        }
        val pathsTitle = StringUtil.escapeXmlEntities(
            MyMessageBundle.message("redundant.managed.dependency.dialog.detail.consumers.title")
        )

        detailEditor.text = "<html><body><p><b>$reasonTitle</b><br/>$reasonDetail</p>" +
            "$sourcePom<p><b>$pathsTitle</b></p>$paths</body></html>"
        detailEditor.caretPosition = 0
    }

    /** Setzt alle Checkboxen auf den angegebenen Auswahlzustand. */
    internal fun setAllSelected(selected: Boolean) {
        for (i in selectionStates.indices) {
            selectionStates[i] = selected
            tableModel.setValueAt(selected, i, COLUMN_SELECT)
        }
        updateOkActionState()
    }

    /** Aktiviert die Übernahmeaktion genau dann, wenn mindestens eine Empfehlung ausgewählt ist. */
    private fun updateOkActionState() {
        isOKActionEnabled = selectionStates.any { it }
    }

    /** Übernimmt den UI-Zustand und übergibt ausgewählte Empfehlungen und Filterwunsch an den Callback. */
    override fun doOKAction() {
        dialogPanel.apply()
        val selected = getSelectedRecommendations()
        onApply?.invoke(selected, showAllPendingChanges)
        super.doOKAction()
    }

    /** Liefert die lokalisierte Bezeichnung des Redundanzgrunds mit zutreffender Versionsrelation. */
    private fun reasonLabel(recommendation: RedundantManagedDependencyRecommendation): String {
        val relation = versionRelation(recommendation)
        return when (recommendation.reason) {
            RedundancyReason.PARENT_MANAGED ->
                MyMessageBundle.message("redundant.managed.dependency.reason.parentManaged", relation)
            RedundancyReason.DIRECT_DEPENDENCY_MATCH ->
                MyMessageBundle.message("redundant.managed.dependency.reason.directMatch", relation)
            RedundancyReason.TRANSITIVE_MATCH ->
                MyMessageBundle.message("redundant.managed.dependency.reason.transitiveMatch", relation)
            RedundancyReason.UNUSED -> MyMessageBundle.message("redundant.managed.dependency.reason.unused")
        }
    }

    /**
     * Bestimmt, ob die bereitgestellten Versionen mit der verwalteten Version übereinstimmen oder höher sind.
     *
     * @param recommendation Die zu beschriftende Empfehlung.
     * @return Die lokalisierte Kennzeichnung „Same Version“, „Higher Version“ oder eine gemischte Kennzeichnung.
     */
    private fun versionRelation(recommendation: RedundantManagedDependencyRecommendation): String {
        val providedVersions = if (recommendation.reason == RedundancyReason.TRANSITIVE_MATCH) {
            recommendation.consumers.map { it.resolvedVersion }.filter(String::isNotBlank).distinct()
        } else {
            listOfNotNull(recommendation.providedVersion)
        }
        val currentVersion = ComparableVersion(recommendation.currentVersion)
        val hasSameVersion = providedVersions.any {
            ComparableVersion(it).compareTo(currentVersion) == 0
        }
        val hasHigherVersion = providedVersions.any {
            ComparableVersion(it).compareTo(currentVersion) > 0
        }
        val key = when {
            hasSameVersion && hasHigherVersion -> "redundant.managed.dependency.reason.versionLabel.mixed"
            hasHigherVersion -> "redundant.managed.dependency.reason.versionLabel.higher"
            else -> "redundant.managed.dependency.reason.versionLabel.same"
        }
        return MyMessageBundle.message(key)
    }

    /** Zeichnet eine themenabhängige Trennlinie mit mittiger, bei Hover hervorgehobener Griffleiste. */
    private class RedundantDividerBorder : AbstractBorder() {
        var hovered = false

        /** Zeichnet die Linie und drei Griffpunkte ohne Änderungen am übergebenen Grafikkontext. */
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

    companion object {
        /** Textfarbe zur Hervorhebung betroffener Abhängigkeiten in Light- und Dark-Mode. */
        internal val AFFECTED_DEPENDENCY_COLOR = JBColor(Color(0x00, 0x55, 0xAA), Color(0x58, 0x9D, 0xF6))
    }
}

/**
 * Formatiert die Redundanzbegründung für die HTML-Detailansicht und hebt die betroffene Koordinate farblich hervor.
 *
 * @param reasonDetail Der unformatierte Begründungstext.
 * @param targetCoordinate Die Koordinate `groupId:artifactId` der betroffenen Abhängigkeit.
 * @param colorHex Der hexadezimale Farbcode zur themenabhängigen Darstellung.
 * @return Der HTML-formatierte und maskierte Begründungstext mit farblicher Hervorhebung.
 */
internal fun formatReasonDetail(
    reasonDetail: String,
    targetCoordinate: String,
    colorHex: String
): String {
    val escapedReason = StringUtil.escapeXmlEntities(reasonDetail)
    if (targetCoordinate.isBlank()) return escapedReason
    val escapedTarget = StringUtil.escapeXmlEntities(targetCoordinate)
    val regex = Regex("""(?<![a-zA-Z0-9_\-.:])${Regex.escape(escapedTarget)}(?![a-zA-Z0-9_\-.])""")
    return regex.replace(escapedReason) { match ->
        "<span style=\"color: $colorHex;\">${match.value}</span>"
    }
}

/**
 * Formatiert einen Konsumentenpfad für die HTML-Detailansicht und hebt betroffene Abhängigkeiten farblich hervor.
 *
 * @param pathDescription Der Abhängigkeitspfad mit Trennern (` -> `).
 * @param targetCoordinate Die Koordinate `groupId:artifactId` der betroffenen Abhängigkeit.
 * @param colorHex Der hexadezimale Farbcode zur themenabhängigen Darstellung.
 * @return Der HTML-formatierte und maskierte Konsumentenpfad mit farblicher Hervorhebung.
 */
internal fun formatConsumerPath(
    pathDescription: String,
    targetCoordinate: String,
    colorHex: String
): String {
    if (targetCoordinate.isBlank()) {
        return StringUtil.escapeXmlEntities(pathDescription)
    }
    val segments = pathDescription.split(" -> ")
    val formattedSegments = segments.map { segment ->
        val trimmed = segment.trim()
        val isTarget = trimmed == targetCoordinate ||
            trimmed.startsWith("$targetCoordinate:") ||
            trimmed.startsWith("$targetCoordinate ")
        val escaped = StringUtil.escapeXmlEntities(trimmed)
        if (isTarget) {
            "<span style=\"color: $colorHex;\">$escaped</span>"
        } else {
            escaped
        }
    }
    return formattedSegments.joinToString(" -&gt; ")
}
