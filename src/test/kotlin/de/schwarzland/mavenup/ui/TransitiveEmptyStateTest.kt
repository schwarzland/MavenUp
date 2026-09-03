package de.schwarzland.mavenup.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests der reinen Hinweislogik nach einem Sicherheits-Scan
 * ([transitiveEmptyState] und [isNoVulnerabilitiesHintVisible]).
 */
class TransitiveEmptyStateTest {

    /** Ohne ausgeführten Scan fordert der Empty State zum Scan auf. */
    @Test
    fun `empty state without scan asks for a scan`() {
        assertEquals(
            TransitiveEmptyState.NOT_SCANNED,
            transitiveEmptyState(scanPerformed = false, hasTransitiveRows = false, hasDirectFindings = false)
        )
    }

    /** Vorhandene Zeilen bedeuten, dass ausschließlich der Filter die Ansicht leert. */
    @Test
    fun `empty state with rows reports the active filter`() {
        assertEquals(
            TransitiveEmptyState.NO_MATCHES,
            transitiveEmptyState(scanPerformed = true, hasTransitiveRows = true, hasDirectFindings = true)
        )
    }

    /** Zeilen aus einem früheren Scan haben Vorrang vor dem zurückgesetzten Scan-Status. */
    @Test
    fun `empty state with rows ignores a missing scan flag`() {
        assertEquals(
            TransitiveEmptyState.NO_MATCHES,
            transitiveEmptyState(scanPerformed = false, hasTransitiveRows = true, hasDirectFindings = false)
        )
    }

    /** Ausschließlich direkte Befunde werden im Empty State erklärt. */
    @Test
    fun `empty state with only direct findings explains them`() {
        assertEquals(
            TransitiveEmptyState.ONLY_DIRECT,
            transitiveEmptyState(scanPerformed = true, hasTransitiveRows = false, hasDirectFindings = true)
        )
    }

    /** Ein Scan ganz ohne Befunde meldet dies im Empty State. */
    @Test
    fun `empty state without any findings reports the clean scan`() {
        assertEquals(
            TransitiveEmptyState.NO_FINDINGS,
            transitiveEmptyState(scanPerformed = true, hasTransitiveRows = false, hasDirectFindings = false)
        )
    }

    /** Jeder Zustand verweist auf einen Bundle-Schlüssel. */
    @Test
    fun `every empty state has a bundle key`() {
        TransitiveEmptyState.entries.forEach { state ->
            assertTrue(state.name, state.bundleKey.startsWith("toolwindow.TransitiveVulnerabilities.emptyText."))
        }
    }

    /** Ohne Scan erscheint kein Erfolgshinweis. */
    @Test
    fun `hint stays hidden without a scan`() {
        assertFalse(isNoVulnerabilitiesHintVisible(scanPerformed = false, directFindings = 0, transitiveFindings = 0))
    }

    /** Direkte Befunde unterdrücken den Erfolgshinweis. */
    @Test
    fun `hint stays hidden for direct findings`() {
        assertFalse(isNoVulnerabilitiesHintVisible(scanPerformed = true, directFindings = 1, transitiveFindings = 0))
    }

    /** Transitive Befunde unterdrücken den Erfolgshinweis. */
    @Test
    fun `hint stays hidden for transitive findings`() {
        assertFalse(isNoVulnerabilitiesHintVisible(scanPerformed = true, directFindings = 0, transitiveFindings = 2))
    }

    /** Ein Scan ohne jeden Befund blendet den Erfolgshinweis ein. */
    @Test
    fun `hint appears for a scan without findings`() {
        assertTrue(isNoVulnerabilitiesHintVisible(scanPerformed = true, directFindings = 0, transitiveFindings = 0))
    }
}
