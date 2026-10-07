package de.schwarzland.mavenup.ui

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.JBUI
import de.schwarzland.mavenup.model.ConsumerDependencyInfo
import de.schwarzland.mavenup.model.ManagedDependencyRemovalRecommendation
import de.schwarzland.mavenup.model.ManagedDependencyTargetVersion
import javax.swing.JEditorPane
import javax.swing.JComponent
import javax.swing.UIManager
import java.awt.Color
import java.awt.Cursor
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage

/**
 * Tests für [ManagedDependencyRemovalDialog].
 */
class ManagedDependencyRemovalDialogTest : BasePlatformTestCase() {

    /**
     * Prüft, dass ausgewählte Empfehlungen dieselbe niedrigste gemeinsame Zielversion verwenden.
     */
    fun testSelectedRecommendationsUseLowestCommonTargetVersion() {
        val consumer = ConsumerDependencyInfo("com.example", "consumer", "2.0.0", "consumer -> managed")
        val first = recommendation(
            managedArtifactId = "first-managed",
            targetVersionOptions = listOf(
                ManagedDependencyTargetVersion("2.0.0", "1.5.0", listOf(consumer)),
                ManagedDependencyTargetVersion("3.0.0", "1.6.0", listOf(consumer))
            )
        )
        val second = recommendation(
            managedArtifactId = "second-managed",
            targetVersionOptions = listOf(
                ManagedDependencyTargetVersion("3.0.0", "2.5.0", listOf(consumer)),
                ManagedDependencyTargetVersion("4.0.0", "2.6.0", listOf(consumer))
            )
        )
        val dialog = ManagedDependencyRemovalDialog(project, listOf(first, second))
        Disposer.register(testRootDisposable, dialog.disposable)
        val table = UIUtil.findComponentOfType(dialog.createCenterPanel(), JBTable::class.java)!!

        val selected = dialog.getSelectedRecommendations()
        assertEquals(listOf("3.0.0", "3.0.0"), selected.map { it.triggerTargetVersion })
        assertEquals(listOf("1.6.0", "2.5.0"), selected.map { it.transitiveVersionInTarget })
        assertEquals("3.0.0", table.model.getValueAt(0, 4))
        assertEquals("3.0.0", table.model.getValueAt(1, 4))

        table.model.setValueAt(false, 1, 0)
        assertEquals("2.0.0", dialog.getSelectedRecommendations().single().triggerTargetVersion)
    }

    /**
     * Prüft, dass ein Konflikt sichtbar ist und die Anwendung bis zur Auflösung verhindert.
     */
    fun testConflictingTargetVersionsPreventApplyingSelectedRecommendations() {
        val consumer = ConsumerDependencyInfo("com.example", "consumer", "2.0.0", "consumer -> managed")
        val first = recommendation(
            managedArtifactId = "first-managed",
            targetVersionOptions = listOf(ManagedDependencyTargetVersion("2.0.0", "1.5.0", listOf(consumer)))
        )
        val second = recommendation(
            managedArtifactId = "second-managed",
            targetVersionOptions = listOf(ManagedDependencyTargetVersion("3.0.0", "2.5.0", listOf(consumer)))
        )
        val independent = recommendation(
            managedArtifactId = "independent-managed",
            triggerArtifactId = "other-trigger",
            targetVersionOptions = listOf(ManagedDependencyTargetVersion("4.0.0", "3.5.0", listOf(consumer)))
        )
        var appliedRecommendations: List<ManagedDependencyRemovalRecommendation>? = null
        val dialog = ManagedDependencyRemovalDialog(project, listOf(first, second, independent)) {
            appliedRecommendations = it
        }
        Disposer.register(testRootDisposable, dialog.disposable)
        val table = UIUtil.findComponentOfType(dialog.createCenterPanel(), JBTable::class.java)!!

        assertTrue(dialog.getSelectedRecommendations().isEmpty())
        assertEquals(
            MyMessageBundle.message("managed.dependency.removal.dialog.targetVersion.conflict"),
            table.model.getValueAt(0, 4)
        )
        assertEquals("4.0.0", table.model.getValueAt(2, 4))
        dialog.doOKAction()
        assertNull(appliedRecommendations)

        table.model.setValueAt(false, 1, 0)
        assertEquals(
            "2.0.0",
            dialog.getSelectedRecommendations().first { it.managedArtifactId == "first-managed" }.triggerTargetVersion
        )
    }

    /**
     * Erstellt eine Testempfehlung mit den angegebenen geprüften Zielversionen.
     *
     * @param managedArtifactId Artefakt-ID des verwalteten Eintrags.
     * @param triggerArtifactId Artefakt-ID der auslösenden Komponente.
     * @param targetVersionOptions Geprüfte Zielversionen und bereitgestellte Versionen.
     * @return Die konfigurierte Bereinigungsempfehlung.
     */
    private fun recommendation(
        managedArtifactId: String,
        triggerArtifactId: String = "trigger",
        targetVersionOptions: List<ManagedDependencyTargetVersion>
    ) = ManagedDependencyRemovalRecommendation(
        managedGroupId = "com.example",
        managedArtifactId = managedArtifactId,
        managedCurrentVersion = "1.0.0",
        triggerGroupId = "com.example",
        triggerArtifactId = triggerArtifactId,
        triggerType = "dependency",
        triggerCurrentVersion = "1.0.0",
        triggerTargetVersion = targetVersionOptions.first().version,
        transitiveVersionInTarget = targetVersionOptions.first().transitiveVersionInTarget,
        consumers = targetVersionOptions.first().consumers,
        isSatisfiedAcrossAllConsumers = true,
        targetVersionOptions = targetVersionOptions
    )

    /** Prüft Auswahl, Detailwechsel und die unveränderte Übernahme ausgewählter Empfehlungen. */
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
        Disposer.register(testRootDisposable, dialog.disposable)

        val splitter = UIUtil.findComponentOfType(dialog.createCenterPanel(), JBSplitter::class.java)!!
        val detailScroll = UIUtil.findComponentOfType(splitter.secondComponent, JBScrollPane::class.java)!!
        val editor = detailScroll.viewport.view as JEditorPane
        assertTrue(editor.text.contains("jackson-databind"))
        assertTrue(editor.text.contains("spring-boot-starter-json"))
        assertFalse(editor.isEditable)
        assertEquals(JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER, detailScroll.horizontalScrollBarPolicy)

        val table = UIUtil.findComponentOfType(splitter.firstComponent, JBTable::class.java)!!
        assertEquals(2, table.model.rowCount)
        assertEquals("com.fasterxml.jackson.core:jackson-databind", table.model.getValueAt(0, 1))
        assertEquals("org.slf4j:slf4j-api", table.model.getValueAt(1, 1))

        // All initially selected
        assertEquals(2, dialog.getSelectedRecommendations().size)

        // Deselect all
        dialog.setAllSelected(false)
        assertTrue(dialog.getSelectedRecommendations().isEmpty())

        // Select all
        dialog.setAllSelected(true)
        assertEquals(2, dialog.getSelectedRecommendations().size)

        // Update detail panel for row 0 and row 1
        dialog.updateDetailPanel(0)
        dialog.updateDetailPanel(1)
        assertTrue(editor.text.contains("logging-lib"))
        assertTrue(editor.text.contains("slf4j-api"))
        assertFalse(editor.text.contains("jackson-databind"))
        assertEquals(0, editor.caretPosition)
        val previousDetails = editor.text
        dialog.updateDetailPanel(-1)
        dialog.updateDetailPanel(2)
        assertEquals(previousDetails, editor.text)

        // OK action trigger
        dialog.doOKAction()
        assertNotNull(appliedRecommendations)
        assertEquals(2, appliedRecommendations!!.size)
    }

    /** Prüft Orientierung, Mindesthöhen, Größenänderung und gespeicherte Splitter-Aufteilung. */
    fun testResizableSplitLayoutAndRememberedProportion() {
        val key = "MavenUp.ManagedDependencyRemovalDialog.splitter"
        val properties = PropertiesComponent.getInstance()
        val previousValue = properties.getValue(key)
        properties.unsetValue(key)
        try {
            val dialog = ManagedDependencyRemovalDialog(project, emptyList())
            Disposer.register(testRootDisposable, dialog.disposable)
            val splitter = UIUtil.findComponentOfType(dialog.createCenterPanel(), JBSplitter::class.java)!!
            assertTrue(splitter.isVertical)
            assertTrue(splitter.isHonorMinimumSize)
            assertEquals(0.65f, splitter.proportion)
            assertTrue(splitter.firstComponent.minimumSize.height >= 120)
            assertTrue(splitter.secondComponent.minimumSize.height >= 100)
            assertNotNull(UIUtil.findComponentOfType(splitter.firstComponent, JBTable::class.java))

            splitter.setSize(900, 450)
            splitter.proportion = 0.15f
            splitter.doLayout()
            assertTrue(splitter.firstComponent.height >= splitter.firstComponent.minimumSize.height)
            splitter.proportion = 0.85f
            splitter.doLayout()
            assertTrue(splitter.secondComponent.height >= splitter.secondComponent.minimumSize.height)
            splitter.proportion = 0.4f
            splitter.doLayout()
            val expandedDetailsHeight = splitter.secondComponent.height
            splitter.proportion = 0.75f
            splitter.doLayout()
            assertTrue(splitter.secondComponent.height < expandedDetailsHeight)
            assertEquals(0.75f, properties.getFloat(key, 0.65f))

            val reopened = ManagedDependencyRemovalDialog(project, emptyList())
            Disposer.register(testRootDisposable, reopened.disposable)
            val restored = UIUtil.findComponentOfType(reopened.createCenterPanel(), JBSplitter::class.java)!!
            assertEquals(0.75f, restored.proportion)
            assertTrue(reopened.getSelectedRecommendations().isEmpty())
        } finally {
            properties.setValue(key, previousValue)
        }
    }

    /** Prüft lange Inhalte, HTML-Maskierung und den gemeinsamen Scrollbereich für alle Details. */
    fun testLongDetailsRemainScrollableAndResetOnSelection() {
        val consumers = (1..80).map { index ->
            ConsumerDependencyInfo(
                groupId = "com.example",
                artifactId = "consumer-$index",
                resolvedVersion = "2.0",
                pathDescription = "consumer-$index -> ${"long-path-".repeat(30)}<leaf>&:2.0"
            )
        }
        val recommendation = ManagedDependencyRemovalRecommendation(
            managedGroupId = "com.example",
            managedArtifactId = "managed",
            managedCurrentVersion = "1.0",
            triggerGroupId = "com.example",
            triggerArtifactId = "trigger",
            triggerType = "dependency",
            triggerCurrentVersion = "1.0",
            triggerTargetVersion = "2.0",
            transitiveVersionInTarget = "2.0",
            consumers = consumers,
            isSatisfiedAcrossAllConsumers = true
        )
        val dialog = ManagedDependencyRemovalDialog(
            project, listOf(recommendation, recommendation.copy(consumers = emptyList()))
        )
        Disposer.register(testRootDisposable, dialog.disposable)
        val splitter = UIUtil.findComponentOfType(dialog.createCenterPanel(), JBSplitter::class.java)!!
        val scroll = UIUtil.findComponentOfType(splitter.secondComponent, JBScrollPane::class.java)!!
        val editor = scroll.viewport.view as JEditorPane
        scroll.setSize(400, 120)
        scroll.doLayout()
        editor.setSize(scroll.viewport.extentSize.width, editor.preferredSize.height)
        scroll.doLayout()
        assertTrue(editor.preferredSize.height > scroll.viewport.extentSize.height)
        assertTrue(editor.text.contains("consumer-80"))
        assertTrue(editor.text.contains("&lt;leaf&gt;&amp;"))
        assertTrue(editor.text.contains("upgrading dependency"))

        editor.caretPosition = editor.document.length
        val table = UIUtil.findComponentOfType(splitter.firstComponent, JBTable::class.java)!!
        table.setRowSelectionInterval(1, 1)
        assertEquals(0, editor.caretPosition)
        assertFalse(editor.text.contains("consumer-80"))
        assertTrue(editor.text.contains(
            MyMessageBundle.message("managed.dependency.removal.dialog.detail.consumers.empty")
        ))
    }

    /** Prüft sichtbare Linie und Griff, Theme-Farben, Hover und native Ziehfunktion. */
    fun testVisibleDividerGripAndMouseDragging() {
        val key = "MavenUp.ManagedDependencyRemovalDialog.splitter"
        val properties = PropertiesComponent.getInstance()
        val previousProportion = properties.getValue(key)
        val lineColor = UIManager.get("Separator.separatorColor")
        val focusColor = UIManager.get("Component.focusColor")
        try {
            val dialog = ManagedDependencyRemovalDialog(project, emptyList())
            Disposer.register(testRootDisposable, dialog.disposable)
            val splitter = UIUtil.findComponentOfType(dialog.createCenterPanel(), JBSplitter::class.java)!!
            splitter.setSize(900, 450)
            splitter.proportion = 0.65f
            splitter.doLayout()
            val divider = splitter.divider
            assertEquals(JBUI.scale(10), divider.height)
            assertEquals(Cursor.N_RESIZE_CURSOR, divider.cursor.type)
            assertEquals(
                MyMessageBundle.message("managed.dependency.removal.dialog.divider"),
                divider.accessibleContext.accessibleName
            )
            val normal = renderDivider(divider)
            val centerX = divider.width / 2
            val centerY = divider.height / 2
            assertTrue(normal.getRGB(JBUI.scale(20), centerY) ushr 24 > 0)
            assertTrue(normal.getRGB(centerX, centerY) ushr 24 > 0)
            assertTrue(normal.getRGB(JBUI.scale(20), centerY) != normal.getRGB(JBUI.scale(20), 0))
            assertTrue(normal.getRGB(centerX, centerY) != normal.getRGB(centerX, 0))
            assertEquals(normal.getRGB(centerX, 0), normal.getRGB(centerX + JBUI.scale(10), centerY))

            UIManager.put("Component.focusColor", Color.MAGENTA)
            divider.dispatchEvent(MouseEvent(divider, MouseEvent.MOUSE_ENTERED, 0, 0, centerX, centerY, 0, false))
            assertEquals(Color.MAGENTA.rgb, renderDivider(divider).getRGB(centerX, centerY))
            divider.dispatchEvent(MouseEvent(divider, MouseEvent.MOUSE_EXITED, 0, 0, centerX, centerY, 0, false))
            assertEquals(normal.getRGB(centerX, centerY), renderDivider(divider).getRGB(centerX, centerY))
            for (themeLine in listOf(Color.DARK_GRAY, Color.LIGHT_GRAY)) {
                UIManager.put("Separator.separatorColor", themeLine)
                assertEquals(themeLine.rgb, renderDivider(divider).getRGB(JBUI.scale(20), centerY))
            }

            divider.dispatchEvent(MouseEvent(
                divider, MouseEvent.MOUSE_DRAGGED, 0, MouseEvent.BUTTON1_DOWN_MASK,
                centerX, -JBUI.scale(60), 0, false
            ))
            assertTrue(splitter.proportion < 0.65f)
            assertEquals(splitter.proportion, properties.getFloat(key, 0.65f))
        } finally {
            properties.setValue(key, previousProportion)
            UIManager.put("Separator.separatorColor", lineColor)
            UIManager.put("Component.focusColor", focusColor)
        }
    }

    /** Rendert den echten Divider inklusive Hintergrund, um sichtbare Linie und Griffpixel zu prüfen. */
    private fun renderDivider(divider: JComponent): BufferedImage {
        val image = BufferedImage(divider.width, divider.height, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            divider.paint(graphics)
        } finally {
            graphics.dispose()
        }
        return image
    }

    /** Prüft die Detailtexte, wenn die verwaltete Abhängigkeit bereits in der aktuellen Version bereitgestellt wird. */
    fun testDetailExplanationForCurrentVersionParentAndDependency() {
        val recParent = ManagedDependencyRemovalRecommendation(
            managedGroupId = "org.xmlunit",
            managedArtifactId = "xmlunit-core",
            managedCurrentVersion = "2.9.1",
            triggerGroupId = "org.springframework.boot",
            triggerArtifactId = "spring-boot-starter-parent",
            triggerType = "parent",
            triggerCurrentVersion = "3.3.5",
            triggerTargetVersion = "3.3.5",
            transitiveVersionInTarget = "2.9.1",
            consumers = emptyList(),
            isSatisfiedAcrossAllConsumers = true
        )

        val recDependency = ManagedDependencyRemovalRecommendation(
            managedGroupId = "org.xmlunit",
            managedArtifactId = "xmlunit-core",
            managedCurrentVersion = "2.9.1",
            triggerGroupId = "org.springframework.boot",
            triggerArtifactId = "spring-boot-starter-test",
            triggerType = "dependency",
            triggerCurrentVersion = "3.3.5",
            triggerTargetVersion = "3.3.5",
            transitiveVersionInTarget = "2.9.1",
            consumers = listOf(
                ConsumerDependencyInfo(
                    groupId = "org.springframework.boot",
                    artifactId = "spring-boot-starter-test",
                    resolvedVersion = "2.9.1",
                    pathDescription = "spring-boot-starter-test:3.3.5 -> xmlunit-core:2.9.1"
                )
            ),
            isSatisfiedAcrossAllConsumers = true
        )

        val dialog = ManagedDependencyRemovalDialog(project, listOf(recParent, recDependency))
        Disposer.register(testRootDisposable, dialog.disposable)

        val splitter = UIUtil.findComponentOfType(dialog.createCenterPanel(), JBSplitter::class.java)!!
        val scroll = UIUtil.findComponentOfType(splitter.secondComponent, JBScrollPane::class.java)!!
        val editor = scroll.viewport.view as JEditorPane

        dialog.updateDetailPanel(0)
        assertTrue(editor.text.contains("parent POM"))
        assertTrue(editor.text.contains("spring-boot-starter-parent"))
        assertTrue(editor.text.contains("already provides version 2.9.1"))
        assertFalse(editor.text.contains("upgrading parent POM"))

        dialog.updateDetailPanel(1)
        assertTrue(editor.text.contains("dependency"))
        assertTrue(editor.text.contains("spring-boot-starter-test"))
        assertTrue(editor.text.contains("already provides version 2.9.1"))
        assertFalse(editor.text.contains("upgrading dependency"))
    }
}
