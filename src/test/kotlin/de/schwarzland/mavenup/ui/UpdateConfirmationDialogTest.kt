package de.schwarzland.mavenup.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.schwarzland.mavenup.model.DependencyUpdate
import de.schwarzland.mavenup.service.MavenUpSettings

class UpdateConfirmationDialogTest : BasePlatformTestCase() {

    fun testBuildTableDisplaysWillBeCommentedOutWhenConfigured() {
        val settings = MavenUpSettings.getInstance()
        val originalCommentOut = settings.state.commentOutManagedEntriesOnRemoval
        try {
            settings.state.commentOutManagedEntriesOnRemoval = true
            val updates = listOf(
                DependencyUpdate(
                    groupId = "org.example",
                    artifactId = "managed-dep",
                    type = "managed dependency",
                    oldVersion = "1.0.0",
                    newVersion = "1.0.0",
                    removeFromPom = true
                )
            )
            val dialog = UpdateConfirmationDialog(project, updates)
            val table = dialog.buildTable()

            assertEquals(1, table.rowCount)
            assertEquals(
                MyMessageBundle.message("toolwindow.MyToolWindow.version.willCommentOut"),
                table.getValueAt(0, 4)
            )
        } finally {
            settings.state.commentOutManagedEntriesOnRemoval = originalCommentOut
        }
    }

    fun testBuildTableDisplaysWillBeRemovedWhenConfigured() {
        val settings = MavenUpSettings.getInstance()
        val originalCommentOut = settings.state.commentOutManagedEntriesOnRemoval
        try {
            settings.state.commentOutManagedEntriesOnRemoval = false
            val updates = listOf(
                DependencyUpdate(
                    groupId = "org.example",
                    artifactId = "managed-dep",
                    type = "managed dependency",
                    oldVersion = "1.0.0",
                    newVersion = "1.0.0",
                    removeFromPom = true
                )
            )
            val dialog = UpdateConfirmationDialog(project, updates)
            val table = dialog.buildTable()

            assertEquals(1, table.rowCount)
            assertEquals(
                MyMessageBundle.message("toolwindow.MyToolWindow.version.willRemove"),
                table.getValueAt(0, 4)
            )
        } finally {
            settings.state.commentOutManagedEntriesOnRemoval = originalCommentOut
        }
    }
}
