package de.schwarzland.mavenup.ui

import de.schwarzland.mavenup.service.VersionAutoSelectionMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Testet die zustandslosen Auto-Selektions-Helfer [extractLeadingMajorNumber],
 * [latestVersionWithinSameMajor], [selectableRecommendedVersion] und [chooseAutoSelectedVersion].
 */
class VersionAutoSelectionTest {

    @Test
    fun testExtractLeadingMajorNumberParsesNumericPrefix() {
        assertEquals(2, extractLeadingMajorNumber("2.7.18"))
        assertEquals(1, extractLeadingMajorNumber("1-RC1"))
        assertEquals(3, extractLeadingMajorNumber("  3.0.0 "))
    }

    @Test
    fun testExtractLeadingMajorNumberReturnsNullForNonNumeric() {
        assertNull(extractLeadingMajorNumber("RELEASE"))
        assertNull(extractLeadingMajorNumber(""))
    }

    @Test
    fun testLatestVersionWithinSameMajorPicksHighestOfSameMajor() {
        val versions = listOf("3.0.0", "2.5.0", "2.3.0", "1.0.0")
        assertEquals("2.5.0", latestVersionWithinSameMajor("2.1.0", versions))
    }

    @Test
    fun testLatestVersionWithinSameMajorReturnsNullWhenNoSameMajor() {
        val versions = listOf("3.0.0", "4.0.0")
        assertNull(latestVersionWithinSameMajor("2.1.0", versions))
    }

    @Test
    fun testLatestVersionWithinSameMajorReturnsNullForNonNumericCurrent() {
        assertNull(latestVersionWithinSameMajor("RELEASE", listOf("1.0.0", "2.0.0")))
    }

    @Test
    fun testChooseAutoSelectedVersionDisabledKeepsCurrent() {
        assertEquals(
            "1.0.0",
            chooseAutoSelectedVersion("1.0.0", listOf("2.0.0", "1.0.0"), VersionAutoSelectionMode.DISABLED)
        )
    }

    @Test
    fun testChooseAutoSelectedVersionLatestPicksNewest() {
        assertEquals(
            "2.0.0",
            chooseAutoSelectedVersion("1.0.0", listOf("2.0.0", "1.5.0", "1.0.0"), VersionAutoSelectionMode.LATEST)
        )
    }

    @Test
    fun testChooseAutoSelectedVersionLatestMinorStaysInMajor() {
        assertEquals(
            "1.5.0",
            chooseAutoSelectedVersion(
                "1.0.0",
                listOf("2.0.0", "1.5.0", "1.2.0", "1.0.0"),
                VersionAutoSelectionMode.LATEST_MINOR
            )
        )
    }

    @Test
    fun testChooseAutoSelectedVersionKeepsCurrentWhenNewestEqualsCurrent() {
        assertEquals(
            "2.0.0",
            chooseAutoSelectedVersion("2.0.0", listOf("2.0.0", "1.0.0"), VersionAutoSelectionMode.LATEST)
        )
    }

    @Test
    fun testChooseAutoSelectedVersionKeepsCurrentWhenNoVersions() {
        assertEquals(
            "1.0.0",
            chooseAutoSelectedVersion("1.0.0", emptyList(), VersionAutoSelectionMode.LATEST)
        )
    }

    @Test
    fun testSelectableRecommendedVersionPrefersExactMatch() {
        assertEquals(
            "1.2.0",
            selectableRecommendedVersion("1.2.0", listOf("2.0.0", "1.2.0", "1.0.0"))
        )
    }

    @Test
    fun testSelectableRecommendedVersionFallsBackToLowestHigherVersion() {
        assertEquals(
            "1.3.0",
            selectableRecommendedVersion("1.2.0", listOf("2.0.0", "1.3.0", "1.1.0"))
        )
    }

    @Test
    fun testSelectableRecommendedVersionReturnsEmptyWhenNoVersionReachesRecommendation() {
        assertEquals("", selectableRecommendedVersion("3.0.0", listOf("2.0.0", "1.0.0")))
    }

    @Test
    fun testSelectableRecommendedVersionReturnsEmptyForMissingInput() {
        assertEquals("", selectableRecommendedVersion("", listOf("1.0.0")))
        assertEquals("", selectableRecommendedVersion("1.0.0", emptyList()))
    }
}
