package de.schwarzland.mavenup.ui

import java.awt.Container
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.JScrollPane
import javax.swing.SwingUtilities

/**
 * Ein [FlowLayout], das seine Komponenten bei zu geringer Breite in mehrere Zeilen umbricht und dabei
 * eine korrekte bevorzugte Höhe für die umgebrochenen Zeilen berechnet.
 *
 * Das Standard-[FlowLayout] ordnet Komponenten zwar mehrzeilig an, meldet aber stets die Höhe einer
 * einzigen Zeile als bevorzugte Größe. Dadurch werden umgebrochene Zeilen abgeschnitten. Diese
 * Implementierung berechnet die tatsächlich benötigte Höhe anhand der zur Verfügung stehenden Breite.
 *
 * @param align Ausrichtung der Komponenten innerhalb einer Zeile (siehe [FlowLayout]-Konstanten).
 */
class WrapLayout(align: Int = LEFT) : FlowLayout(align) {

    /**
     * Liefert die bevorzugte Größe des Containers unter Berücksichtigung des Zeilenumbruchs.
     *
     * @param target Der Container, dessen bevorzugte Größe ermittelt wird.
     * @return Die bevorzugte Größe inklusive der Höhe aller umgebrochenen Zeilen.
     */
    override fun preferredLayoutSize(target: Container): Dimension = layoutSize(target, true)

    /**
     * Liefert die minimale Größe des Containers unter Berücksichtigung des Zeilenumbruchs.
     *
     * @param target Der Container, dessen minimale Größe ermittelt wird.
     * @return Die minimale Größe inklusive der Höhe aller umgebrochenen Zeilen.
     */
    override fun minimumLayoutSize(target: Container): Dimension {
        val minimum = layoutSize(target, false)
        minimum.width -= hgap + 1
        return minimum
    }

    /**
     * Berechnet die benötigte Größe des Containers anhand der aktuell verfügbaren Breite, sodass
     * Komponenten bei Bedarf in mehrere Zeilen umgebrochen werden.
     *
     * @param target Der Container, für den die Größe berechnet wird.
     * @param preferred true für die bevorzugte, false für die minimale Komponentengröße.
     * @return Die berechnete Größe inklusive der Höhe aller Zeilen.
     */
    private fun layoutSize(target: Container, preferred: Boolean): Dimension {
        synchronized(target.treeLock) {
            var targetWidth = target.size.width
            if (targetWidth == 0) {
                targetWidth = Int.MAX_VALUE
            }

            val horizontalInsetsAndGap = hgap * 2
            val insets = target.insets
            val maxWidth = targetWidth - (insets.left + insets.right + horizontalInsetsAndGap)

            val dimension = Dimension(0, 0)
            var rowWidth = 0
            var rowHeight = 0

            for (i in 0 until target.componentCount) {
                val component = target.getComponent(i)
                if (!component.isVisible) {
                    continue
                }

                val size = if (preferred) component.preferredSize else component.minimumSize

                if (rowWidth + size.width > maxWidth) {
                    addRow(dimension, rowWidth, rowHeight)
                    rowWidth = 0
                    rowHeight = 0
                }

                if (rowWidth != 0) {
                    rowWidth += hgap
                }

                rowWidth += size.width
                rowHeight = maxOf(rowHeight, size.height)
            }

            addRow(dimension, rowWidth, rowHeight)

            dimension.width += horizontalInsetsAndGap + insets.left + insets.right
            dimension.height += vgap * 2 + insets.top + insets.bottom

            val scrollPane = SwingUtilities.getAncestorOfClass(JScrollPane::class.java, target)
            if (scrollPane != null && target.isValid) {
                dimension.width -= hgap + 1
            }

            return dimension
        }
    }

    /**
     * Übernimmt die Breite und Höhe einer abgeschlossenen Zeile in die Gesamtgröße.
     *
     * @param dimension Die bisher berechnete Gesamtgröße, die aktualisiert wird.
     * @param rowWidth Die Breite der abgeschlossenen Zeile.
     * @param rowHeight Die Höhe der abgeschlossenen Zeile.
     */
    private fun addRow(dimension: Dimension, rowWidth: Int, rowHeight: Int) {
        dimension.width = maxOf(dimension.width, rowWidth)
        if (dimension.height > 0) {
            dimension.height += vgap
        }
        dimension.height += rowHeight
    }
}
