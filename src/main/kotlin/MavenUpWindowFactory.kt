package de.schwarzland

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.content.ContentFactory
import org.jetbrains.idea.maven.project.MavenProjectsManager
import javax.swing.JButton
import kotlin.random.Random

/**
 * Zusammenfassung
 * MyToolWindowFactory macht Folgendes:
 * registriert bzw. erzeugt den Inhalt eines IntelliJ Tool Windows
 * erstellt ein einfaches UI-Panel
 * zeigt ein Label mit einer Zahl an
 * bietet einen Button, der die Zahl zufällig neu setzt
 * verwendet MyMessageBundle, um UI-Texte aus Properties-Dateien zu laden
 * Aktuell ist das also ein einfaches Beispiel-Tool-Window, vermutlich aus einem Plugin-Template, das als Grundlage für weitere Funktionalität dienen kann.
 */

class MavenUpWindowFactory : ToolWindowFactory {
    override fun shouldBeAvailable(project: Project): Boolean {
        return MavenProjectsManager.getInstance(project).hasProjects()
    }

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val myToolWindow = MyToolWindow()

        val content = ContentFactory
            .getInstance()
            .createContent(myToolWindow.getContent(), null, false)

        toolWindow.contentManager.addContent(content)
    }

    class MyToolWindow {
        private val content = JBPanel<JBPanel<*>>().apply {
            val label = JBLabel(MyMessageBundle.message("toolwindow.MyToolWindow.number.label", "?"))

            add(label)
            add(JButton(MyMessageBundle.message("toolwindow.MyToolWindow.shuffle.button")).apply {
                addActionListener {
                    label.text = MyMessageBundle.message(
                        "toolwindow.MyToolWindow.number.label",
                        Random(System.currentTimeMillis()).nextInt(1000)
                    )
                }
            })
        }

        fun getContent(): JBPanel<JBPanel<*>> = content
    }
}
