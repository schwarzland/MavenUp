package de.schwarzland.mavenup.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.schwarzland.mavenup.model.VulnerabilityAdvisory
import de.schwarzland.mavenup.model.VulnerabilitySeverity

/**
 * Reproduziert das Szenario: Hierarchie-Split-View ist geöffnet, ein erneuter Scan findet keine
 * Vulnerabilities mehr (Tabelle wird leer) und die Split-View soll weiterhin über "x" bzw. den
 * Toggle-Button schließbar bleiben.
 */
class TransitiveVulnerabilitiesViewHierarchyScanTest : BasePlatformTestCase() {

    private val coordinate = "org.trans:lib:1.2.0"

    fun testHierarchyCanBeClosedAfterScanClearsRows() {
        val view = TransitiveVulnerabilitiesView(project)
        view.update(
            mapOf(
                coordinate to listOf(
                    VulnerabilityAdvisory(id = "CVE-1", severity = VulnerabilitySeverity.HIGH, sources = setOf("OSV"))
                )
            ),
            setOf(coordinate)
        )
        view.table.setRowSelectionInterval(0, 0)
        view.openSelectedDependencyHierarchy()
        assertTrue(view.isDependencyHierarchyVisible())

        // Erneuter Scan findet keine Vulnerabilities mehr -> Tabelle wird leer.
        view.update(emptyMap(), emptySet())
        assertEquals(0, view.table.rowCount)
        assertTrue(
            "Split-View sollte trotz leerer Tabelle weiterhin geöffnet (aber leer) sein",
            view.isDependencyHierarchyVisible()
        )

        // Schließen über "x" (onClose-Callback) muss weiterhin funktionieren.
        view.hideDependencyHierarchy()
        assertFalse(view.isDependencyHierarchyVisible())

        // Erneutes Öffnen/Schließen über den Toggle-Button muss ebenfalls funktionieren.
        view.toggleDependencyHierarchy(true)
        assertTrue(view.isDependencyHierarchyVisible())
        view.toggleDependencyHierarchy(false)
        assertFalse(view.isDependencyHierarchyVisible())
    }
}
