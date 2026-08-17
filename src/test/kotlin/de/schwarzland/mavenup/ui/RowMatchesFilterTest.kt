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
        assertTrue(rowMatchesFilter("org.a", "lib", "a.version", "dependency", "", ""))
    }

    @Test
    fun testSearchMatchesGroupIdCaseInsensitive() {
        assertTrue(rowMatchesFilter("org.Apache", "lib", "p", "dependency", "apache", ""))
    }

    @Test
    fun testSearchMatchesArtifactId() {
        assertTrue(rowMatchesFilter("org.a", "commons-lang", "p", "dependency", "lang", ""))
    }

    @Test
    fun testSearchMatchesProperty() {
        assertTrue(rowMatchesFilter("org.a", "lib", "spring.version", "dependency", "spring", ""))
    }

    @Test
    fun testSearchIsTrimmedBeforeMatching() {
        assertTrue(rowMatchesFilter("org.a", "commons-lang", "p", "dependency", "  lang  ", ""))
    }

    @Test
    fun testSearchNotInAnyColumnDoesNotMatch() {
        assertFalse(rowMatchesFilter("org.a", "lib", "p", "dependency", "zzz", ""))
    }

    @Test
    fun testSearchDoesNotConsiderTypeColumn() {
        assertFalse(rowMatchesFilter("org.a", "lib", "p", "dependency", "dependency", ""))
    }

    @Test
    fun testTypeFilterMatchesExactType() {
        assertTrue(rowMatchesFilter("org.a", "lib", "p", "plugin", "", "plugin"))
    }

    @Test
    fun testTypeFilterRejectsOtherType() {
        assertFalse(rowMatchesFilter("org.a", "lib", "p", "dependency", "", "plugin"))
    }

    @Test
    fun testTextAndTypeMustBothMatch() {
        assertTrue(rowMatchesFilter("org.a", "lib", "p", "plugin", "lib", "plugin"))
        assertFalse(rowMatchesFilter("org.a", "lib", "p", "dependency", "lib", "plugin"))
        assertFalse(rowMatchesFilter("org.a", "lib", "p", "plugin", "zzz", "plugin"))
    }

    @Test
    fun testEmptyPropertyDoesNotBreakMatching() {
        assertTrue(rowMatchesFilter("org.a", "lib", "", "dependency", "lib", ""))
    }

    @Test
    fun testChangesFilterAllAcceptsBothStates() {
        assertTrue(rowMatchesFilter("org.a", "lib", "p", "dependency", "", "", hasChange = true, changesFilter = TriStateFilter.ALL))
        assertTrue(rowMatchesFilter("org.a", "lib", "p", "dependency", "", "", hasChange = false, changesFilter = TriStateFilter.ALL))
    }

    @Test
    fun testChangesFilterYesOnlyAcceptsRowsWithChanges() {
        assertTrue(rowMatchesFilter("org.a", "lib", "p", "dependency", "", "", hasChange = true, changesFilter = TriStateFilter.YES))
        assertFalse(rowMatchesFilter("org.a", "lib", "p", "dependency", "", "", hasChange = false, changesFilter = TriStateFilter.YES))
    }

    @Test
    fun testChangesFilterNoOnlyAcceptsRowsWithoutChanges() {
        assertFalse(rowMatchesFilter("org.a", "lib", "p", "dependency", "", "", hasChange = true, changesFilter = TriStateFilter.NO))
        assertTrue(rowMatchesFilter("org.a", "lib", "p", "dependency", "", "", hasChange = false, changesFilter = TriStateFilter.NO))
    }

    @Test
    fun testVulnerabilitiesFilterAllAcceptsBothStates() {
        assertTrue(rowMatchesFilter("org.a", "lib", "p", "dependency", "", "", hasVulnerabilities = true, vulnerabilitiesFilter = TriStateFilter.ALL))
        assertTrue(rowMatchesFilter("org.a", "lib", "p", "dependency", "", "", hasVulnerabilities = false, vulnerabilitiesFilter = TriStateFilter.ALL))
    }

    @Test
    fun testVulnerabilitiesFilterYesOnlyAcceptsRowsWithVulnerabilities() {
        assertTrue(rowMatchesFilter("org.a", "lib", "p", "dependency", "", "", hasVulnerabilities = true, vulnerabilitiesFilter = TriStateFilter.YES))
        assertFalse(rowMatchesFilter("org.a", "lib", "p", "dependency", "", "", hasVulnerabilities = false, vulnerabilitiesFilter = TriStateFilter.YES))
    }

    @Test
    fun testVulnerabilitiesFilterNoOnlyAcceptsRowsWithoutVulnerabilities() {
        assertFalse(rowMatchesFilter("org.a", "lib", "p", "dependency", "", "", hasVulnerabilities = true, vulnerabilitiesFilter = TriStateFilter.NO))
        assertTrue(rowMatchesFilter("org.a", "lib", "p", "dependency", "", "", hasVulnerabilities = false, vulnerabilitiesFilter = TriStateFilter.NO))
    }

    @Test
    fun testAllFiltersCombinedMatching() {
        assertTrue(
            rowMatchesFilter(
                groupId = "org.apache.commons",
                artifactId = "commons-lang3",
                property = "commons-lang3.version",
                type = "dependency",
                searchText = "commons",
                typeFilter = "dependency",
                hasChange = true,
                changesFilter = TriStateFilter.YES,
                hasVulnerabilities = true,
                vulnerabilitiesFilter = TriStateFilter.YES
            )
        )

        // Text mismatch
        assertFalse(
            rowMatchesFilter(
                groupId = "org.apache.commons",
                artifactId = "commons-lang3",
                property = "commons-lang3.version",
                type = "dependency",
                searchText = "spring",
                typeFilter = "dependency",
                hasChange = true,
                changesFilter = TriStateFilter.YES,
                hasVulnerabilities = true,
                vulnerabilitiesFilter = TriStateFilter.YES
            )
        )

        // Type mismatch
        assertFalse(
            rowMatchesFilter(
                groupId = "org.apache.commons",
                artifactId = "commons-lang3",
                property = "commons-lang3.version",
                type = "dependency",
                searchText = "commons",
                typeFilter = "plugin",
                hasChange = true,
                changesFilter = TriStateFilter.YES,
                hasVulnerabilities = true,
                vulnerabilitiesFilter = TriStateFilter.YES
            )
        )

        // Changes mismatch
        assertFalse(
            rowMatchesFilter(
                groupId = "org.apache.commons",
                artifactId = "commons-lang3",
                property = "commons-lang3.version",
                type = "dependency",
                searchText = "commons",
                typeFilter = "dependency",
                hasChange = false,
                changesFilter = TriStateFilter.YES,
                hasVulnerabilities = true,
                vulnerabilitiesFilter = TriStateFilter.YES
            )
        )

        // Vulnerabilities mismatch
        assertFalse(
            rowMatchesFilter(
                groupId = "org.apache.commons",
                artifactId = "commons-lang3",
                property = "commons-lang3.version",
                type = "dependency",
                searchText = "commons",
                typeFilter = "dependency",
                hasChange = true,
                changesFilter = TriStateFilter.YES,
                hasVulnerabilities = false,
                vulnerabilitiesFilter = TriStateFilter.YES
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
