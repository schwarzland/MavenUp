package de.schwarzland.mavenup.ui

import com.intellij.openapi.ui.DialogPanel
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.schwarzland.mavenup.service.MavenUpSettings
import de.schwarzland.mavenup.service.VersionAutoSelectionMode
import de.schwarzland.mavenup.service.VersionMetadataCache
import de.schwarzland.mavenup.service.VulnerabilityResultCache

class MavenUpVersionsConfigurableTest : BasePlatformTestCase() {

    override fun tearDown() {
        try {
            MavenUpSettings.getInstance().loadState(MavenUpSettings.State())
            VersionMetadataCache.getInstance().clear()
            VulnerabilityResultCache.getInstance().clear()
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

    fun testVersionCacheTtlMinutesDefaultIsSixty() {
        assertEquals(60, MavenUpSettings.State().versionCacheTtlMinutes)
    }

    fun testVersionCacheTtlMinutesIsPersistedOnApply() {
        val settings = MavenUpSettings.getInstance()
        settings.state.versionCacheTtlMinutes = 60

        val configurable = createConfigurable()
        configurable.versionCacheTtlMinutesSpinner!!.number = 30
        assertTrue("Änderung des Spinners sollte isModified() true machen", configurable.isModified)

        configurable.apply()

        assertEquals(30, settings.state.versionCacheTtlMinutes)
    }

    fun testVersionCacheTtlMinutesZeroDisablesCaching() {
        val settings = MavenUpSettings.getInstance()
        settings.state.versionCacheTtlMinutes = 60

        val configurable = createConfigurable()
        configurable.versionCacheTtlMinutesSpinner!!.number = 0
        configurable.apply()

        assertEquals(0, settings.state.versionCacheTtlMinutes)
    }

    fun testApplyClearsVersionAndVulnerabilityCaches() {
        VersionMetadataCache.getInstance().getOrFetch("com.example", "artifact", ttlMinutes = 60) { listOf("1.0.0") }
        VulnerabilityResultCache.getInstance().put("com.example:artifact:1.0.0", emptyList())

        val configurable = createConfigurable()
        configurable.apply()

        assertEquals(0, VersionMetadataCache.getInstance().size())
        assertEquals(0, VulnerabilityResultCache.getInstance().size())
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
        configurable.privateGroupIdsField!!.text = " com.mycompany, de.meinefirma.produkt "
        assertTrue("Änderung des Felds sollte isModified() true machen", configurable.isModified)

        configurable.apply()

        assertEquals("com.mycompany, de.meinefirma.produkt", settings.state.privateGroupIds)
    }

    fun testPrivateGroupIdsValidationAcceptsValidInputs() {
        assertTrue(isValidPrivateGroupIds(""))
        assertTrue(isValidPrivateGroupIds("   "))
        assertTrue(isValidPrivateGroupIds("com.mycompany"))
        assertTrue(isValidPrivateGroupIds("com.mycompany, de.meinefirma.produkt"))
        assertTrue(isValidPrivateGroupIds("  com.mycompany  ,  de.meinefirma.produkt  "))
        assertTrue(isValidPrivateGroupIds("com.my-company.service_123, org.example-456_test"))
        assertTrue(isValidPrivateGroupIds(",,com.mycompany,,,de.meinefirma,,"))
    }

    fun testPrivateGroupIdsValidationRejectsInvalidCharacters() {
        assertFalse("Großbuchstaben sind nicht erlaubt", isValidPrivateGroupIds("com.myCompany"))
        assertFalse("Wildcard-Stern ist nicht erlaubt", isValidPrivateGroupIds("com.mycompany*"))
        assertFalse("Dollar-Zeichen ist nicht erlaubt", isValidPrivateGroupIds("${'$'}de.meinefirma"))
        assertFalse("Leerzeichen innerhalb eines Eintrags sind nicht erlaubt", isValidPrivateGroupIds("com .mycompany"))
        assertFalse("Sonderzeichen wie Schrägstrich sind nicht erlaubt", isValidPrivateGroupIds("com/mycompany"))
        assertFalse("Sonderzeichen wie Doppelpunkt sind nicht erlaubt", isValidPrivateGroupIds("com:mycompany"))
        assertFalse("Sonderzeichen wie At-Zeichen sind nicht erlaubt", isValidPrivateGroupIds("@mycompany"))
    }

    fun testPrivateGroupIdsInvalidInputPreventsApply() {
        val settings = MavenUpSettings.getInstance()
        settings.state.privateGroupIds = "com.mycompany"

        val configurable = createConfigurable()
        configurable.privateGroupIdsField!!.text = "com.myCompany"

        try {
            configurable.apply()
            fail("apply() muss bei ungültigen Zeichen fehlschlagen")
        } catch (e: Exception) {
            val expectedMessage = MyMessageBundle.message("settings.privateGroupIds.invalid")
            assertTrue("Fehlermeldung muss erlaubte Zeichen enthalten: ${e.message}", e.message?.contains(expectedMessage) == true)
        }

        assertEquals("Einstellung darf bei Validierungsfehler nicht übernommen werden", "com.mycompany", settings.state.privateGroupIds)
        assertEquals(OUTLINE_ERROR, configurable.privateGroupIdsField!!.getClientProperty(OUTLINE_PROPERTY))
    }

    fun testPrivateGroupIdsInvalidInputSetsAndClearsErrorOutline() {
        val configurable = createConfigurable()
        val field = configurable.privateGroupIdsField!!
        field.text = "invalid:group"

        try {
            configurable.apply()
            fail("apply() muss bei ungültigen Zeichen fehlschlagen")
        } catch (_: Exception) {
        }

        assertEquals(OUTLINE_ERROR, field.getClientProperty(OUTLINE_PROPERTY))

        field.text = "com.mycompany"
        configurable.apply()
        assertNull(field.getClientProperty(OUTLINE_PROPERTY))
    }

    fun testPrivateGroupIdsValidationCallbackReturnsErrorForInvalidInput() {
        val configurable = MavenUpVersionsConfigurable(project)
        val panel = configurable.createComponent() as DialogPanel
        configurable.reset()

        configurable.privateGroupIdsField!!.text = "com.myCompany"
        val validations = panel.validateAll()
        assertEquals(1, validations.size)
        assertEquals(MyMessageBundle.message("settings.privateGroupIds.invalid"), validations.first().message)
        assertEquals(configurable.privateGroupIdsField, validations.first().component)

        configurable.privateGroupIdsField!!.text = "com.mycompany, de.meinefirma.produkt"
        val validValidations = panel.validateAll()
        assertTrue("Bei gültigen GroupIds darf kein Validierungsfehler vorliegen", validValidations.isEmpty())
    }

    fun testDisposeUiResourcesReleasesComponents() {
        val configurable = createConfigurable()

        configurable.disposeUIResources()

        assertNull(configurable.autoSearchVersionsCheckBox)
        assertNull(configurable.hiddenVersionQualifiersField)
        assertNull(configurable.versionAutoSelectionModeComboBox)
        assertNull(configurable.versionCacheTtlMinutesSpinner)
    }
}
