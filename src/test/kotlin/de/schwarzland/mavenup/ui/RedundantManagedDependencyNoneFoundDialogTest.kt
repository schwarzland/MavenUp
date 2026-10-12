package de.schwarzland.mavenup.ui

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.JBUI
import java.awt.Component
import java.awt.Container
import javax.swing.JLabel
import javax.swing.text.JTextComponent

/**
 * Tests für [RedundantManagedDependencyNoneFoundDialog].
 */
class RedundantManagedDependencyNoneFoundDialogTest : BasePlatformTestCase() {

    /**
     * Prüft Titel, Close-Aktion, Größenangaben und die Einbettung der Standardtexte im Center-Panel.
     */
    fun testDialogPropertiesAndDefaultContent() {
        val dialog = RedundantManagedDependencyNoneFoundDialog(project)
        Disposer.register(testRootDisposable, dialog.disposable)

        assertEquals(
            MyMessageBundle.message("redundant.managed.dependency.dialog.title"),
            dialog.title
        )

        val actions = dialog.createActions()
        assertEquals(1, actions.size)
        assertEquals(MyMessageBundle.message("button.close"), actions[0].getValue(javax.swing.Action.NAME))

        val centerPanel = dialog.createCenterPanel()
        assertTrue(centerPanel.preferredSize.width >= JBUI.scale(600))
        assertTrue(centerPanel.preferredSize.height >= JBUI.scale(150))

        val combinedText = extractAllTexts(centerPanel)

        assertTrue(
            "Dialog should contain the no-redundancy message",
            combinedText.contains(MyMessageBundle.message("toolwindow.MyToolWindow.checkRedundantManaged.noneFound"))
        )
        assertTrue(
            "Dialog should contain the default global scope",
            combinedText.contains(MyMessageBundle.message("redundant.managed.dependency.scope.global"))
        )
        assertTrue(
            "Dialog should contain the coverage limitations",
            combinedText.contains(MyMessageBundle.message("managed.dependency.removal.coverage.limitations"))
        )
    }

    /**
     * Prüft, dass ein spezifischer Prüfumfang (z. B. aus dem Kontextmenü) korrekt im Dialog angezeigt wird.
     */
    fun testDialogWithCustomScopeDescription() {
        val customScope = "Scope: Checks if managed dependency org.example:lib is redundant."
        val dialog = RedundantManagedDependencyNoneFoundDialog(project, customScope)
        Disposer.register(testRootDisposable, dialog.disposable)

        val centerPanel = dialog.createCenterPanel()
        val combinedText = extractAllTexts(centerPanel)

        assertTrue(
            "Dialog should display the custom scope description",
            combinedText.contains(customScope)
        )
    }

    private fun extractAllTexts(container: Container): String {
        val texts = mutableListOf<String>()
        fun collect(comp: Component) {
            when (comp) {
                is JLabel -> texts.add(comp.text)
                is JTextComponent -> texts.add(comp.text)
            }
            if (comp is Container) {
                comp.components.forEach { collect(it) }
            }
        }
        collect(container)
        return texts.joinToString(" ").replace(Regex("\\s+"), " ")
    }
}
