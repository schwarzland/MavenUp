package de.schwarzland.mavenup.ui

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.UIUtil
import de.schwarzland.mavenup.service.VersionCacheEntrySnapshot
import de.schwarzland.mavenup.service.VersionMetadataCache
import de.schwarzland.mavenup.service.VulnerabilityCacheEntrySnapshot
import de.schwarzland.mavenup.service.VulnerabilityResultCache
import javax.swing.JButton
import javax.swing.SortOrder
import javax.swing.table.DefaultTableModel
import javax.swing.table.TableRowSorter

/**
 * Tests für [VersionCacheContentsDialog] und [VulnerabilityCacheContentsDialog].
 */
class CacheContentsDialogTest : BasePlatformTestCase() {

    /**
     * Prüft, dass [formatCacheTimestamp] chronologisch sortierbare, zweistellig gepolsterte Werte liefert.
     */
    fun testFormatCacheTimestampIsSortableAsPlainText() {
        val earlier = formatCacheTimestamp(0L)
        val later = formatCacheTimestamp(60_000L)

        assertTrue("Ein späterer Zeitpunkt muss lexikografisch nach einem früheren sortiert werden", later > earlier)
    }

    /**
     * Prüft, dass die Vulnerability-Cache-Tabelle alle Einträge alphabetisch nach Koordinate sortiert
     * mit den richtigen Spaltenwerten befüllt.
     */
    fun testBuildVulnerabilityTablePopulatesRowsSortedByCoordinate() {
        val dialog = VulnerabilityCacheContentsDialog(project)
        val entries = listOf(
            VulnerabilityCacheEntrySnapshot("g:b:2.0.0", 0, 222L),
            VulnerabilityCacheEntrySnapshot("g:a:1.0.0", 2, 111L)
        )

        val table = dialog.buildVulnerabilityTable(entries, ttlMinutes = 1, nowMillis = 1_111L)

        assertEquals(2, table.rowCount)
        assertEquals(4, table.columnCount)
        assertEquals("TTL (s)", table.getColumnName(3))
        assertEquals(59L, table.getValueAt(0, 3))
        assertEquals(60L, table.getValueAt(1, 3))
        assertFalse(table.isCellEditable(0, 3))
        assertEquals("g:a:1.0.0", table.getValueAt(0, 0))
        assertEquals(2, table.getValueAt(0, 1))
        assertEquals("g:b:2.0.0", table.getValueAt(1, 0))
        assertEquals(0, table.getValueAt(1, 1))
    }

    /**
     * Prüft, dass die Version-Cache-Tabelle alle Einträge nach GroupId und ArtifactId sortiert mit den
     * richtigen Spaltenwerten befüllt.
     */
    fun testBuildVersionTablePopulatesRowsSortedByGroupAndArtifact() {
        val dialog = VersionCacheContentsDialog(project)
        val entries = listOf(
            VersionCacheEntrySnapshot("com.example", "b-lib", 1, 222L),
            VersionCacheEntrySnapshot("com.example", "a-lib", 3, 111L)
        )

        val table = dialog.buildVersionTable(entries, ttlMinutes = 2, nowMillis = 1_111L)

        assertEquals(2, table.rowCount)
        assertEquals(5, table.columnCount)
        assertEquals("TTL (s)", table.getColumnName(4))
        assertEquals(119L, table.getValueAt(0, 4))
        assertEquals(120L, table.getValueAt(1, 4))
        assertFalse(table.isCellEditable(0, 4))
        assertEquals("a-lib", table.getValueAt(0, 1))
        assertEquals(3, table.getValueAt(0, 2))
        assertEquals("b-lib", table.getValueAt(1, 1))
        assertEquals(1, table.getValueAt(1, 2))
    }

    /**
     * Prüft, dass beide Tabellen keine Mehrfachselektion und kein Umordnen der Spalten erlauben.
     */
    fun testTablesUseSingleSelectionAndFixedColumnOrder() {
        val vulnDialog = VulnerabilityCacheContentsDialog(project)
        val versionDialog = VersionCacheContentsDialog(project)

        val vulnTable = vulnDialog.buildVulnerabilityTable(emptyList())
        val versionTable = versionDialog.buildVersionTable(emptyList())

        assertEquals(javax.swing.ListSelectionModel.SINGLE_SELECTION, vulnTable.selectionModel.selectionMode)
        assertFalse(vulnTable.tableHeader.reorderingAllowed)
        assertEquals(javax.swing.ListSelectionModel.SINGLE_SELECTION, versionTable.selectionModel.selectionMode)
        assertFalse(versionTable.tableHeader.reorderingAllowed)
    }

    /**
     * Prüft, dass die Anzahl-Spalte numerisch statt alphabetisch sortiert wird (z. B. `2` vor `10`).
     */
    fun testCountColumnSortsNumerically() {
        val vulnDialog = VulnerabilityCacheContentsDialog(project)
        val vulnEntries = listOf(
            VulnerabilityCacheEntrySnapshot("g:a:1.0.0", 2, 0L),
            VulnerabilityCacheEntrySnapshot("g:b:1.0.0", 10, 0L)
        )

        val vulnTable = vulnDialog.buildVulnerabilityTable(vulnEntries)
        @Suppress("UNCHECKED_CAST")
        val vulnSorter = vulnTable.rowSorter as TableRowSorter<DefaultTableModel>
        vulnSorter.toggleSortOrder(1)
        assertEquals(SortOrder.ASCENDING, vulnSorter.sortKeys.first().sortOrder)
        assertEquals(listOf(2, 10), (0 until vulnTable.rowCount).map { vulnTable.getValueAt(it, 1) })

        val versionDialog = VersionCacheContentsDialog(project)
        val versionEntries = listOf(
            VersionCacheEntrySnapshot("g", "a", 2, 0L),
            VersionCacheEntrySnapshot("g", "b", 10, 0L)
        )

        val versionTable = versionDialog.buildVersionTable(versionEntries)
        @Suppress("UNCHECKED_CAST")
        val versionSorter = versionTable.rowSorter as TableRowSorter<DefaultTableModel>
        versionSorter.toggleSortOrder(2)
        assertEquals(SortOrder.ASCENDING, versionSorter.sortKeys.first().sortOrder)
        assertEquals(listOf(2, 10), (0 until versionTable.rowCount).map { versionTable.getValueAt(it, 2) })
    }

    /**
     * Prüft, dass beide Tabellen bei leerem Zwischenspeicher ohne Zeilen erstellt werden.
     */
    fun testDialogBuildsEmptyTablesWhenCachesAreEmpty() {
        VulnerabilityResultCache.getInstance().clear()
        VersionMetadataCache.getInstance().clear()
        val vulnDialog = VulnerabilityCacheContentsDialog(project)
        val versionDialog = VersionCacheContentsDialog(project)

        assertTrue(vulnDialog.buildVulnerabilityTable(emptyList()).rowCount == 0)
        assertTrue(versionDialog.buildVersionTable(emptyList()).rowCount == 0)
    }

    /** Prüft Restlaufzeit, Rundung, Ablauf, deaktiviertes Caching und große TTL-Werte. */
    fun testRemainingCacheTtlSecondsHandlesBoundaries() {
        assertEquals(60L, remainingCacheTtlSeconds(0L, 1, 0L))
        assertEquals(60L, remainingCacheTtlSeconds(0L, 1, 1L))
        assertEquals(1L, remainingCacheTtlSeconds(0L, 1, 59_999L))
        assertEquals(0L, remainingCacheTtlSeconds(0L, 1, 60_000L))
        assertEquals(0L, remainingCacheTtlSeconds(0L, 1, 60_001L))
        assertEquals(0L, remainingCacheTtlSeconds(0L, 0, 0L))
        assertEquals(0L, remainingCacheTtlSeconds(0L, -1, 0L))
        assertEquals(60L, remainingCacheTtlSeconds(1_000L, 1, 0L))
        assertEquals(Int.MAX_VALUE * 60L, remainingCacheTtlSeconds(0L, Int.MAX_VALUE, 0L))
    }

    /** Prüft die numerische TTL-Sortierung und den vollständigen Sortierzyklus beider Tabellen. */
    fun testTtlColumnsSortNumerically() {
        val vulnDialog = VulnerabilityCacheContentsDialog(project)
        val versionDialog = VersionCacheContentsDialog(project)

        val vulnerabilityTable = vulnDialog.buildVulnerabilityTable(
            listOf(
                VulnerabilityCacheEntrySnapshot("g:a:1", 0, 10_000L),
                VulnerabilityCacheEntrySnapshot("g:b:1", 0, 2_000L)
            ), ttlMinutes = 1, nowMillis = 60_000L
        )
        val versionTable = versionDialog.buildVersionTable(
            listOf(
                VersionCacheEntrySnapshot("g", "a", 1, 10_000L),
                VersionCacheEntrySnapshot("g", "b", 1, 2_000L)
            ), ttlMinutes = 1, nowMillis = 60_000L
        )
        for (table in listOf(vulnerabilityTable, versionTable)) {
            val column = table.columnCount - 1
            table.rowSorter.toggleSortOrder(column)
            assertEquals(listOf(2L, 10L), (0 until table.rowCount).map { table.getValueAt(it, column) })
            table.rowSorter.toggleSortOrder(column)
            assertEquals(listOf(10L, 2L), (0 until table.rowCount).map { table.getValueAt(it, column) })
            table.rowSorter.toggleSortOrder(column)
            assertTrue(table.rowSorter.sortKeys.isEmpty())
        }
    }

    /** Prüft das Leeren des Versions-Caches und die Aktualisierung der Tabelle im Dialog. */
    fun testInvalidateClearsVersionCacheAndRefreshesDisplay() {
        val versions = VersionMetadataCache()
        versions.getOrFetch("g", "a", 60) { listOf("1") }
        val dialog = VersionCacheContentsDialog(project, versions)
        Disposer.register(testRootDisposable, dialog.disposable)
        val content = dialog.createCenterPanel()
        val invalidate = UIUtil.uiTraverser(content).filter(JButton::class.java)
            .first { it.text == MyMessageBundle.message("cache.contents.invalidate") }

        val table = UIUtil.uiTraverser(content).filter(JBTable::class.java).first()!!
        assertEquals(1, table.rowCount)
        assertEquals(1, versions.size())

        invalidate.doClick()
        assertEquals(0, versions.size())
        assertEquals(0, table.rowCount)

        // Erneuter Klick auf leerem Cache bleibt bei 0
        invalidate.doClick()
        assertEquals(0, versions.size())
        assertEquals(0, table.rowCount)
    }

    /** Prüft das Leeren des Vulnerability-Caches und die Aktualisierung der Tabelle im Dialog. */
    fun testInvalidateClearsVulnerabilityCacheAndRefreshesDisplay() {
        val vulnerabilities = VulnerabilityResultCache()
        vulnerabilities.put("g:a:1", emptyList())
        val dialog = VulnerabilityCacheContentsDialog(project, vulnerabilities)
        Disposer.register(testRootDisposable, dialog.disposable)
        val content = dialog.createCenterPanel()
        val invalidate = UIUtil.uiTraverser(content).filter(JButton::class.java)
            .first { it.text == MyMessageBundle.message("cache.contents.invalidate") }

        val table = UIUtil.uiTraverser(content).filter(JBTable::class.java).first()!!
        assertEquals(1, table.rowCount)
        assertEquals(1, vulnerabilities.size())

        invalidate.doClick()
        assertEquals(0, vulnerabilities.size())
        assertEquals(0, table.rowCount)

        // Erneuter Klick auf leerem Cache bleibt bei 0
        invalidate.doClick()
        assertEquals(0, vulnerabilities.size())
        assertEquals(0, table.rowCount)
    }
}
