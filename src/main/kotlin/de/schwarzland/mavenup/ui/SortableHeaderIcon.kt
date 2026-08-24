package de.schwarzland.mavenup.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.util.IconLoader
import javax.swing.Icon
import javax.swing.SortOrder

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
