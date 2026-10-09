package de.schwarzland.mavenup.ui

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import de.schwarzland.mavenup.model.ConsumerDependencyInfo
import de.schwarzland.mavenup.model.RedundancyReason
import de.schwarzland.mavenup.model.RedundantManagedDependencyRecommendation
import java.awt.Container
import javax.swing.JEditorPane

/**
 * Tests für [RedundantManagedDependencyDialog].
 */
class RedundantManagedDependencyDialogTest : BasePlatformTestCase() {

    /**
     * Prüft, dass lange Details und mehrfaches Verbreitern die Mindestbreite nicht erhöhen.
     */
    fun testDialogCanShrinkAfterDisplayingLongDetails() {
        val shortRecommendation = RedundantManagedDependencyRecommendation(
            groupId = "org.example",
            artifactId = "a-short",
            currentVersion = "1.0",
            reason = RedundancyReason.UNUSED,
            reasonDetail = "Unused",
            providedVersion = null,
            sourceProjectId = "module",
            sourcePomPath = ""
        )
        val longRecommendation = shortRecommendation.copy(
            artifactId = "z-long",
            reasonDetail = "Managed by parent POM with a matching version. ".repeat(40),
            sourcePomPath = "C:\\" + "long-directory\\".repeat(40) + "pom.xml",
            consumers = listOf(
                ConsumerDependencyInfo(
                    groupId = "org.example",
                    artifactId = "consumer",
                    resolvedVersion = "1.0",
                    pathDescription = "org.example:consumer:1.0 -> " + "unbroken-coordinate".repeat(100)
                )
            )
        )
        val dialog = RedundantManagedDependencyDialog(project, listOf(shortRecommendation, longRecommendation))
        Disposer.register(testRootDisposable, dialog.disposable)
        val center = dialog.createCenterPanel()
        val table = UIUtil.findComponentOfType(center, JBTable::class.java)!!
        val splitter = UIUtil.findComponentOfType(center, JBSplitter::class.java)!!
        val scroll = UIUtil.findComponentOfType(splitter.secondComponent, JBScrollPane::class.java)!!
        val initialMinimumWidth = center.minimumSize.width
        assertEquals(JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER, scroll.horizontalScrollBarPolicy)

        table.setRowSelectionInterval(1, 1)
        for (width in listOf(1200, 1600, 700)) {
            center.setSize(JBUI.scale(width), JBUI.scale(600))
            layoutRecursively(center)
            assertEquals("Details must not change the minimum width", initialMinimumWidth, center.minimumSize.width)
            assertTrue(
                "The minimum must permit a narrower dialog: ${center.minimumSize.width}",
                center.minimumSize.width < JBUI.scale(700)
            )
            assertTrue("The details must fit the viewport", scroll.viewport.view.width <= scroll.viewport.extentSize.width)
        }
        table.setRowSelectionInterval(0, 0)
        layoutRecursively(center)
        assertEquals(initialMinimumWidth, center.minimumSize.width)
    }

    /**
     * Prüft die schrumpfbare, leere Ansicht ohne aktivierte Übernahmeaktion.
     */
    fun testEmptyDialogRemainsResizable() {
        val dialog = RedundantManagedDependencyDialog(project, emptyList())
        Disposer.register(testRootDisposable, dialog.disposable)
        val center = dialog.createCenterPanel()
        center.setSize(JBUI.scale(700), JBUI.scale(400))
        layoutRecursively(center)
        assertFalse(dialog.isOKActionEnabled)
        assertTrue(dialog.getSelectedRecommendations().isEmpty())
        assertTrue(center.minimumSize.width < center.width)
    }

    /**
     * Legt auch nicht angezeigte Swing-Container für Größenprüfungen rekursiv aus.
     */
    private fun layoutRecursively(container: Container) {
        container.doLayout()
        container.components.filterIsInstance<Container>().forEach(::layoutRecursively)
    }

    /**
     * Prüft Tabelleneinträge, Sammelauswahl und die Details zur ausgewählten Zeile.
     */
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
