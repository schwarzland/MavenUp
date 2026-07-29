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

    fun testResetLoadsCurrentSettingsIntoComponent() {
        val settings = MavenUpSettings.getInstance(project)
        settings.state.jumpOnSingleClick = true
        settings.state.selectLatestVersion = false
        settings.state.hideUnstableVersions = true
        settings.state.hiddenVersionQualifiers = "rc,beta"

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

        configurable.apply()

        assertTrue(settings.state.jumpOnSingleClick)
        assertEquals("rc,beta,milestone", settings.state.hiddenVersionQualifiers)
    }
}
