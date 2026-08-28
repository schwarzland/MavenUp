package de.schwarzland.mavenup.ui

import com.intellij.ui.table.JBTable

/** Standard-Innenabstand pro Spalte, damit Inhalte nicht am Zellrand kleben. */
internal const val DEFAULT_COLUMN_WIDTH_PADDING = 24

/** Standard-Mindestbreite einer Spalte, damit auch kurze Werte samt Kopfzeile lesbar bleiben. */
internal const val DEFAULT_MIN_COLUMN_WIDTH = 80

/** Standard-Höchstbreite einer Spalte, damit einzelne sehr lange Werte die Tabelle nicht sprengen. */
internal const val DEFAULT_MAX_COLUMN_WIDTH = 600

/**
 * Trimmt die Spaltenbreiten einer Tabelle auf den tatsächlichen Zellen- und Kopfzeileninhalt, damit
 * beim Öffnen bzw. nach dem Befüllen möglichst viel gut lesbar ist. Diese Regel gilt für alle
 * Plugin-Tabellen: Für jede Spalte wird die breiteste gerenderte Zelle (inklusive Kopfzeile)
 * ermittelt, um einen Innenabstand ergänzt und auf eine sinnvolle Mindest- und Höchstbreite
 * begrenzt, sodass einzelne sehr lange Werte die Tabelle nicht überproportional breit machen.
 *
 * @param table Die Tabelle, deren Spaltenbreiten optimiert werden.
 * @param padding Zusätzlicher Innenabstand pro Spalte.
 * @param minWidth Untere Grenze der berechneten Spaltenbreite.
 * @param maxWidth Obere Grenze der berechneten Spaltenbreite.
 */
internal fun trimColumnWidthsToContent(
    table: JBTable,
    padding: Int = DEFAULT_COLUMN_WIDTH_PADDING,
    minWidth: Int = DEFAULT_MIN_COLUMN_WIDTH,
    maxWidth: Int = DEFAULT_MAX_COLUMN_WIDTH
) {
    for (columnIndex in 0 until table.columnCount) {
        val column = table.columnModel.getColumn(columnIndex)
        val headerRenderer = column.headerRenderer ?: table.tableHeader.defaultRenderer
        val headerComponent = headerRenderer.getTableCellRendererComponent(
            table, column.headerValue, false, false, -1, columnIndex
        )
        var width = headerComponent.preferredSize.width
        for (row in 0 until table.rowCount) {
            val cellRenderer = table.getCellRenderer(row, columnIndex)
            val cellComponent = table.prepareRenderer(cellRenderer, row, columnIndex)
            width = maxOf(width, cellComponent.preferredSize.width)
        }
        width += padding
        column.preferredWidth = width.coerceIn(minWidth, maxWidth)
    }
}
