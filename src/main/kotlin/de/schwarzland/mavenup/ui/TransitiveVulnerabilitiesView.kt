package de.schwarzland.mavenup.ui

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import de.schwarzland.mavenup.model.VulnerabilityAdvisory
import de.schwarzland.mavenup.service.MavenUpSettings
import java.awt.BorderLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.ListSelectionModel
import javax.swing.RowSorter.SortKey
import javax.swing.SortOrder
import javax.swing.table.DefaultTableModel
import javax.swing.table.TableRowSorter

/** Spaltenindex der GroupId in der Tabelle der transitiven Sicherheitslücken. */
internal const val TRANSITIVE_GROUP_ID_COLUMN = 0

/** Spaltenindex der ArtifactId in der Tabelle der transitiven Sicherheitslücken. */
internal const val TRANSITIVE_ARTIFACT_ID_COLUMN = 1

/** Spaltenindex des Typs in der Tabelle der transitiven Sicherheitslücken. */
internal const val TRANSITIVE_TYPE_COLUMN = 2

/** Spaltenindex der Version in der Tabelle der transitiven Sicherheitslücken. */
internal const val TRANSITIVE_VERSION_COLUMN = 3

/** Spaltenindex der Sicherheitslücken-Zelle in der Tabelle der transitiven Sicherheitslücken. */
internal const val TRANSITIVE_VULNERABILITIES_COLUMN = 4

/**
 * Eine Zeile der Ansicht der transitiven, verwundbaren Abhängigkeiten.
 *
 * @property groupId GroupId der transitiven Abhängigkeit.
 * @property artifactId ArtifactId der transitiven Abhängigkeit.
 * @property type Anzeigetyp der Koordinate (z. B. `managed dependency`, wenn sie bereits im
 * `dependencyManagement` der `pom.xml` deklariert ist, sonst der transitive Standardtyp).
 * @property version Aufgelöste Version der transitiven Abhängigkeit.
 * @property cell Die zusammengefassten Sicherheitswarnungen dieser Koordinate.
 */
internal data class TransitiveVulnerabilityRow(
    val groupId: String,
    val artifactId: String,
    val type: String,
    val version: String,
    val cell: VulnerabilityCell
)

/**
 * Ermittelt die anzuzeigenden Zeilen der transitiven Sicherheitslücken-Ansicht.
 *
 * Berücksichtigt ausschließlich transitive Koordinaten, für die mindestens eine Sicherheitswarnung
 * vorliegt. Die Koordinate wird in GroupId, ArtifactId und Version zerlegt; Koordinaten ohne die drei
 * erforderlichen Bestandteile werden übersprungen. Der Typ einer Koordinate wird über ihre GroupId/ArtifactId
 * in [knownTypes] nachgeschlagen: Ist die Koordinate bereits als Abhängigkeit oder im `dependencyManagement`
 * der `pom.xml` deklariert, wird der dortige Typ übernommen; andernfalls gilt [transitiveTypeLabel].
 * Die Zeilen werden absteigend nach höchstem
 * Schweregrad, dann nach Anzahl der Warnungen und schließlich alphabetisch nach GroupId/ArtifactId
 * sortiert, damit die kritischsten Funde zuerst erscheinen.
 *
 * @param advisoriesByCoordinate Zuordnung von Koordinate (`groupId:artifactId:version`) zu ihren Warnungen.
 * @param transitiveCoordinates Menge aller transitiven Koordinaten aus dem letzten Scan.
 * @param knownTypes Zuordnung von `groupId:artifactId` zum in der Haupttabelle angezeigten Typ.
 * @param transitiveTypeLabel Anzeigetyp für rein transitive Koordinaten, die nicht in der `pom.xml` deklariert sind.
 * @return Die sortierte Liste der darzustellenden Zeilen.
 */
internal fun collectTransitiveVulnerabilityRows(
    advisoriesByCoordinate: Map<String, List<VulnerabilityAdvisory>>,
    transitiveCoordinates: Set<String>,
    knownTypes: Map<String, String> = emptyMap(),
    transitiveTypeLabel: String = "transitive"
): List<TransitiveVulnerabilityRow> =
    transitiveCoordinates.asSequence()
        .mapNotNull { coordinate ->
            val advisories = advisoriesByCoordinate[coordinate]?.takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            val parts = coordinate.split(":")
            if (parts.size < 3) return@mapNotNull null
            TransitiveVulnerabilityRow(
                groupId = parts[0],
                artifactId = parts[1],
                type = knownTypes["${parts[0]}:${parts[1]}"] ?: transitiveTypeLabel,
                version = parts.drop(2).joinToString(":"),
                cell = VulnerabilityCell(mapOf(coordinate to advisories), emptySet())
            )
        }
        .sortedWith(
            compareByDescending<TransitiveVulnerabilityRow> { worstSeverity(it.cell.allAdvisories).rank }
                .thenByDescending { it.cell.allAdvisories.size }
                .thenBy { it.groupId }
                .thenBy { it.artifactId }
        )
        .toList()

/**
 * Alternative Ansicht des MavenUp-Tool-Windows, die ausschließlich die transitiven, verwundbaren
 * Abhängigkeiten auflistet.
 *
 * Zeigt pro transitiver Koordinate GroupId, ArtifactId, aufgelöste Version und die Anzahl der
 * Sicherheitslücken (inklusive höchstem Schweregrad und farblicher Hervorhebung) an. Ein Klick auf
 * die Sicherheitslücken-Zelle öffnet den Detaildialog der jeweiligen Komponente. Die Ansicht ist als
 * eigenständige Komponente ausgelegt, damit ihr künftig weitere Aktionen hinzugefügt werden können.
 *
 * @param project Das zugehörige Projekt, für das der Detaildialog geöffnet wird.
 */
internal class TransitiveVulnerabilitiesView(private val project: Project) : JBPanel<JBPanel<*>>(BorderLayout()) {

    /** Tabellenmodell der Ansicht; nicht editierbar, wird über [update] befüllt. */
    private val tableModel = object : DefaultTableModel() {
        override fun isCellEditable(row: Int, column: Int): Boolean = false
    }.apply {
        addColumn(MyMessageBundle.message("toolwindow.MyToolWindow.table.header.groupId"))
        addColumn(MyMessageBundle.message("toolwindow.MyToolWindow.table.header.artifactId"))
        addColumn(MyMessageBundle.message("toolwindow.MyToolWindow.table.header.type"))
        addColumn(MyMessageBundle.message("toolwindow.TransitiveVulnerabilities.table.header.version"))
        addColumn(MyMessageBundle.message("toolwindow.TransitiveVulnerabilities.table.header.vulnerabilities"))
    }

    /** Die Tabelle der transitiven, verwundbaren Abhängigkeiten. */
    internal val table: JBTable = JBTable(tableModel)

    init {
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        table.tableHeader.reorderingAllowed = false
        table.columnModel.getColumn(TRANSITIVE_VULNERABILITIES_COLUMN).cellRenderer = vulnerabilityCellRenderer()

        val textComparator = Comparator<Any?> { a, b ->
            String.CASE_INSENSITIVE_ORDER.compare(a?.toString().orEmpty(), b?.toString().orEmpty())
        }
        val sorter = object : TableRowSorter<DefaultTableModel>(tableModel) {
            /**
             * Schaltet die Sortierung einer Spalte zyklisch weiter: aufsteigend →
             * absteigend → unsortiert (ursprüngliche, nach Schweregrad vorsortierte Reihenfolge).
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
        for (column in 0 until tableModel.columnCount) {
            when (column) {
                TRANSITIVE_VERSION_COLUMN -> sorter.setSortable(column, false)
                TRANSITIVE_VULNERABILITIES_COLUMN -> {
                    sorter.setSortable(column, true)
                    sorter.setComparator(column, vulnerabilityCellComparator)
                }
                else -> {
                    sorter.setSortable(column, true)
                    sorter.setComparator(column, textComparator)
                }
            }
        }
        table.rowSorter = sorter
        installSortableHeaderRenderer(table)

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
                if (row >= 0 && column == TRANSITIVE_VULNERABILITIES_COLUMN && e.clickCount == 1) {
                    openVulnerabilityDetails(row)
                }
            }
        })

        add(JBScrollPane(table), BorderLayout.CENTER)
    }

    /**
     * Zeigt das zeilenbezogene Kontextmenü der transitiven Ansicht an.
     *
     * Angelehnt an das Kontextmenü der Haupttabelle bietet es **Open on [Browser]** (öffnet die
     * angeklickte Koordinate im konfigurierten Maven-Repository-Browser) und **Show Vulnerability
     * Details** (öffnet den Detaildialog; nur aktiv, wenn Sicherheitswarnungen vorliegen). Das Menü
     * wird über IntelliJs `ActionSystem` erzeugt, damit Theme, Abstände und Auswahlfarben der IDE
     * verwendet werden.
     *
     * @param e Das auslösende Maus-Ereignis mit der Klickposition.
     */
    private fun showContextMenu(e: MouseEvent) {
        val viewRow = table.rowAtPoint(e.point)
        if (viewRow < 0) return
        if (!table.isRowSelected(viewRow)) {
            table.setRowSelectionInterval(viewRow, viewRow)
        }
        val modelRow = table.convertRowIndexToModel(viewRow)
        val cell = tableModel.getValueAt(modelRow, TRANSITIVE_VULNERABILITIES_COLUMN) as? VulnerabilityCell

        val group = DefaultActionGroup()
        fun addAction(label: String, enabled: Boolean = true, action: () -> Unit) {
            group.add(object : AnAction(label) {
                override fun getActionUpdateThread() = ActionUpdateThread.BGT
                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = enabled
                }
                override fun actionPerformed(e: AnActionEvent) = action()
            })
        }

        val browser = MavenUpSettings.getInstance().state.repositoryBrowser
        addAction(
            MyMessageBundle.message(TOOLWINDOW_MY_TOOL_WINDOW_CONTEXT_MENU_OPEN_IN_MVN_REPOSITORY, browser.displayName)
        ) {
            openInRepository(viewRow)
        }
        val hasVulnerabilities = cell != null && cell.allAdvisories.isNotEmpty()
        group.addSeparator()
        addAction(
            MyMessageBundle.message("toolwindow.MyToolWindow.contextMenu.showVulnerabilityDetails"),
            hasVulnerabilities
        ) {
            openVulnerabilityDetails(viewRow)
        }

        ActionManager.getInstance().createActionPopupMenu(
            "MavenUp.TransitiveVulnerabilitiesTable", group
        ).component.show(e.component, e.x, e.y)
    }

    /**
     * Prüft, ob aktuell eine Zeile der transitiven Tabelle selektiert ist.
     *
     * @return `true`, wenn mindestens eine Zeile selektiert ist.
     */
    internal fun hasSelectedRow(): Boolean = table.selectedRow >= 0

    /**
     * Prüft, ob die aktuell selektierte Zeile mindestens eine Sicherheitswarnung besitzt.
     *
     * @return `true`, wenn die selektierte Zeile Sicherheitswarnungen enthält.
     */
    internal fun selectedRowHasVulnerabilities(): Boolean {
        val cell = selectedCell() ?: return false
        return cell.allAdvisories.isNotEmpty()
    }

    /**
     * Öffnet die aktuell selektierte Koordinate im konfigurierten Maven-Repository-Browser.
     */
    internal fun openSelectedInRepository() {
        val viewRow = table.selectedRow
        if (viewRow < 0) return
        openInRepository(viewRow)
    }

    /**
     * Öffnet den Vulnerability-Detaildialog für die aktuell selektierte Zeile.
     */
    internal fun openSelectedVulnerabilityDetails() {
        val viewRow = table.selectedRow
        if (viewRow < 0) return
        openVulnerabilityDetails(viewRow)
    }

    /**
     * Liefert die Sicherheitslücken-Zelle der aktuell selektierten Zeile.
     *
     * @return Die [VulnerabilityCell] der selektierten Zeile oder `null`, wenn keine Zeile selektiert ist.
     */
    private fun selectedCell(): VulnerabilityCell? {
        val viewRow = table.selectedRow
        if (viewRow < 0) return null
        val modelRow = table.convertRowIndexToModel(viewRow)
        return tableModel.getValueAt(modelRow, TRANSITIVE_VULNERABILITIES_COLUMN) as? VulnerabilityCell
    }

    /**
     * Öffnet die Koordinate der angegebenen Sichtzeile im konfigurierten Maven-Repository-Browser.
     *
     * @param viewRow Der Zeilenindex in der (ggf. sortierten) Sicht.
     */
    private fun openInRepository(viewRow: Int) {
        val modelRow = table.convertRowIndexToModel(viewRow)
        val groupId = tableModel.getValueAt(modelRow, TRANSITIVE_GROUP_ID_COLUMN) as? String ?: ""
        val artifactId = tableModel.getValueAt(modelRow, TRANSITIVE_ARTIFACT_ID_COLUMN) as? String ?: ""
        val version = tableModel.getValueAt(modelRow, TRANSITIVE_VERSION_COLUMN) as? String ?: ""
        val browser = MavenUpSettings.getInstance().state.repositoryBrowser
        BrowserUtil.browse(buildMavenRepositoryUrl(groupId, artifactId, version, browser))
    }

    /**
     * Aktualisiert die Ansicht mit den transitiven, verwundbaren Abhängigkeiten des letzten Scans.
     *
     * @param advisoriesByCoordinate Zuordnung aller Koordinaten zu ihren Warnungen.
     * @param transitiveCoordinates Menge aller transitiven Koordinaten.
     * @param knownTypes Zuordnung von `groupId:artifactId` zum in der Haupttabelle angezeigten Typ.
     */
    fun update(
        advisoriesByCoordinate: Map<String, List<VulnerabilityAdvisory>>,
        transitiveCoordinates: Set<String>,
        knownTypes: Map<String, String> = emptyMap()
    ) {
        val rows = collectTransitiveVulnerabilityRows(
            advisoriesByCoordinate,
            transitiveCoordinates,
            knownTypes,
            MyMessageBundle.message("toolwindow.TransitiveVulnerabilities.type.transitive")
        )
        tableModel.setRowCount(0)
        rows.forEach { row ->
            tableModel.addRow(arrayOf(row.groupId, row.artifactId, row.type, row.version, row.cell))
        }
        trimColumnWidthsToContent(table)
    }

    /**
     * Öffnet den Detaildialog für die Sicherheitslücken der angeklickten Sichtzeile.
     *
     * @param viewRow Der Zeilenindex in der (ggf. sortierten) Sicht.
     */
    private fun openVulnerabilityDetails(viewRow: Int) {
        val modelRow = table.convertRowIndexToModel(viewRow)
        val cell = tableModel.getValueAt(modelRow, TRANSITIVE_VULNERABILITIES_COLUMN) as? VulnerabilityCell ?: return
        if (cell.allAdvisories.isEmpty()) return
        val coordinate = listOf(TRANSITIVE_GROUP_ID_COLUMN, TRANSITIVE_ARTIFACT_ID_COLUMN, TRANSITIVE_VERSION_COLUMN)
            .joinToString(":") { tableModel.getValueAt(modelRow, it).toString() }
        VulnerabilityDetailDialog(
            project,
            cell.detailFindings(),
            "$coordinate - ${MyMessageBundle.message(VULNERABILITY_DETAILS_TITLE)}"
        ).show()
    }
}
