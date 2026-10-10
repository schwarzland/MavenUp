package de.schwarzland.mavenup.ui

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.ColorUtil
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
        assertTrue("The initial dialog should provide a larger workspace", center.preferredSize.width >= JBUI.scale(1024))
        assertTrue("The initial dialog should provide a larger workspace", center.preferredSize.height >= JBUI.scale(680))
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
            consumers = listOf(
                ConsumerDependencyInfo(
                    groupId = "org.example",
                    artifactId = "consumer",
                    resolvedVersion = "1.0.0",
                    pathDescription = "org.example:consumer:1.0.0 -> org.example:lib-a:1.0.0"
                )
            ),
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
            recommendations = listOf(rec2, rec1),
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
        assertEquals(5, table.columnCount)
        assertFalse((0 until table.columnCount).any { table.getColumnName(it) == "Maven Project" })

        val splitter = UIUtil.findComponentOfType(centerPanel, JBSplitter::class.java)!!
        val scroll = UIUtil.findComponentOfType(splitter.secondComponent, JBScrollPane::class.java)!!
        val editor = scroll.viewport.view as JEditorPane
        assertEquals(0, table.selectedRow)
        assertEquals("org.example:lib-a", table.getValueAt(table.selectedRow, 1))
        assertTrue("Initial details must describe the selected sorted row", editor.text.contains(rec1.reasonDetail))
        assertTrue(editor.text.contains("Consumer Paths in Project"))
        assertTrue("Consumer paths should be rendered as a bulleted list", editor.text.contains("<li>"))
        assertTrue("The reason label should be followed by a line break", editor.text.contains("<br"))
        assertTrue("The source POM label should be followed by a line break", editor.text.contains("Source POM:</b><br"))
        assertEquals("Mark Selected Entries for Removal", MyMessageBundle.message("redundant.managed.dependency.dialog.stageRemoval"))

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
        table.setRowSelectionInterval(0, 0)
        assertTrue(editor.text.contains("Managed by parent POM"))

        table.setRowSelectionInterval(1, 1)
        assertTrue(editor.text.contains("Unused in project"))
    }

    /**
     * Prüft die Reason-Labels für gleiche, höhere und gemischte bereitgestellte Versionen.
     */
    fun testReasonLabelsDescribeProvidedVersionRelation() {
        val sameParent = recommendation(
            artifactId = "a-same-parent",
            reason = RedundancyReason.PARENT_MANAGED,
            currentVersion = "1.0",
            providedVersion = "1.0"
        )
        val higherParent = recommendation(
            artifactId = "b-higher-parent",
            reason = RedundancyReason.PARENT_MANAGED,
            currentVersion = "1.0",
            providedVersion = "1.1"
        )
        val higherDirect = recommendation(
            artifactId = "c-higher-direct",
            reason = RedundancyReason.DIRECT_DEPENDENCY_MATCH,
            currentVersion = "1.0",
            providedVersion = "1.2"
        )
        val mixedTransitive = recommendation(
            artifactId = "d-mixed-transitive",
            reason = RedundancyReason.TRANSITIVE_MATCH,
            currentVersion = "1.0",
            providedVersion = "1.0, 1.3",
            consumers = listOf(
                consumer("consumer-a", "1.0"),
                consumer("consumer-b", "1.3")
            )
        )

        val dialog = RedundantManagedDependencyDialog(
            project,
            listOf(mixedTransitive, higherDirect, higherParent, sameParent)
        )
        Disposer.register(testRootDisposable, dialog.disposable)
        val centerPanel = dialog.createCenterPanel()
        val table = UIUtil.findComponentOfType(centerPanel, JBTable::class.java)!!

        assertEquals("Version Managed by Parent (Same Version)", table.getValueAt(0, 3))
        assertEquals("Version Managed by Parent (Higher Version)", table.getValueAt(1, 3))
        assertEquals("Explicit Direct Dependency (Higher Version)", table.getValueAt(2, 3))
        assertEquals("Version Provided Transitively (Same and Higher Versions)", table.getValueAt(3, 3))
    }

    /**
     * Prüft die Projektspalte, wenn Empfehlungen aus mehreren Maven-Projekten stammen.
     */
    fun testProjectColumnIsShownForMultipleProjects() {
        val recommendations = listOf(
            recommendation(
                artifactId = "lib-a",
                reason = RedundancyReason.UNUSED,
                currentVersion = "1.0",
                providedVersion = ""
            ).copy(sourceProjectId = "module-a"),
            recommendation(
                artifactId = "lib-b",
                reason = RedundancyReason.UNUSED,
                currentVersion = "1.0",
                providedVersion = ""
            ).copy(sourceProjectId = "module-b")
        )
        val dialog = RedundantManagedDependencyDialog(project, recommendations)
        Disposer.register(testRootDisposable, dialog.disposable)
        val centerPanel = dialog.createCenterPanel()
        val table = UIUtil.findComponentOfType(centerPanel, JBTable::class.java)!!

        assertEquals(6, table.columnCount)
        assertEquals("Maven Project", table.getColumnName(5))
    }

    /**
     * Prüft, dass der Dialogtext die Maven-XML-Bezeichnung als Text statt als HTML-Tag enthält.
     */
    fun testExplanationIncludesDependencyManagementName() {
        val explanation = MyMessageBundle.message("redundant.managed.dependency.dialog.explanation")

        assertTrue(explanation.contains("Review candidate entries"))
        assertTrue(explanation.contains("dependencyManagement"))
        assertFalse(explanation.contains("in  are"))
    }

    /**
     * Erstellt eine Empfehlung für Tests der tabellarischen Reason-Beschriftung.
     */
    private fun recommendation(
        artifactId: String,
        reason: RedundancyReason,
        currentVersion: String,
        providedVersion: String,
        consumers: List<ConsumerDependencyInfo> = emptyList()
    ): RedundantManagedDependencyRecommendation = RedundantManagedDependencyRecommendation(
        groupId = "org.example",
        artifactId = artifactId,
        currentVersion = currentVersion,
        reason = reason,
        reasonDetail = "Details",
        providedVersion = providedVersion,
        consumers = consumers,
        sourceProjectId = "module",
        sourcePomPath = ""
    )

    /**
     * Erstellt einen Consumer Path mit bereitgestellter Version.
     */
    private fun consumer(artifactId: String, version: String): ConsumerDependencyInfo = ConsumerDependencyInfo(
        groupId = "org.example",
        artifactId = artifactId,
        resolvedVersion = version,
        pathDescription = "org.example:$artifactId:1.0 -> org.example:lib:$version"
    )

    /**
     * Prüft die Definition der Hervorhebungsfarbe für Light- und Dark-Mode.
     */
    fun testAffectedDependencyColorDefinition() {
        val color = RedundantManagedDependencyDialog.AFFECTED_DEPENDENCY_COLOR
        assertNotNull(color)
        val htmlColor = ColorUtil.toHtmlColor(color)
        assertTrue(htmlColor.startsWith("#"))
    }

    /**
     * Prüft, dass die betroffene Koordinate in der Redundanzbegründung farblich hervorgehoben wird.
     */
    fun testFormatReasonDetailHighlightsAffectedDependency() {
        val target = "org.apache.tomcat.embed:tomcat-embed-core"
        val reason = "Parent hierarchy org.springframework.boot:spring-boot-starter-parent:4.1.1 manages " +
            "org.apache.tomcat.embed:tomcat-embed-core at version 11.0.24, which is higher than the local version 11.0.20."
        val colorHex = "#0055aa"

        val formatted = formatReasonDetail(reason, target, colorHex)
        assertTrue(formatted.contains("<span style=\"color: #0055aa;\">org.apache.tomcat.embed:tomcat-embed-core</span>"))
        assertFalse(formatted.contains("<span style=\"color: #0055aa;\">org.springframework.boot:spring-boot-starter-parent"))
    }

    /**
     * Prüft, dass die betroffene Abhängigkeit am Ende und innerhalb von Konsumentenpfaden farblich hervorgehoben wird.
     */
    fun testFormatConsumerPathHighlightsAffectedDependency() {
        val target = "org.apache.tomcat.embed:tomcat-embed-core"
        val colorHex = "#0055aa"
        val path1 = "org.springframework.boot:spring-boot-starter-web:4.1.1 -> " +
            "org.springframework.boot:spring-boot-starter-tomcat:4.1.1 -> " +
            "org.springframework.boot:spring-boot-starter-tomcat-runtime:4.1.1 -> " +
            "org.apache.tomcat.embed:tomcat-embed-core:11.0.20"
        val path2 = "org.springframework.boot:spring-boot-starter-web:4.1.1 -> " +
            "org.springframework.boot:spring-boot-starter-tomcat:4.1.1 -> " +
            "org.springframework.boot:spring-boot-starter-tomcat-runtime:4.1.1 -> " +
            "org.apache.tomcat.embed:tomcat-embed-websocket:11.0.24 -> " +
            "org.apache.tomcat.embed:tomcat-embed-core:11.0.20"

        val formatted1 = formatConsumerPath(path1, target, colorHex)
        assertTrue(formatted1.contains("<span style=\"color: #0055aa;\">org.apache.tomcat.embed:tomcat-embed-core:11.0.20</span>"))
        assertFalse(formatted1.contains("<span style=\"color: #0055aa;\">org.springframework.boot:spring-boot-starter-web"))

        val formatted2 = formatConsumerPath(path2, target, colorHex)
        assertTrue(formatted2.contains("<span style=\"color: #0055aa;\">org.apache.tomcat.embed:tomcat-embed-core:11.0.20</span>"))
        assertFalse(formatted2.contains("<span style=\"color: #0055aa;\">org.apache.tomcat.embed:tomcat-embed-websocket"))
    }

    /**
     * Prüft die Detailansicht im Dialog für das im Issue beschriebene Szenario mit farblicher Hervorhebung.
     */
    fun testDialogDetailsPanelRendersColoredAffectedDependency() {
        val path1 = "org.springframework.boot:spring-boot-starter-web:4.1.1 -> " +
            "org.springframework.boot:spring-boot-starter-tomcat:4.1.1 -> " +
            "org.springframework.boot:spring-boot-starter-tomcat-runtime:4.1.1 -> " +
            "org.apache.tomcat.embed:tomcat-embed-core:11.0.20"
        val path2 = "org.springframework.boot:spring-boot-starter-web:4.1.1 -> " +
            "org.springframework.boot:spring-boot-starter-tomcat:4.1.1 -> " +
            "org.springframework.boot:spring-boot-starter-tomcat-runtime:4.1.1 -> " +
            "org.apache.tomcat.embed:tomcat-embed-websocket:11.0.24 -> " +
            "org.apache.tomcat.embed:tomcat-embed-core:11.0.20"

        val rec = RedundantManagedDependencyRecommendation(
            groupId = "org.apache.tomcat.embed",
            artifactId = "tomcat-embed-core",
            currentVersion = "11.0.20",
            reason = RedundancyReason.PARENT_MANAGED,
            reasonDetail = "Parent hierarchy org.springframework.boot:spring-boot-starter-parent:4.1.1 manages " +
                "org.apache.tomcat.embed:tomcat-embed-core at version 11.0.24, which is higher than the local version 11.0.20. " +
                "Removing the local entry will make Maven use 11.0.24; review the affected consumer paths before applying.",
            providedVersion = "11.0.24",
            consumers = listOf(
                ConsumerDependencyInfo(
                    groupId = "org.springframework.boot",
                    artifactId = "spring-boot-starter-web",
                    resolvedVersion = "11.0.24",
                    pathDescription = path1
                ),
                ConsumerDependencyInfo(
                    groupId = "org.springframework.boot",
                    artifactId = "spring-boot-starter-web",
                    resolvedVersion = "11.0.24",
                    pathDescription = path2
                )
            ),
            sourceProjectId = "pellet-price-backend",
            sourcePomPath = "/Users/michael/Coding/Schwarzland/pellet-price-backend/pom.xml"
        )

        val dialog = RedundantManagedDependencyDialog(project, listOf(rec))
        Disposer.register(testRootDisposable, dialog.disposable)
        val centerPanel = dialog.createCenterPanel()
        val splitter = UIUtil.findComponentOfType(centerPanel, JBSplitter::class.java)!!
        val scroll = UIUtil.findComponentOfType(splitter.secondComponent, JBScrollPane::class.java)!!
        val editor = scroll.viewport.view as JEditorPane

        val colorHex = ColorUtil.toHtmlColor(RedundantManagedDependencyDialog.AFFECTED_DEPENDENCY_COLOR)
        assertTrue(editor.text.contains(colorHex))
        assertTrue(editor.text.contains("tomcat-embed-core"))
    }
}
