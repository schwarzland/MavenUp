package de.schwarzland.mavenup.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Testet die reine Filterlogik [rowMatchesFilter] für die Haupttabelle.
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
}
