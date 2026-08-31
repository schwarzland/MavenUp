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

    private fun vulnerabilityRow(direct: Boolean, transitive: Boolean) = FilterRow(
        "org.a",
        "lib",
        "p",
        "dependency",
        hasDirectVulnerabilities = direct,
        hasTransitiveVulnerabilities = transitive
    )

    private fun matchesVulnerabilityFilter(
        direct: Boolean,
        transitive: Boolean,
        filter: VulnerabilityFilter
    ): Boolean = rowMatchesFilter(
        vulnerabilityRow(direct, transitive),
        FilterCriteria("", "", vulnerabilitiesFilter = filter)
    )

    @Test
    fun testFilterRowDerivesCombinedVulnerabilityFlag() {
        assertFalse(vulnerabilityRow(direct = false, transitive = false).hasVulnerabilities)
        assertTrue(vulnerabilityRow(direct = true, transitive = false).hasVulnerabilities)
        assertTrue(vulnerabilityRow(direct = false, transitive = true).hasVulnerabilities)
        assertTrue(vulnerabilityRow(direct = true, transitive = true).hasVulnerabilities)
    }

    @Test
    fun testVulnerabilitiesFilterAllAcceptsEveryState() {
        assertTrue(matchesVulnerabilityFilter(direct = true, transitive = true, filter = VulnerabilityFilter.ALL))
        assertTrue(matchesVulnerabilityFilter(direct = true, transitive = false, filter = VulnerabilityFilter.ALL))
        assertTrue(matchesVulnerabilityFilter(direct = false, transitive = true, filter = VulnerabilityFilter.ALL))
        assertTrue(matchesVulnerabilityFilter(direct = false, transitive = false, filter = VulnerabilityFilter.ALL))
    }

    @Test
    fun testVulnerabilitiesFilterVulnerableAcceptsOwnAndTransitiveFindings() {
        assertTrue(matchesVulnerabilityFilter(direct = true, transitive = false, filter = VulnerabilityFilter.VULNERABLE))
        assertTrue(matchesVulnerabilityFilter(direct = false, transitive = true, filter = VulnerabilityFilter.VULNERABLE))
        assertTrue(matchesVulnerabilityFilter(direct = true, transitive = true, filter = VulnerabilityFilter.VULNERABLE))
        assertFalse(matchesVulnerabilityFilter(direct = false, transitive = false, filter = VulnerabilityFilter.VULNERABLE))
    }

    @Test
    fun testVulnerabilitiesFilterSelfOnlyAcceptsRowsWithOwnFindings() {
        assertTrue(matchesVulnerabilityFilter(direct = true, transitive = false, filter = VulnerabilityFilter.SELF_VULNERABLE))
        assertTrue(matchesVulnerabilityFilter(direct = true, transitive = true, filter = VulnerabilityFilter.SELF_VULNERABLE))
        assertFalse(matchesVulnerabilityFilter(direct = false, transitive = true, filter = VulnerabilityFilter.SELF_VULNERABLE))
        assertFalse(matchesVulnerabilityFilter(direct = false, transitive = false, filter = VulnerabilityFilter.SELF_VULNERABLE))
    }

    @Test
    fun testVulnerabilitiesFilterTransitiveOnlyAcceptsRowsWithTransitiveFindings() {
        assertTrue(
            matchesVulnerabilityFilter(direct = false, transitive = true, filter = VulnerabilityFilter.TRANSITIVE_VULNERABLE)
        )
        assertTrue(
            matchesVulnerabilityFilter(direct = true, transitive = true, filter = VulnerabilityFilter.TRANSITIVE_VULNERABLE)
        )
        assertFalse(
            matchesVulnerabilityFilter(direct = true, transitive = false, filter = VulnerabilityFilter.TRANSITIVE_VULNERABLE)
        )
        assertFalse(
            matchesVulnerabilityFilter(direct = false, transitive = false, filter = VulnerabilityFilter.TRANSITIVE_VULNERABLE)
        )
    }

    @Test
    fun testVulnerabilitiesFilterNotVulnerableOnlyAcceptsRowsWithoutFindings() {
        assertTrue(
            matchesVulnerabilityFilter(direct = false, transitive = false, filter = VulnerabilityFilter.NOT_VULNERABLE)
        )
        assertFalse(matchesVulnerabilityFilter(direct = true, transitive = false, filter = VulnerabilityFilter.NOT_VULNERABLE))
        assertFalse(matchesVulnerabilityFilter(direct = false, transitive = true, filter = VulnerabilityFilter.NOT_VULNERABLE))
    }

    @Test
    fun testUpdatesFilterAllAcceptsBothStates() {
        assertTrue(
            rowMatchesFilter(
                FilterRow("org.a", "lib", "p", "dependency", hasUpdate = true),
                FilterCriteria("", "", updatesFilter = TriStateFilter.ALL)
            )
        )
        assertTrue(
            rowMatchesFilter(
                FilterRow("org.a", "lib", "p", "dependency", hasUpdate = false),
                FilterCriteria("", "", updatesFilter = TriStateFilter.ALL)
            )
        )
    }

    @Test
    fun testUpdatesFilterYesOnlyAcceptsRowsWithUpdates() {
        assertTrue(
            rowMatchesFilter(
                FilterRow("org.a", "lib", "p", "dependency", hasUpdate = true),
                FilterCriteria("", "", updatesFilter = TriStateFilter.YES)
            )
        )
        assertFalse(
            rowMatchesFilter(
                FilterRow("org.a", "lib", "p", "dependency", hasUpdate = false),
                FilterCriteria("", "", updatesFilter = TriStateFilter.YES)
            )
        )
    }

    @Test
    fun testUpdatesFilterNoOnlyAcceptsRowsWithoutUpdates() {
        assertFalse(
            rowMatchesFilter(
                FilterRow("org.a", "lib", "p", "dependency", hasUpdate = true),
                FilterCriteria("", "", updatesFilter = TriStateFilter.NO)
            )
        )
        assertTrue(
            rowMatchesFilter(
                FilterRow("org.a", "lib", "p", "dependency", hasUpdate = false),
                FilterCriteria("", "", updatesFilter = TriStateFilter.NO)
            )
        )
    }

    @Test
    fun testHasNewerVersionDetectsAvailableUpdate() {
        assertTrue(hasNewerVersion("1.0.0", "2.0.0"))
        assertFalse(hasNewerVersion("2.0.0", "2.0.0"))
        assertFalse(hasNewerVersion("1.0.0", ""))
    }

    @Test
    fun testAllFiltersCombinedMatching() {
        val matchingRow = FilterRow(
            groupId = "org.apache.commons",
            artifactId = "commons-lang3",
            property = "commons-lang3.version",
            type = "dependency",
            hasChange = true,
            hasUpdate = true,
            hasDirectVulnerabilities = true
        )

        assertTrue(
            rowMatchesFilter(
                matchingRow,
                FilterCriteria(
                    searchText = "commons",
                    typeFilter = "dependency",
                    changesFilter = TriStateFilter.YES,
                    updatesFilter = TriStateFilter.YES,
                    vulnerabilitiesFilter = VulnerabilityFilter.VULNERABLE
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
                    updatesFilter = TriStateFilter.YES,
                    vulnerabilitiesFilter = VulnerabilityFilter.VULNERABLE
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
                    updatesFilter = TriStateFilter.YES,
                    vulnerabilitiesFilter = VulnerabilityFilter.VULNERABLE
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
                    updatesFilter = TriStateFilter.YES,
                    vulnerabilitiesFilter = VulnerabilityFilter.VULNERABLE
                )
            )
        )

        // Updates mismatch
        assertFalse(
            rowMatchesFilter(
                matchingRow.copy(hasUpdate = false),
                FilterCriteria(
                    searchText = "commons",
                    typeFilter = "dependency",
                    changesFilter = TriStateFilter.YES,
                    updatesFilter = TriStateFilter.YES,
                    vulnerabilitiesFilter = VulnerabilityFilter.VULNERABLE
                )
            )
        )

        // Vulnerabilities mismatch
        assertFalse(
            rowMatchesFilter(
                matchingRow.copy(hasDirectVulnerabilities = false),
                FilterCriteria(
                    searchText = "commons",
                    typeFilter = "dependency",
                    changesFilter = TriStateFilter.YES,
                    updatesFilter = TriStateFilter.YES,
                    vulnerabilitiesFilter = VulnerabilityFilter.VULNERABLE
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

    @Test
    fun testTriStateFilterOptionLabelsAreContextSpecific() {
        assertEquals("All", triStateFilterOptionLabel(TriStateFilter.ALL, CHANGES_FILTER_LABELS))
        assertEquals("Will update", triStateFilterOptionLabel(TriStateFilter.YES, CHANGES_FILTER_LABELS))
        assertEquals("Unchanged", triStateFilterOptionLabel(TriStateFilter.NO, CHANGES_FILTER_LABELS))

        assertEquals("All", triStateFilterOptionLabel(TriStateFilter.ALL, UPDATES_FILTER_LABELS))
        assertEquals("Update available", triStateFilterOptionLabel(TriStateFilter.YES, UPDATES_FILTER_LABELS))
        assertEquals("Up to date", triStateFilterOptionLabel(TriStateFilter.NO, UPDATES_FILTER_LABELS))

        assertEquals("All", triStateFilterOptionLabel(TriStateFilter.ALL, VERSION_SOURCE_FILTER_LABELS))
        assertEquals("Inherited", triStateFilterOptionLabel(TriStateFilter.YES, VERSION_SOURCE_FILTER_LABELS))
        assertEquals("Declared in pom.xml", triStateFilterOptionLabel(TriStateFilter.NO, VERSION_SOURCE_FILTER_LABELS))
    }

    @Test
    fun testVersionSourceFilterAllMatchesBothKinds() {
        val inherited = FilterRow("org.a", "lib", "p", "dependency", versionInherited = true)
        val declared = FilterRow("org.a", "lib", "p", "dependency", versionInherited = false)
        val criteria = FilterCriteria("", "", versionSourceFilter = TriStateFilter.ALL)

        assertTrue(rowMatchesFilter(inherited, criteria))
        assertTrue(rowMatchesFilter(declared, criteria))
    }

    @Test
    fun testVersionSourceFilterYesKeepsOnlyInheritedRows() {
        val criteria = FilterCriteria("", "", versionSourceFilter = TriStateFilter.YES)

        assertTrue(rowMatchesFilter(FilterRow("org.a", "lib", "p", "dependency", versionInherited = true), criteria))
        assertFalse(rowMatchesFilter(FilterRow("org.a", "lib", "p", "dependency", versionInherited = false), criteria))
    }

    @Test
    fun testVersionSourceFilterNoHidesInheritedRows() {
        val criteria = FilterCriteria("", "", versionSourceFilter = TriStateFilter.NO)

        assertFalse(rowMatchesFilter(FilterRow("org.a", "lib", "p", "dependency", versionInherited = true), criteria))
        assertTrue(rowMatchesFilter(FilterRow("org.a", "lib", "p", "dependency", versionInherited = false), criteria))
    }

    @Test
    fun testVersionSourceFilterCombinesWithOtherCriteria() {
        val row = FilterRow("org.a", "lib", "p", "plugin", versionInherited = true)

        assertTrue(
            rowMatchesFilter(row, FilterCriteria("lib", "plugin", versionSourceFilter = TriStateFilter.YES))
        )
        assertFalse(
            rowMatchesFilter(row, FilterCriteria("lib", "dependency", versionSourceFilter = TriStateFilter.YES))
        )
        assertFalse(
            rowMatchesFilter(row, FilterCriteria("other", "plugin", versionSourceFilter = TriStateFilter.YES))
        )
    }

    @Test
    fun testVersionInheritedDefaultsToFalse() {
        assertFalse(FilterRow("org.a", "lib", "p", "dependency").versionInherited)
        assertEquals(TriStateFilter.ALL, FilterCriteria("", "").versionSourceFilter)
    }

    @Test
    fun testVulnerabilityFilterEnumLabels() {
        assertEquals("All", VulnerabilityFilter.ALL.label)
        assertEquals("Any vulnerability", VulnerabilityFilter.VULNERABLE.label)
        assertEquals("Own vulnerability", VulnerabilityFilter.SELF_VULNERABLE.label)
        assertEquals("Transitive vulnerability", VulnerabilityFilter.TRANSITIVE_VULNERABLE.label)
        assertEquals("No vulnerability", VulnerabilityFilter.NOT_VULNERABLE.label)
        assertEquals("Own vulnerability", VulnerabilityFilter.SELF_VULNERABLE.toString())
    }
}
