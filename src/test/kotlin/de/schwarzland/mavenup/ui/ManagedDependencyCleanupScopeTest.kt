package de.schwarzland.mavenup.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Prüft die fachlichen Filter und die verständlichen Prüfumfangstexte der Cleanup-Aktion.
 */
class ManagedDependencyCleanupScopeTest : BasePlatformTestCase() {

    /**
     * Prüft globale sowie zeilenbezogene Filter für Managed-Dependency-, Parent- und Dependency-Zeilen.
     */
    fun testFromTargetMapsAllSupportedScopes() {
        val managedType = "Managed Dependency"
        val global = ManagedDependencyCleanupScope.fromTarget(null, managedType)
        assertNull(global.managedCoordinate)
        assertNull(global.triggerCoordinate)
        assertTrue(global.description.contains("every Maven module"))

        val managed = ManagedDependencyCleanupScope.fromTarget(
            DependencyContextMenuTarget(0, "g", "managed", "", managedType, "1"),
            managedType
        )
        assertEquals("g:managed", managed.managedCoordinate)
        assertNull(managed.triggerCoordinate)

        val parent = ManagedDependencyCleanupScope.fromTarget(
            DependencyContextMenuTarget(0, "g", "parent", "", "parent", "1"),
            managedType
        )
        assertNull(parent.managedCoordinate)
        assertEquals("g:parent", parent.triggerCoordinate)

        val dependency = ManagedDependencyCleanupScope.fromTarget(
            DependencyContextMenuTarget(0, "g", "library", "", "dependency", "1"),
            managedType
        )
        assertNull(dependency.managedCoordinate)
        assertEquals("g:library", dependency.triggerCoordinate)
        assertTrue(dependency.description.contains("every Maven module"))
    }

    /**
     * Prüft, dass eine unvollständige Prüfung höchstens fünf Lookup-Koordinaten ausschreibt.
     */
    fun testIncompleteLookupSummaryNamesSomeUnavailableCoordinates() {
        val lookups = (1..7).map { "POM: g:artifact-$it:1" }.toSet()

        val summary = incompleteCleanupLookupSummary(lookups)

        assertTrue(summary.contains("INCOMPLETE CHECK"))
        assertTrue(summary.contains("7"))
        assertTrue(summary.contains("artifact-1"))
        assertTrue(summary.contains("and 2 more"))
        assertFalse(summary.contains("artifact-7"))
    }
}
