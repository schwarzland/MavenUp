package de.schwarzland.mavenup.model

import org.apache.maven.artifact.versioning.ComparableVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AffectedVersionRangeTest {
    private fun version(value: String) = ComparableVersion(value)

    @Test
    fun testParseRangeWithLowerAndUpperBound() {
        val range = parseAffectedVersionRange(">= 1.0.0, < 1.2.4")

        assertEquals(version("1.0.0"), range?.introduced)
        assertEquals(version("1.2.4"), range?.fixed)
    }

    @Test
    fun testParseRangeWithUpperBoundOnly() {
        val range = parseAffectedVersionRange("< 1.2.4")

        assertNull(range?.introduced)
        assertEquals(version("1.2.4"), range?.fixed)
    }

    @Test
    fun testParseRangeWithLowerBoundOnly() {
        val range = parseAffectedVersionRange(">= 2.0.0")

        assertEquals(version("2.0.0"), range?.introduced)
        assertNull(range?.fixed)
    }

    @Test
    fun testParseRangeWithInclusiveUpperBound() {
        val range = parseAffectedVersionRange(">= 8.5.0, <= 8.5.100")

        assertEquals(version("8.5.0"), range?.introduced)
        assertNull(range?.fixed)
        assertEquals(version("8.5.100"), range?.lastAffected)
    }

    @Test
    fun testContainsRespectsInclusiveUpperBound() {
        val range = AffectedVersionRange(version("8.5.0"), null, version("8.5.100"))

        assertTrue(range.contains(version("8.5.100")))
        assertFalse(range.contains(version("8.5.101")))
        assertFalse(range.contains(version("8.4.0")))
    }

    @Test
    fun testIsFixedInRespectsLastAffectedUpperBound() {
        val advisory = VulnerabilityAdvisory(
            id = "CVE-1",
            sources = setOf("TEST"),
            affectedRanges = setOf(">= 4.0.0, <= 4.4.9", ">= 4.5.0, < 4.5.12", ">= 5.0.0, < 5.0.6"),
            fixedVersions = setOf("4.5.12", "5.0.6")
        )

        assertFalse(advisory.isFixedIn(version("4.5.11")))
        assertTrue(advisory.isFixedIn(version("4.5.12")))
    }

    @Test
    fun testParseRangeReturnsNullForUnknownFormat() {
        assertNull(parseAffectedVersionRange("all versions"))
        assertNull(parseAffectedVersionRange(""))
    }

    @Test
    fun testParseRangesSkipsUnparsableEntries() {
        val ranges = parseAffectedVersionRanges(listOf("< 1.2.4", "unknown"))

        assertEquals(1, ranges.size)
    }

    @Test
    fun testContainsRespectsInclusiveLowerAndExclusiveUpperBound() {
        val range = AffectedVersionRange(version("1.0.0"), version("1.2.4"))

        assertFalse(range.contains(version("0.9.0")))
        assertTrue(range.contains(version("1.0.0")))
        assertTrue(range.contains(version("1.2.3")))
        assertFalse(range.contains(version("1.2.4")))
    }

    @Test
    fun testContainsWithOpenBounds() {
        assertTrue(AffectedVersionRange(null, null).contains(version("1.0.0")))
        assertTrue(AffectedVersionRange(version("1.0.0"), null).contains(version("9.9.9")))
        assertTrue(AffectedVersionRange(null, version("1.0.0")).contains(version("0.1.0")))
    }

    @Test
    fun testIsFixedInUsesAffectedRanges() {
        val advisory = VulnerabilityAdvisory(
            id = "CVE-1",
            sources = setOf("TEST"),
            affectedRanges = setOf("< 4.1.16", ">= 4.1.16, < 4.1.17"),
            fixedVersions = setOf("4.1.16", "4.1.17")
        )

        assertFalse(advisory.isFixedIn(version("4.1.16")))
        assertTrue(advisory.isFixedIn(version("4.1.17")))
    }

    @Test
    fun testIsFixedInFallsBackToFixedVersionsWithoutRanges() {
        val advisory = VulnerabilityAdvisory(
            id = "CVE-1",
            sources = setOf("TEST"),
            fixedVersions = setOf("1.2.4")
        )

        assertFalse(advisory.isFixedIn(version("1.2.3")))
        assertTrue(advisory.isFixedIn(version("1.2.4")))
    }

    @Test
    fun testIsFixedInReturnsTrueWithoutAnyVersionInformation() {
        val advisory = VulnerabilityAdvisory(id = "CVE-1", sources = setOf("TEST"))

        assertTrue(advisory.isFixedIn(version("1.0.0")))
    }
}
