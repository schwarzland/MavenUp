package de.schwarzland.mavenup.ui

import de.schwarzland.mavenup.service.MavenRepositoryBrowser
import de.schwarzland.mavenup.service.MavenUpSettings
import de.schwarzland.mavenup.service.OssIndexCredentialStore
import de.schwarzland.mavenup.service.VersionAutoSelectionMode
import com.intellij.credentialStore.Credentials
import com.intellij.openapi.options.ConfigurationException
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.SwingUtilities

class MavenUpConfigurableTest : BasePlatformTestCase() {

    override fun tearDown() {
        try {
            val settings = MavenUpSettings.getInstance()
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
        assertEquals("https://ossindex.sonatype.org", OSS_INDEX_ACCOUNT_URL)
    }

    fun testResetLoadsCurrentSettingsIntoComponent() {
        val settings = MavenUpSettings.getInstance()
        settings.state.jumpOnSingleClick = true
        settings.state.versionAutoSelectionMode = VersionAutoSelectionMode.DISABLED
        settings.state.hideUnstableVersions = true
        settings.state.hiddenVersionQualifiers = "rc,beta"
        settings.state.checkTransitiveDependencies = false
        settings.state.ossIndexEnabled = true

        val configurable = MavenUpConfigurable(project)
        configurable.createComponent()
        configurable.reset()

        assertFalse("Nach dem Erstellen und Reset sollte isModified() false sein", configurable.isModified)
    }

    fun testIsModifiedDetectsCheckboxAndFieldChanges() {
        val settings = MavenUpSettings.getInstance()
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

    fun testCredentialLookupRunsOutsideEdtAndIsModifiedDoesNotReloadIt() {
        val lookupCompleted = CountDownLatch(1)
        val lookupRanOnEdt = AtomicBoolean(true)
        val lookupCount = AtomicInteger()
        val credentialStore = object : OssIndexCredentialStore {
            override fun store(token: String) = Unit

            override fun retrieve(): Credentials {
                lookupRanOnEdt.set(SwingUtilities.isEventDispatchThread())
                lookupCount.incrementAndGet()
                lookupCompleted.countDown()
                return Credentials("user@example.test", "secret-token")
            }
        }
        val configurable = MavenUpConfigurable(project, credentialStore)
        configurable.createComponent()
        configurable.reset()

        assertFalse(configurable.isModified)
        assertTrue("Credential lookup did not complete", lookupCompleted.await(5, TimeUnit.SECONDS))
        assertFalse("Credential lookup must not run on the EDT", lookupRanOnEdt.get())
        val tokenField = configurable.javaClass.getDeclaredField("ossIndexTokenField")
            .apply { isAccessible = true }
            .get(configurable) as javax.swing.JPasswordField
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (tokenField.password.concatToString() != "secret-token" && System.nanoTime() < deadline) {
            UIUtil.dispatchAllInvocationEvents()
            Thread.sleep(10)
        }

        assertEquals("secret-token", tokenField.password.concatToString())
        assertFalse(configurable.isModified)
        tokenField.text = "changed-token"

        assertTrue(configurable.isModified)
        assertEquals("isModified() must use the cached token", 1, lookupCount.get())
    }

    fun testApplyPersistsComponentValuesToSettings() {
        val settings = MavenUpSettings.getInstance()
        settings.state.jumpOnSingleClick = false
        settings.state.versionAutoSelectionMode = VersionAutoSelectionMode.LATEST
        settings.state.hideUnstableVersions = false
        settings.state.hiddenVersionQualifiers = "rc"
        settings.state.checkTransitiveDependencies = true
        settings.state.ossIndexEnabled = false

        val credentialStore = object : OssIndexCredentialStore {
            override fun store(token: String) = Unit
            override fun retrieve(): Credentials = Credentials("user@example.test", "secret-token")
        }
        val configurable = MavenUpConfigurable(project, credentialStore)
        configurable.createComponent()
        configurable.reset()
        waitForToken(configurable, "secret-token")

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

        configurable.apply()

        assertTrue(settings.state.jumpOnSingleClick)
        assertEquals("rc,beta,milestone", settings.state.hiddenVersionQualifiers)
        assertTrue(settings.state.ossIndexEnabled)
    }

    fun testEnabledOssIndexMarksCredentialsRequiredAndRejectsMissingToken() {
        val credentialStore = object : OssIndexCredentialStore {
            override fun store(token: String) = Unit
            override fun retrieve(): Credentials? = null
        }
        val configurable = MavenUpConfigurable(project, credentialStore)
        configurable.createComponent()
        configurable.reset()
        waitForCredentialsLoaded(configurable)

        val enabledCheckBox = configurable.field<com.intellij.ui.components.JBCheckBox>(
            "ossIndexEnabledCheckBox"
        )
        enabledCheckBox.doClick()

        assertEquals(
            "OSS Index API token (required)",
            configurable.field<javax.swing.JLabel>("ossIndexTokenLabel").text
        )

        try {
            configurable.apply()
            fail("Applying enabled OSS Index settings without a token should fail")
        } catch (exception: ConfigurationException) {
            assertEquals(
                "An API token is required when Sonatype OSS Index is enabled.",
                exception.localizedMessage
            )
        }
    }

    fun testApplyDoesNotBlockOtherSettingsWhileExistingCredentialsLoad() {
        val loadStarted = CountDownLatch(1)
        val releaseLoad = CountDownLatch(1)
        val credentialStore = object : OssIndexCredentialStore {
            override fun store(token: String) = Unit
            override fun retrieve(): Credentials {
                loadStarted.countDown()
                assertTrue(releaseLoad.await(5, TimeUnit.SECONDS))
                return Credentials("user@example.test", "secret-token")
            }
        }
        val settings = MavenUpSettings.getInstance()
        settings.state.ossIndexEnabled = true
        val configurable = MavenUpConfigurable(project, credentialStore)

        try {
            configurable.createComponent()
            configurable.reset()
            assertTrue(loadStarted.await(5, TimeUnit.SECONDS))
            configurable.field<com.intellij.ui.components.JBCheckBox>("jumpOnSingleClickCheckBox").isSelected = true

            configurable.apply()

            assertTrue(settings.state.jumpOnSingleClick)
            assertTrue(settings.state.ossIndexEnabled)
        } finally {
            releaseLoad.countDown()
        }
    }

    fun testRepositoryBrowserSelectionIsPersistedOnApply() {
        val settings = MavenUpSettings.getInstance()
        settings.state.repositoryBrowser = MavenRepositoryBrowser.MVN_REPOSITORY

        val configurable = MavenUpConfigurable(project)
        configurable.createComponent()
        configurable.reset()
        assertFalse(configurable.isModified)

        @Suppress("UNCHECKED_CAST")
        val comboBox = configurable.field<com.intellij.openapi.ui.ComboBox<MavenRepositoryBrowser>>("repositoryBrowserComboBox")
        comboBox.selectedItem = MavenRepositoryBrowser.SONATYPE_CENTRAL
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

        val configurable = MavenUpConfigurable(project)
        configurable.createComponent()
        configurable.reset()
        assertFalse(configurable.isModified)

        val checkBox = configurable.field<com.intellij.ui.components.JBCheckBox>("toolbarShowTextCheckBox")
        checkBox.isSelected = true
        assertTrue("Änderung der Checkbox sollte isModified() true machen", configurable.isModified)

        configurable.apply()
        assertTrue(settings.state.toolbarShowText)

        settings.state.toolbarShowText = false
    }

    fun testSyncMavenAfterUpdateDefaultIsTrue() {
        assertTrue(MavenUpSettings.State().syncMavenAfterUpdate)
    }

    fun testStopAfterCentralSuccessDefaultIsTrue() {
        assertTrue(MavenUpSettings.State().stopAfterCentralSuccess)
    }

    fun testVersionAutoSelectionModeDefaultIsDisabled() {
        assertEquals(VersionAutoSelectionMode.DISABLED, MavenUpSettings.State().versionAutoSelectionMode)
    }

    fun testSyncMavenAfterUpdateSelectionIsPersistedOnApply() {
        val settings = MavenUpSettings.getInstance()
        settings.state.syncMavenAfterUpdate = true

        val configurable = MavenUpConfigurable(project)
        configurable.createComponent()
        configurable.reset()
        assertFalse(configurable.isModified)

        val checkBox = configurable.field<com.intellij.ui.components.JBCheckBox>("syncMavenAfterUpdateCheckBox")
        checkBox.isSelected = false
        assertTrue("Änderung der Checkbox sollte isModified() true machen", configurable.isModified)

        configurable.apply()
        assertFalse(settings.state.syncMavenAfterUpdate)

        settings.state.syncMavenAfterUpdate = true
    }

    fun testStopAfterCentralSuccessSelectionIsPersistedOnApply() {
        val settings = MavenUpSettings.getInstance()
        settings.state.stopAfterCentralSuccess = true

        val configurable = MavenUpConfigurable(project)
        configurable.createComponent()
        configurable.reset()
        assertFalse(configurable.isModified)

        val checkBox = configurable.field<com.intellij.ui.components.JBCheckBox>("stopAfterCentralSuccessCheckBox")
        checkBox.isSelected = false
        assertTrue("Änderung der Checkbox sollte isModified() true machen", configurable.isModified)

        configurable.apply()
        assertFalse(settings.state.stopAfterCentralSuccess)

        settings.state.stopAfterCentralSuccess = true
    }

    fun testOfferAllVersionsDefaultIsFalse() {
        assertFalse(MavenUpSettings.State().offerAllVersions)
    }

    fun testConfirmVersionResetDefaultIsTrue() {
        assertTrue(MavenUpSettings.State().confirmVersionReset)
    }

    fun testConfirmVersionResetSelectionIsPersistedOnApply() {
        val settings = MavenUpSettings.getInstance()
        settings.state.confirmVersionReset = true

        val configurable = MavenUpConfigurable(project)
        configurable.createComponent()
        configurable.reset()
        assertFalse(configurable.isModified)

        val checkBox = configurable.field<com.intellij.ui.components.JBCheckBox>("confirmVersionResetCheckBox")
        checkBox.isSelected = false
        assertTrue("Änderung der Checkbox sollte isModified() true machen", configurable.isModified)

        configurable.apply()
        assertFalse(settings.state.confirmVersionReset)

        settings.state.confirmVersionReset = true
    }

    fun testOfferAllVersionsSelectionIsPersistedOnApply() {
        val settings = MavenUpSettings.getInstance()
        settings.state.offerAllVersions = false

        val configurable = MavenUpConfigurable(project)
        configurable.createComponent()
        configurable.reset()
        assertFalse(configurable.isModified)

        val checkBox = configurable.field<com.intellij.ui.components.JBCheckBox>("offerAllVersionsCheckBox")
        checkBox.isSelected = true
        assertTrue("Änderung der Checkbox sollte isModified() true machen", configurable.isModified)

        configurable.apply()
        assertTrue(settings.state.offerAllVersions)

        settings.state.offerAllVersions = false
    }

    fun testVersionAutoSelectionModeSelectionIsPersistedOnApply() {
        val settings = MavenUpSettings.getInstance()
        settings.state.versionAutoSelectionMode = VersionAutoSelectionMode.LATEST

        val configurable = MavenUpConfigurable(project)
        configurable.createComponent()
        configurable.reset()
        assertFalse(configurable.isModified)

        @Suppress("UNCHECKED_CAST")
        val comboBox = configurable.field<com.intellij.openapi.ui.ComboBox<VersionAutoSelectionMode>>(
            "versionAutoSelectionModeComboBox"
        )
        comboBox.selectedItem = VersionAutoSelectionMode.LATEST_MINOR
        assertTrue("Änderung der Combobox sollte isModified() true machen", configurable.isModified)

        configurable.apply()
        assertEquals(VersionAutoSelectionMode.LATEST_MINOR, settings.state.versionAutoSelectionMode)

        settings.state.versionAutoSelectionMode = VersionAutoSelectionMode.DISABLED
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

    private fun waitForToken(configurable: MavenUpConfigurable, expectedToken: String) {
        val tokenField = configurable.field<javax.swing.JPasswordField>("ossIndexTokenField")
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (tokenField.password.concatToString() != expectedToken && System.nanoTime() < deadline) {
            UIUtil.dispatchAllInvocationEvents()
            Thread.sleep(10)
        }
        assertEquals(expectedToken, tokenField.password.concatToString())
    }

    private fun waitForCredentialsLoaded(configurable: MavenUpConfigurable) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!configurable.field<Boolean>("credentialsLoaded") && System.nanoTime() < deadline) {
            UIUtil.dispatchAllInvocationEvents()
            Thread.sleep(10)
        }
        assertTrue(configurable.field<Boolean>("credentialsLoaded"))
    }

    private inline fun <reified T> MavenUpConfigurable.field(name: String): T =
        javaClass.getDeclaredField(name)
            .apply { isAccessible = true }
            .get(this) as T
}
