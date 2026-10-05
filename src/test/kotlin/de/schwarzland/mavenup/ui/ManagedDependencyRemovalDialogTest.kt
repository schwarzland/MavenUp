package de.schwarzland.mavenup.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.schwarzland.mavenup.model.ConsumerDependencyInfo
import de.schwarzland.mavenup.model.ManagedDependencyRemovalRecommendation

/**
 * Tests für [ManagedDependencyRemovalDialog].
 */
class ManagedDependencyRemovalDialogTest : BasePlatformTestCase() {

    fun testDialogInitializationAndSelection() {
        val rec1 = ManagedDependencyRemovalRecommendation(
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
                    pathDescription = "spring-boot-starter-web -> spring-boot-starter-json -> jackson-databind:2.15.2"
                )
            ),
            isSatisfiedAcrossAllConsumers = true
        )

        val rec2 = ManagedDependencyRemovalRecommendation(
            managedGroupId = "org.slf4j",
            managedArtifactId = "slf4j-api",
            managedCurrentVersion = "1.7.36",
            triggerGroupId = "com.example",
            triggerArtifactId = "logging-lib",
            triggerType = "dependency",
            triggerCurrentVersion = "1.0.0",
            triggerTargetVersion = "2.0.0",
            transitiveVersionInTarget = "2.0.7",
            consumers = listOf(
                ConsumerDependencyInfo(
                    groupId = "com.example",
                    artifactId = "logging-lib",
                    resolvedVersion = "2.0.7",
                    pathDescription = "logging-lib -> slf4j-api:2.0.7"
                )
            ),
            isSatisfiedAcrossAllConsumers = true
        )

        var appliedRecommendations: List<ManagedDependencyRemovalRecommendation>? = null
        val dialog = ManagedDependencyRemovalDialog(
            project = project,
            recommendations = listOf(rec1, rec2),
            onApply = { appliedRecommendations = it }
        )

        val table = dialog.buildTable()
        assertEquals(2, table.model.rowCount)
        assertEquals("com.fasterxml.jackson.core:jackson-databind", table.model.getValueAt(0, 1))
        assertEquals("org.slf4j:slf4j-api", table.model.getValueAt(1, 1))

        // All initially selected
        var selected = dialog.getSelectedRecommendations()
        assertEquals(2, selected.size)

        // Deselect all
        dialog.setAllSelected(false)
        selected = dialog.getSelectedRecommendations()
        assertTrue(selected.isEmpty())

        // Select all
        dialog.setAllSelected(true)
        selected = dialog.getSelectedRecommendations()
        assertEquals(2, selected.size)

        // Update detail panel for row 0 and row 1
        dialog.updateDetailPanel(0)
        dialog.updateDetailPanel(1)

        // OK action trigger
        dialog.doOKAction()
        assertNotNull(appliedRecommendations)
        assertEquals(2, appliedRecommendations!!.size)
    }
}
