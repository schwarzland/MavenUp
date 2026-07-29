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
}
