package de.schwarzland.mavenup.service

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.apache.maven.artifact.versioning.ComparableVersion
import org.w3c.dom.Document
import java.io.ByteArrayInputStream
import java.nio.file.Files
import javax.xml.parsers.DocumentBuilderFactory

class DependencyApiServiceTest : BasePlatformTestCase() {
    fun testResolveCredentialValueWithSystemPropertyPlaceholder() {
        val service = DependencyApiService(project)
        val key = "mavenup.test.credential.service"
        val expected = "secret-value"
        try {
            System.setProperty(key, expected)
            val resolved = service.resolveCredentialValue(
                "${'$'}{$key}",
                "test-server",
                "username"
            )
            assertEquals(expected, resolved)
        } finally {
            System.clearProperty(key)
        }
    }

    fun testResolveCredentialValueWithMissingEnvPlaceholderReturnsNull() {
        val service = DependencyApiService(project)
        val missingVar = "MAVENUP_TEST_MISSING_ENV_1234567890"

        val resolved = service.resolveCredentialValue(
            "${'$'}{env.$missingVar}",
            "test-server",
            "password"
        )

        assertNull(resolved)
    }

    fun testResolveCredentialValueWithMissingPropertyPlaceholderReturnsNull() {
        val service = DependencyApiService(project)
        val missingVar = "MAVENUP_TEST_MISSING_PROP_1234567890"

        val resolved = service.resolveCredentialValue(
            "${'$'}{$missingVar}",
            "test-server",
            "username"
        )

        assertNull(resolved)
    }

    fun testResolveCredentialValueWithPlainTextKeepsValue() {
        val service = DependencyApiService(project)

        val resolved = service.resolveCredentialValue(
            " plain-secret ",
            "test-server",
            "username"
        )

        assertEquals("plain-secret", resolved)
    }

    fun testResolveCredentialValueWithBlankInputReturnsNull() {
        val service = DependencyApiService(project)

        val resolved = service.resolveCredentialValue(
            "   ",
            "test-server",
            "username"
        )

        assertNull(resolved)
    }

    fun testResolveCredentialValueWithNullInputReturnsNull() {
        val service = DependencyApiService(project)

        val resolved = service.resolveCredentialValue(null, "test-server", "username")

        assertNull(resolved)
    }

    fun testFindServerCredentialsFallsBackToRepositoryUrl() {
        val service = DependencyApiService(project)
        val repo = Pair<String?, String>(null, "https://repo.example.org/maven")
        val creds = mapOf<String, Pair<String?, String?>>(
            "https://repo.example.org/maven" to Pair("user", "pass")
        )

        val resolved = service.findServerCredentials(repo, creds)

        assertEquals("user", resolved?.first)
        assertEquals("pass", resolved?.second)
    }

    fun testFindServerCredentialsFallsBackToHost() {
        val service = DependencyApiService(project)
        val repo = Pair<String?, String>(null, "https://repo.example.org/maven")
        val creds = mapOf<String, Pair<String?, String?>>(
            "repo.example.org" to Pair("host-user", "host-pass")
        )

        val resolved = service.findServerCredentials(repo, creds)

        assertEquals("host-user", resolved?.first)
        assertEquals("host-pass", resolved?.second)
    }

    fun testFindServerCredentialsPrefersServerIdOverUrl() {
        val service = DependencyApiService(project)
        val repo = Pair<String?, String>("my-server", "https://repo.example.org/maven")
        val creds = mapOf<String, Pair<String?, String?>>(
            "my-server" to Pair("id-user", "id-pass"),
            "https://repo.example.org/maven" to Pair("url-user", "url-pass")
        )

        val resolved = service.findServerCredentials(repo, creds)

        assertEquals("id-user", resolved?.first)
        assertEquals("id-pass", resolved?.second)
    }

    fun testFindServerCredentialsReturnsNullWhenNoneMatch() {
        val service = DependencyApiService(project)
        val repo = Pair<String?, String>("unknown-server", "https://unknown.example.org/maven")
        val creds = mapOf<String, Pair<String?, String?>>(
            "other-server" to Pair("user", "pass")
        )

        val resolved = service.findServerCredentials(repo, creds)

        assertNull(resolved)
    }

    fun testCollectVersionsFromRepositoriesPrioritizesCentralAndShortCircuitsOnSuccess() {
        val service = DependencyApiService(project)
        val repositories = listOf(
            Pair<String?, String>("private-1", "https://private-1.example.org/maven"),
            Pair<String?, String>("central", "https://repo1.maven.org/maven2"),
            Pair<String?, String>("private-2", "https://private-2.example.org/maven")
        )
        val called = mutableListOf<String>()
        val fetcher: (Pair<String?, String>) -> RepositoryVersions = { repo ->
            called.add(repo.second)
            if (repo.second == "https://repo1.maven.org/maven2") {
                RepositoryVersions(true, listOf("1.2.0"), "1.2.0")
            } else {
                RepositoryVersions(true, listOf("9.9.9"), "9.9.9")
            }
        }

        val collected = service.collectVersionsFromRepositories(repositories, true, fetcher)

        assertEquals(listOf("https://repo1.maven.org/maven2"), called)
        assertEquals(setOf("1.2.0"), collected.versions)
        assertEquals("1.2.0", collected.newestVersion)
    }

    fun testCollectVersionsFromRepositoriesContinuesWhenCentralFails() {
        val service = DependencyApiService(project)
        val repositories = listOf(
            Pair<String?, String>("private-1", "https://private-1.example.org/maven"),
            Pair<String?, String>("central", "https://repo1.maven.org/maven2"),
            Pair<String?, String>("private-2", "https://private-2.example.org/maven")
        )
        val called = mutableListOf<String>()
        val fetcher: (Pair<String?, String>) -> RepositoryVersions = { repo ->
            called.add(repo.second)
            if (repo.second == "https://repo1.maven.org/maven2") {
                RepositoryVersions(false, emptyList(), null)
            } else {
                RepositoryVersions(true, listOf("1.0.0"), "1.0.0")
            }
        }

        val collected = service.collectVersionsFromRepositories(repositories, true, fetcher)

        assertEquals(
            listOf(
                "https://repo1.maven.org/maven2",
                "https://private-1.example.org/maven",
                "https://private-2.example.org/maven"
            ),
            called
        )
        assertEquals(setOf("1.0.0"), collected.versions)
        assertEquals("1.0.0", collected.newestVersion)
    }

    fun testCollectVersionsFromRepositoriesContinuesAfterCentralWhenShortCircuitDisabled() {
        val service = DependencyApiService(project)
        val repositories = listOf(
            Pair<String?, String>("private-1", "https://private-1.example.org/maven"),
            Pair<String?, String>("central", "https://repo1.maven.org/maven2"),
            Pair<String?, String>("private-2", "https://private-2.example.org/maven")
        )
        val called = mutableListOf<String>()
        val fetcher: (Pair<String?, String>) -> RepositoryVersions = { repo ->
            called.add(repo.second)
            if (repo.second == "https://repo1.maven.org/maven2") {
                RepositoryVersions(true, listOf("1.2.0"), "1.2.0")
            } else {
                RepositoryVersions(true, listOf("9.9.9"), "9.9.9")
            }
        }

        val collected = service.collectVersionsFromRepositories(repositories, false, fetcher)

        assertEquals(
            listOf(
                "https://repo1.maven.org/maven2",
                "https://private-1.example.org/maven",
                "https://private-2.example.org/maven"
            ),
            called
        )
        assertEquals(setOf("1.2.0", "9.9.9"), collected.versions)
        // Maven Central wird als Referenz bevorzugt, auch wenn ein privates Repo eine numerisch höhere Version meldet.
        assertEquals("1.2.0", collected.newestVersion)
    }

    fun testExtractNewestFromMetadataPrefersReleaseOverLatest() {
        val service = DependencyApiService(project)
        val doc = parseMetadata(
            """
            <metadata>
              <versioning>
                <latest>2025-1234</latest>
                <release>24.0</release>
                <versions>
                  <version>2022-1234</version>
                  <version>2023-1234</version>
                  <version>24.0</version>
                  <version>2025-1234</version>
                </versions>
              </versioning>
            </metadata>
            """.trimIndent()
        )

        assertEquals("24.0", service.extractNewestFromMetadata(doc))
    }

    fun testExtractNewestFromMetadataFallsBackToLatestWhenReleaseMissing() {
        val service = DependencyApiService(project)
        val doc = parseMetadata(
            """
            <metadata>
              <versioning>
                <latest>3.2.0-SNAPSHOT</latest>
                <versions>
                  <version>3.1.0</version>
                  <version>3.2.0-SNAPSHOT</version>
                </versions>
              </versioning>
            </metadata>
            """.trimIndent()
        )

        assertEquals("3.2.0-SNAPSHOT", service.extractNewestFromMetadata(doc))
    }

    fun testExtractNewestFromMetadataReturnsNullWhenAbsent() {
        val service = DependencyApiService(project)
        val doc = parseMetadata(
            """
            <metadata>
              <versioning>
                <versions>
                  <version>1.0.0</version>
                </versions>
              </versioning>
            </metadata>
            """.trimIndent()
        )

        assertNull(service.extractNewestFromMetadata(doc))
    }

    fun testOrderWithNewestFirstMovesDeclaredNewestToFront() {
        val service = DependencyApiService(project)
        // Absteigend nach ComparableVersion; datumsbasierte Versionen stehen dadurch vor 24.0.
        val versions = listOf("2025-1234", "2023-1234", "2022-1234", "24.0")

        val ordered = service.orderWithNewestFirst(versions, "24.0")

        assertEquals(listOf("24.0", "2025-1234", "2023-1234", "2022-1234"), ordered)
    }

    fun testOrderWithNewestFirstKeepsOrderWhenNewestNullOrAbsent() {
        val service = DependencyApiService(project)
        val versions = listOf("2.0.0", "1.5.0", "1.0.0")

        assertEquals(versions, service.orderWithNewestFirst(versions, null))
        assertEquals(versions, service.orderWithNewestFirst(versions, "9.9.9"))
        // Bereits vorne stehende neueste Version bleibt unverändert.
        assertEquals(versions, service.orderWithNewestFirst(versions, "2.0.0"))
    }

    private fun parseMetadata(xml: String): Document {
        val builder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        return builder.parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
    }

    fun testResolveVersionFloorUsesCurrentVersionWhenOfferAllDisabled() {
        val service = DependencyApiService(project)

        val floor = service.resolveVersionFloor("2.0.0", offerAllVersions = false)

        assertEquals(ComparableVersion("2.0.0"), floor)
        assertTrue("Neuere Version sollte über der Grenze liegen", ComparableVersion("2.1.0") >= floor)
        assertFalse("Ältere Version sollte unter der Grenze liegen", ComparableVersion("1.9.0") >= floor)
    }

    fun testResolveVersionFloorAllowsOlderVersionsWhenOfferAllEnabled() {
        val service = DependencyApiService(project)

        val floor = service.resolveVersionFloor("2.0.0", offerAllVersions = true)

        assertTrue("Ältere Version sollte bei 'alle anbieten' die Grenze erfüllen", ComparableVersion("1.0.0") >= floor)
        assertTrue("Aktuelle Version sollte die Grenze erfüllen", ComparableVersion("2.0.0") >= floor)
        assertTrue("Neuere Version sollte die Grenze erfüllen", ComparableVersion("3.0.0") >= floor)
    }

    fun testFilterVersionsBySettingsWhenDisabledKeepsAll() {
        val settings = MavenUpSettings.getInstance()
        val previousHide = settings.state.hideUnstableVersions
        val previousQualifiers = settings.state.hiddenVersionQualifiers
        settings.state.hideUnstableVersions = false
        settings.state.hiddenVersionQualifiers = "rc,beta"

        val service = DependencyApiService(project)
        val input = listOf("2.0.0-RC1", "2.0.0-beta2", "2.0.0")
        try {
            val filtered = service.filterVersionsBySettings(input)
            assertEquals(input, filtered)
        } finally {
            settings.state.hideUnstableVersions = previousHide
            settings.state.hiddenVersionQualifiers = previousQualifiers
        }
    }

    fun testFilterVersionsBySettingsRemovesConfiguredQualifiers() {
        val settings = MavenUpSettings.getInstance()
        val previousHide = settings.state.hideUnstableVersions
        val previousQualifiers = settings.state.hiddenVersionQualifiers
        settings.state.hideUnstableVersions = true
        settings.state.hiddenVersionQualifiers = "rc,beta,milestone"

        val service = DependencyApiService(project)
        val input = listOf("2.0.0-RC1", "2.0.0-beta2", "2.0.0-MILESTONE-1", "2.0.0", "1.9.9")
        try {
            val filtered = service.filterVersionsBySettings(input)
            assertEquals(listOf("2.0.0", "1.9.9"), filtered)
        } finally {
            settings.state.hideUnstableVersions = previousHide
            settings.state.hiddenVersionQualifiers = previousQualifiers
        }
    }

    fun testFilterVersionsBySettingsWithBlankQualifiersKeepsAll() {
        val settings = MavenUpSettings.getInstance()
        val previousHide = settings.state.hideUnstableVersions
        val previousQualifiers = settings.state.hiddenVersionQualifiers
        settings.state.hideUnstableVersions = true
        settings.state.hiddenVersionQualifiers = " , ;"

        val service = DependencyApiService(project)
        val input = listOf("2.0.0-RC1", "2.0.0")
        try {
            val filtered = service.filterVersionsBySettings(input)
            assertEquals(input, filtered)
        } finally {
            settings.state.hideUnstableVersions = previousHide
            settings.state.hiddenVersionQualifiers = previousQualifiers
        }
    }

    fun testApplyVersionSettingsKeepsOnlyNewerVersionsByDefault() {
        val settings = MavenUpSettings.getInstance()
        val previousHide = settings.state.hideUnstableVersions
        val previousOfferAll = settings.state.offerAllVersions
        settings.state.hideUnstableVersions = false
        settings.state.offerAllVersions = false

        val service = DependencyApiService(project)
        try {
            val filtered = service.applyVersionSettings(listOf("3.0.0", "2.0.0", "1.0.0"), "2.0.0")
            assertEquals(listOf("3.0.0", "2.0.0"), filtered)
        } finally {
            settings.state.hideUnstableVersions = previousHide
            settings.state.offerAllVersions = previousOfferAll
        }
    }

    fun testApplyVersionSettingsKeepsOlderVersionsWhenOfferAllEnabled() {
        val settings = MavenUpSettings.getInstance()
        val previousHide = settings.state.hideUnstableVersions
        val previousOfferAll = settings.state.offerAllVersions
        settings.state.hideUnstableVersions = false
        settings.state.offerAllVersions = true

        val service = DependencyApiService(project)
        val input = listOf("3.0.0", "2.0.0", "1.0.0")
        try {
            assertEquals(input, service.applyVersionSettings(input, "2.0.0"))
        } finally {
            settings.state.hideUnstableVersions = previousHide
            settings.state.offerAllVersions = previousOfferAll
        }
    }

    fun testApplyVersionSettingsRemovesUnstableVersionsWhenHideEnabled() {
        val settings = MavenUpSettings.getInstance()
        val previousHide = settings.state.hideUnstableVersions
        val previousQualifiers = settings.state.hiddenVersionQualifiers
        val previousOfferAll = settings.state.offerAllVersions
        settings.state.hideUnstableVersions = true
        settings.state.hiddenVersionQualifiers = "rc"
        settings.state.offerAllVersions = true

        val service = DependencyApiService(project)
        try {
            val filtered = service.applyVersionSettings(listOf("3.0.0", "3.0.0-RC1", "1.0.0"), "2.0.0")
            assertEquals(listOf("3.0.0", "1.0.0"), filtered)
        } finally {
            settings.state.hideUnstableVersions = previousHide
            settings.state.hiddenVersionQualifiers = previousQualifiers
            settings.state.offerAllVersions = previousOfferAll
        }
    }

    fun testApplyVersionSettingsWithEmptyInputReturnsEmptyList() {
        val service = DependencyApiService(project)

        assertTrue(service.applyVersionSettings(emptyList(), "1.0.0").isEmpty())
    }

    fun testVersionHasQualifierIsCaseInsensitive() {
        val service = DependencyApiService(project)

        assertTrue(service.versionHasQualifier("2.0.0-RC1", "rc"))
        assertTrue(service.versionHasQualifier("2.0.0.beta2", "beta"))
        assertFalse(service.versionHasQualifier("2.0.0", "rc"))
    }

    fun testNormalizeSettingsIdTrimsAndHandlesBlank() {
        val service = DependencyApiService(project)

        assertEquals("my-id", service.normalizeSettingsId("  my-id  "))
        assertNull(service.normalizeSettingsId(""))
        assertNull(service.normalizeSettingsId(null))
    }

    fun testResolveMavenUserSettingsFilePrefersConfiguredPath() {
        val service = DependencyApiService(project)
        val tempDir = Files.createTempDirectory("mavenup-settings-prefers-configured")
        val configuredFile = tempDir.resolve("custom-settings.xml")
        val defaultDir = tempDir.resolve("home").resolve(".m2")
        Files.createDirectories(defaultDir)
        val defaultFile = defaultDir.resolve("settings.xml")
        Files.writeString(configuredFile, "<settings/>")
        Files.writeString(defaultFile, "<settings/>")

        val resolved = service.resolveMavenUserSettingsFile(configuredFile.toString(), tempDir.resolve("home").toString())

        assertEquals(configuredFile.toFile().canonicalFile, resolved?.canonicalFile)
    }

    fun testResolveMavenUserSettingsFileFallsBackToDefaultWhenConfiguredMissing() {
        val service = DependencyApiService(project)
        val tempDir = Files.createTempDirectory("mavenup-settings-fallback-default")
        val missingConfigured = tempDir.resolve("does-not-exist.xml")
        val defaultDir = tempDir.resolve("home").resolve(".m2")
        Files.createDirectories(defaultDir)
        val defaultFile = defaultDir.resolve("settings.xml")
        Files.writeString(defaultFile, "<settings/>")

        val resolved = service.resolveMavenUserSettingsFile(missingConfigured.toString(), tempDir.resolve("home").toString())

        assertEquals(defaultFile.toFile().canonicalFile, resolved?.canonicalFile)
    }

    fun testResolveMavenUserSettingsFileFallsBackToDefaultWhenConfiguredBlank() {
        val service = DependencyApiService(project)
        val tempDir = Files.createTempDirectory("mavenup-settings-fallback-blank")
        val defaultDir = tempDir.resolve("home").resolve(".m2")
        Files.createDirectories(defaultDir)
        val defaultFile = defaultDir.resolve("settings.xml")
        Files.writeString(defaultFile, "<settings/>")

        val resolved = service.resolveMavenUserSettingsFile("   ", tempDir.resolve("home").toString())

        assertEquals(defaultFile.toFile().canonicalFile, resolved?.canonicalFile)
    }

    fun testResolveMavenUserSettingsFileReturnsNullWhenNothingExists() {
        val service = DependencyApiService(project)
        val tempDir = Files.createTempDirectory("mavenup-settings-none")

        val resolved = service.resolveMavenUserSettingsFile("", tempDir.toString())

        assertNull(resolved)
    }
}
