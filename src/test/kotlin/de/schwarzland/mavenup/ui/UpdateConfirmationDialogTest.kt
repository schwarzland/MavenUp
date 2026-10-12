package de.schwarzland.mavenup.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.schwarzland.mavenup.model.DependencyUpdate
import de.schwarzland.mavenup.model.TargetPomMode
import de.schwarzland.mavenup.service.MavenUpSettings

class UpdateConfirmationDialogTest : BasePlatformTestCase() {

    fun testTargetPomComboBoxDefaultsToRootPom() {
        val updates = listOf(
            DependencyUpdate(
                groupId = "org.example",
                artifactId = "transitive-dep",
                type = "managed dependency",
                oldVersion = "1.0.0",
                newVersion = "1.1.0",
                transitive = true
            )
        )
        val dialog = UpdateConfirmationDialog(project, updates)
        assertEquals(TargetPomMode.ROOT_POM, dialog.getSelectedTargetPomMode())
        assertEquals(TargetPomMode.ROOT_POM, dialog.targetPomComboBox.selectedItem)
    }

    fun testSetAndGetSelectedTargetPomMode() {
        val updates = listOf(
            DependencyUpdate(
                groupId = "org.example",
                artifactId = "transitive-dep",
                type = "managed dependency",
                oldVersion = "1.0.0",
                newVersion = "1.1.0",
                transitive = true
            )
        )
        val dialog = UpdateConfirmationDialog(project, updates)
        dialog.setSelectedTargetPomMode(TargetPomMode.AFFECTED_MODULES)
        assertEquals(TargetPomMode.AFFECTED_MODULES, dialog.getSelectedTargetPomMode())

        dialog.setSelectedTargetPomMode(TargetPomMode.ALL_MODULES)
        assertEquals(TargetPomMode.ALL_MODULES, dialog.getSelectedTargetPomMode())
    }

    fun testTargetPomComboBoxContainsAllModes() {
        val dialog = UpdateConfirmationDialog(project, emptyList())
        val items = (0 until dialog.targetPomComboBox.itemCount).map { dialog.targetPomComboBox.getItemAt(it) }
        assertEquals(listOf(TargetPomMode.ROOT_POM, TargetPomMode.AFFECTED_MODULES, TargetPomMode.ALL_MODULES), items)
    }

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

    fun testBuildTableShowsTargetModuleWithArtifactFallback() {
        val pomFile = myFixture.tempDirFixture.createFile("module-a/pom.xml")
        val updates = listOf(
            DependencyUpdate(
                groupId = "org.example",
                artifactId = "managed-dep",
                type = "managed dependency",
                oldVersion = "1.0.0",
                newVersion = "1.1.0",
                pomFile = pomFile
            )
        )
        val dialog = UpdateConfirmationDialog(project, updates)
        val table = dialog.buildTable()

        assertEquals(MyMessageBundle.message("toolwindow.MyToolWindow.table.header.module"), table.getColumnName(5))
        assertEquals("managed-dep", table.getValueAt(0, 5))
    }
}
