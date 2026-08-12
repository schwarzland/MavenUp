package de.schwarzland.mavenup.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * Reine JUnit-Tests für [WrapLayout], die das mehrzeilige Umbruchverhalten und die
 * Höhenberechnung ohne IntelliJ-Plattform überprüfen.
 */
class WrapLayoutTest {

    /**
     * Erzeugt eine Komponente mit fester bevorzugter Größe für deterministische Layout-Berechnungen.
     *
     * @param width Bevorzugte Breite der Komponente.
     * @param height Bevorzugte Höhe der Komponente.
     * @return Eine [JLabel]-Instanz mit fixierter bevorzugter Größe.
     */
    private fun fixedComponent(width: Int, height: Int): JLabel = JLabel().apply {
        preferredSize = Dimension(width, height)
        minimumSize = Dimension(width, height)
    }

    /**
     * Prüft, dass alle Komponenten in einer einzigen Zeile bleiben, wenn der Container breit genug ist.
     */
    @Test
    fun testSingleRowWhenWideEnough() {
        val panel = JPanel(WrapLayout(FlowLayout.LEFT).apply { hgap = 5; vgap = 5 })
        repeat(3) { panel.add(fixedComponent(50, 20)) }
        panel.setSize(1000, 100)

        val preferred = panel.preferredSize

        assertEquals(30, preferred.height)
    }

    /**
     * Prüft, dass die bevorzugte Höhe wächst, wenn der Container zu schmal für eine einzige Zeile ist.
     */
    @Test
    fun testWrapsIntoMultipleRowsWhenNarrow() {
        val panel = JPanel(WrapLayout(FlowLayout.LEFT).apply { hgap = 5; vgap = 5 })
        repeat(3) { panel.add(fixedComponent(50, 20)) }
        panel.setSize(60, 100)

        val preferred = panel.preferredSize

        assertTrue("Height should grow when wrapping: ${preferred.height}", preferred.height > 30)
    }

    /**
     * Prüft, dass unsichtbare Komponenten nicht zur berechneten Größe beitragen.
     */
    @Test
    fun testInvisibleComponentsAreIgnored() {
        val panel = JPanel(WrapLayout(FlowLayout.LEFT).apply { hgap = 5; vgap = 5 })
        panel.add(fixedComponent(50, 20))
        panel.add(fixedComponent(50, 40).apply { isVisible = false })
        panel.setSize(1000, 100)

        val preferred = panel.preferredSize

        assertEquals(30, preferred.height)
    }

    /**
     * Prüft, dass ein leerer Container nur die Einfügeabstände als Höhe meldet.
     */
    @Test
    fun testEmptyContainerHasOnlyGapHeight() {
        val panel = JPanel(WrapLayout(FlowLayout.LEFT).apply { hgap = 5; vgap = 5 })
        panel.setSize(1000, 100)

        val preferred = panel.preferredSize

        assertEquals(10, preferred.height)
    }
}
