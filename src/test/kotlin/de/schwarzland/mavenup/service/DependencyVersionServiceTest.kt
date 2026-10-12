package de.schwarzland.mavenup.service

import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jetbrains.idea.maven.project.MavenProject

/**
 * Testet die Versionsauswahl-Logik des [DependencyVersionService] über injizierte Versionsdaten,
 * sodass keine echten Repository-Abfragen erfolgen.
 */
class DependencyVersionServiceTest : BasePlatformTestCase() {

    private fun serviceReturning(versions: List<String>): DependencyVersionService =
        DependencyVersionService(
            project,
            fetchAllVersions = { _, _, _ -> versions },
            applyVersionSettings = { fetched, _ -> fetched }
        )

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
            fetchAllVersions = { _, artifactId, _ ->
                if (artifactId == "known") listOf("1.2.4", "1.2.0") else emptyList()
            },
            applyVersionSettings = { fetched, _ -> fetched }
        )

        val result = service.fetchAvailableVersions(
            mapOf("org.a:known" to "1.2.0", "org.a:empty" to "1.0.0"),
            EmptyProgressIndicator()
        )

        assertEquals(listOf("1.2.4", "1.2.0"), result["org.a:known"])
        assertFalse("Coordinates without versions are omitted", result.containsKey("org.a:empty"))
    }

    fun testFetchAvailableVersionsReturnsUnfilteredVersions() {
        val service = DependencyVersionService(
            project,
            fetchAllVersions = { _, _, _ -> listOf("2.0.0", "1.0.0", "0.9.0") },
            applyVersionSettings = { _, _ -> emptyList() }
        )

        val result = service.fetchAvailableVersions(mapOf("org.a:lib" to "1.0.0"), EmptyProgressIndicator())

        assertEquals(listOf("2.0.0", "1.0.0", "0.9.0"), result["org.a:lib"])
    }

    fun testFetchAvailableVersionsStopsWhenCancelled() {
        val indicator = EmptyProgressIndicator().apply { cancel() }
        val service = serviceReturning(listOf("9.9.9"))

        val result = service.fetchAvailableVersions(mapOf("org.a:lib" to "1.0.0"), indicator)

        assertTrue("No versions fetched when already cancelled", result.isEmpty())
    }

    fun testCheckArtifactUpdateSelectsNewestWhenModeLatest() {
        withAutoSelectionMode(VersionAutoSelectionMode.LATEST) {
            val service = serviceReturning(listOf("2.0.0", "1.5.0", "1.0.0"))
            val available = mutableMapOf<String, List<String>>()
            val raw = mutableMapOf<String, List<String>>()
            val selected = mutableMapOf<String, String>()

            service.checkArtifactUpdate(
                "com.example",
                "lib",
                "1.0.0",
                EmptyProgressIndicator(),
                available,
                raw,
                selected
            )

            assertEquals(listOf("2.0.0", "1.5.0", "1.0.0"), available["com.example:lib"])
            assertEquals("2.0.0", selected["com.example:lib"])
        }
    }

    /** Zählt ein gecachtes Artefakt trotz mehrfacher Treffer nur einmal. */
    fun testCheckArtifactUpdateRecordsCachedArtifactOnce() {
        withAutoSelectionMode(VersionAutoSelectionMode.LATEST) {
            val service = DependencyVersionService(
                project,
                fetchAllVersions = { _, _, onCacheHit ->
                    onCacheHit()
                    listOf("2.0.0", "1.0.0")
                },
                applyVersionSettings = { fetched, _ -> fetched }
            )
            val cachedArtifactKeys = mutableSetOf<String>()

            service.checkArtifactUpdate(
                "com.example",
                "lib",
                "1.0.0",
                EmptyProgressIndicator(),
                mutableMapOf(),
                mutableMapOf(),
                mutableMapOf(),
                cachedArtifactKeys
            )
            service.checkArtifactUpdate(
                "com.example",
                "lib",
                "1.0.0",
                EmptyProgressIndicator(),
                mutableMapOf(),
                mutableMapOf(),
                mutableMapOf(),
                cachedArtifactKeys
            )

            assertEquals(setOf("com.example:lib"), cachedArtifactKeys)
        }
    }

    /** Live geladene Versionslisten werden nicht als Cache-Treffer gezählt. */
    fun testCheckArtifactUpdateDoesNotRecordLiveArtifactAsCached() {
        withAutoSelectionMode(VersionAutoSelectionMode.LATEST) {
            val service = serviceReturning(listOf("2.0.0", "1.0.0"))
            val cachedArtifactKeys = mutableSetOf<String>()

            service.checkArtifactUpdate(
                "com.example",
                "lib",
                "1.0.0",
                EmptyProgressIndicator(),
                mutableMapOf(),
                mutableMapOf(),
                mutableMapOf(),
                cachedArtifactKeys
            )

            assertTrue(cachedArtifactKeys.isEmpty())
        }
    }

    fun testCheckArtifactUpdateStoresUnfilteredVersionsSeparately() {
        withAutoSelectionMode(VersionAutoSelectionMode.LATEST) {
            val service = DependencyVersionService(
                project,
                fetchAllVersions = { _, _, _ -> listOf("2.0.0", "2.0.0-RC1", "1.0.0") },
                applyVersionSettings = { fetched, _ -> fetched.filterNot { it.contains("RC") } }
            )
            val available = mutableMapOf<String, List<String>>()
            val raw = mutableMapOf<String, List<String>>()
            val selected = mutableMapOf<String, String>()

            service.checkArtifactUpdate(
                "com.example",
                "lib",
                "1.0.0",
                EmptyProgressIndicator(),
                available,
                raw,
                selected
            )

            assertEquals(listOf("2.0.0", "1.0.0"), available["com.example:lib"])
            assertEquals(listOf("2.0.0", "2.0.0-RC1", "1.0.0"), raw["com.example:lib"])
        }
    }

    fun testCheckArtifactUpdateSkipsSelectionWhenAllVersionsFilteredOut() {
        withAutoSelectionMode(VersionAutoSelectionMode.LATEST) {
            val service = DependencyVersionService(
                project,
                fetchAllVersions = { _, _, _ -> listOf("2.0.0", "1.0.0") },
                applyVersionSettings = { _, _ -> emptyList() }
            )
            val available = mutableMapOf<String, List<String>>()
            val raw = mutableMapOf<String, List<String>>()
            val selected = mutableMapOf<String, String>()

            service.checkArtifactUpdate(
                "com.example",
                "lib",
                "1.0.0",
                EmptyProgressIndicator(),
                available,
                raw,
                selected
            )

            assertEquals(emptyList<String>(), available["com.example:lib"])
            assertEquals(listOf("2.0.0", "1.0.0"), raw["com.example:lib"])
            assertTrue(selected.isEmpty())
        }
    }

    fun testCheckArtifactUpdateKeepsCurrentWhenModeDisabled() {
        withAutoSelectionMode(VersionAutoSelectionMode.DISABLED) {
            val service = serviceReturning(listOf("2.0.0", "1.0.0"))
            val available = mutableMapOf<String, List<String>>()
            val raw = mutableMapOf<String, List<String>>()
            val selected = mutableMapOf<String, String>()

            service.checkArtifactUpdate(
                "com.example",
                "lib",
                "1.0.0",
                EmptyProgressIndicator(),
                available,
                raw,
                selected
            )

            assertEquals(listOf("2.0.0", "1.0.0"), available["com.example:lib"])
            assertTrue(selected.isEmpty())
        }
    }

    fun testCheckArtifactUpdateIgnoresEmptyVersionResult() {
        withAutoSelectionMode(VersionAutoSelectionMode.LATEST) {
            val service = serviceReturning(emptyList())
            val available = mutableMapOf<String, List<String>>()
            val raw = mutableMapOf<String, List<String>>()
            val selected = mutableMapOf<String, String>()

            service.checkArtifactUpdate(
                "com.example",
                "lib",
                "1.0.0",
                EmptyProgressIndicator(),
                available,
                raw,
                selected
            )

            assertTrue(available.isEmpty())
            assertTrue(raw.isEmpty())
            assertTrue(selected.isEmpty())
        }
    }

    fun testIntersectVersionsReducesToCommonVersions() {
        withAutoSelectionMode(VersionAutoSelectionMode.DISABLED) {
            val service = serviceReturning(emptyList())
            val available = mutableMapOf<String, List<String>>()
            val raw = mutableMapOf(
                "com.example:a" to listOf("3.0.0", "2.0.0", "1.0.0"),
                "com.example:b" to listOf("2.0.0", "1.0.0")
            )
            val selected = mutableMapOf<String, String>()
            val currentVersions = mapOf("com.example:a" to "1.0.0", "com.example:b" to "1.0.0")

            service.intersectVersions(
                listOf("com.example:a", "com.example:b"),
                currentVersions,
                available,
                raw,
                selected
            )

            assertEquals(listOf("2.0.0", "1.0.0"), available["com.example:a"])
            assertEquals(listOf("2.0.0", "1.0.0"), available["com.example:b"])
            assertEquals(listOf("2.0.0", "1.0.0"), raw["com.example:a"])
            assertTrue(selected.isEmpty())
        }
    }

    fun testIntersectVersionsAppliesSettingsToVisibleVersions() {
        withAutoSelectionMode(VersionAutoSelectionMode.DISABLED) {
            val service = DependencyVersionService(
                project,
                fetchAllVersions = { _, _, _ -> emptyList() },
                applyVersionSettings = { fetched, _ -> fetched.filterNot { it.contains("RC") } }
            )
            val available = mutableMapOf<String, List<String>>()
            val raw = mutableMapOf(
                "com.example:a" to listOf("2.0.0", "2.0.0-RC1", "1.0.0"),
                "com.example:b" to listOf("2.0.0", "2.0.0-RC1", "1.0.0")
            )
            val selected = mutableMapOf<String, String>()

            service.intersectVersions(
                listOf("com.example:a", "com.example:b"),
                mapOf("com.example:a" to "1.0.0", "com.example:b" to "1.0.0"),
                available,
                raw,
                selected
            )

            assertEquals(listOf("2.0.0", "1.0.0"), available["com.example:a"])
            assertEquals(listOf("2.0.0", "2.0.0-RC1", "1.0.0"), raw["com.example:a"])
        }
    }

    fun testSearchVersionsReturnsEmptyResultWithoutMavenProjects() {
        val service = serviceReturning(listOf("2.0.0"))

        val result = service.searchVersions(emptyMap(), emptyMap(), EmptyProgressIndicator())

        assertTrue(result.availableVersions.isEmpty())
        assertTrue(result.rawVersions.isEmpty())
        assertTrue(result.selectedVersions.isEmpty())
        assertEquals(0, result.cachedArtifactCount)
    }

    /** Verwendet den Schnappschuss auch fuer Filter und Vorauswahl statt einer Maven-Zeitstempelversion. */
    fun testProcessProjectUpdatesUsesSnapshotVersionAndFallsBackForUnknownCoordinates() {
        withAutoSelectionMode(VersionAutoSelectionMode.DISABLED) {
            val pom = myFixture.configureByText("pom.xml", """
                <project>
                    <dependencies>
                        <dependency><groupId>com.example</groupId><artifactId>snapshot</artifactId>
                            <version>5.0.0-20261005.135311-3</version></dependency>
                        <dependency><groupId>com.example</groupId><artifactId>fallback</artifactId>
                            <version>2.0.0</version></dependency>
                    </dependencies>
                </project>
            """.trimIndent())
            val filteredAgainst = mutableListOf<String>()
            val service = DependencyVersionService(
                project,
                fetchAllVersions = { _, _, _ -> listOf("5.1.0-SNAPSHOT", "5.0.0-SNAPSHOT") },
                applyVersionSettings = { versions, current ->
                    filteredAgainst.add(current)
                    versions
                }
            )
            val selected = mutableMapOf<String, String>()
            service.processProjectUpdates(
                MavenProject(pom.virtualFile).apply {
                    updateState(emptyList(), java.util.Properties(), emptyList())
                },
                mapOf("com.example:snapshot" to "5.0.0-SNAPSHOT"),
                EmptyProgressIndicator(),
                mutableMapOf(),
                mutableMapOf(),
                selected,
                mutableSetOf()
            )
            assertEquals(listOf("5.0.0-SNAPSHOT", "2.0.0"), filteredAgainst)
            assertTrue(selected.isEmpty())
        }
    }

    /** Deaktivierte Vorauswahl verwirft auch alte Auswahlen einer gemeinsamen Property. */
    fun testIntersectVersionsClearsSelectionsWhenDisabledOrIntersectionIsEmpty() {
        val keys = listOf("com.example:a", "com.example:b")
        for (mode in VersionAutoSelectionMode.entries) {
            withAutoSelectionMode(mode) {
                val service = serviceReturning(emptyList())
                val selected = keys.associateWith { "9.0.0" }.toMutableMap()
                service.intersectVersions(
                    keys,
                    keys.associateWith { "1.0.0" },
                    mutableMapOf(),
                    mutableMapOf(keys[0] to listOf("2.0.0"), keys[1] to listOf("3.0.0")),
                    selected
                )
                assertTrue(selected.isEmpty())
            }
        }
        withAutoSelectionMode(VersionAutoSelectionMode.DISABLED) {
            val selected = keys.associateWith { "9.0.0" }.toMutableMap()
            serviceReturning(emptyList()).intersectVersions(
                keys,
                keys.associateWith { "1.0.0" },
                mutableMapOf(),
                keys.associateWith { listOf("2.0.0", "1.0.0") }.toMutableMap(),
                selected
            )
            assertTrue(selected.isEmpty())
        }
    }
}
