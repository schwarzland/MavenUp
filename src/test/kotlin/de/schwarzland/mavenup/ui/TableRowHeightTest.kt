package de.schwarzland.mavenup.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import javax.swing.table.DefaultTableModel

/**
 * Tests für [recommendedTableRowHeight] und [applyRecommendedRowHeight].
 */
class TableRowHeightTest : BasePlatformTestCase() {

    /**
     * Prüft, dass die empfohlene Zeilenhöhe dem skalierten Styleguide-Wert entspricht.
     */
    fun testRecommendedRowHeightIsScaledStyleguideValue() {
        assertEquals(JBUI.scale(RECOMMENDED_TABLE_ROW_HEIGHT), recommendedTableRowHeight())
    }

    /**
     * Prüft, dass die Zeilenhöhe unabhängig von der Höhe der Renderer-Komponenten gesetzt wird.
     */
    fun testApplySetsRowHeightRegardlessOfContent() {
        val table = JBTable(model())

        applyRecommendedRowHeight(table)

        assertEquals(recommendedTableRowHeight(), table.rowHeight)
    }

    /**
     * Prüft, dass zwei unterschiedlich befüllte Tabellen nach dem Anwenden dieselbe Zeilenhöhe haben.
     */
    fun testAllTablesShareTheSameRowHeight() {
        val emptyTable = JBTable(DefaultTableModel())
        val filledTable = JBTable(model())

        applyRecommendedRowHeight(emptyTable)
        applyRecommendedRowHeight(filledTable)

        assertEquals(emptyTable.rowHeight, filledTable.rowHeight)
    }

    /**
     * Erstellt ein einfaches Tabellenmodell mit einer Spalte und einer Zeile.
     */
    private fun model(): DefaultTableModel =
        object : DefaultTableModel() {
            override fun isCellEditable(row: Int, column: Int): Boolean = false
        }.apply {
            addColumn("value")
            addRow(arrayOf("content"))
        }
}
