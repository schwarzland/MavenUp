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

    fun testEnforcedRowHeightIsAppliedAndSurvivesUpdate() {
        val view = TransitiveVulnerabilitiesView(project)
        view.enforcedRowHeight = 17
        assertEquals(17, view.table.rowHeight)

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
        assertEquals("Row height stays pinned after populating rows", 17, view.table.rowHeight)
    }
}
