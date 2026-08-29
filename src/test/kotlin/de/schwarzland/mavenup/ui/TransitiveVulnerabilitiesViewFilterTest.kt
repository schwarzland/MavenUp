package de.schwarzland.mavenup.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.schwarzland.mavenup.model.VulnerabilityAdvisory
import de.schwarzland.mavenup.model.VulnerabilitySeverity

/**
 * Testet die Filterzeile der [TransitiveVulnerabilitiesView]: Textfilter, Updates- und
 * Änderungs-Filter, deren Aktivierungszustand sowie das Zurücksetzen aller Filter.
 */
class TransitiveVulnerabilitiesViewFilterTest : BasePlatformTestCase() {

    private val withVersions = "org.trans:lib:1.2.0"
    private val withoutVersions = "org.other:tool:2.0.0"

    /**
     * Erzeugt eine gefüllte Ansicht mit zwei verwundbaren Koordinaten: eine mit abrufbaren
     * Versionen (Update verfügbar) und eine ohne.
     */
    private fun buildView(): TransitiveVulnerabilitiesView {
        val view = TransitiveVulnerabilitiesView(project)
        val advisories = mapOf(
            withVersions to listOf(
                VulnerabilityAdvisory(
                    id = "CVE-1",
                    severity = VulnerabilitySeverity.HIGH,
                    sources = setOf("OSV"),
                    fixedVersions = setOf("1.2.4")
                )
            ),
            withoutVersions to listOf(
                VulnerabilityAdvisory(id = "CVE-2", severity = VulnerabilitySeverity.LOW, sources = setOf("OSV"))
            )
        )
        view.update(
            advisories,
            setOf(withVersions, withoutVersions),
            emptyMap(),
            mapOf("org.trans:lib" to listOf("1.2.4", "1.2.0"))
        )
        return view
    }

    fun testAllRowsVisibleWithoutFilter() {
        val view = buildView()
        assertEquals(2, view.table.rowCount)
        assertFalse(view.filterPanel.isResetFiltersEnabled())
        assertFalse(view.isRowFilterHidingEntries())
    }

    fun testSearchTextFiltersByGroupId() {
        val view = buildView()
        view.filterPanel.filterBy("org.trans")
        assertEquals(1, view.table.rowCount)
        assertEquals("org.trans", view.table.getValueAt(0, TRANSITIVE_GROUP_ID_COLUMN))
        assertTrue(view.filterPanel.isResetFiltersEnabled())
        assertTrue(view.isRowFilterHidingEntries())
    }

    fun testSearchTextFiltersByArtifactIdCaseInsensitive() {
        val view = buildView()
        view.filterPanel.filterBy("TOOL")
        assertEquals(1, view.table.rowCount)
        assertEquals("tool", view.table.getValueAt(0, TRANSITIVE_ARTIFACT_ID_COLUMN))
    }

    fun testSearchTextWithoutMatchHidesAllRows() {
        val view = buildView()
        view.filterPanel.filterBy("does-not-exist")
        assertEquals(0, view.table.rowCount)
    }

    fun testResetAllFiltersRestoresEveryRow() {
        val view = buildView()
        view.filterPanel.filterBy("org.trans")
        view.filterPanel.updatesFilterComboBox.selectedItem = TriStateFilter.YES
        view.filterPanel.resetAllFilters()
        assertEquals("", view.filterPanel.searchTextField.text)
        assertEquals(TriStateFilter.ALL, view.filterPanel.updatesFilterComboBox.selectedItem)
        assertEquals(TriStateFilter.ALL, view.filterPanel.changesFilterComboBox.selectedItem)
        assertEquals(2, view.table.rowCount)
        assertFalse(view.filterPanel.isResetFiltersEnabled())
    }

    fun testUpdatesFilterShowsOnlyRowsWithNewerVersion() {
        val view = buildView()
        assertTrue(view.filterPanel.updatesFilterComboBox.isEnabled)

        view.filterPanel.updatesFilterComboBox.selectedItem = TriStateFilter.YES
        assertEquals(1, view.table.rowCount)
        assertEquals("lib", view.table.getValueAt(0, TRANSITIVE_ARTIFACT_ID_COLUMN))

        view.filterPanel.updatesFilterComboBox.selectedItem = TriStateFilter.NO
        assertEquals(1, view.table.rowCount)
        assertEquals("tool", view.table.getValueAt(0, TRANSITIVE_ARTIFACT_ID_COLUMN))
    }

    fun testUpdatesFilterIsDisabledWithoutAvailableVersions() {
        val view = TransitiveVulnerabilitiesView(project)
        view.update(emptyMap(), emptySet(), emptyMap(), emptyMap())
        assertFalse(view.filterPanel.updatesFilterComboBox.isEnabled)
        assertEquals(TriStateFilter.ALL, view.filterPanel.updatesFilterComboBox.selectedItem)
    }

    fun testChangesFilterBecomesAvailableWithSelection() {
        val view = buildView()
        assertFalse(view.filterPanel.changesFilterComboBox.isEnabled)

        view.selectHighestMajorVersionForDependency("org.trans:lib")
        assertTrue(view.filterPanel.changesFilterComboBox.isEnabled)

        view.filterPanel.changesFilterComboBox.selectedItem = TriStateFilter.YES
        assertEquals(1, view.table.rowCount)
        assertEquals("lib", view.table.getValueAt(0, TRANSITIVE_ARTIFACT_ID_COLUMN))

        view.filterPanel.changesFilterComboBox.selectedItem = TriStateFilter.NO
        assertEquals(1, view.table.rowCount)
        assertEquals("tool", view.table.getValueAt(0, TRANSITIVE_ARTIFACT_ID_COLUMN))
    }

    fun testChangesFilterResetsWhenSelectionIsDiscarded() {
        val view = buildView()
        view.selectHighestMajorVersionForDependency("org.trans:lib")
        view.filterPanel.changesFilterComboBox.selectedItem = TriStateFilter.YES
        view.resetSelections()
        assertFalse(view.filterPanel.changesFilterComboBox.isEnabled)
        assertEquals(TriStateFilter.ALL, view.filterPanel.changesFilterComboBox.selectedItem)
        assertEquals(2, view.table.rowCount)
    }

    fun testBulkSelectionSkipsFilteredRows() {
        val view = buildView()
        view.filterPanel.filterBy("org.other")
        view.selectHighestMajorVersionForAll()
        assertTrue(view.selectedVersions.isEmpty())

        view.filterPanel.filterBy("org.trans")
        view.selectHighestMajorVersionForAll()
        assertEquals("1.2.4", view.selectedVersions["org.trans:lib"])
    }

    fun testCriteriaReflectsSelectedFilters() {
        val view = buildView()
        view.filterPanel.filterBy("lib")
        view.filterPanel.updatesFilterComboBox.selectedItem = TriStateFilter.YES

        val criteria = view.filterPanel.criteria()
        assertEquals("lib", criteria.searchText)
        assertEquals("", criteria.typeFilter)
        assertEquals(TriStateFilter.YES, criteria.updatesFilter)
        assertEquals(TriStateFilter.ALL, criteria.changesFilter)
        assertEquals(VulnerabilityFilter.ALL, criteria.vulnerabilitiesFilter)
    }

    fun testResetSelectionsVisibleOnlyKeepsFilteredOutSelections() {
        val view = buildView()
        view.selectedVersions["org.trans:lib"] = "1.2.4"
        view.selectedVersions["org.other:tool"] = "3.0.0"
        view.filterPanel.filterBy("org.trans")
        view.resetVisibleSelections()
        assertFalse(view.selectedVersions.containsKey("org.trans:lib"))
        assertEquals("3.0.0", view.selectedVersions["org.other:tool"])
    }
}
