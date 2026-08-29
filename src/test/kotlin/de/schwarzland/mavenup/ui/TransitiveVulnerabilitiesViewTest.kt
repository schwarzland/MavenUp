package de.schwarzland.mavenup.ui

import de.schwarzland.mavenup.model.VulnerabilityAdvisory
import de.schwarzland.mavenup.model.VulnerabilitySeverity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Testet die reine Logik [collectTransitiveVulnerabilityRows], die die Zeilen der Ansicht der
 * transitiven, verwundbaren Abhängigkeiten aufbereitet.
 */
class TransitiveVulnerabilitiesViewTest {

    /**
     * Erstellt eine Sicherheitswarnung mit dem gewünschten Schweregrad für Testzwecke.
     *
     * @param id Advisory-ID der Warnung.
     * @param severity Schweregrad der Warnung.
     * @return Die konstruierte [VulnerabilityAdvisory].
     */
    private fun advisory(id: String, severity: VulnerabilitySeverity): VulnerabilityAdvisory =
        VulnerabilityAdvisory(id = id, severity = severity, sources = setOf("TEST"))

    @Test
    fun testEmptyInputsProduceNoRows() {
        val rows = collectTransitiveVulnerabilityRows(emptyMap(), emptySet())
        assertTrue(rows.isEmpty())
    }

    @Test
    fun testOnlyTransitiveCoordinatesWithFindingsAreIncluded() {
        val advisories = mapOf(
            "org.a:vuln:1.0.0" to listOf(advisory("CVE-1", VulnerabilitySeverity.HIGH)),
            "org.a:clean:2.0.0" to emptyList(),
            "org.a:direct:3.0.0" to listOf(advisory("CVE-2", VulnerabilitySeverity.LOW))
        )
        val transitive = setOf("org.a:vuln:1.0.0", "org.a:clean:2.0.0")

        val rows = collectTransitiveVulnerabilityRows(advisories, transitive)

        assertEquals(1, rows.size)
        assertEquals("org.a", rows[0].groupId)
        assertEquals("vuln", rows[0].artifactId)
        assertEquals("1.0.0", rows[0].version)
        assertEquals(1, rows[0].cell.allAdvisories.size)
    }

    @Test
    fun testUnmanagedCoordinateUsesTransitiveTypeLabel() {
        val advisories = mapOf("org.a:vuln:1.0.0" to listOf(advisory("CVE-1", VulnerabilitySeverity.HIGH)))

        val rows = collectTransitiveVulnerabilityRows(
            advisories,
            setOf("org.a:vuln:1.0.0"),
            knownTypes = emptyMap(),
            transitiveTypeLabel = "transitive"
        )

        assertEquals(1, rows.size)
        assertEquals("transitive", rows[0].type)
    }

    @Test
    fun testManagedCoordinateUsesKnownType() {
        val advisories = mapOf("org.a:vuln:1.0.0" to listOf(advisory("CVE-1", VulnerabilitySeverity.HIGH)))

        val rows = collectTransitiveVulnerabilityRows(
            advisories,
            setOf("org.a:vuln:1.0.0"),
            knownTypes = mapOf("org.a:vuln" to "managed dependency"),
            transitiveTypeLabel = "transitive"
        )

        assertEquals(1, rows.size)
        assertEquals("managed dependency", rows[0].type)
    }

    @Test
    fun testCoordinateWithoutThreePartsIsSkipped() {
        val advisories = mapOf(
            "incomplete:coordinate" to listOf(advisory("CVE-1", VulnerabilitySeverity.HIGH))
        )
        val rows = collectTransitiveVulnerabilityRows(advisories, setOf("incomplete:coordinate"))
        assertTrue(rows.isEmpty())
    }

    @Test
    fun testRowsAreSortedBySeverityThenCount() {
        val advisories = mapOf(
            "org.a:low:1.0.0" to listOf(advisory("CVE-L", VulnerabilitySeverity.LOW)),
            "org.a:critical:1.0.0" to listOf(advisory("CVE-C", VulnerabilitySeverity.CRITICAL)),
            "org.a:highMany:1.0.0" to listOf(
                advisory("CVE-H1", VulnerabilitySeverity.HIGH),
                advisory("CVE-H2", VulnerabilitySeverity.HIGH)
            ),
            "org.a:highOne:1.0.0" to listOf(advisory("CVE-H3", VulnerabilitySeverity.HIGH))
        )
        val transitive = advisories.keys

        val rows = collectTransitiveVulnerabilityRows(advisories, transitive)

        assertEquals(listOf("critical", "highMany", "highOne", "low"), rows.map { it.artifactId })
    }

    @Test
    fun testVersionMayContainColon() {
        val coordinate = "org.a:artifact:1.0.0:classifier"
        val advisories = mapOf(coordinate to listOf(advisory("CVE-1", VulnerabilitySeverity.MEDIUM)))

        val rows = collectTransitiveVulnerabilityRows(advisories, setOf(coordinate))

        assertEquals(1, rows.size)
        assertEquals("1.0.0:classifier", rows[0].version)
    }

    @Test
    fun testRecommendedFixVersionPicksLowestFixResolvingAllAdvisories() {
        val advisories = listOf(
            VulnerabilityAdvisory(id = "CVE-1", sources = setOf("TEST"), fixedVersions = setOf("1.2.4", "2.0.0")),
            VulnerabilityAdvisory(id = "CVE-2", sources = setOf("TEST"), fixedVersions = setOf("1.3.0"))
        )
        assertEquals("1.3.0", recommendedFixVersion(advisories, "1.2.0"))
    }

    @Test
    fun testRecommendedFixVersionSkipsFixStillCoveredByLaterRange() {
        val advisories = listOf(
            VulnerabilityAdvisory(
                id = "CVE-1",
                sources = setOf("TEST"),
                affectedRanges = setOf("< 4.1.16"),
                fixedVersions = setOf("4.1.16")
            ),
            VulnerabilityAdvisory(
                id = "CVE-2",
                sources = setOf("TEST"),
                affectedRanges = setOf("< 4.1.16", ">= 4.1.16, < 4.1.17"),
                fixedVersions = setOf("4.1.16", "4.1.17")
            )
        )
        assertEquals("4.1.17", recommendedFixVersion(advisories, "4.1.10"))
    }

    @Test
    fun testRecommendedFixVersionKeepsLowestFixWhenRangesAreDisjoint() {
        val advisories = listOf(
            VulnerabilityAdvisory(
                id = "CVE-1",
                sources = setOf("TEST"),
                affectedRanges = setOf(">= 1.0.0, < 1.2.4", ">= 2.0.0, < 2.0.5"),
                fixedVersions = setOf("1.2.4", "2.0.5")
            )
        )
        assertEquals("1.2.4", recommendedFixVersion(advisories, "1.2.0"))
    }

    @Test
    fun testRecommendedFixVersionFallsBackToHighestFixWhenNoVersionResolvesAll() {
        val advisories = listOf(
            VulnerabilityAdvisory(
                id = "CVE-1",
                sources = setOf("TEST"),
                affectedRanges = setOf(">= 1.0.0"),
                fixedVersions = setOf("1.2.4")
            ),
            VulnerabilityAdvisory(id = "CVE-2", sources = setOf("TEST"), fixedVersions = setOf("1.3.0"))
        )
        assertEquals("1.3.0", recommendedFixVersion(advisories, "1.2.0"))
    }

    @Test
    fun testRecommendedFixVersionIgnoresLowerOrEqualFixes() {
        val advisories = listOf(
            VulnerabilityAdvisory(id = "CVE-1", sources = setOf("TEST"), fixedVersions = setOf("1.0.0", "1.2.0"))
        )
        assertEquals("", recommendedFixVersion(advisories, "1.2.0"))
    }

    @Test
    fun testRecommendedFixVersionEmptyWhenNoFixedVersions() {
        val advisories = listOf(advisory("CVE-1", VulnerabilitySeverity.HIGH))
        assertEquals("", recommendedFixVersion(advisories, "1.0.0"))
    }

    @Test
    fun testCollectRowsExposesRecommendedVersion() {
        val advisories = mapOf(
            "org.a:vuln:1.2.0" to listOf(
                VulnerabilityAdvisory(id = "CVE-1", sources = setOf("TEST"), fixedVersions = setOf("1.2.4"))
            )
        )
        val rows = collectTransitiveVulnerabilityRows(advisories, setOf("org.a:vuln:1.2.0"))
        assertEquals("1.2.4", rows[0].recommendedVersion)
    }
}
