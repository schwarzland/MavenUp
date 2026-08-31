package de.schwarzland.mavenup.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.JBColor
import java.awt.Font
import javax.swing.JLabel
import javax.swing.JTable
import javax.swing.table.DefaultTableModel

/**
 * Testet die Kennzeichnung von Abhängigkeiten, deren Version nicht in der `pom.xml` deklariert,
 * sondern vom Parent-POM oder einem importierten BOM geerbt wird.
 */
class InheritedVersionUiTest : BasePlatformTestCase() {

    /**
     * Erzeugt eine Tabelle mit den Spalten der Abhängigkeitstabelle und einer einzelnen Zeile.
     *
     * @param groupId Die GroupId der Zeile.
     * @param artifactId Die ArtifactId der Zeile.
     * @param version Die aktuelle Version der Zeile.
     * @return Die befüllte Tabelle.
     */
    private fun tableWithRow(groupId: String, artifactId: String, version: String): JTable {
        val model = DefaultTableModel()
        repeat(NEW_VERSION_COLUMN + 1) { model.addColumn(it.toString()) }
        model.addRow(arrayOf<Any?>(groupId, artifactId, "", "dependency", null, version, emptyList<String>()))
        return JTable(model)
    }

    fun testInheritedVersionCellTextAddsMarker() {
        assertEquals("1.2.3 (inherited)", inheritedVersionCellText("1.2.3", true))
    }

    fun testInheritedVersionCellTextLeavesDeclaredVersionUnchanged() {
        assertEquals("1.2.3", inheritedVersionCellText("1.2.3", false))
    }

    fun testInheritedVersionCellTextHandlesEmptyVersion() {
        assertEquals(" (inherited)", inheritedVersionCellText("", true))
        assertEquals("", inheritedVersionCellText("", false))
    }

    fun testInheritedVersionTooltipReturnsTextOnlyWhenInherited() {
        assertNotNull(inheritedVersionTooltip(true))
        assertNull(inheritedVersionTooltip(false))
    }

    fun testRendererMarksInheritedRow() {
        val table = tableWithRow("com.example", "core", "1.2.3")
        val renderer = createCurrentVersionRenderer(setOf("com.example:core"))

        val label = renderer.getTableCellRendererComponent(
            table, "1.2.3", false, false, 0, CURRENT_VERSION_COLUMN
        ) as JLabel

        assertEquals("1.2.3 (inherited)", label.text)
        assertTrue("Geerbte Versionen werden kursiv dargestellt", label.font.isItalic)
        assertEquals(JBColor.GRAY, label.foreground)
        assertNotNull(label.toolTipText)
    }

    fun testRendererDoesNotLeakInheritedColorToFollowingRows() {
        val model = DefaultTableModel()
        repeat(NEW_VERSION_COLUMN + 1) { model.addColumn(it.toString()) }
        model.addRow(arrayOf<Any?>("com.example", "inherited", "", "dependency", null, "1.0.0", emptyList<String>()))
        model.addRow(arrayOf<Any?>("com.example", "declared", "", "dependency", null, "2.0.0", emptyList<String>()))
        val table = JTable(model)
        val renderer = createCurrentVersionRenderer(setOf("com.example:inherited"))

        renderer.getTableCellRendererComponent(table, "1.0.0", false, false, 0, CURRENT_VERSION_COLUMN)
        val second = renderer.getTableCellRendererComponent(
            table, "2.0.0", false, false, 1, CURRENT_VERSION_COLUMN
        ) as JLabel

        assertEquals("2.0.0", second.text)
        assertFalse(second.font.isItalic)
        assertEquals(table.foreground, second.foreground)
    }

    fun testRendererLeavesDeclaredRowUnchanged() {
        val table = tableWithRow("com.example", "core", "1.2.3")
        val renderer = createCurrentVersionRenderer(setOf("com.other:lib"))

        val label = renderer.getTableCellRendererComponent(
            table, "1.2.3", false, false, 0, CURRENT_VERSION_COLUMN
        ) as JLabel

        assertEquals("1.2.3", label.text)
        assertFalse(label.font.isItalic)
        assertNull(label.toolTipText)
    }

    fun testRendererHandlesEmptyKeySetAndInvalidRow() {
        val table = tableWithRow("com.example", "core", "1.2.3")
        val renderer = createCurrentVersionRenderer(emptySet())

        val label = renderer.getTableCellRendererComponent(
            table, "1.2.3", false, false, 0, CURRENT_VERSION_COLUMN
        ) as JLabel

        assertEquals("1.2.3", label.text)
        assertEquals(Font.PLAIN, label.font.style)
    }

    fun testRendererReactsToLaterAddedKeys() {
        val table = tableWithRow("com.example", "core", "1.2.3")
        val inheritedKeys = mutableSetOf<String>()
        val renderer = createCurrentVersionRenderer(inheritedKeys)

        inheritedKeys.add("com.example:core")
        val label = renderer.getTableCellRendererComponent(
            table, "1.2.3", false, false, 0, CURRENT_VERSION_COLUMN
        ) as JLabel

        assertEquals("1.2.3 (inherited)", label.text)
    }
}
