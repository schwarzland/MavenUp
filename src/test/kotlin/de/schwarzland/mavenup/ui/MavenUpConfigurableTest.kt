package de.schwarzland.mavenup.ui

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ex.ConfigurableExtensionPointUtil
import com.intellij.openapi.options.ex.ConfigurableVisitor
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.schwarzland.mavenup.service.MavenRepositoryBrowser
import de.schwarzland.mavenup.service.MavenUpSettings
import de.schwarzland.mavenup.service.ToolWindowBadgeMode

private const val ROOT_CONFIGURABLE_ID = "de.schwarzland.mavenup.ui.MavenUpConfigurable"

class MavenUpConfigurableTest : BasePlatformTestCase() {

    override fun tearDown() {
        try {
            MavenUpSettings.getInstance().loadState(MavenUpSettings.State())
        } finally {
            super.tearDown()
        }
    }

    private fun createConfigurable(): MavenUpConfigurable {
        val configurable = MavenUpConfigurable(project)
        configurable.createComponent()
        configurable.reset()
        return configurable
    }

    fun testGetDisplayNameReturnsPluginName() {
        assertEquals("MavenUp", MavenUpConfigurable(project).displayName)
    }

    fun testResetLoadsCurrentSettingsIntoComponent() {
        val settings = MavenUpSettings.getInstance()
        settings.state.jumpOnSingleClick = true
        settings.state.repositoryBrowser = MavenRepositoryBrowser.SONATYPE_CENTRAL
        settings.state.toolbarShowText = false

        val configurable = createConfigurable()

        assertTrue(configurable.jumpOnSingleClickCheckBox!!.isSelected)
        assertEquals(MavenRepositoryBrowser.SONATYPE_CENTRAL, configurable.repositoryBrowserComboBox!!.selectedItem)
        assertFalse(configurable.toolbarShowTextCheckBox!!.isSelected)
        assertFalse("Nach dem Erstellen und Reset sollte isModified() false sein", configurable.isModified)
    }

    fun testJumpOnSingleClickSelectionIsPersistedOnApply() {
        val settings = MavenUpSettings.getInstance()
        settings.state.jumpOnSingleClick = false

        val configurable = createConfigurable()
        configurable.jumpOnSingleClickCheckBox!!.isSelected = true
        assertTrue("Änderung der Checkbox sollte isModified() true machen", configurable.isModified)

        configurable.apply()

        assertTrue(settings.state.jumpOnSingleClick)
        assertFalse(configurable.isModified)
    }

    fun testRepositoryBrowserSelectionIsPersistedOnApply() {
        val settings = MavenUpSettings.getInstance()
        settings.state.repositoryBrowser = MavenRepositoryBrowser.MVN_REPOSITORY

        val configurable = createConfigurable()
        configurable.repositoryBrowserComboBox!!.selectedItem = MavenRepositoryBrowser.SONATYPE_CENTRAL
        assertTrue("Änderung der Combobox sollte isModified() true machen", configurable.isModified)

        configurable.apply()

        assertEquals(MavenRepositoryBrowser.SONATYPE_CENTRAL, settings.state.repositoryBrowser)
    }

    fun testRepositoryBrowserDefaultIsMvnRepository() {
        assertEquals(MavenRepositoryBrowser.MVN_REPOSITORY, MavenUpSettings.State().repositoryBrowser)
    }

    fun testToolbarShowTextDefaultIsTrue() {
        assertTrue(MavenUpSettings.State().toolbarShowText)
    }

    fun testToolbarShowTextSelectionIsPersistedOnApply() {
        val settings = MavenUpSettings.getInstance()
        settings.state.toolbarShowText = false

        val configurable = createConfigurable()
        configurable.toolbarShowTextCheckBox!!.isSelected = true
        assertTrue("Änderung der Checkbox sollte isModified() true machen", configurable.isModified)

        configurable.apply()

        assertTrue(settings.state.toolbarShowText)
    }

    fun testToolWindowBadgeModeDefaultShowsVulnerabilitiesAndUpdates() {
        assertEquals(
            ToolWindowBadgeMode.VULNERABILITIES_AND_UPDATES,
            MavenUpSettings.State().toolWindowBadgeMode
        )
    }

    fun testToolWindowBadgeModeSelectionIsPersistedOnApply() {
        val settings = MavenUpSettings.getInstance()
        settings.state.toolWindowBadgeMode = ToolWindowBadgeMode.VULNERABILITIES_AND_UPDATES

        val configurable = createConfigurable()
        configurable.toolWindowBadgeModeComboBox!!.selectedItem = ToolWindowBadgeMode.OFF
        assertTrue("Änderung der Auswahl sollte isModified() true machen", configurable.isModified)

        configurable.apply()

        assertEquals(ToolWindowBadgeMode.OFF, settings.state.toolWindowBadgeMode)
    }

    fun testSubPageQuickLinksAreShownInTreeOrder() {
        val configurable = createConfigurable()

        val linkTexts = configurable.subPageLinks.map { it.text }

        assertEquals(
            listOf("Versions and Updates", "Vulnerability Check", "Pom.xml Changes"),
            linkTexts
        )
    }

    fun testSubPageQuickLinksMatchTheSettingsTreeOrder() {
        val configurable = createConfigurable()

        assertEquals(
            "Die Quick-Links müssen den Unterseiten in der Reihenfolge des Einstellungsbaums entsprechen",
            settingsTreeSubPageTitles(),
            configurable.subPageLinks.map { it.text }
        )
    }

    /**
     * Ermittelt die Titel der Unterseiten so, wie die Plattform sie im Einstellungsbaum anordnet.
     * Die Reihenfolge ergibt sich aus `groupWeight` (absteigend) und bei Gleichstand alphabetisch,
     * daher wird sie über den echten Configurable-Baum und nicht über `plugin.xml` gelesen.
     */
    private fun settingsTreeSubPageTitles(): List<String> {
        val group = ConfigurableExtensionPointUtil.getConfigurableGroup(project, true)
        val root = ConfigurableVisitor.findById(ROOT_CONFIGURABLE_ID, listOf(group))
        assertNotNull("MavenUp-Wurzelseite muss im Einstellungsbaum registriert sein", root)
        val composite = root as Configurable.Composite
        return composite.configurables.map { it.displayName }
    }

    fun testSubPageQuickLinksAreEnabled() {
        val configurable = createConfigurable()

        assertTrue(configurable.subPageLinks.isNotEmpty())
        configurable.subPageLinks.forEach { assertTrue("Quick-Link muss bedienbar sein", it.isEnabled) }
    }

    fun testDisposeUiResourcesReleasesComponents() {
        val configurable = createConfigurable()

        configurable.disposeUIResources()

        assertNull(configurable.repositoryBrowserComboBox)
        assertNull(configurable.toolbarShowTextCheckBox)
        assertNull(configurable.jumpOnSingleClickCheckBox)
        assertNull(configurable.toolWindowBadgeModeComboBox)
        assertTrue(configurable.subPageLinks.isEmpty())
    }

    fun testMavenRepositoryBrowserUrlPatterns() {
        assertEquals(
            "https://mvnrepository.com/artifact/com.example/lib/1.0",
            MavenRepositoryBrowser.MVN_REPOSITORY.urlFor("com.example", "lib", "1.0")
        )
        assertEquals(
            "https://central.sonatype.com/artifact/com.example/lib/1.0",
            MavenRepositoryBrowser.SONATYPE_CENTRAL.urlFor("com.example", "lib", "1.0")
        )
    }
}
