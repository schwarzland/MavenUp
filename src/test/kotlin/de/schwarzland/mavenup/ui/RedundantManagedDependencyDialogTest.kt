package de.schwarzland.mavenup.ui

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.UIUtil
import de.schwarzland.mavenup.model.RedundancyReason
import de.schwarzland.mavenup.model.RedundantManagedDependencyRecommendation
import javax.swing.JEditorPane

/**
 * Tests für [RedundantManagedDependencyDialog].
 */
class RedundantManagedDependencyDialogTest : BasePlatformTestCase() {

    fun testDialogTableAndSelection() {
        val rec1 = RedundantManagedDependencyRecommendation(
            groupId = "org.example",
            artifactId = "lib-a",
            currentVersion = "1.0.0",
            reason = RedundancyReason.PARENT_MANAGED,
            reasonDetail = "Managed by parent POM parent:1.0 with version 1.0.0",
            providedVersion = "1.0.0",
            sourceProjectId = "my-module",
            sourcePomPath = "/path/to/pom.xml"
        )
        val rec2 = RedundantManagedDependencyRecommendation(
            groupId = "org.example",
            artifactId = "lib-b",
            currentVersion = "2.0.0",
            reason = RedundancyReason.UNUSED,
            reasonDetail = "Unused in project",
            providedVersion = null,
            sourceProjectId = "my-module",
            sourcePomPath = "/path/to/pom.xml"
        )

        var appliedRecs: List<RedundantManagedDependencyRecommendation>? = null
        var appliedShowPending: Boolean? = null

        val dialog = RedundantManagedDependencyDialog(
            project = project,
            recommendations = listOf(rec1, rec2),
            scopeDescription = "Test Scope",
            onApply = { recs, showPending ->
                appliedRecs = recs
                appliedShowPending = showPending
            }
        )
        Disposer.register(testRootDisposable, dialog.disposable)

        val centerPanel = dialog.createCenterPanel()
        assertNotNull(centerPanel)

        val table = UIUtil.findComponentOfType(centerPanel, JBTable::class.java)!!
        assertEquals(2, table.rowCount)
        assertEquals(6, table.columnCount)

        // All selected initially
        assertEquals(2, dialog.getSelectedRecommendations().size)
        assertTrue(dialog.isOKActionEnabled)

        // Deselect all
        dialog.setAllSelected(false)
        assertEquals(0, dialog.getSelectedRecommendations().size)
        assertFalse(dialog.isOKActionEnabled)

        // Select all
        dialog.setAllSelected(true)
        assertEquals(2, dialog.getSelectedRecommendations().size)
        assertTrue(dialog.isOKActionEnabled)

        // Check details panel HTML content
        val splitter = UIUtil.findComponentOfType(centerPanel, JBSplitter::class.java)!!
        val scroll = UIUtil.findComponentOfType(splitter.secondComponent, JBScrollPane::class.java)!!
        val editor = scroll.viewport.view as JEditorPane

        table.setRowSelectionInterval(0, 0)
        assertTrue(editor.text.contains("Managed by parent POM"))

        table.setRowSelectionInterval(1, 1)
        assertTrue(editor.text.contains("Unused in project"))
    }
}
