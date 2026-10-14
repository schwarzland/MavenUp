package de.schwarzland.mavenup.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.table.JBTable
import de.schwarzland.mavenup.service.MavenUpSettings
import java.awt.Container
import java.awt.event.MouseEvent
import javax.swing.RowFilter
import javax.swing.table.DefaultTableModel
import javax.swing.table.TableRowSorter

/** Prueft echte Maus-Tooltips bei adaptiven Spalten sowie sortierten und gefilterten Zeilen. */
class DependencyTableTooltipTest : BasePlatformTestCase() {

    /** Versionsstatus und Property-Navigation bleiben beim Ein- und Ausblenden der Modulspalte korrekt. */
    fun testTooltipsWithHiddenAndVisibleModuleColumn() {
        val window = MavenUpWindowFactory().MyToolWindow(project)
        val table = dependencyTable(window.getContent())
        val model = table.model as DefaultTableModel
        model.addRow(arrayOf<Any?>("org.test", "library", "module", "lib.version", "dependency", null,
            "1.0.0", listOf("2.0.0", "1.0.0")))
        window.selectedVersions["org.test:library"] = "2.0.0"

        listOf(false, true, false).forEach { moduleVisible ->
            window.updateModuleColumnVisibility(moduleVisible)
            assertEquals(if (moduleVisible) 8 else 7, table.columnCount)
            assertEquals(versionStatusTooltip("1.0.0", "2.0.0", "2.0.0"),
                tooltip(table, 0, NEW_VERSION_COLUMN))
            assertEquals(navigationMessage("property"), tooltip(table, 0, PROPERTY_COLUMN))
            assertEquals(navigationMessage("row"), tooltip(table, 0, TYPE_COLUMN))
            assertEquals(navigationMessage("row"), tooltip(table, 0, CURRENT_VERSION_COLUMN))
        }
    }

    /** Die Ansichtssortierung und Filterung aendern nicht die verwendeten Modellwerte. */
    fun testTooltipsFollowSortedAndFilteredRows() {
        val window = MavenUpWindowFactory().MyToolWindow(project)
        val table = dependencyTable(window.getContent())
        val model = table.model as DefaultTableModel
        model.addRow(arrayOf<Any?>("org.z", "last", "", "", "dependency", null,
            "3.0.0", listOf("4.0.0")))
        model.addRow(arrayOf<Any?>("org.a", "first", "", "first.version", "dependency", null,
            "1.0.0", listOf("2.0.0")))
        val sorter = table.rowSorter as TableRowSorter<*>
        sorter.toggleSortOrder(GROUP_ID_COLUMN)
        assertEquals(1, table.convertRowIndexToModel(0))

        listOf(false, true).forEach { moduleVisible ->
            window.updateModuleColumnVisibility(moduleVisible)
            assertEquals(versionStatusTooltip("1.0.0", "1.0.0", "2.0.0"),
                tooltip(table, 0, NEW_VERSION_COLUMN))
            assertEquals(navigationMessage("property"), tooltip(table, 0, PROPERTY_COLUMN))
            assertEquals(navigationMessage("row"), tooltip(table, 1, PROPERTY_COLUMN))
        }

        sorter.rowFilter = RowFilter.regexFilter("org.z", GROUP_ID_COLUMN)
        assertEquals(1, table.rowCount)
        assertEquals(0, table.convertRowIndexToModel(0))
        window.updateModuleColumnVisibility(false)
        assertEquals(versionStatusTooltip("3.0.0", "3.0.0", "4.0.0"),
            tooltip(table, 0, NEW_VERSION_COLUMN))
        assertEquals(navigationMessage("row"), tooltip(table, 0, PROPERTY_COLUMN))
    }

    /** Der Fallback fuer geerbte Versionen liest Koordinaten aus der richtigen Modellzeile. */
    fun testInheritedVersionTooltipFollowsSortedAndFilteredRows() {
        val window = MavenUpWindowFactory().MyToolWindow(project)
        val table = dependencyTable(window.getContent())
        val model = table.model as DefaultTableModel
        model.addRow(arrayOf<Any?>("org.z", "declared", "", null, "dependency", null,
            "3.0.0", emptyList<String>()))
        model.addRow(arrayOf<Any?>("org.a", "inherited", "", null, "dependency", null,
            "1.0.0", emptyList<String>()))
        window.inheritedVersionDependencies.add("org.a:inherited")
        val sorter = table.rowSorter as TableRowSorter<*>
        sorter.toggleSortOrder(GROUP_ID_COLUMN)

        listOf(false, true).forEach { moduleVisible ->
            window.updateModuleColumnVisibility(moduleVisible)
            assertEquals(inheritedVersionTooltip(true), tooltip(table, 0, CURRENT_VERSION_COLUMN))
            assertEquals(navigationMessage("row"), tooltip(table, 1, CURRENT_VERSION_COLUMN))
        }
        sorter.rowFilter = RowFilter.regexFilter("org.a", GROUP_ID_COLUMN)
        assertEquals(inheritedVersionTooltip(true), tooltip(table, 0, CURRENT_VERSION_COLUMN))
    }

    /** Ohne Versionskandidaten wird kein Versionsstatus erfunden. */
    fun testNewVersionTooltipWithoutCandidates() {
        val window = MavenUpWindowFactory().MyToolWindow(project)
        val table = dependencyTable(window.getContent())
        val model = table.model as DefaultTableModel
        listOf(emptyList<String>(), null).forEach { versions ->
            model.addRow(arrayOf<Any?>("org.test", "library", "", null, "dependency", null, "1.0.0", versions))
        }
        listOf(false, true).forEach { moduleVisible ->
            window.updateModuleColumnVisibility(moduleVisible)
            assertNull(tooltip(table, 0, NEW_VERSION_COLUMN))
            assertNull(tooltip(table, 1, NEW_VERSION_COLUMN))
        }
    }

    /** Ereignisse ausserhalb einer Zelle und in einer leeren Tabelle besitzen keinen Tooltip. */
    fun testMouseOutsideCellsHasNoTooltip() {
        val window = MavenUpWindowFactory().MyToolWindow(project)
        val table = dependencyTable(window.getContent())
        assertNull(table.getToolTipText(mouseEvent(table, 1, 1)))
        (table.model as DefaultTableModel).addRow(arrayOf<Any?>("org.test", "library", "", null,
            "dependency", null, "1.0.0", listOf("2.0.0")))
        assertNull(table.getToolTipText(mouseEvent(table, -1, 1)))
        assertNull(table.getToolTipText(mouseEvent(table, 1, table.rowHeight + 1)))
        val tableWidth = (0 until table.columnCount).sumOf { table.columnModel.getColumn(it).width }
        assertNull(table.getToolTipText(mouseEvent(table, tableWidth + 1, 1)))
    }

    /** Beide konfigurierbaren Klickarten liefern den zur Modellspalte passenden Hinweis. */
    fun testNavigationTooltipUsesConfiguredClickMode() {
        val settings = MavenUpSettings.getInstance().state
        val original = settings.jumpOnSingleClick
        val window = MavenUpWindowFactory().MyToolWindow(project)
        val table = dependencyTable(window.getContent())
        (table.model as DefaultTableModel).addRow(arrayOf<Any?>("org.test", "library", "", "lib.version",
            "dependency", null, "1.0.0", emptyList<String>()))
        try {
            listOf(false, true).forEach { singleClick ->
                settings.jumpOnSingleClick = singleClick
                assertEquals(navigationMessage("property"), tooltip(table, 0, PROPERTY_COLUMN))
                assertEquals(navigationMessage("row"), tooltip(table, 0, TYPE_COLUMN))
            }
        } finally {
            settings.jumpOnSingleClick = original
        }
    }

    /** Findet die Haupttabelle im Tool-Window-Inhalt ohne Reflection. */
    private fun dependencyTable(container: Container): JBTable =
        findTable(container) ?: error("Dependency table not found")

    /** Sucht rekursiv nach der ersten Tabelle im angegebenen Teilbaum. */
    private fun findTable(container: Container): JBTable? {
        if (container is JBTable) return container
        return container.components.filterIsInstance<Container>().firstNotNullOfOrNull(::findTable)
    }

    /** Fragt den echten Zell-Tooltip anhand der Ansichtsspalte des Modellindex ab. */
    private fun tooltip(table: JBTable, viewRow: Int, modelColumn: Int): String? {
        val viewColumn = table.convertColumnIndexToView(modelColumn)
        check(viewColumn >= 0)
        val cell = table.getCellRect(viewRow, viewColumn, true)
        return table.getToolTipText(mouseEvent(table, cell.x + cell.width / 2, cell.y + cell.height / 2))
    }

    /** Erzeugt ein Mausereignis fuer die Tooltip-Abfrage. */
    private fun mouseEvent(table: JBTable, x: Int, y: Int): MouseEvent =
        MouseEvent(table, MouseEvent.MOUSE_MOVED, 0L, 0, x, y, 0, false)

    /** Liefert den lokalisierten Navigationshinweis fuer die aktuelle Klickkonfiguration. */
    private fun navigationMessage(target: String): String {
        val clickType = if (MavenUpSettings.getInstance().state.jumpOnSingleClick) "singleClick" else "doubleClick"
        return MyMessageBundle.message("toolwindow.MyToolWindow.table.$target.tooltip.$clickType")
    }
}
