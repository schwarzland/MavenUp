package de.schwarzland.mavenup.ui

import de.schwarzland.mavenup.service.MavenUpSettings
import de.schwarzland.mavenup.service.OssIndexCredentialStore
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

    fun testCredentialLookupRunsOutsideEdtAndIsModifiedDoesNotReloadIt() {
        val lookupCompleted = CountDownLatch(1)
        val lookupRanOnEdt = AtomicBoolean(true)
        val lookupCount = AtomicInteger()
        val credentialStore = object : OssIndexCredentialStore {
            override fun store(username: String, token: String) = Unit

            override fun retrieve(): Credentials? {
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
        val settings = MavenUpSettings.getInstance(project)
        settings.state.jumpOnSingleClick = false
        settings.state.selectLatestVersion = true
        settings.state.hideUnstableVersions = false
        settings.state.hiddenVersionQualifiers = "rc"
        settings.state.checkTransitiveDependencies = true
        settings.state.ossIndexEnabled = false
        settings.state.ossIndexUsername = ""

        val credentialStore = object : OssIndexCredentialStore {
            override fun store(username: String, token: String) = Unit
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

    fun testEnabledOssIndexMarksCredentialsRequiredAndRejectsMissingToken() {
        val credentialStore = object : OssIndexCredentialStore {
            override fun store(username: String, token: String) = Unit
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
        configurable.field<javax.swing.JTextField>("ossIndexUsernameField").text = "user@example.test"

        assertEquals(
            "OSS Index username/email (required)",
            configurable.field<javax.swing.JLabel>("ossIndexUsernameLabel").text
        )
        assertEquals(
            "OSS Index API token (required)",
            configurable.field<javax.swing.JLabel>("ossIndexTokenLabel").text
        )

        try {
            configurable.apply()
            fail("Applying enabled OSS Index settings without a token should fail")
        } catch (exception: ConfigurationException) {
            assertEquals(
                "Username/email and API token are required when Sonatype OSS Index is enabled.",
                exception.message
            )
        }
    }

    fun testApplyDoesNotBlockOtherSettingsWhileExistingCredentialsLoad() {
        val loadStarted = CountDownLatch(1)
        val releaseLoad = CountDownLatch(1)
        val credentialStore = object : OssIndexCredentialStore {
            override fun store(username: String, token: String) = Unit
            override fun retrieve(): Credentials {
                loadStarted.countDown()
                assertTrue(releaseLoad.await(5, TimeUnit.SECONDS))
                return Credentials("user@example.test", "secret-token")
            }
        }
        val settings = MavenUpSettings.getInstance(project)
        settings.state.ossIndexEnabled = true
        settings.state.ossIndexUsername = "user@example.test"
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
