package de.schwarzland.mavenup.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.schwarzland.mavenup.service.MavenUpSettings
import de.schwarzland.mavenup.service.VersionAutoSelectionMode

class MavenUpVersionsConfigurableTest : BasePlatformTestCase() {

    override fun tearDown() {
        try {
            MavenUpSettings.getInstance().loadState(MavenUpSettings.State())
        } finally {
            super.tearDown()
        }
    }

    private fun createConfigurable(): MavenUpVersionsConfigurable {
        val configurable = MavenUpVersionsConfigurable(project)
        configurable.createComponent()
        configurable.reset()
        return configurable
    }

    fun testDisplayNameMatchesPageTitle() {
        assertEquals("Versions and Updates", MavenUpVersionsConfigurable(project).displayName)
    }

    fun testResetLoadsCurrentSettingsIntoComponent() {
        val settings = MavenUpSettings.getInstance()
        settings.state.hideUnstableVersions = true
        settings.state.hiddenVersionQualifiers = "rc,beta"

        val configurable = createConfigurable()

        assertTrue(configurable.hideUnstableVersionsCheckBox!!.isSelected)
        assertEquals("rc,beta", configurable.hiddenVersionQualifiersField!!.text)
        assertFalse(configurable.isModified)
    }

    fun testIsModifiedDetectsCheckboxChanges() {
        MavenUpSettings.getInstance().state.hideUnstableVersions = false

        val configurable = createConfigurable()
        configurable.hideUnstableVersionsCheckBox!!.isSelected = true

        assertTrue("Änderung der Checkbox sollte isModified() true machen", configurable.isModified)
    }

    fun testHiddenQualifiersFieldFollowsHideUnstableVersions() {
        MavenUpSettings.getInstance().state.hideUnstableVersions = false

        val configurable = createConfigurable()
        assertFalse(configurable.hiddenVersionQualifiersField!!.isEnabled)

        configurable.hideUnstableVersionsCheckBox!!.doClick()

        assertTrue(configurable.hiddenVersionQualifiersField!!.isEnabled)
    }

    fun testHiddenQualifiersAreTrimmedOnApply() {
        val settings = MavenUpSettings.getInstance()
        settings.state.hiddenVersionQualifiers = "rc"

        val configurable = createConfigurable()
        configurable.hiddenVersionQualifiersField!!.text = "  rc,beta,milestone  "

        configurable.apply()

        assertEquals("rc,beta,milestone", settings.state.hiddenVersionQualifiers)
    }

    fun testAutoSearchVersionsDefaultIsTrue() {
        assertTrue(MavenUpSettings.State().autoSearchVersions)
    }

    fun testAutoSearchVersionsSelectionIsPersistedOnApply() {
        val settings = MavenUpSettings.getInstance()
        settings.state.autoSearchVersions = true

        val configurable = createConfigurable()
        val checkBox = configurable.autoSearchVersionsCheckBox!!
        checkBox.isSelected = false
        assertTrue("Änderung der Checkbox sollte isModified() true machen", configurable.isModified)

        configurable.apply()
        assertFalse(settings.state.autoSearchVersions)

        configurable.reset()
        assertFalse("Nach reset() muss die Checkbox den gespeicherten Wert zeigen", checkBox.isSelected)
    }

    fun testStopAfterCentralSuccessDefaultIsTrue() {
        assertTrue(MavenUpSettings.State().stopAfterCentralSuccess)
    }

    fun testStopAfterCentralSuccessSelectionIsPersistedOnApply() {
        val settings = MavenUpSettings.getInstance()
        settings.state.stopAfterCentralSuccess = true

        val configurable = createConfigurable()
        configurable.stopAfterCentralSuccessCheckBox!!.isSelected = false
        assertTrue("Änderung der Checkbox sollte isModified() true machen", configurable.isModified)

        configurable.apply()

        assertFalse(settings.state.stopAfterCentralSuccess)
    }

    fun testOfferAllVersionsDefaultIsFalse() {
        assertFalse(MavenUpSettings.State().offerAllVersions)
    }

    fun testOfferAllVersionsSelectionIsPersistedOnApply() {
        val settings = MavenUpSettings.getInstance()
        settings.state.offerAllVersions = false

        val configurable = createConfigurable()
        configurable.offerAllVersionsCheckBox!!.isSelected = true
        assertTrue("Änderung der Checkbox sollte isModified() true machen", configurable.isModified)

        configurable.apply()

        assertTrue(settings.state.offerAllVersions)
    }

    fun testConfirmVersionResetDefaultIsTrue() {
        assertTrue(MavenUpSettings.State().confirmVersionReset)
    }

    fun testConfirmVersionResetSelectionIsPersistedOnApply() {
        val settings = MavenUpSettings.getInstance()
        settings.state.confirmVersionReset = true

        val configurable = createConfigurable()
        configurable.confirmVersionResetCheckBox!!.isSelected = false
        assertTrue("Änderung der Checkbox sollte isModified() true machen", configurable.isModified)

        configurable.apply()

        assertFalse(settings.state.confirmVersionReset)
    }

    fun testVersionAutoSelectionModeDefaultIsDisabled() {
        assertEquals(VersionAutoSelectionMode.DISABLED, MavenUpSettings.State().versionAutoSelectionMode)
    }

    fun testVersionAutoSelectionModeSelectionIsPersistedOnApply() {
        val settings = MavenUpSettings.getInstance()
        settings.state.versionAutoSelectionMode = VersionAutoSelectionMode.LATEST

        val configurable = createConfigurable()
        configurable.versionAutoSelectionModeComboBox!!.selectedItem = VersionAutoSelectionMode.LATEST_MINOR
        assertTrue("Änderung der Combobox sollte isModified() true machen", configurable.isModified)

        configurable.apply()

        assertEquals(VersionAutoSelectionMode.LATEST_MINOR, settings.state.versionAutoSelectionMode)
        assertTrue("Legacy-Flag bleibt konsistent gesetzt", settings.state.selectLatestVersion)
        assertTrue("Legacy-Flag bleibt konsistent gesetzt", settings.state.selectLatestMinorVersion)
    }

    fun testVersionAutoSelectionModeDisabledClearsLegacyFlagsOnApply() {
        val settings = MavenUpSettings.getInstance()
        settings.state.versionAutoSelectionMode = VersionAutoSelectionMode.LATEST

        val configurable = createConfigurable()
        configurable.versionAutoSelectionModeComboBox!!.selectedItem = VersionAutoSelectionMode.DISABLED

        configurable.apply()

        assertFalse(settings.state.selectLatestVersion)
        assertFalse(settings.state.selectLatestMinorVersion)
    }

    fun testPrivateGroupIdsDefaultIsEmpty() {
        assertEquals("", MavenUpSettings.State().privateGroupIds)
    }

    fun testPrivateGroupIdsArePersistedOnApply() {
        val settings = MavenUpSettings.getInstance()
        settings.state.privateGroupIds = ""

        val configurable = createConfigurable()
        configurable.privateGroupIdsField!!.text = " com.myCompany, de.meineFirma.produkt "
        assertTrue("Änderung des Felds sollte isModified() true machen", configurable.isModified)

        configurable.apply()

        assertEquals("com.myCompany, de.meineFirma.produkt", settings.state.privateGroupIds)
    }

    fun testDisposeUiResourcesReleasesComponents() {
        val configurable = createConfigurable()

        configurable.disposeUIResources()

        assertNull(configurable.autoSearchVersionsCheckBox)
        assertNull(configurable.hiddenVersionQualifiersField)
        assertNull(configurable.versionAutoSelectionModeComboBox)
    }
}
