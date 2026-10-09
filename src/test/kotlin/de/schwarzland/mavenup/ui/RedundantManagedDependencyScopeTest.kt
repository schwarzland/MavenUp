package de.schwarzland.mavenup.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Prüft den Scope und die Beschreibungen für die Prüfung auf redundante verwaltete Abhängigkeiten.
 */
class RedundantManagedDependencyScopeTest : BasePlatformTestCase() {

    /**
     * Prüft die Erzeugung von globalen und zielgerichteten Scopes.
     */
    fun testFromTargetMapsGlobalAndTargetScopes() {
        val managedType = "Managed Dependency"
        val global = RedundantManagedDependencyScope.fromTarget(null, managedType)
        assertNull(global.managedCoordinate)
        assertTrue(global.description.contains("every Maven module"))

        val managed = RedundantManagedDependencyScope.fromTarget(
            DependencyContextMenuTarget(0, "org.example", "my-lib", "", managedType, "1.0.0"),
            managedType
        )
        assertEquals("org.example:my-lib", managed.managedCoordinate)
        assertTrue(managed.description.contains("org.example:my-lib"))

        val nonManaged = RedundantManagedDependencyScope.fromTarget(
            DependencyContextMenuTarget(0, "org.example", "direct-lib", "", "dependency", "1.0.0"),
            managedType
        )
        assertNull(nonManaged.managedCoordinate)
        assertTrue(nonManaged.description.contains("every Maven module"))
    }
}
