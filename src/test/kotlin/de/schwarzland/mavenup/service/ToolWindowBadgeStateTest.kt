package de.schwarzland.mavenup.service

import de.schwarzland.mavenup.model.MavenUpBadgeState
import de.schwarzland.mavenup.model.VulnerabilitySeverity
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit-Tests für [determineBadgeState].
 *
 * Prüft die Rangfolge der Badge-Zustände, die Wirkung der konfigurierten Anzeigeoption
 * sowie die Grenzfälle ohne Funde und ohne Updates.
 */
class ToolWindowBadgeStateTest {

    @Test
    fun testCriticalSeverityYieldsSevereBadge() {
        assertEquals(
            MavenUpBadgeState.SEVERE_VULNERABILITIES,
            determineBadgeState(
                VulnerabilitySeverity.CRITICAL,
                hasUpdates = false,
                mode = ToolWindowBadgeMode.VULNERABILITIES_AND_UPDATES
            )
        )
    }

    @Test
    fun testHighSeverityYieldsSevereBadge() {
        assertEquals(
            MavenUpBadgeState.SEVERE_VULNERABILITIES,
            determineBadgeState(
                VulnerabilitySeverity.HIGH,
                hasUpdates = true,
                mode = ToolWindowBadgeMode.VULNERABILITIES
            )
        )
    }

    @Test
    fun testMediumSeverityYieldsWarningBadge() {
        assertEquals(
            MavenUpBadgeState.VULNERABILITIES,
            determineBadgeState(
                VulnerabilitySeverity.MEDIUM,
                hasUpdates = true,
                mode = ToolWindowBadgeMode.VULNERABILITIES_AND_UPDATES
            )
        )
    }

    @Test
    fun testUnknownSeverityStillYieldsWarningBadge() {
        assertEquals(
            MavenUpBadgeState.VULNERABILITIES,
            determineBadgeState(
                VulnerabilitySeverity.UNKNOWN,
                hasUpdates = false,
                mode = ToolWindowBadgeMode.VULNERABILITIES
            )
        )
    }

    @Test
    fun testUpdatesYieldInfoBadgeWithoutFindings() {
        assertEquals(
            MavenUpBadgeState.UPDATES,
            determineBadgeState(
                null,
                hasUpdates = true,
                mode = ToolWindowBadgeMode.VULNERABILITIES_AND_UPDATES
            )
        )
    }

    @Test
    fun testUpdatesAreIgnoredInVulnerabilitiesOnlyMode() {
        assertEquals(
            MavenUpBadgeState.NONE,
            determineBadgeState(
                null,
                hasUpdates = true,
                mode = ToolWindowBadgeMode.VULNERABILITIES
            )
        )
    }

    @Test
    fun testDisabledModeNeverShowsBadge() {
        assertEquals(
            MavenUpBadgeState.NONE,
            determineBadgeState(
                VulnerabilitySeverity.CRITICAL,
                hasUpdates = true,
                mode = ToolWindowBadgeMode.OFF
            )
        )
    }

    @Test
    fun testNothingToDoYieldsNoBadge() {
        assertEquals(
            MavenUpBadgeState.NONE,
            determineBadgeState(
                null,
                hasUpdates = false,
                mode = ToolWindowBadgeMode.VULNERABILITIES_AND_UPDATES
            )
        )
    }
}
