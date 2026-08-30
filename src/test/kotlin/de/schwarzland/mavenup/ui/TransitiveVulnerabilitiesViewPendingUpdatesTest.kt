package de.schwarzland.mavenup.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.schwarzland.mavenup.model.VulnerabilityAdvisory
import de.schwarzland.mavenup.model.VulnerabilitySeverity

/**
 * Testet die Auswahl- und Update-Logik der [TransitiveVulnerabilitiesView], insbesondere das
 * Sammeln anstehender Pin-Updates aus der New-Version-Spalte.
 */
class TransitiveVulnerabilitiesViewPendingUpdatesTest : BasePlatformTestCase() {

    private fun buildView(): TransitiveVulnerabilitiesView {
        val view = TransitiveVulnerabilitiesView(project)
        val coordinate = "org.trans:lib:1.2.0"
        val advisories = mapOf(
            coordinate to listOf(
                VulnerabilityAdvisory(
                    id = "CVE-1",
                    severity = VulnerabilitySeverity.HIGH,
                    sources = setOf("OSV"),
                    fixedVersions = setOf("1.2.4")
                )
            )
        )
        view.update(
            advisories,
            setOf(coordinate),
            emptyMap(),
            mapOf("org.trans:lib" to listOf("1.2.4", "1.2.0"))
        )
        return view
    }

    fun testNoPendingUpdatesWithoutSelection() {
        val view = buildView()
        assertFalse(view.hasPendingUpdates())
        assertTrue(view.collectPendingUpdates().isEmpty())
    }

    fun testSelectedVersionProducesManagedDependencyUpdate() {
        val view = buildView()
        view.selectedVersions["org.trans:lib"] = "1.2.4"

        val pending = view.collectPendingUpdates()
        assertEquals(1, pending.size)
        assertEquals("org.trans", pending[0].groupId)
        assertEquals("lib", pending[0].artifactId)
        assertEquals("managed dependency", pending[0].type)
        assertEquals("1.2.0", pending[0].oldVersion)
        assertEquals("1.2.4", pending[0].newVersion)
        assertEquals(listOf("CVE-1"), pending[0].fixedVulnerabilities)
        assertTrue("Rein transitive Koordinaten sollten als transitiv markiert sein", pending[0].transitive)
    }

    fun testKnownCoordinateIsNotMarkedTransitive() {
        val view = TransitiveVulnerabilitiesView(project)
        val coordinate = "org.trans:lib:1.2.0"
        view.update(
            mapOf(
                coordinate to listOf(
                    VulnerabilityAdvisory(id = "CVE-1", severity = VulnerabilitySeverity.HIGH, sources = setOf("OSV"))
                )
            ),
            setOf(coordinate),
            mapOf("org.trans:lib" to "managed dependency"),
            mapOf("org.trans:lib" to listOf("1.2.4", "1.2.0"))
        )
        view.selectedVersions["org.trans:lib"] = "1.2.4"

        val pending = view.collectPendingUpdates()
        assertEquals(1, pending.size)
        assertFalse(
            "In der pom.xml deklarierte Koordinaten sollten nicht als transitiv markiert sein",
            pending[0].transitive
        )
    }

    fun testFixedVulnerabilitiesAreOrderedBySeverityDescending() {
        val view = TransitiveVulnerabilitiesView(project)
        val coordinate = "org.trans:lib:1.2.0"
        view.update(
            mapOf(
                coordinate to listOf(
                    VulnerabilityAdvisory(
                        id = "GHSA-low",
                        aliases = setOf("CVE-low"),
                        severity = VulnerabilitySeverity.LOW,
                        sources = setOf("OSV")
                    ),
                    VulnerabilityAdvisory(
                        id = "GHSA-critical",
                        aliases = setOf("CVE-critical"),
                        severity = VulnerabilitySeverity.CRITICAL,
                        sources = setOf("OSV")
                    ),
                    VulnerabilityAdvisory(
                        id = "GHSA-medium",
                        aliases = setOf("CVE-medium"),
                        severity = VulnerabilitySeverity.MEDIUM,
                        sources = setOf("OSV")
                    )
                )
            ),
            setOf(coordinate),
            emptyMap(),
            mapOf("org.trans:lib" to listOf("1.2.4", "1.2.0"))
        )
        view.selectedVersions["org.trans:lib"] = "1.2.4"

        val pending = view.collectPendingUpdates()
        assertEquals(1, pending.size)
        assertEquals(
            listOf("GHSA-critical", "GHSA-medium", "GHSA-low"),
            pending[0].fixedVulnerabilities
        )
        assertEquals(
            listOf("CVE-critical", "CVE-medium", "CVE-low"),
            pending[0].fixedVulnerabilityAliases
        )
    }

    fun testAdvisoriesBySeverityBreaksTiesByScoreAndId() {
        val sorted = advisoriesBySeverity(
            listOf(
                VulnerabilityAdvisory(
                    id = "GHSA-b",
                    severity = VulnerabilitySeverity.HIGH,
                    cvssScore = 7.5,
                    sources = setOf("OSV")
                ),
                VulnerabilityAdvisory(
                    id = "GHSA-a",
                    severity = VulnerabilitySeverity.HIGH,
                    cvssScore = 7.5,
                    sources = setOf("OSV")
                ),
                VulnerabilityAdvisory(
                    id = "GHSA-c",
                    severity = VulnerabilitySeverity.HIGH,
                    cvssScore = 8.8,
                    sources = setOf("OSV")
                )
            )
        )
        assertEquals(listOf("GHSA-c", "GHSA-a", "GHSA-b"), sorted.map { it.id })
    }

    fun testSelectingCurrentVersionIsNoUpdate() {
        val view = buildView()
        view.selectedVersions["org.trans:lib"] = "1.2.0"
        assertTrue(view.collectPendingUpdates().isEmpty())
    }

    fun testResetSelectionsClearsPendingUpdates() {
        val view = buildView()
        view.selectedVersions["org.trans:lib"] = "1.2.4"
        view.resetSelections()
        assertTrue(view.selectedVersions.isEmpty())
        assertTrue(view.collectPendingUpdates().isEmpty())
    }

    fun testUpdatePrunesSelectionsForRemovedCoordinates() {
        val view = buildView()
        view.selectedVersions["org.trans:lib"] = "1.2.4"
        view.update(emptyMap(), emptySet(), emptyMap(), emptyMap())
        assertTrue(view.selectedVersions.isEmpty())
    }

    fun testSelectHighestMajorVersionForDependencyPicksNewest() {
        val view = buildView()
        view.selectHighestMajorVersionForDependency("org.trans:lib")
        assertEquals("1.2.4", view.selectedVersions["org.trans:lib"])
    }

    fun testSelectHighestMinorVersionForDependencyStaysInSameMajor() {
        val view = buildView()
        view.selectHighestMinorVersionForDependency("org.trans:lib")
        assertEquals("1.2.4", view.selectedVersions["org.trans:lib"])
    }

    fun testSelectRecommendedVersionForDependencyUsesFixVersion() {
        val view = buildView()
        view.selectRecommendedVersionForDependency("org.trans:lib")
        assertEquals("1.2.4", view.selectedVersions["org.trans:lib"])
    }

    fun testResetVersionForDependencyClearsSelection() {
        val view = buildView()
        view.selectedVersions["org.trans:lib"] = "1.2.4"
        assertTrue(view.isVersionResetEnabledForDependency("org.trans:lib"))
        view.resetVersionForDependency("org.trans:lib")
        assertFalse(view.selectedVersions.containsKey("org.trans:lib"))
        assertFalse(view.isVersionResetEnabledForDependency("org.trans:lib"))
    }

    fun testSelectHighestMajorVersionForAllSelectsEveryRow() {
        val view = buildView()
        view.selectHighestMajorVersionForAll()
        assertEquals("1.2.4", view.selectedVersions["org.trans:lib"])
    }

    fun testSelectRecommendedVersionForAllSelectsEveryRow() {
        val view = buildView()
        view.selectRecommendedVersionForAll()
        assertEquals("1.2.4", view.selectedVersions["org.trans:lib"])
    }

    fun testSelectionEnablementReflectsAvailableData() {
        val view = buildView()
        assertTrue(view.isBulkVersionSelectionEnabled())
        assertTrue(view.hasRecommendedVersions())
        assertTrue(view.hasSelectableVersionsForDependency("org.trans:lib"))
        assertTrue(view.hasRecommendedVersionForDependency("org.trans:lib"))
    }

    fun testSelectionEnablementFalseForUnknownDependency() {
        val view = buildView()
        assertFalse(view.hasSelectableVersionsForDependency("org.other:missing"))
        assertFalse(view.hasRecommendedVersionForDependency("org.other:missing"))
        assertFalse(view.isVersionResetEnabledForDependency("org.other:missing"))
    }

    fun testRecommendedRowHeightIsAppliedAndSurvivesUpdate() {
        val view = TransitiveVulnerabilitiesView(project)
        val expected = recommendedTableRowHeight()
        assertEquals(expected, view.table.rowHeight)

        val coordinate = "org.trans:lib:1.2.0"
        view.update(
            mapOf(
                coordinate to listOf(
                    VulnerabilityAdvisory(id = "CVE-1", severity = VulnerabilitySeverity.HIGH, sources = setOf("OSV"))
                )
            ),
            setOf(coordinate),
            emptyMap(),
            mapOf("org.trans:lib" to listOf("1.2.4", "1.2.0"))
        )
        assertEquals("Row height stays pinned after populating rows", expected, view.table.rowHeight)
    }
}
