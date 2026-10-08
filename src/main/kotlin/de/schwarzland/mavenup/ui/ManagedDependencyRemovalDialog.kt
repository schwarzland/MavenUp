package de.schwarzland.mavenup.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.JBSplitter
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.HTMLEditorKitBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import de.schwarzland.mavenup.model.ManagedDependencyRemovalRecommendation
import de.schwarzland.mavenup.model.ManagedDependencyTargetVersion
import org.apache.maven.artifact.versioning.ComparableVersion
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
 * Spaltenindex für die abgestimmte Trigger-Zielversion.
 */
private const val COLUMN_TARGET_VERSION = 4

/**
 * Spaltenindex für die bereitgestellte transitive Version.
 */
private const val COLUMN_PROVIDED_VERSION = 5

/**
 * Dialog zur interaktiven Prüfung und Übernahme von Empfehlungen zur Bereinigung
 * redundanter Einträge in `<dependencyManagement>`.
 *
 * Zeigt die erkannten Empfehlungen in einer Tabelle mit Auswahl-Checkboxen sowie
 * detaillierte Erklärungen und Pfadangaben zur jeweils selektierten Zeile an.
 * Der Dialog zeigt außerdem den projektweiten Prüfumfang, das Quellprojekt jeder Empfehlung,
 * bei fehlenden Quelldaten einen deutlichen Hinweis auf möglicherweise unvollständige Ergebnisse
 * und die Maven-Deklarationen, die von dieser Prüfung nicht abgedeckt werden.
 * Ein vertikaler Splitter speichert die vom Anwender gewählte Aufteilung zwischen
 * Tabelle und vollständig scrollbar dargestellten Details.
 * Eine nicht gespeicherte, initial deaktivierte Option fordert nach der Übernahme
 * die Anzeige aller ausstehenden Änderungen in der Haupttabelle an.
 *
 * @param project Das aktuelle IntelliJ-Projekt.
 * @property recommendations Die Liste der zur Bereinigung vorgeschlagenen Empfehlungen.
 * @property scopeDescription Erklärung des projektweiten Prüfumfangs und möglicher Koordinatenfilter.
 * @property incompleteLookups Koordinaten, deren Versions- oder POM-Daten nicht verfügbar waren.
 * @property onApply Optionaler Callback mit ausgewählten Empfehlungen und gewünschtem Pending-Filterwechsel.
 */
class ManagedDependencyRemovalDialog(
    project: Project,
    private val recommendations: List<ManagedDependencyRemovalRecommendation>,
    private val scopeDescription: String = MyMessageBundle.message("managed.dependency.removal.scope.global"),
    private val incompleteLookups: Set<String> = emptySet(),
    private val onApply: ((List<ManagedDependencyRemovalRecommendation>, Boolean) -> Unit)? = null
) : DialogWrapper(project) {

    /**
     * Ergebnis der gemeinsamen Versionsauflösung für die aktuelle Tabellenauswahl.
     *
     * @property recommendationsByRow Aufgelöste Empfehlungen, nach Tabellenmodellzeile indiziert.
     * @property conflictingRows Ausgewählte Zeilen ohne gemeinsame Zielversion.
     */
    private data class SelectionResolution(
        val recommendationsByRow: Map<Int, ManagedDependencyRemovalRecommendation>,
        val conflictingRows: Set<Int>
    ) {
        /** Gibt an, ob mindestens eine ausgewählte Trigger-Gruppe einen Zielversionskonflikt hat. */
        val hasConflicts: Boolean
            get() = conflictingRows.isNotEmpty()
    }

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
        title = MyMessageBundle.message("managed.dependency.removal.dialog.title")
        isResizable = true
        setOKButtonText(MyMessageBundle.message("managed.dependency.removal.dialog.apply"))
        init()
        updateDetailPanel(0)
        updateOkActionState()
    }

    /**
     * Liefert die ausgewählten Empfehlungen mit jeweils abgestimmter gemeinsamer Zielversion.
     *
     * @return Liste der ausgewählten [ManagedDependencyRemovalRecommendation].
     */
    fun getSelectedRecommendations(): List<ManagedDependencyRemovalRecommendation> {
        val resolution = resolveSelectedRecommendations()
        return if (resolution.hasConflicts) emptyList() else resolution.recommendationsByRow.values.toList()
    }

    /**
     * Erstellt den zentralen Bereich mit UI DSL v2, einem vertikalen Splitter und der Folgeansicht-Option.
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
                text(StringUtil.escapeXmlEntities(scopeDescription)).align(Align.FILL)
            }
            row {
                text(
                    StringUtil.escapeXmlEntities(
                        MyMessageBundle.message("managed.dependency.removal.coverage.limitations")
                    )
                ).align(Align.FILL)
            }
            if (incompleteLookups.isNotEmpty()) {
                row {
                    text(StringUtil.escapeXmlEntities(incompleteCleanupLookupSummary(incompleteLookups)))
                        .align(Align.FILL)
                }
            }
            row {
                cell(splitter).align(Align.FILL)
            }.resizableRow()
            row {
                checkBox(MyMessageBundle.message("managed.dependency.removal.dialog.showPending"))
                    .bindSelected(::showAllPendingChanges)
                    .comment(MyMessageBundle.message("managed.dependency.removal.dialog.showPending.comment"))
            }
        }.apply {
            preferredSize = JBUI.size(900, 520)
            minimumSize = JBUI.size(650, 350)
        }.also {
            dialogPanel = it
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
            MyMessageBundle.message("managed.dependency.removal.dialog.table.header.providedVersion"),
            MyMessageBundle.message("managed.dependency.removal.dialog.table.header.project")
        )

        tableModel = object : DefaultTableModel(columnNames, 0) {
            /** Liefert den Datentyp für Checkboxen beziehungsweise Textspalten. */
            override fun getColumnClass(columnIndex: Int): Class<*> =
                if (columnIndex == COLUMN_SELECT) Boolean::class.javaObjectType else String::class.java

            /** Erlaubt ausschließlich Änderungen an der Auswahlspalte. */
            override fun isCellEditable(row: Int, column: Int): Boolean = column == COLUMN_SELECT

            /** Synchronisiert die Checkbox-Auswahl mit dem Dialogzustand. */
            override fun setValueAt(aValue: Any?, row: Int, column: Int) {
                if (column == COLUMN_SELECT && aValue is Boolean) {
                    selectionStates[row] = aValue
                    super.setValueAt(aValue, row, column)
                    refreshTargetVersionCells()
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
                    rec.transitiveVersionInTarget,
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
        refreshTargetVersionCells()

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
        val rec = resolveSelectedRecommendations().recommendationsByRow[modelRow] ?: recommendations[modelRow]

        val explanation = if (rec.triggerType == "parent") {
            if (rec.triggerTargetVersion == rec.triggerCurrentVersion) {
                MyMessageBundle.message(
                    "managed.dependency.removal.dialog.detail.explanation.parent.current",
                    rec.managedGroupId,
                    rec.managedArtifactId,
                    rec.managedCurrentVersion,
                    rec.triggerGroupId,
                    rec.triggerArtifactId,
                    rec.triggerCurrentVersion,
                    rec.transitiveVersionInTarget
                )
            } else {
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
            }
        } else {
            if (rec.triggerTargetVersion == rec.triggerCurrentVersion) {
                MyMessageBundle.message(
                    "managed.dependency.removal.dialog.detail.explanation.dependency.current",
                    rec.managedGroupId,
                    rec.managedArtifactId,
                    rec.managedCurrentVersion,
                    rec.triggerGroupId,
                    rec.triggerArtifactId,
                    rec.triggerCurrentVersion,
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
        val sourcePom = rec.sourcePomPath.takeIf { it.isNotBlank() }?.let {
            "<p><b>${StringUtil.escapeXmlEntities(
                MyMessageBundle.message("managed.dependency.removal.dialog.detail.sourcePom")
            )}</b> ${StringUtil.escapeXmlEntities(it)}</p>"
        }.orEmpty()
        detailEditor.text = "<html><body>${StringUtil.escapeXmlEntities(explanation)}" +
            "$sourcePom<p><b>$pathsTitle</b></p>$paths</body></html>"
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
        isOKActionEnabled = selectionStates.any { it } && !resolveSelectedRecommendations().hasConflicts
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
     * Übernimmt bei gültiger Auswahl die Folgeansicht-Option und übergibt beides an den Callback.
     */
    public override fun doOKAction() {
        val selectedRecommendations = getSelectedRecommendations()
        if (selectedRecommendations.isEmpty()) return
        dialogPanel.apply()
        onApply?.invoke(selectedRecommendations, showAllPendingChanges)
        super.doOKAction()
    }

    /**
     * Stimmt ausgewählte Empfehlungen derselben Trigger-Komponente auf eine gemeinsame
     * niedrigste kompatible Zielversion ab.
     *
     * @return Die aufgelösten Empfehlungen nach Modellzeile oder `null`, wenn ein Konflikt besteht.
     */
    private fun resolveSelectedRecommendations(): SelectionResolution {
        val selectedByTrigger = recommendations.indices
            .filter { selectionStates[it] }
            .groupBy { triggerKey(recommendations[it]) }
        val resolved = mutableMapOf<Int, ManagedDependencyRemovalRecommendation>()
        val conflictingRows = mutableSetOf<Int>()

        for (indices in selectedByTrigger.values) {
            val commonVersions = indices
                .map { index -> targetVersionOptions(recommendations[index]).map { it.version }.toSet() }
                .reduce { common, versions -> common.intersect(versions) }
            val targetVersion = commonVersions
                .sortedWith { left, right ->
                    val versionComparison = ComparableVersion(left).compareTo(ComparableVersion(right))
                    if (versionComparison != 0) versionComparison else left.compareTo(right)
                }
                .firstOrNull()
            if (targetVersion == null) {
                conflictingRows.addAll(indices)
                continue
            }

            for (index in indices) {
                val recommendation = recommendations[index]
                val option = targetVersionOptions(recommendation).first { it.version == targetVersion }
                resolved[index] = recommendation.copy(
                    triggerTargetVersion = option.version,
                    transitiveVersionInTarget = option.transitiveVersionInTarget,
                    consumers = option.consumers
                )
            }
        }
        return SelectionResolution(resolved, conflictingRows)
    }

    /**
     * Liefert alle geprüften Optionen oder den einzelnen Vorschlag für ältere Empfehlungsersteller.
     *
     * @param recommendation Die Bereinigungsempfehlung.
     * @return Die verifizierten Zielversionen samt bereitgestellter Version und Konsumenten-Pfaden.
     */
    private fun targetVersionOptions(
        recommendation: ManagedDependencyRemovalRecommendation
    ) = recommendation.targetVersionOptions.ifEmpty {
        listOf(
            ManagedDependencyTargetVersion(
                recommendation.triggerTargetVersion,
                recommendation.transitiveVersionInTarget,
                recommendation.consumers
            )
        )
    }

    /**
     * Aktualisiert die angezeigten Ziel- und bereitgestellten Versionen anhand der Auswahl.
     */
    private fun refreshTargetVersionCells() {
        if (!::tableModel.isInitialized) return
        val resolution = resolveSelectedRecommendations()
        for (index in recommendations.indices) {
            val recommendation = resolution.recommendationsByRow[index]
            if (index in resolution.conflictingRows) {
                tableModel.setValueAt(
                    MyMessageBundle.message("managed.dependency.removal.dialog.targetVersion.conflict"),
                    index,
                    COLUMN_TARGET_VERSION
                )
                tableModel.setValueAt("", index, COLUMN_PROVIDED_VERSION)
            } else {
                tableModel.setValueAt(
                    recommendation?.triggerTargetVersion ?: recommendations[index].triggerTargetVersion,
                    index,
                    COLUMN_TARGET_VERSION
                )
                tableModel.setValueAt(
                    recommendation?.transitiveVersionInTarget ?: recommendations[index].transitiveVersionInTarget,
                    index,
                    COLUMN_PROVIDED_VERSION
                )
            }
        }
        val selectedRow = table.selectedRow
        if (selectedRow >= 0) {
            updateDetailPanel(table.convertRowIndexToModel(selectedRow))
        }
    }

    /**
     * Erzeugt den Gruppenschlüssel einer auslösenden Komponente.
     *
     * @param recommendation Die Bereinigungsempfehlung.
     * @return Maven-Koordinate der auslösenden Komponente.
     */
    private fun triggerKey(recommendation: ManagedDependencyRemovalRecommendation): String =
        "${recommendation.triggerGroupId}:${recommendation.triggerArtifactId}"

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
