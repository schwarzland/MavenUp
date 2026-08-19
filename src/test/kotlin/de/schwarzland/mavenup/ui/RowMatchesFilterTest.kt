package de.schwarzland.mavenup.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Testet die reine Filterlogik [rowMatchesFilter] und die Filteroptionen [TriStateFilter] für die Haupttabelle.
 */
class RowMatchesFilterTest {

    @Test
    fun testEmptySearchAndEmptyTypeMatchesEverything() {
        assertTrue(
            rowMatchesFilter(
                FilterRow("org.a", "lib", "a.version", "dependency"),
                FilterCriteria("", "")
            )
        )
    }

    @Test
    fun testSearchMatchesGroupIdCaseInsensitive() {
        assertTrue(
            rowMatchesFilter(
                FilterRow("org.Apache", "lib", "p", "dependency"),
                FilterCriteria("apache", "")
            )
        )
    }

    @Test
    fun testSearchMatchesArtifactId() {
        assertTrue(
            rowMatchesFilter(
                FilterRow("org.a", "commons-lang", "p", "dependency"),
                FilterCriteria("lang", "")
            )
        )
    }

    @Test
    fun testSearchMatchesProperty() {
        assertTrue(
            rowMatchesFilter(
                FilterRow("org.a", "lib", "spring.version", "dependency"),
                FilterCriteria("spring", "")
            )
        )
    }

    @Test
    fun testSearchIsTrimmedBeforeMatching() {
        assertTrue(
            rowMatchesFilter(
                FilterRow("org.a", "commons-lang", "p", "dependency"),
                FilterCriteria("  lang  ", "")
            )
        )
    }

    @Test
    fun testSearchNotInAnyColumnDoesNotMatch() {
        assertFalse(
            rowMatchesFilter(
                FilterRow("org.a", "lib", "p", "dependency"),
                FilterCriteria("zzz", "")
            )
        )
    }

    @Test
    fun testSearchDoesNotConsiderTypeColumn() {
        assertFalse(
            rowMatchesFilter(
                FilterRow("org.a", "lib", "p", "dependency"),
                FilterCriteria("dependency", "")
            )
        )
    }

    @Test
    fun testTypeFilterMatchesExactType() {
        assertTrue(
            rowMatchesFilter(
                FilterRow("org.a", "lib", "p", "plugin"),
                FilterCriteria("", "plugin")
            )
        )
    }

    @Test
    fun testTypeFilterRejectsOtherType() {
        assertFalse(
            rowMatchesFilter(
                FilterRow("org.a", "lib", "p", "dependency"),
                FilterCriteria("", "plugin")
            )
        )
    }

    @Test
    fun testTextAndTypeMustBothMatch() {
        assertTrue(
            rowMatchesFilter(
                FilterRow("org.a", "lib", "p", "plugin"),
                FilterCriteria("lib", "plugin")
            )
        )
        assertFalse(
            rowMatchesFilter(
                FilterRow("org.a", "lib", "p", "dependency"),
                FilterCriteria("lib", "plugin")
            )
        )
        assertFalse(
            rowMatchesFilter(
                FilterRow("org.a", "lib", "p", "plugin"),
                FilterCriteria("zzz", "plugin")
            )
        )
    }

    @Test
    fun testEmptyPropertyDoesNotBreakMatching() {
        assertTrue(
            rowMatchesFilter(
                FilterRow("org.a", "lib", "", "dependency"),
                FilterCriteria("lib", "")
            )
        )
    }

    @Test
    fun testChangesFilterAllAcceptsBothStates() {
        assertTrue(
            rowMatchesFilter(
                FilterRow("org.a", "lib", "p", "dependency", hasChange = true),
                FilterCriteria("", "", changesFilter = TriStateFilter.ALL)
            )
        )
        assertTrue(
            rowMatchesFilter(
                FilterRow("org.a", "lib", "p", "dependency", hasChange = false),
                FilterCriteria("", "", changesFilter = TriStateFilter.ALL)
            )
        )
    }

    @Test
    fun testChangesFilterYesOnlyAcceptsRowsWithChanges() {
        assertTrue(
            rowMatchesFilter(
                FilterRow("org.a", "lib", "p", "dependency", hasChange = true),
                FilterCriteria("", "", changesFilter = TriStateFilter.YES)
            )
        )
        assertFalse(
            rowMatchesFilter(
                FilterRow("org.a", "lib", "p", "dependency", hasChange = false),
                FilterCriteria("", "", changesFilter = TriStateFilter.YES)
            )
        )
    }

    @Test
    fun testChangesFilterNoOnlyAcceptsRowsWithoutChanges() {
        assertFalse(
            rowMatchesFilter(
                FilterRow("org.a", "lib", "p", "dependency", hasChange = true),
                FilterCriteria("", "", changesFilter = TriStateFilter.NO)
            )
        )
        assertTrue(
            rowMatchesFilter(
                FilterRow("org.a", "lib", "p", "dependency", hasChange = false),
                FilterCriteria("", "", changesFilter = TriStateFilter.NO)
            )
        )
    }

    @Test
    fun testVulnerabilitiesFilterAllAcceptsBothStates() {
        assertTrue(
            rowMatchesFilter(
                FilterRow("org.a", "lib", "p", "dependency", hasVulnerabilities = true),
                FilterCriteria("", "", vulnerabilitiesFilter = TriStateFilter.ALL)
            )
        )
        assertTrue(
            rowMatchesFilter(
                FilterRow("org.a", "lib", "p", "dependency", hasVulnerabilities = false),
                FilterCriteria("", "", vulnerabilitiesFilter = TriStateFilter.ALL)
            )
        )
    }

    @Test
    fun testVulnerabilitiesFilterYesOnlyAcceptsRowsWithVulnerabilities() {
        assertTrue(
            rowMatchesFilter(
                FilterRow("org.a", "lib", "p", "dependency", hasVulnerabilities = true),
                FilterCriteria("", "", vulnerabilitiesFilter = TriStateFilter.YES)
            )
        )
        assertFalse(
            rowMatchesFilter(
                FilterRow("org.a", "lib", "p", "dependency", hasVulnerabilities = false),
                FilterCriteria("", "", vulnerabilitiesFilter = TriStateFilter.YES)
            )
        )
    }

    @Test
    fun testVulnerabilitiesFilterNoOnlyAcceptsRowsWithoutVulnerabilities() {
        assertFalse(
            rowMatchesFilter(
                FilterRow("org.a", "lib", "p", "dependency", hasVulnerabilities = true),
                FilterCriteria("", "", vulnerabilitiesFilter = TriStateFilter.NO)
            )
        )
        assertTrue(
            rowMatchesFilter(
                FilterRow("org.a", "lib", "p", "dependency", hasVulnerabilities = false),
                FilterCriteria("", "", vulnerabilitiesFilter = TriStateFilter.NO)
            )
        )
    }

    @Test
    fun testAllFiltersCombinedMatching() {
        val matchingRow = FilterRow(
            groupId = "org.apache.commons",
            artifactId = "commons-lang3",
            property = "commons-lang3.version",
            type = "dependency",
            hasChange = true,
            hasVulnerabilities = true
        )

        assertTrue(
            rowMatchesFilter(
                matchingRow,
                FilterCriteria(
                    searchText = "commons",
                    typeFilter = "dependency",
                    changesFilter = TriStateFilter.YES,
                    vulnerabilitiesFilter = TriStateFilter.YES
                )
            )
        )

        // Text mismatch
        assertFalse(
            rowMatchesFilter(
                matchingRow,
                FilterCriteria(
                    searchText = "spring",
                    typeFilter = "dependency",
                    changesFilter = TriStateFilter.YES,
                    vulnerabilitiesFilter = TriStateFilter.YES
                )
            )
        )

        // Type mismatch
        assertFalse(
            rowMatchesFilter(
                matchingRow,
                FilterCriteria(
                    searchText = "commons",
                    typeFilter = "plugin",
                    changesFilter = TriStateFilter.YES,
                    vulnerabilitiesFilter = TriStateFilter.YES
                )
            )
        )

        // Changes mismatch
        assertFalse(
            rowMatchesFilter(
                matchingRow.copy(hasChange = false),
                FilterCriteria(
                    searchText = "commons",
                    typeFilter = "dependency",
                    changesFilter = TriStateFilter.YES,
                    vulnerabilitiesFilter = TriStateFilter.YES
                )
            )
        )

        // Vulnerabilities mismatch
        assertFalse(
            rowMatchesFilter(
                matchingRow.copy(hasVulnerabilities = false),
                FilterCriteria(
                    searchText = "commons",
                    typeFilter = "dependency",
                    changesFilter = TriStateFilter.YES,
                    vulnerabilitiesFilter = TriStateFilter.YES
                )
            )
        )
    }

    @Test
    fun testTriStateFilterEnumLabels() {
        assertEquals("All", TriStateFilter.ALL.label)
        assertEquals("Yes", TriStateFilter.YES.label)
        assertEquals("No", TriStateFilter.NO.label)
        assertEquals("All", TriStateFilter.ALL.toString())
        assertEquals("Yes", TriStateFilter.YES.toString())
        assertEquals("No", TriStateFilter.NO.toString())
    }
}
