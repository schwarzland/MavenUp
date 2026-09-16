package de.schwarzland.mavenup.service

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
}
