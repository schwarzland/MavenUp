package de.schwarzland.mavenup.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.schwarzland.mavenup.model.RedundancyReason
import de.schwarzland.mavenup.model.RedundantManagedDependencyRecommendation

/**
 * Integrationstests für die Prüfung und Anwendung redundanter verwalteter Abhängigkeiten im Tool-Window.
 */
class RedundantManagedDependencyIntegrationTest : BasePlatformTestCase() {

    /**
     * Prüft die Vormerkung zur Entfernung ohne Änderung von Versionsauswahlen.
     */
    fun testApplyRecommendationsMarksManagedEntriesForRemoval() {
        val factory = MavenUpWindowFactory()
        val toolWindow = factory.MyToolWindow(project)

        val rec = RedundantManagedDependencyRecommendation(
            groupId = "com.fasterxml.jackson.core",
            artifactId = "jackson-databind",
            currentVersion = "2.14.0",
            reason = RedundancyReason.PARENT_MANAGED,
            reasonDetail = "Managed by parent POM",
            providedVersion = "2.14.0"
        )

        val managedKey = "com.fasterxml.jackson.core:jackson-databind"
        val managedType = MyMessageBundle.message("toolwindow.MyToolWindow.type.managedDependency")

        assertFalse(toolWindow.isManagedEntryMarkedForRemoval(managedKey, managedType))

        // Empfehlung anwenden
        toolWindow.applyRedundantManagedDependencyRecommendations(listOf(rec))

        // Eintrag muss zur Entfernung vorgemerkt sein
        assertTrue(toolWindow.isManagedEntryMarkedForRemoval(managedKey, managedType))
        // Versionen dürfen nicht verändert worden sein
        assertNull(toolWindow.selectedVersions[managedKey])
    }

    /**
     * Prüft, dass das Kontextmenü für verwaltete Abhängigkeiten die Aktion "Check if redundant" enthält.
     */
    fun testContextMenuContainsCheckIfRedundantActionForManagedDependency() {
        val factory = MavenUpWindowFactory()
        val toolWindow = factory.MyToolWindow(project)
        val target = DependencyContextMenuTarget(
            column = 1,
            groupId = "com.fasterxml.jackson.core",
            artifactId = "jackson-databind",
            property = "",
            type = MyMessageBundle.message("toolwindow.MyToolWindow.type.managedDependency"),
            currentVersion = "2.14.0",
            vulnerabilityCell = null
        )

        val group = toolWindow.buildContextMenuGroup(target)
        val actions = group.getChildren(null)
        val hasCheckAction = actions.any {
            it.templatePresentation.text == MyMessageBundle.message("toolwindow.MyToolWindow.checkRedundantManaged.contextMenu")
        }
        assertTrue(hasCheckAction)
        assertEquals("Check if redundant", MyMessageBundle.message("toolwindow.MyToolWindow.checkRedundantManaged.contextMenu"))
    }

    /**
     * Prüft, dass die Aktion "Check if redundant" bei normalen direkten Abhängigkeiten nicht im Kontextmenü erscheint.
     */
    fun testContextMenuDoesNotContainCheckIfRedundantForDirectDependency() {
        val factory = MavenUpWindowFactory()
        val toolWindow = factory.MyToolWindow(project)
        val target = DependencyContextMenuTarget(
            column = 1,
            groupId = "com.fasterxml.jackson.core",
            artifactId = "jackson-databind",
            property = "",
            type = "dependency",
            currentVersion = "2.14.0",
            vulnerabilityCell = null
        )

        val group = toolWindow.buildContextMenuGroup(target)
        val actions = group.getChildren(null)
        val hasCheckAction = actions.any {
            it.templatePresentation.text == MyMessageBundle.message("toolwindow.MyToolWindow.checkRedundantManaged.contextMenu")
        }
        assertFalse(hasCheckAction)
    }
}
