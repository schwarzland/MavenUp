package de.schwarzland.mavenup.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.table.JBTable
import de.schwarzland.mavenup.service.VersionCacheEntrySnapshot
import de.schwarzland.mavenup.service.VersionMetadataCache
import de.schwarzland.mavenup.service.VulnerabilityCacheEntrySnapshot
import de.schwarzland.mavenup.service.VulnerabilityResultCache
import java.awt.BorderLayout
import java.awt.Dimension
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.ListSelectionModel
import javax.swing.SortOrder
import javax.swing.table.DefaultTableModel
import javax.swing.table.TableRowSorter

/**
 * Formatiert einen Zeitstempel (Millisekunden seit der Epoche) als lokal sortierbaren Text
 * (`yyyy-MM-dd HH:mm:ss`), sodass eine alphabetische Sortierung stets der chronologischen Reihenfolge
 * entspricht.
 *
 * @param timestampMillis Der zu formatierende Zeitpunkt in Millisekunden seit der Epoche.
 * @return Der formatierte Zeitstempel in der Standard-Zeitzone der JVM.
 */
internal fun formatCacheTimestamp(timestampMillis: Long): String =
    CACHE_TIMESTAMP_FORMATTER.format(Instant.ofEpochMilli(timestampMillis).atZone(ZoneId.systemDefault()))

/** Formatiervorlage für Zeitstempel in den Cache-Inhalte-Tabellen (siehe [formatCacheTimestamp]). */
private val CACHE_TIMESTAMP_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

/**
 * Ein rein informativer Dialog, der die aktuellen Inhalte der beiden anwendungsweiten
 * Diagnose-Zwischenspeicher anzeigt: den [VulnerabilityResultCache] (zusammengeführte Scan-Ergebnisse
 * je Koordinate) und den [VersionMetadataCache] (abgerufene Versionslisten je Artefakt).
 *
 * Der Dialog erstellt bei jedem Öffnen einen frischen Schnappschuss beider Zwischenspeicher; er
 * aktualisiert sich während der Anzeige nicht automatisch. Beide Tabellen sind wie die Haupttabelle
 * über die Kopfzeile sortierbar (aufsteigend → absteigend → unsortiert) und zeigen denselben
 * Sortier-Indikator.
 */
internal class CacheContentsDialog(
    project: Project,
    private val vulnerabilityCache: VulnerabilityResultCache = VulnerabilityResultCache.getInstance(),
    private val versionCache: VersionMetadataCache = VersionMetadataCache.getInstance()
) : DialogWrapper(project) {

    init {
        title = MyMessageBundle.message("cache.contents.title")
        setOKButtonText(MyMessageBundle.message("button.close"))
        init()
    }

    /** Zeigt ausschließlich den Close-Button, da der Dialog rein informativ ist. */
    override fun createActions(): Array<Action> = arrayOf(okAction)

    /**
     * Erstellt den zentralen Inhaltsbereich: eine Tabbed-Pane mit je einer sortierbaren Tabelle für
     * die Inhalte beider Zwischenspeicher.
     */
    override fun createCenterPanel(): JComponent {
        val vulnerabilityEntries = vulnerabilityCache.snapshot()
        val versionEntries = versionCache.snapshot()
        val tabs = JBTabbedPane().apply {
            addTab(
                MyMessageBundle.message("cache.contents.tab.vulnerability", vulnerabilityEntries.size),
                JBScrollPane(buildVulnerabilityTable(vulnerabilityEntries))
            )
            addTab(
                MyMessageBundle.message("cache.contents.tab.version", versionEntries.size),
                JBScrollPane(buildVersionTable(versionEntries))
            )
        }
        return JBPanel<JBPanel<*>>(BorderLayout()).apply {
            preferredSize = Dimension(760, 480)
            add(tabs, BorderLayout.CENTER)
        }
    }

    /** Erstellt die sortierbare Tabelle mit den Einträgen des [VulnerabilityResultCache]. */
    internal fun buildVulnerabilityTable(entries: List<VulnerabilityCacheEntrySnapshot>): JBTable {
        val model = object : DefaultTableModel() {
            override fun isCellEditable(row: Int, column: Int): Boolean = false
        }.apply {
            addColumn(MyMessageBundle.message("cache.contents.vulnerability.coordinate"))
            addColumn(MyMessageBundle.message("cache.contents.vulnerability.count"))
            addColumn(MyMessageBundle.message("cache.contents.vulnerability.queriedAt"))
        }
        val sortedEntries = entries.sortedWith(Comparator { a, b -> a.coordinate.compareTo(b.coordinate) })
        sortedEntries.forEach { entry ->
            model.addRow(arrayOf<Any>(entry.coordinate, entry.vulnerabilityCount, formatCacheTimestamp(entry.timestampMillis)))
        }
        return buildSortableTable(model, numericColumns = setOf(VULNERABILITY_COUNT_COLUMN))
    }

    /** Erstellt die sortierbare Tabelle mit den Einträgen des [VersionMetadataCache]. */
    internal fun buildVersionTable(entries: List<VersionCacheEntrySnapshot>): JBTable {
        val model = object : DefaultTableModel() {
            override fun isCellEditable(row: Int, column: Int): Boolean = false
        }.apply {
            addColumn(MyMessageBundle.message("cache.contents.version.groupId"))
            addColumn(MyMessageBundle.message("cache.contents.version.artifactId"))
            addColumn(MyMessageBundle.message("cache.contents.version.count"))
            addColumn(MyMessageBundle.message("cache.contents.version.queriedAt"))
        }
        val sortedEntries = entries.sortedWith(
            Comparator { a, b ->
                val groupIdCompare = a.groupId.compareTo(b.groupId)
                if (groupIdCompare != 0) groupIdCompare else a.artifactId.compareTo(b.artifactId)
            }
        )
        sortedEntries.forEach { entry ->
            model.addRow(arrayOf<Any>(entry.groupId, entry.artifactId, entry.versionCount, formatCacheTimestamp(entry.timestampMillis)))
        }
        return buildSortableTable(model, numericColumns = setOf(VERSION_COUNT_COLUMN))
    }

    /**
     * Erstellt eine Tabelle mit einheitlicher Optik (Zeilenhöhe, Spaltenbreiten, Sortier-Indikator in
     * der Kopfzeile) auf Basis des übergebenen Modells. Alle Spalten sind sortierbar und durchlaufen
     * bei Klick denselben Zyklus (aufsteigend → absteigend → unsortiert) wie die Haupttabelle.
     *
     * @param model Das Tabellenmodell.
     * @param numericColumns Die Modellindizes der Spalten, deren Zellwerte als [Int] gespeichert sind
     * und daher numerisch statt alphabetisch verglichen werden müssen.
     */
    private fun buildSortableTable(model: DefaultTableModel, numericColumns: Set<Int>): JBTable = JBTable(model).apply {
        autoResizeMode = JBTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        tableHeader.reorderingAllowed = false
        emptyText.text = MyMessageBundle.message("cache.contents.empty")
        rowSorter = buildRowSorter(model, numericColumns)
        installSortableHeaderRenderer(this)
        trimColumnWidthsToContent(this)
        applyRecommendedRowHeight(this)
    }

    /**
     * Erstellt den [TableRowSorter] für eine Cache-Inhalte-Tabelle. Alle Spalten sind sortierbar; die
     * in [numericColumns] genannten Zahlenspalten werden numerisch, alle übrigen Spalten alphabetisch
     * ohne Beachtung der Groß-/Kleinschreibung (via [cellTextComparator]) verglichen.
     */
    private fun buildRowSorter(model: DefaultTableModel, numericColumns: Set<Int>): TableRowSorter<DefaultTableModel> {
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
            sorter.setSortable(columnIndex, true)
            sorter.setComparator(
                columnIndex,
                if (columnIndex in numericColumns) numericCellComparator else cellTextComparator
            )
        }
        return sorter
    }

    private companion object {
        /** Modellindex der Fundstellen-Anzahl-Spalte in der Vulnerability-Cache-Tabelle. */
        private const val VULNERABILITY_COUNT_COLUMN = 1

        /** Modellindex der Versionsanzahl-Spalte in der Version-Cache-Tabelle. */
        private const val VERSION_COUNT_COLUMN = 2
    }
}

/** Vergleicht zwei als [Int] gespeicherte Zellwerte numerisch (z. B. Anzahl-Spalten). */
private val numericCellComparator: Comparator<Any?> = Comparator { a, b ->
    (a as? Int ?: 0).compareTo(b as? Int ?: 0)
}
