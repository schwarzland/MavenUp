package de.schwarzland.mavenup.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.schwarzland.mavenup.service.MavenUpSettings
import de.schwarzland.mavenup.service.VulnerabilityCommentMode

class MavenUpPomChangesConfigurableTest : BasePlatformTestCase() {

    override fun tearDown() {
        try {
            MavenUpSettings.getInstance().loadState(MavenUpSettings.State())
        } finally {
            super.tearDown()
        }
    }

    private fun createConfigurable(): MavenUpPomChangesConfigurable {
        val configurable = MavenUpPomChangesConfigurable(project)
        configurable.createComponent()
        configurable.reset()
        return configurable
    }

    fun testDisplayNameMatchesPageTitle() {
        assertEquals("Pom.xml Changes", MavenUpPomChangesConfigurable(project).displayName)
    }

    fun testSyncMavenAfterUpdateDefaultIsTrue() {
        assertTrue(MavenUpSettings.State().syncMavenAfterUpdate)
    }

    fun testSyncMavenAfterUpdateSelectionIsPersistedOnApply() {
        val settings = MavenUpSettings.getInstance()
        settings.state.syncMavenAfterUpdate = true

        val configurable = createConfigurable()
        assertFalse(configurable.isModified)
        configurable.syncMavenAfterUpdateCheckBox!!.isSelected = false
        assertTrue("Änderung der Checkbox sollte isModified() true machen", configurable.isModified)

        configurable.apply()

        assertFalse(settings.state.syncMavenAfterUpdate)
    }

    fun testVulnerabilityCommentModeDefaultIsAdvisoryIds() {
        assertEquals(VulnerabilityCommentMode.ADVISORY_IDS, MavenUpSettings.State().vulnerabilityCommentMode)
    }

    fun testVulnerabilityCommentModeSelectionIsPersistedOnApply() {
        val settings = MavenUpSettings.getInstance()
        settings.state.vulnerabilityCommentMode = VulnerabilityCommentMode.ADVISORY_IDS

        val configurable = createConfigurable()
        configurable.vulnerabilityCommentModeComboBox!!.selectedItem = VulnerabilityCommentMode.ALIASES
        assertTrue("Änderung der Combobox sollte isModified() true machen", configurable.isModified)

        configurable.apply()

        assertEquals(VulnerabilityCommentMode.ALIASES, settings.state.vulnerabilityCommentMode)
        assertTrue("Legacy-Flag bleibt konsistent gesetzt", settings.state.addVulnerabilityFixComment)
    }

    fun testVulnerabilityCommentModeNoneClearsLegacyFlagOnApply() {
        val settings = MavenUpSettings.getInstance()
        settings.state.vulnerabilityCommentMode = VulnerabilityCommentMode.ADVISORY_IDS

        val configurable = createConfigurable()
        configurable.vulnerabilityCommentModeComboBox!!.selectedItem = VulnerabilityCommentMode.NONE

        configurable.apply()

        assertEquals(VulnerabilityCommentMode.NONE, settings.state.vulnerabilityCommentMode)
        assertFalse("Legacy-Flag wird abgeschaltet", settings.state.addVulnerabilityFixComment)
    }

    fun testVulnerabilityCommentPrefixAndMaxIdsDefaults() {
        val state = MavenUpSettings.State()
        assertEquals("Pinned by MavenUp to fix:", state.vulnerabilityCommentPrefix)
        assertEquals(3, state.vulnerabilityCommentMaxIds)
    }

    fun testVulnerabilityCommentPrefixAndMaxIdsArePersistedOnApply() {
        val settings = MavenUpSettings.getInstance()

        val configurable = createConfigurable()
        assertFalse(configurable.isModified)
        configurable.vulnerabilityCommentPrefixField!!.text = "  Fixes:  "
        assertTrue("Änderung des Präfix sollte isModified() true machen", configurable.isModified)
        configurable.vulnerabilityCommentMaxIdsSpinner!!.value = 7

        configurable.apply()

        assertEquals("Fixes:", settings.state.vulnerabilityCommentPrefix)
        assertEquals(7, settings.state.vulnerabilityCommentMaxIds)
    }

    fun testVulnerabilityCommentControlsAreDisabledForModeNone() {
        MavenUpSettings.getInstance().state.vulnerabilityCommentMode = VulnerabilityCommentMode.NONE

        val configurable = createConfigurable()

        assertFalse(configurable.vulnerabilityCommentPrefixField!!.isEnabled)
        assertFalse(configurable.vulnerabilityCommentMaxIdsSpinner!!.isEnabled)
    }

    fun testVulnerabilityCommentMaxIdsIsDisabledForTextOnlyMode() {
        MavenUpSettings.getInstance().state.vulnerabilityCommentMode = VulnerabilityCommentMode.TEXT_ONLY

        val configurable = createConfigurable()

        assertTrue(configurable.vulnerabilityCommentPrefixField!!.isEnabled)
        assertFalse(configurable.vulnerabilityCommentMaxIdsSpinner!!.isEnabled)
    }

    fun testVulnerabilityCommentControlsFollowModeChange() {
        MavenUpSettings.getInstance().state.vulnerabilityCommentMode = VulnerabilityCommentMode.NONE

        val configurable = createConfigurable()
        configurable.vulnerabilityCommentModeComboBox!!.selectedItem = VulnerabilityCommentMode.ALL_IDS

        assertTrue(configurable.vulnerabilityCommentPrefixField!!.isEnabled)
        assertTrue(configurable.vulnerabilityCommentMaxIdsSpinner!!.isEnabled)
    }

    fun testLegacyDisabledVulnerabilityCommentIsMigratedToNone() {
        val settings = MavenUpSettings.getInstance()
        settings.loadState(MavenUpSettings.State(addVulnerabilityFixComment = false))

        assertEquals(VulnerabilityCommentMode.NONE, settings.state.vulnerabilityCommentMode)
    }

    fun testDisposeUiResourcesReleasesComponents() {
        val configurable = createConfigurable()

        configurable.disposeUIResources()

        assertNull(configurable.syncMavenAfterUpdateCheckBox)
        assertNull(configurable.vulnerabilityCommentModeComboBox)
        assertNull(configurable.vulnerabilityCommentPrefixField)
        assertNull(configurable.vulnerabilityCommentMaxIdsSpinner)
    }
}
