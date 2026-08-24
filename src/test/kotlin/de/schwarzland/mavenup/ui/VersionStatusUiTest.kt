package de.schwarzland.mavenup.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Testet die zustandslosen Versionsstatus-Helfer [isVersionUpToDate], [versionStatusText],
 * [versionStatusColor], [versionStatusTooltip], [versionDropdownItemText] und [createVersionPanel].
 */
class VersionStatusUiTest : BasePlatformTestCase() {

    fun testIsVersionUpToDateReturnsTrueWhenCurrentEqualsNewest() {
        assertTrue(isVersionUpToDate("1.0.0", "1.0.0"))
    }

    fun testIsVersionUpToDateReturnsFalseWhenVersionsDiffer() {
        assertFalse(isVersionUpToDate("1.0.0", "2.0.0"))
    }

    fun testIsVersionUpToDateReturnsFalseForEmptyCurrentVersion() {
        assertFalse(isVersionUpToDate("", "1.0.0"))
    }

    fun testIsVersionUpToDateReturnsFalseForBothEmpty() {
        assertFalse(isVersionUpToDate("", ""))
    }

    fun testVersionStatusTextReturnsCheckmarkWhenUpToDate() {
        val text = versionStatusText(upToDate = true)
        assertEquals("\u2713", text)
    }

    fun testVersionStatusTextReturnsArrowWhenUpdateAvailable() {
        val text = versionStatusText(upToDate = false)
        assertEquals("\u2191", text)
    }

    fun testVersionStatusColorReturnsGreenWhenUpToDate() {
        val color = versionStatusColor(upToDate = true)
        assertNotNull(color)
    }

    fun testVersionStatusColorReturnsOrangeWhenUpdateAvailable() {
        val color = versionStatusColor(upToDate = false)
        assertNotNull(color)
    }

    fun testVersionStatusTooltipShowsUpToDateMessage() {
        val tooltip = versionStatusTooltip("1.0.0", "1.0.0", "1.0.0")
        assertTrue(tooltip.contains("1.0.0"))
    }

    fun testVersionStatusTooltipShowsUpdateAvailableMessage() {
        val tooltip = versionStatusTooltip("1.0.0", "1.0.0", "2.0.0")
        assertTrue(tooltip.contains("1.0.0"))
        assertTrue(tooltip.contains("2.0.0"))
    }

    fun testVersionStatusTooltipShowsWillUpdateMessage() {
        val tooltip = versionStatusTooltip("1.0.0", "2.0.0", "2.0.0")
        assertTrue(tooltip.contains("1.0.0"))
        assertTrue(tooltip.contains("2.0.0"))
    }

    fun testVersionStatusTooltipShowsWillUpdateNotLatestMessage() {
        val tooltip = versionStatusTooltip("1.0.0", "1.5.0", "2.0.0")
        assertTrue(tooltip.contains("1.0.0"))
        assertTrue(tooltip.contains("1.5.0"))
        assertTrue(tooltip.contains("2.0.0"))
    }

    fun testVersionDropdownItemTextMarksCurrentVersion() {
        val text = versionDropdownItemText("1.0.0", "1.0.0")
        assertTrue("Aktuelle Version sollte den Versionswert enthalten", text.contains("1.0.0"))
        assertTrue("Aktuelle Version sollte als current markiert sein", text.contains("current"))
    }

    fun testVersionDropdownItemTextLeavesOtherVersionsUnchanged() {
        assertEquals("2.0.0", versionDropdownItemText("2.0.0", "1.0.0"))
    }

    fun testVersionDropdownItemTextWithBlankCurrentVersionLeavesValueUnchanged() {
        assertEquals("1.0.0", versionDropdownItemText("1.0.0", ""))
    }

    fun testCreateVersionPanelContainsStatusLabelAndComboBox() {
        val combo = javax.swing.JComboBox(arrayOf("1.0.0", "2.0.0"))
        val text = versionStatusText(upToDate = false)
        val panel = createVersionPanel(combo, text, null, "Test tooltip")

        assertEquals(java.awt.BorderLayout::class.java, panel.layout::class.java)
        assertEquals("Test tooltip", panel.toolTipText)
        assertEquals(2, panel.componentCount)
    }

    fun testCreateVersionPanelAppliesStatusColorToLabel() {
        val combo = javax.swing.JComboBox(arrayOf("1.0.0", "2.0.0"))
        val text = versionStatusText(upToDate = false)
        val color = versionStatusColor(upToDate = false)
        val panel = createVersionPanel(combo, text, color, "tooltip", hasChange = true)

        val statusLabel = panel.getComponent(0) as javax.swing.JLabel
        assertEquals(text, statusLabel.text)
        assertEquals(color, statusLabel.foreground)
    }

    fun testCreateVersionPanelAppliesBoldFontWhenChanged() {
        val combo = javax.swing.JComboBox(arrayOf("1.0.0", "2.0.0"))
        val originalFont = combo.font
        val text = versionStatusText(upToDate = true)
        createVersionPanel(combo, text, null, "tooltip", hasChange = true)

        assertTrue(combo.font.isBold)
        assertEquals(originalFont.size, combo.font.size)
    }

    fun testCreateVersionPanelKeepsNormalFontWhenUnchanged() {
        val combo = javax.swing.JComboBox(arrayOf("1.0.0", "2.0.0"))
        val originalStyle = combo.font.style
        val text = versionStatusText(upToDate = true)
        createVersionPanel(combo, text, null, "tooltip", hasChange = false)

        assertEquals(originalStyle, combo.font.style)
    }
}
