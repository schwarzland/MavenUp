package de.schwarzland.mavenup.ui

import com.intellij.openapi.ui.ComboBox
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import de.schwarzland.mavenup.model.DependencyUpdate
import de.schwarzland.mavenup.model.TargetPomMode
import de.schwarzland.mavenup.service.MavenUpSettings
import javax.swing.JLabel
import javax.swing.table.TableRowSorter
import javax.swing.text.JTextComponent

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

    fun testTargetPomComboBoxRemainsVisibleButDisabledWithoutTransitiveMultiModuleUpdates() {
        val dialog = UpdateConfirmationDialog(project, emptyList())
        val centerPanel = dialog.buildCenterPanel()

        assertSame(dialog.targetPomComboBox, UIUtil.findComponentOfType(centerPanel, ComboBox::class.java))
        assertFalse(dialog.targetPomComboBox.isEnabled)
    }

    /** Zeigt die Anzahl nur für transitive Pins, deren Ziel-POM tatsächlich über den Selektor bestimmt wird. */
    fun testTargetPomCountIncludesOnlyTransitiveUpdatesWithoutAssignedPom() {
        val assignedPom = myFixture.tempDirFixture.createFile("module-a/pom.xml")
        val updates = listOf(
            DependencyUpdate("org.example", "transitive-a", "managed dependency", "1.0", "1.1", transitive = true),
            DependencyUpdate("org.example", "transitive-b", "managed dependency", "1.0", "1.1", transitive = true),
            DependencyUpdate("org.example", "direct", "dependency", "1.0", "1.1"),
            DependencyUpdate(
                "org.example",
                "targeted-transitive",
                "managed dependency",
                "1.0",
                "1.1",
                transitive = true,
                pomFile = assignedPom
            )
        )
        val dialog = UpdateConfirmationDialog(project, updates)
        val centerPanel = dialog.buildCenterPanel()
        val components = UIUtil.uiTraverser(centerPanel).toList()
        val displayedTexts = components.mapNotNull {
            when (it) {
                is JLabel -> it.text
                is JTextComponent -> it.text
                else -> null
            }
        }

        val expectedCount = MyMessageBundle.message(
            "toolwindow.MyToolWindow.update.confirm.targetPom.affectedCount",
            2
        )
        assertTrue(displayedTexts.any { it.contains(expectedCount) })
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
                table.getValueAt(0, 5)
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
                table.getValueAt(0, 5)
            )
        } finally {
            settings.state.commentOutManagedEntriesOnRemoval = originalCommentOut
        }
    }

    fun testBuildTableShowsPomPathWhenTargetModuleCannotBeResolved() {
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

        assertEquals(
            listOf(
                "toolwindow.MyToolWindow.table.header.groupId",
                "toolwindow.MyToolWindow.table.header.artifactId",
                "toolwindow.MyToolWindow.table.header.module",
                "toolwindow.MyToolWindow.table.header.type",
                "toolwindow.MyToolWindow.table.header.currentVersion",
                "toolwindow.MyToolWindow.table.header.newVersion"
            ).map(MyMessageBundle::message),
            (0 until table.columnCount).map(table::getColumnName)
        )
        assertEquals(pomFile.path, table.getValueAt(0, 2))
        assertEquals("managed dependency", table.getValueAt(0, 3))

        val sorter = table.rowSorter as TableRowSorter<*>
        (0..3).forEach { assertTrue("Column $it should be sortable", sorter.isSortable(it)) }
        (4..5).forEach { assertFalse("Column $it should not be sortable", sorter.isSortable(it)) }
    }

    fun testTargetPomModeChangeRefreshesModuleColumnWithArtifactFallbackWhenNoTargetPomExists() {
        val update = DependencyUpdate(
            groupId = "org.example",
            artifactId = "spring-core",
            type = "managed dependency",
            oldVersion = "1.0.0",
            newVersion = "1.1.0",
            transitive = true
        )
        val dialog = UpdateConfirmationDialog(project, listOf(update))
        val table = dialog.buildTable()

        assertEquals("spring-core", table.getValueAt(0, 2))
        dialog.setSelectedTargetPomMode(TargetPomMode.ALL_MODULES)
        assertEquals("spring-core", table.getValueAt(0, 2))
    }
}
