package de.schwarzland.mavenup.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.table.JBTable
import javax.swing.table.DefaultTableModel

/**
 * Tests für [trimColumnWidthsToContent].
 */
class TableColumnWidthsTest : BasePlatformTestCase() {

    /**
     * Prüft, dass sehr lange Zelleninhalte auf die Höchstbreite begrenzt und kurze Inhalte nicht
     * unter die Mindestbreite gedrückt werden.
     */
    fun testTrimClampsToMinAndMax() {
        val table = JBTable(model(longValue = "x".repeat(500), shortValue = "a"))

        trimColumnWidthsToContent(table)

        val longWidth = table.columnModel.getColumn(0).preferredWidth
        val shortWidth = table.columnModel.getColumn(1).preferredWidth
        assertTrue("Lange Inhalte sollten auf die Höchstbreite begrenzt werden", longWidth <= DEFAULT_MAX_COLUMN_WIDTH)
        assertTrue("Kurze Inhalte sollten die Mindestbreite halten", shortWidth >= DEFAULT_MIN_COLUMN_WIDTH)
        assertTrue("Eine Spalte mit langem Inhalt sollte breiter sein als eine mit kurzem Inhalt",
            longWidth > shortWidth)
    }

    /**
     * Prüft, dass benutzerdefinierte Grenzen (Mindest-/Höchstbreite) angewendet werden.
     */
    fun testTrimRespectsCustomBounds() {
        val table = JBTable(model(longValue = "x".repeat(500), shortValue = "a"))

        trimColumnWidthsToContent(table, padding = 0, minWidth = 120, maxWidth = 200)

        assertEquals(200, table.columnModel.getColumn(0).preferredWidth)
        assertEquals(120, table.columnModel.getColumn(1).preferredWidth)
    }

    /**
     * Erstellt ein Tabellenmodell mit zwei Spalten und einer Zeile für die Breitenberechnung.
     */
    private fun model(longValue: String, shortValue: String): DefaultTableModel =
        object : DefaultTableModel() {
            override fun isCellEditable(row: Int, column: Int): Boolean = false
        }.apply {
            addColumn("long")
            addColumn("short")
            addRow(arrayOf(longValue, shortValue))
        }
}
