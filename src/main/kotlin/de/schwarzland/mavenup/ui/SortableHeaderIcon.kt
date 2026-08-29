package de.schwarzland.mavenup.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.util.IconLoader
import com.intellij.ui.table.JBTable
import javax.swing.Icon
import javax.swing.JLabel
import javax.swing.SortOrder
import javax.swing.SwingConstants
import javax.swing.table.TableCellRenderer
import javax.swing.table.TableRowSorter

/**
 * Liefert das Icon, das den Sortierzustand einer Tabellenspalte in der Kopfzeile anzeigt.
 *
 * Für sortierbare, aber aktuell unsortierte Spalten wird ein gedämpfter Doppelpfeil zurückgegeben,
 * der andeutet, dass die Spalte per Klick sortiert werden kann. Ist eine Sortierrichtung aktiv,
 * wird der entsprechende Auf- bzw. Ab-Pfeil verwendet. Nicht sortierbare Spalten erhalten kein Icon.
 *
 * @param sortable Ob die Spalte grundsätzlich sortierbar ist.
 * @param sortOrder Aktuelle Sortierrichtung der Spalte oder `null`/[SortOrder.UNSORTED], wenn sie nicht sortiert ist.
 * @return Das anzuzeigende [Icon] oder `null`, wenn die Spalte nicht sortierbar ist.
 */
internal fun sortableHeaderIcon(sortable: Boolean, sortOrder: SortOrder?): Icon? {
    if (!sortable) return null
    return when (sortOrder) {
        SortOrder.ASCENDING -> AllIcons.General.ArrowUp
        SortOrder.DESCENDING -> AllIcons.General.ArrowDown
        else -> IconLoader.getDisabledIcon(AllIcons.General.ArrowSplitCenterV)
    }
}

/**
 * Installiert einen Kopfzeilen-Renderer, der die Sortierbarkeit der Spalten sichtbar macht.
 *
 * Sortierbare Spalten erhalten ein rechtsbündiges Icon: einen gedämpften Doppelpfeil im
 * unsortierten Zustand sowie einen Auf-/Ab-Pfeil bei aktiver Sortierung. Nicht sortierbare
 * Spalten bleiben ohne Icon. Der ursprüngliche Renderer wird für das Look-and-Feel-konforme
 * Aussehen der Kopfzeile weiterverwendet. Diese Regel gilt einheitlich für alle Plugin-Tabellen.
 *
 * @param table Die Tabelle, deren Kopfzeile den Sortier-Indikator anzeigen soll.
 */
internal fun installSortableHeaderRenderer(table: JBTable) {
    val originalRenderer = table.tableHeader.defaultRenderer
    table.tableHeader.defaultRenderer = TableCellRenderer { tbl, value, isSelected, hasFocus, row, column ->
        val component = originalRenderer.getTableCellRendererComponent(tbl, value, isSelected, hasFocus, row, column)
        if (component is JLabel) {
            val modelColumn = tbl.convertColumnIndexToModel(column)
            val sorter = tbl.rowSorter
            val sortable = sorter is TableRowSorter<*> && sorter.isSortable(modelColumn)
            val sortOrder = sorter?.sortKeys?.firstOrNull { it.column == modelColumn }?.sortOrder
            component.icon = sortableHeaderIcon(sortable, sortOrder)
            component.horizontalTextPosition = SwingConstants.LEADING
        }
        component
    }
}
