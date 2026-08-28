package de.schwarzland.mavenup.ui

import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import de.schwarzland.mavenup.model.VulnerabilityAdvisory
import java.awt.BorderLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.ListSelectionModel
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
        val sorter = TableRowSorter(tableModel)
        for (column in 0 until tableModel.columnCount) {
            sorter.setSortable(column, true)
            if (column == TRANSITIVE_VULNERABILITIES_COLUMN) {
                sorter.setComparator(column, vulnerabilityCellComparator)
            } else {
                sorter.setComparator(column, textComparator)
            }
        }
        table.rowSorter = sorter
        installSortableHeaderRenderer(table)

        table.addMouseListener(object : MouseAdapter() {
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
