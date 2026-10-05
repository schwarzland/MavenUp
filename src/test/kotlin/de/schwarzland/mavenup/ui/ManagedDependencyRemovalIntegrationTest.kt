package de.schwarzland.mavenup.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.schwarzland.mavenup.model.ConsumerDependencyInfo
import de.schwarzland.mavenup.model.ManagedDependencyRemovalRecommendation

/**
 * Integrationstests für die Erkennung und Anwendung von Bereinigungsempfehlungen im Tool-Window.
 */
class ManagedDependencyRemovalIntegrationTest : BasePlatformTestCase() {

    fun testApplyRecommendationsUpdatesToolWindowState() {
        val factory = MavenUpWindowFactory()
        val toolWindow = factory.MyToolWindow(project)

        val rec = ManagedDependencyRemovalRecommendation(
            managedGroupId = "com.fasterxml.jackson.core",
            managedArtifactId = "jackson-databind",
            managedCurrentVersion = "2.14.0",
            triggerGroupId = "org.springframework.boot",
            triggerArtifactId = "spring-boot-starter-parent",
            triggerType = "parent",
            triggerCurrentVersion = "3.1.0",
            triggerTargetVersion = "3.2.0",
            transitiveVersionInTarget = "2.15.2",
            consumers = listOf(
                ConsumerDependencyInfo(
                    groupId = "org.springframework.boot",
                    artifactId = "spring-boot-starter-web",
                    resolvedVersion = "2.15.2",
                    pathDescription = "spring-boot-starter-web -> jackson-databind:2.15.2"
                )
            ),
            isSatisfiedAcrossAllConsumers = true
        )

        // Apply recommendation
        toolWindow.applyManagedDependencyRemovalRecommendations(listOf(rec))

        // Check that trigger version was selected
        val triggerKey = "org.springframework.boot:spring-boot-starter-parent"
        assertEquals("3.2.0", toolWindow.selectedVersions[triggerKey])

        // Check that managed dependency was marked for removal
        val managedKey = "com.fasterxml.jackson.core:jackson-databind"
        val managedType = MyMessageBundle.message("toolwindow.MyToolWindow.type.managedDependency")
        assertTrue(toolWindow.isManagedEntryMarkedForRemoval(managedKey, managedType))

        val removalUpdate = toolWindow.pendingManagedRemovalUpdates["$managedType|$managedKey"]
        assertNotNull(removalUpdate)
        assertTrue(removalUpdate!!.removeFromPom)
        assertEquals("2.14.0", removalUpdate.oldVersion)
        assertTrue(toolWindow.hasSelectedUpdates())
    }

    fun testContextMenuContainsCheckRemovalAction() {
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
            it.templatePresentation.text == MyMessageBundle.message("toolwindow.MyToolWindow.checkManagedRemoval.contextMenu")
        }
        assertTrue(hasCheckAction)
    }
}
