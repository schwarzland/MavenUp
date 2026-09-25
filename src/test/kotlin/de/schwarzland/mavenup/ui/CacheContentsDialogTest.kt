package de.schwarzland.mavenup.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.schwarzland.mavenup.service.VersionCacheEntrySnapshot
import de.schwarzland.mavenup.service.VersionMetadataCache
import de.schwarzland.mavenup.service.VulnerabilityCacheEntrySnapshot
import de.schwarzland.mavenup.service.VulnerabilityResultCache
import javax.swing.SortOrder
import javax.swing.table.DefaultTableModel
import javax.swing.table.TableRowSorter

/**
 * Tests für den [CacheContentsDialog].
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
        val dialog = CacheContentsDialog(project)
        val entries = listOf(
            VulnerabilityCacheEntrySnapshot("g:b:2.0.0", 0, 222L),
            VulnerabilityCacheEntrySnapshot("g:a:1.0.0", 2, 111L)
        )

        val table = dialog.buildVulnerabilityTable(entries)

        assertEquals(2, table.rowCount)
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
        val dialog = CacheContentsDialog(project)
        val entries = listOf(
            VersionCacheEntrySnapshot("com.example", "b-lib", 1, 222L),
            VersionCacheEntrySnapshot("com.example", "a-lib", 3, 111L)
        )

        val table = dialog.buildVersionTable(entries)

        assertEquals(2, table.rowCount)
        assertEquals("a-lib", table.getValueAt(0, 1))
        assertEquals(3, table.getValueAt(0, 2))
        assertEquals("b-lib", table.getValueAt(1, 1))
        assertEquals(1, table.getValueAt(1, 2))
    }

    /**
     * Prüft, dass beide Tabellen keine Mehrfachselektion und kein Umordnen der Spalten erlauben.
     */
    fun testTablesUseSingleSelectionAndFixedColumnOrder() {
        val dialog = CacheContentsDialog(project)

        val table = dialog.buildVulnerabilityTable(emptyList())

        assertEquals(javax.swing.ListSelectionModel.SINGLE_SELECTION, table.selectionModel.selectionMode)
        assertFalse(table.tableHeader.reorderingAllowed)
    }

    /**
     * Prüft, dass die Anzahl-Spalte numerisch statt alphabetisch sortiert wird (z. B. `2` vor `10`).
     */
    fun testCountColumnSortsNumerically() {
        val dialog = CacheContentsDialog(project)
        val entries = listOf(
            VulnerabilityCacheEntrySnapshot("g:a:1.0.0", 2, 0L),
            VulnerabilityCacheEntrySnapshot("g:b:1.0.0", 10, 0L)
        )

        val table = dialog.buildVulnerabilityTable(entries)
        @Suppress("UNCHECKED_CAST")
        val sorter = table.rowSorter as TableRowSorter<DefaultTableModel>
        sorter.toggleSortOrder(1)
        assertEquals(SortOrder.ASCENDING, sorter.sortKeys.first().sortOrder)

        assertEquals(listOf(2, 10), (0 until table.rowCount).map { table.getValueAt(it, 1) })
    }

    /**
     * Prüft, dass beide Tabellen bei leerem Zwischenspeicher ohne Zeilen erstellt werden.
     */
    fun testDialogBuildsEmptyTablesWhenCachesAreEmpty() {
        VulnerabilityResultCache.getInstance().clear()
        VersionMetadataCache.getInstance().clear()
        val dialog = CacheContentsDialog(project)

        assertTrue(dialog.buildVulnerabilityTable(emptyList()).rowCount == 0)
        assertTrue(dialog.buildVersionTable(emptyList()).rowCount == 0)
    }
}
