package de.schwarzland.mavenup.ui

import de.schwarzland.mavenup.service.MavenUpSettings
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class MavenUpConfigurableTest : BasePlatformTestCase() {

    override fun tearDown() {
        try {
            val settings = MavenUpSettings.getInstance(project)
            settings.loadState(MavenUpSettings.State())
        } finally {
            super.tearDown()
        }
    }

    fun testGetDisplayNameReturnsPluginName() {
        val configurable = MavenUpConfigurable(project)
        assertEquals("MavenUp", configurable.displayName)
    }

    fun testOssIndexAccountLinkTargetsTokenSettings() {
        assertEquals("https://ossindex.sonatype.org/user/settings", OSS_INDEX_ACCOUNT_URL)
    }

    fun testResetLoadsCurrentSettingsIntoComponent() {
        val settings = MavenUpSettings.getInstance(project)
        settings.state.jumpOnSingleClick = true
        settings.state.selectLatestVersion = false
        settings.state.hideUnstableVersions = true
        settings.state.hiddenVersionQualifiers = "rc,beta"
        settings.state.checkTransitiveDependencies = false
        settings.state.ossIndexEnabled = true
        settings.state.ossIndexUsername = "user@example.test"

        val configurable = MavenUpConfigurable(project)
        configurable.createComponent()
        configurable.reset()

        assertFalse("Nach dem Erstellen und Reset sollte isModified() false sein", configurable.isModified)
    }

    fun testIsModifiedDetectsCheckboxAndFieldChanges() {
        val settings = MavenUpSettings.getInstance(project)
        settings.state.hideUnstableVersions = false

        val configurable = MavenUpConfigurable(project)
        configurable.createComponent()
        configurable.reset()
        assertFalse(configurable.isModified)

        val checkBoxField = configurable.javaClass.getDeclaredField("hideUnstableVersionsCheckBox")
            .apply { isAccessible = true }
        val checkBox = checkBoxField.get(configurable) as com.intellij.ui.components.JBCheckBox
        checkBox.isSelected = true

        assertTrue("Änderung der Checkbox sollte isModified() true machen", configurable.isModified)
    }

    fun testApplyPersistsComponentValuesToSettings() {
        val settings = MavenUpSettings.getInstance(project)
        settings.state.jumpOnSingleClick = false
        settings.state.selectLatestVersion = true
        settings.state.hideUnstableVersions = false
        settings.state.hiddenVersionQualifiers = "rc"
        settings.state.checkTransitiveDependencies = true
        settings.state.ossIndexEnabled = false
        settings.state.ossIndexUsername = ""

        val configurable = MavenUpConfigurable(project)
        configurable.createComponent()
        configurable.reset()

        val hiddenFieldField = configurable.javaClass.getDeclaredField("hiddenVersionQualifiersField")
            .apply { isAccessible = true }
        val hiddenField = hiddenFieldField.get(configurable) as javax.swing.JTextField
        hiddenField.text = "rc,beta,milestone"

        val jumpCheckBoxField = configurable.javaClass.getDeclaredField("jumpOnSingleClickCheckBox")
            .apply { isAccessible = true }
        val jumpCheckBox = jumpCheckBoxField.get(configurable) as com.intellij.ui.components.JBCheckBox
        jumpCheckBox.isSelected = true

        val ossIndexEnabledField = configurable.javaClass.getDeclaredField("ossIndexEnabledCheckBox")
            .apply { isAccessible = true }
        val ossIndexEnabled = ossIndexEnabledField.get(configurable) as com.intellij.ui.components.JBCheckBox
        ossIndexEnabled.isSelected = true

        val ossIndexUsernameField = configurable.javaClass.getDeclaredField("ossIndexUsernameField")
            .apply { isAccessible = true }
        val ossIndexUsername = ossIndexUsernameField.get(configurable) as javax.swing.JTextField
        ossIndexUsername.text = "user@example.test"

        configurable.apply()

        assertTrue(settings.state.jumpOnSingleClick)
        assertEquals("rc,beta,milestone", settings.state.hiddenVersionQualifiers)
        assertTrue(settings.state.ossIndexEnabled)
        assertEquals("user@example.test", settings.state.ossIndexUsername)
    }
}
