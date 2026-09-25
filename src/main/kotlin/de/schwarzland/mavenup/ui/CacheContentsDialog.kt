package de.schwarzland.mavenup.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.table.JBTable
import de.schwarzland.mavenup.service.MavenUpSettings
import de.schwarzland.mavenup.service.VersionCacheEntrySnapshot
import de.schwarzland.mavenup.service.VersionMetadataCache
import de.schwarzland.mavenup.service.VulnerabilityCacheEntrySnapshot
import de.schwarzland.mavenup.service.VulnerabilityResultCache
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

/** Modellindex der Fundstellen-Anzahl-Spalte in der Vulnerability-Cache-Tabelle. */
private const val VULNERABILITY_COUNT_COLUMN = 1

/** Modellindex der Rest-TTL-Spalte in der Vulnerability-Cache-Tabelle. */
private const val VULNERABILITY_TTL_COLUMN = 3

/** Modellindex der Versionsanzahl-Spalte in der Version-Cache-Tabelle. */
private const val VERSION_COUNT_COLUMN = 2

/** Modellindex der Rest-TTL-Spalte in der Version-Cache-Tabelle. */
private const val VERSION_TTL_COLUMN = 4

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
 * Berechnet die verbleibende Gültigkeit eines Eintrags, auf volle Sekunden aufgerundet.
 * Abgelaufene Einträge und deaktivierte Caches ergeben 0; zukünftige Zeitstempel verlängern die TTL nicht.
 *
 * @param timestampMillis Zeitpunkt der gespeicherten Abfrage.
 * @param ttlMinutes Konfigurierte Gültigkeit in Minuten; nichtpositive Werte deaktivieren den Cache.
 * @param nowMillis Referenzzeitpunkt des angezeigten Schnappschusses.
 * @return Die verbleibende Gültigkeit in Sekunden.
 */
internal fun remainingCacheTtlSeconds(timestampMillis: Long, ttlMinutes: Int, nowMillis: Long): Long {
    val ageMillis = (nowMillis - timestampMillis).coerceAtLeast(0L)
    val remainingMillis = (ttlMinutes.coerceAtLeast(0) * 60_000L - ageMillis).coerceAtLeast(0L)
    return (remainingMillis + 999L) / 1_000L
}

/**
 * Befüllt das Tabellenmodell für den [VersionMetadataCache].
 *
 * @param model Das zu befüllende Tabellenmodell.
 * @param entries Die darzustellenden Cache-Einträge.
 * @param ttlMinutes Die konfigurierte Gültigkeitsdauer in Minuten.
 * @param nowMillis Der aktuelle Referenzzeitpunkt in Millisekunden.
 */
internal fun populateVersionTableModel(
    model: DefaultTableModel,
    entries: List<VersionCacheEntrySnapshot>,
    ttlMinutes: Int,
    nowMillis: Long
) {
    model.rowCount = 0
    val sortedEntries = entries.sortedWith(
        Comparator { a, b ->
            val groupIdCompare = a.groupId.compareTo(b.groupId)
            if (groupIdCompare != 0) groupIdCompare else a.artifactId.compareTo(b.artifactId)
        }
    )
    sortedEntries.forEach { entry ->
        model.addRow(arrayOf<Any>(
            entry.groupId, entry.artifactId, entry.versionCount, formatCacheTimestamp(entry.timestampMillis),
            remainingCacheTtlSeconds(entry.timestampMillis, ttlMinutes, nowMillis)
        ))
    }
}

/**
 * Befüllt das Tabellenmodell für den [VulnerabilityResultCache].
 *
 * @param model Das zu befüllende Tabellenmodell.
 * @param entries Die darzustellenden Cache-Einträge.
 * @param ttlMinutes Die konfigurierte Gültigkeitsdauer in Minuten.
 * @param nowMillis Der aktuelle Referenzzeitpunkt in Millisekunden.
 */
internal fun populateVulnerabilityTableModel(
    model: DefaultTableModel,
    entries: List<VulnerabilityCacheEntrySnapshot>,
    ttlMinutes: Int,
    nowMillis: Long
) {
    model.rowCount = 0
    val sortedEntries = entries.sortedWith(Comparator { a, b -> a.coordinate.compareTo(b.coordinate) })
    sortedEntries.forEach { entry ->
        model.addRow(arrayOf<Any>(
            entry.coordinate, entry.vulnerabilityCount, formatCacheTimestamp(entry.timestampMillis),
            remainingCacheTtlSeconds(entry.timestampMillis, ttlMinutes, nowMillis)
        ))
    }
}

/**
 * Erstellt eine Tabelle mit einheitlicher Optik (Zeilenhöhe, Spaltenbreiten, Sortier-Indikator in
 * der Kopfzeile) auf Basis des übergebenen Modells. Alle Spalten sind sortierbar und durchlaufen
 * bei Klick denselben Zyklus (aufsteigend → absteigend → unsortiert) wie die Haupttabelle.
 *
 * @param model Das Tabellenmodell.
 * @param numericColumns Die Modellindizes der Spalten, deren Zellwerte als [Number] gespeichert sind
 * und daher numerisch statt alphabetisch verglichen werden müssen.
 * @return Die konfigurierte [JBTable].
 */
internal fun buildSortableTable(model: DefaultTableModel, numericColumns: Set<Int>): JBTable = JBTable(model).apply {
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
 *
 * @param model Das Tabellenmodell.
 * @param numericColumns Die Modellindizes der numerisch zu vergleichenden Spalten.
 * @return Der konfigurierte [TableRowSorter].
 */
internal fun buildRowSorter(model: DefaultTableModel, numericColumns: Set<Int>): TableRowSorter<DefaultTableModel> {
    val sorter = object : TableRowSorter<DefaultTableModel>(model) {
        /** Durchläuft aufsteigende, absteigende und ursprüngliche Reihenfolge. */
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

/** Vergleicht ganzzahlige Zellwerte numerisch, einschließlich der als Long gespeicherten TTL. */
private val numericCellComparator: Comparator<Any?> = Comparator { a, b ->
    (a as Number).toLong().compareTo((b as Number).toLong())
}

/**
 * Ein Diagnosedialog, der die aktuellen Inhalte des anwendungsweiten [VersionMetadataCache] anzeigt.
 *
 * Der Dialog erstellt beim Öffnen und nach dem Leeren des Caches frische Schnappschüsse einschließlich
 * verbleibender TTL; er aktualisiert sich nicht automatisch. Die Tabelle ist wie die Haupttabelle über
 * die Kopfzeile sortierbar (aufsteigend → absteigend → unsortiert) und zeigt denselben Sortier-Indikator.
 *
 * @param project Das aktuelle Projekt.
 * @property versionCache Der Versions-Zwischenspeicher (Standard: [VersionMetadataCache.getInstance]).
 */
internal class VersionCacheContentsDialog(
    project: Project,
    private val versionCache: VersionMetadataCache = VersionMetadataCache.getInstance()
) : DialogWrapper(project) {

    private var tableModel: DefaultTableModel? = null
    private var table: JBTable? = null

    init {
        title = MyMessageBundle.message("cache.contents.version.title")
        setOKButtonText(MyMessageBundle.message("button.close"))
        init()
    }

    /** Schließt den Dialog ohne Bestätigung; Invalidate wirkt unmittelbar im Inhaltsbereich. */
    override fun createActions(): Array<Action> = arrayOf(okAction)

    /**
     * Erstellt den zentralen Inhaltsbereich mit einer sortierbaren Tabelle für die Inhalte des
     * Versions-Zwischenspeichers.
     */
    public override fun createCenterPanel(): JComponent {
        val initialEntries = versionCache.snapshot()
        val createdTable = buildVersionTable(initialEntries)
        table = createdTable
        return panel {
            row {
                cell(JBScrollPane(createdTable)).align(Align.FILL)
            }.resizableRow()
            row {
                button(MyMessageBundle.message("cache.contents.invalidate")) { invalidateCache() }
                    .comment(MyMessageBundle.message("cache.contents.version.invalidate.comment"))
            }
            row {
                comment(MyMessageBundle.message("cache.contents.ttl.comment"))
            }
        }.apply {
            preferredSize = Dimension(860, 480)
        }
    }

    /** Leert den anwendungsweiten Versions-Zwischenspeicher und aktualisiert die Tabelle. */
    internal fun invalidateCache() {
        versionCache.clear()
        refreshTable()
    }

    /** Erneuert die Tabellenzeilen mit einem frischen Schnappschuss. */
    internal fun refreshTable(
        entries: List<VersionCacheEntrySnapshot> = versionCache.snapshot(),
        ttlMinutes: Int = MavenUpSettings.getInstance().state.versionCacheTtlMinutes,
        nowMillis: Long = System.currentTimeMillis()
    ) {
        val model = tableModel ?: return
        val currentTable = table ?: return
        populateVersionTableModel(model, entries, ttlMinutes, nowMillis)
        trimColumnWidthsToContent(currentTable)
    }

    /** Erstellt die Tabelle des [VersionMetadataCache] mit Rest-TTL zum injizierbaren Referenzzeitpunkt. */
    internal fun buildVersionTable(
        entries: List<VersionCacheEntrySnapshot>,
        ttlMinutes: Int = MavenUpSettings.getInstance().state.versionCacheTtlMinutes,
        nowMillis: Long = System.currentTimeMillis()
    ): JBTable {
        val model = object : DefaultTableModel() {
            /** Cache-Inhalte sind nicht direkt editierbar. */
            override fun isCellEditable(row: Int, column: Int): Boolean = false
        }.apply {
            addColumn(MyMessageBundle.message("cache.contents.version.groupId"))
            addColumn(MyMessageBundle.message("cache.contents.version.artifactId"))
            addColumn(MyMessageBundle.message("cache.contents.version.count"))
            addColumn(MyMessageBundle.message("cache.contents.version.queriedAt"))
            addColumn(MyMessageBundle.message("cache.contents.ttl"))
        }
        tableModel = model
        populateVersionTableModel(model, entries, ttlMinutes, nowMillis)
        val createdTable = buildSortableTable(model, numericColumns = setOf(VERSION_COUNT_COLUMN, VERSION_TTL_COLUMN))
        table = createdTable
        return createdTable
    }
}

/**
 * Ein Diagnosedialog, der die aktuellen Inhalte des anwendungsweiten [VulnerabilityResultCache] anzeigt.
 *
 * Der Dialog erstellt beim Öffnen und nach dem Leeren des Caches frische Schnappschüsse einschließlich
 * verbleibender TTL; er aktualisiert sich nicht automatisch. Die Tabelle ist wie die Haupttabelle über
 * die Kopfzeile sortierbar (aufsteigend → absteigend → unsortiert) und zeigt denselben Sortier-Indikator.
 *
 * @param project Das aktuelle Projekt.
 * @property vulnerabilityCache Der Vulnerability-Scan-Zwischenspeicher (Standard: [VulnerabilityResultCache.getInstance]).
 */
internal class VulnerabilityCacheContentsDialog(
    project: Project,
    private val vulnerabilityCache: VulnerabilityResultCache = VulnerabilityResultCache.getInstance()
) : DialogWrapper(project) {

    private var tableModel: DefaultTableModel? = null
    private var table: JBTable? = null

    init {
        title = MyMessageBundle.message("cache.contents.vulnerability.title")
        setOKButtonText(MyMessageBundle.message("button.close"))
        init()
    }

    /** Schließt den Dialog ohne Bestätigung; Invalidate wirkt unmittelbar im Inhaltsbereich. */
    override fun createActions(): Array<Action> = arrayOf(okAction)

    /**
     * Erstellt den zentralen Inhaltsbereich mit einer sortierbaren Tabelle für die Inhalte des
     * Vulnerability-Scan-Zwischenspeichers.
     */
    public override fun createCenterPanel(): JComponent {
        val initialEntries = vulnerabilityCache.snapshot()
        val createdTable = buildVulnerabilityTable(initialEntries)
        table = createdTable
        return panel {
            row {
                cell(JBScrollPane(createdTable)).align(Align.FILL)
            }.resizableRow()
            row {
                button(MyMessageBundle.message("cache.contents.invalidate")) { invalidateCache() }
                    .comment(MyMessageBundle.message("cache.contents.vulnerability.invalidate.comment"))
            }
            row {
                comment(MyMessageBundle.message("cache.contents.ttl.comment"))
            }
        }.apply {
            preferredSize = Dimension(860, 480)
        }
    }

    /** Leert den anwendungsweiten Vulnerability-Zwischenspeicher und aktualisiert die Tabelle. */
    internal fun invalidateCache() {
        vulnerabilityCache.clear()
        refreshTable()
    }

    /** Erneuert die Tabellenzeilen mit einem frischen Schnappschuss. */
    internal fun refreshTable(
        entries: List<VulnerabilityCacheEntrySnapshot> = vulnerabilityCache.snapshot(),
        ttlMinutes: Int = MavenUpSettings.getInstance().state.vulnerabilityCacheTtlMinutes,
        nowMillis: Long = System.currentTimeMillis()
    ) {
        val model = tableModel ?: return
        val currentTable = table ?: return
        populateVulnerabilityTableModel(model, entries, ttlMinutes, nowMillis)
        trimColumnWidthsToContent(currentTable)
    }

    /** Erstellt die Tabelle des [VulnerabilityResultCache] mit Rest-TTL zum injizierbaren Referenzzeitpunkt. */
    internal fun buildVulnerabilityTable(
        entries: List<VulnerabilityCacheEntrySnapshot>,
        ttlMinutes: Int = MavenUpSettings.getInstance().state.vulnerabilityCacheTtlMinutes,
        nowMillis: Long = System.currentTimeMillis()
    ): JBTable {
        val model = object : DefaultTableModel() {
            /** Cache-Inhalte sind nicht direkt editierbar. */
            override fun isCellEditable(row: Int, column: Int): Boolean = false
        }.apply {
            addColumn(MyMessageBundle.message("cache.contents.vulnerability.coordinate"))
            addColumn(MyMessageBundle.message("cache.contents.vulnerability.count"))
            addColumn(MyMessageBundle.message("cache.contents.vulnerability.queriedAt"))
            addColumn(MyMessageBundle.message("cache.contents.ttl"))
        }
        tableModel = model
        populateVulnerabilityTableModel(model, entries, ttlMinutes, nowMillis)
        val createdTable = buildSortableTable(
            model,
            numericColumns = setOf(VULNERABILITY_COUNT_COLUMN, VULNERABILITY_TTL_COLUMN)
        )
        table = createdTable
        return createdTable
    }
}
