package de.schwarzland.mavenup.service

import com.intellij.testFramework.fixtures.BasePlatformTestCase

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
        val fetcher: (Pair<String?, String>) -> Pair<Boolean, List<String>> = { repo ->
            called.add(repo.second)
            if (repo.second == "https://repo1.maven.org/maven2") {
                Pair(true, listOf("1.2.0"))
            } else {
                Pair(true, listOf("9.9.9"))
            }
        }

        val versions = service.collectVersionsFromRepositories(repositories, fetcher)

        assertEquals(listOf("https://repo1.maven.org/maven2"), called)
        assertEquals(setOf("1.2.0"), versions)
    }

    fun testCollectVersionsFromRepositoriesContinuesWhenCentralFails() {
        val service = DependencyApiService(project)
        val repositories = listOf(
            Pair<String?, String>("private-1", "https://private-1.example.org/maven"),
            Pair<String?, String>("central", "https://repo1.maven.org/maven2"),
            Pair<String?, String>("private-2", "https://private-2.example.org/maven")
        )
        val called = mutableListOf<String>()
        val fetcher: (Pair<String?, String>) -> Pair<Boolean, List<String>> = { repo ->
            called.add(repo.second)
            if (repo.second == "https://repo1.maven.org/maven2") {
                Pair(false, emptyList())
            } else {
                Pair(true, listOf("1.0.0"))
            }
        }

        val versions = service.collectVersionsFromRepositories(repositories, fetcher)

        assertEquals(
            listOf(
                "https://repo1.maven.org/maven2",
                "https://private-1.example.org/maven",
                "https://private-2.example.org/maven"
            ),
            called
        )
        assertEquals(setOf("1.0.0"), versions)
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
}
