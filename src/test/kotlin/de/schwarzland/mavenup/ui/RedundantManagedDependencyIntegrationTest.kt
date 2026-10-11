package de.schwarzland.mavenup.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.schwarzland.mavenup.model.RedundancyReason
import de.schwarzland.mavenup.model.RedundantManagedDependencyRecommendation
import de.schwarzland.mavenup.service.AutomaticVersionSearchState

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
     * Prüft, dass in Multi-Modul-Projekten mit gleichnamigen verwalteten Abhängigkeiten in Parent und Child
     * ausschließlich der im Befund referenzierte Child-Eintrag zur Entfernung vorgemerkt wird.
     */
    fun testApplyRecommendationsMarksOnlyTargetChildPomInMultiModuleProject() {
        val factory = MavenUpWindowFactory()
        val toolWindow = factory.MyToolWindow(project)

        val parentPomFile = myFixture.tempDirFixture.createFile("pom.xml")
        val childPomFile = myFixture.tempDirFixture.createFile("child/pom.xml")

        val managedType = MyMessageBundle.message("toolwindow.MyToolWindow.type.managedDependency")
        val parentRow = RefreshRow(
            groupId = "org.apache.commons",
            artifactId = "commons-lang3",
            propertyName = "",
            type = managedType,
            currentVersion = "3.12.0",
            pomFile = parentPomFile
        )
        val childRow = RefreshRow(
            groupId = "org.apache.commons",
            artifactId = "commons-lang3",
            propertyName = "",
            type = managedType,
            currentVersion = "3.12.0",
            pomFile = childPomFile
        )

        toolWindow.applyAutomaticVersionSearchState(
            AutomaticVersionSearchState(
                snapshot = RefreshSnapshot(listOf(parentRow, childRow), emptyMap()),
                versionSearchResult = null,
                repositoryError = null
            )
        )

        val rec = RedundantManagedDependencyRecommendation(
            groupId = "org.apache.commons",
            artifactId = "commons-lang3",
            currentVersion = "3.12.0",
            reason = RedundancyReason.PARENT_MANAGED,
            reasonDetail = "Managed by parent POM",
            providedVersion = "3.12.0",
            sourcePomPath = childPomFile.path
        )

        assertFalse(toolWindow.isManagedEntryMarkedForRemoval(parentRow))
        assertFalse(toolWindow.isManagedEntryMarkedForRemoval(childRow))

        toolWindow.applyRedundantManagedDependencyRecommendations(listOf(rec))

        assertTrue(toolWindow.isManagedEntryMarkedForRemoval(childRow))
        assertFalse(toolWindow.isManagedEntryMarkedForRemoval(parentRow))

        val updates = toolWindow.collectSelectedUpdates()
        assertEquals(1, updates.size)
        val update = updates.single()
        assertEquals("org.apache.commons", update.groupId)
        assertEquals("commons-lang3", update.artifactId)
        assertTrue(update.removeFromPom)
        assertEquals(childPomFile, update.pomFile)
    }

    /**
     * Prüft, dass das Kontextmenü für verwaltete Abhängigkeiten die Aktion "Check for Redundancy" enthält.
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
        assertEquals(
            "Check for Redundancy",
            MyMessageBundle.message("toolwindow.MyToolWindow.checkRedundantManaged.contextMenu")
        )
        assertEquals(
            "Check Managed Dependencies for Redundancy...",
            MyMessageBundle.message("toolwindow.MyToolWindow.checkRedundantManaged.menuItem")
        )
    }

    /**
     * Prüft, dass die Aktion "Check for Redundancy" bei normalen direkten Abhängigkeiten nicht im Kontextmenü erscheint.
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
