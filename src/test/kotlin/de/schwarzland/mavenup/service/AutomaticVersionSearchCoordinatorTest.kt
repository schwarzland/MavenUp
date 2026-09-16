package de.schwarzland.mavenup.service

import de.schwarzland.mavenup.ui.RefreshRow
import de.schwarzland.mavenup.ui.RefreshSnapshot
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit-Tests für die zustandslose Veröffentlichungsentscheidung der automatischen Versionssuche.
 */
class AutomaticVersionSearchCoordinatorTest {

    /**
     * Ein nicht abgebrochenes Ergebnis der aktuellen Generation eines offenen Projekts wird veröffentlicht.
     */
    @Test
    fun publishesCurrentResultForOpenProject() {
        assertTrue(
            shouldPublishAutomaticVersionSearchResult(
                isCancelled = false,
                isCurrentGeneration = true,
                isProjectDisposed = false
            )
        )
    }

    /**
     * Abgebrochene, überholte oder zu einem geschlossenen Projekt gehörende Ergebnisse werden verworfen.
     */
    @Test
    fun doesNotPublishInvalidResults() {
        assertFalse(shouldPublishAutomaticVersionSearchResult(true, true, false))
        assertFalse(shouldPublishAutomaticVersionSearchResult(false, false, false))
        assertFalse(shouldPublishAutomaticVersionSearchResult(false, true, true))
    }

    /**
     * Ein höheres erstes Listenelement gegenüber der aktuellen Version aktiviert den Update-Badge.
     */
    @Test
    fun detectsAvailableVersionUpdates() {
        val snapshot = RefreshSnapshot(
            rows = listOf(RefreshRow("org.example", "library", "", "dependency", "1.0.0")),
            dependencyProperties = emptyMap()
        )
        val result = VersionSearchResult(
            availableVersions = mapOf("org.example:library" to listOf("2.0.0", "1.0.0")),
            rawVersions = emptyMap(),
            selectedVersions = emptyMap()
        )

        assertTrue(hasAvailableVersionUpdates(snapshot, result))
    }

    /**
     * Leere oder mit der aktuellen Version beginnende Listen aktivieren keinen Update-Badge.
     */
    @Test
    fun doesNotDetectUnavailableVersionUpdates() {
        val snapshot = RefreshSnapshot(
            rows = listOf(RefreshRow("org.example", "library", "", "dependency", "1.0.0")),
            dependencyProperties = emptyMap()
        )
        val result = VersionSearchResult(
            availableVersions = mapOf(
                "org.example:library" to listOf("1.0.0"),
                "org.example:empty" to emptyList()
            ),
            rawVersions = emptyMap(),
            selectedVersions = emptyMap()
        )

        assertFalse(hasAvailableVersionUpdates(snapshot, result))
    }
}
