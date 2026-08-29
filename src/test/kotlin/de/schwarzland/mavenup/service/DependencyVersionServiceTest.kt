package de.schwarzland.mavenup.service

import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Testet die Versionsauswahl-Logik des [DependencyVersionService] über injizierte Versionsdaten,
 * sodass keine echten Repository-Abfragen erfolgen.
 */
class DependencyVersionServiceTest : BasePlatformTestCase() {

    private fun serviceReturning(versions: List<String>): DependencyVersionService =
        DependencyVersionService(project, fetchVersions = { _, _, _ -> versions })

    private fun <T> withAutoSelectionMode(mode: VersionAutoSelectionMode, block: () -> T): T {
        val settings = MavenUpSettings.getInstance()
        val original = settings.state.versionAutoSelectionMode
        settings.state.versionAutoSelectionMode = mode
        try {
            return block()
        } finally {
            settings.state.versionAutoSelectionMode = original
        }
    }

    fun testFetchAvailableVersionsReturnsListsPerCoordinate() {
        val service = DependencyVersionService(
            project,
            fetchVersions = { groupId, artifactId, _ ->
                if (artifactId == "known") listOf("1.2.4", "1.2.0") else emptyList()
            }
        )

        val result = service.fetchAvailableVersions(
            mapOf("org.a:known" to "1.2.0", "org.a:empty" to "1.0.0"),
            EmptyProgressIndicator()
        )

        assertEquals(listOf("1.2.4", "1.2.0"), result["org.a:known"])
        assertFalse("Coordinates without versions are omitted", result.containsKey("org.a:empty"))
    }

    fun testFetchAvailableVersionsStopsWhenCancelled() {
        val indicator = EmptyProgressIndicator().apply { cancel() }
        val service = DependencyVersionService(project, fetchVersions = { _, _, _ -> listOf("9.9.9") })

        val result = service.fetchAvailableVersions(mapOf("org.a:lib" to "1.0.0"), indicator)

        assertTrue("No versions fetched when already cancelled", result.isEmpty())
    }

    fun testCheckArtifactUpdateSelectsNewestWhenModeLatest() {
        withAutoSelectionMode(VersionAutoSelectionMode.LATEST) {
            val service = serviceReturning(listOf("2.0.0", "1.5.0", "1.0.0"))
            val available = mutableMapOf<String, List<String>>()
            val selected = mutableMapOf<String, String>()

            service.checkArtifactUpdate("com.example", "lib", "1.0.0", EmptyProgressIndicator(), available, selected)

            assertEquals(listOf("2.0.0", "1.5.0", "1.0.0"), available["com.example:lib"])
            assertEquals("2.0.0", selected["com.example:lib"])
        }
    }

    fun testCheckArtifactUpdateKeepsCurrentWhenModeDisabled() {
        withAutoSelectionMode(VersionAutoSelectionMode.DISABLED) {
            val service = serviceReturning(listOf("2.0.0", "1.0.0"))
            val available = mutableMapOf<String, List<String>>()
            val selected = mutableMapOf<String, String>()

            service.checkArtifactUpdate("com.example", "lib", "1.0.0", EmptyProgressIndicator(), available, selected)

            assertEquals(listOf("2.0.0", "1.0.0"), available["com.example:lib"])
            assertEquals("1.0.0", selected["com.example:lib"])
        }
    }

    fun testCheckArtifactUpdateIgnoresEmptyVersionResult() {
        withAutoSelectionMode(VersionAutoSelectionMode.LATEST) {
            val service = serviceReturning(emptyList())
            val available = mutableMapOf<String, List<String>>()
            val selected = mutableMapOf<String, String>()

            service.checkArtifactUpdate("com.example", "lib", "1.0.0", EmptyProgressIndicator(), available, selected)

            assertTrue(available.isEmpty())
            assertTrue(selected.isEmpty())
        }
    }

    fun testIntersectVersionsReducesToCommonVersions() {
        withAutoSelectionMode(VersionAutoSelectionMode.DISABLED) {
            val service = serviceReturning(emptyList())
            val available = mutableMapOf(
                "com.example:a" to listOf("3.0.0", "2.0.0", "1.0.0"),
                "com.example:b" to listOf("2.0.0", "1.0.0")
            )
            val selected = mutableMapOf<String, String>()
            val currentVersions = mapOf("com.example:a" to "1.0.0", "com.example:b" to "1.0.0")

            service.intersectVersions(
                listOf("com.example:a", "com.example:b"),
                currentVersions,
                available,
                selected
            )

            assertEquals(listOf("2.0.0", "1.0.0"), available["com.example:a"])
            assertEquals(listOf("2.0.0", "1.0.0"), available["com.example:b"])
            assertEquals("1.0.0", selected["com.example:a"])
            assertEquals("1.0.0", selected["com.example:b"])
        }
    }

    fun testSearchVersionsReturnsEmptyResultWithoutMavenProjects() {
        val service = serviceReturning(listOf("2.0.0"))

        val result = service.searchVersions(emptyMap(), emptyMap(), EmptyProgressIndicator())

        assertTrue(result.availableVersions.isEmpty())
        assertTrue(result.selectedVersions.isEmpty())
    }
}
