package de.schwarzland.mavenup.ui

import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI

/**
 * Vom IntelliJ-Styleguide empfohlene Zeilenhöhe einer Tabelle in unskalierten Pixeln. Entspricht dem
 * Wert, den die Plattform auch für Listen verwendet (`JBUI.CurrentTheme.List.rowHeight()`).
 */
internal const val RECOMMENDED_TABLE_ROW_HEIGHT = 24

/**
 * Liefert die empfohlene Zeilenhöhe für Plugin-Tabellen, skaliert auf die aktuelle
 * Oberflächenskalierung (HiDPI, Zoom-Einstellung der IDE).
 *
 * Ohne explizite Vorgabe leitet [JBTable] die Zeilenhöhe aus den Schriftmetriken und den
 * Renderer-Komponenten ab. Das führt je nach Betriebssystem (insbesondere macOS) und je nach
 * Zelleninhalt zu unterschiedlich hohen Zeilen in den einzelnen Tabellen.
 *
 * @return Die skalierte, für alle Tabellen einheitliche Zeilenhöhe.
 */
internal fun recommendedTableRowHeight(): Int = JBUI.scale(RECOMMENDED_TABLE_ROW_HEIGHT)

/**
 * Setzt die einheitliche, vom IntelliJ-Styleguide empfohlene Zeilenhöhe an einer Tabelle. Diese Regel
 * gilt für alle Plugin-Tabellen, damit Zeilenhöhen plattform- und ansichtsübergreifend identisch sind.
 *
 * @param table Die Tabelle, deren Zeilenhöhe vereinheitlicht wird.
 */
internal fun applyRecommendedRowHeight(table: JBTable) {
    table.rowHeight = recommendedTableRowHeight()
}
