package de.schwarzland.mavenup

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import org.jetbrains.idea.maven.project.MavenProjectsManager
import java.awt.BorderLayout
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JPanel
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
        val myToolWindow = MyToolWindow(project)

        val content = ContentFactory
            .getInstance()
            .createContent(myToolWindow.getContent(), null, false)

        toolWindow.contentManager.addContent(content)
    }

    class MyToolWindow(private val project: Project) {
        private val content = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            val dependenciesPanel = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
            }

            val refreshAction = {
                dependenciesPanel.removeAll()
                val mavenProjectsManager = MavenProjectsManager.getInstance(project)
                val projects = mavenProjectsManager.projects
                
                if (projects.isEmpty()) {
                    dependenciesPanel.add(JBLabel(MyMessageBundle.message("toolwindow.MyToolWindow.noProjects.label")))
                } else {
                    projects.forEach { mavenProject ->
                        dependenciesPanel.add(JBLabel(MyMessageBundle.message("toolwindow.MyToolWindow.project.label", mavenProject.mavenId.artifactId)).apply {
                            font = font.deriveFont(java.awt.Font.BOLD)
                        })
                        
                        mavenProject.dependencies.forEach { dependency ->
                            dependenciesPanel.add(JBLabel("  ${dependency.groupId}:${dependency.artifactId}:${dependency.version}"))
                        }
                    }
                }
                dependenciesPanel.revalidate()
                dependenciesPanel.repaint()
            }

            refreshAction()

            add(JBScrollPane(dependenciesPanel), BorderLayout.CENTER)
            add(JButton(MyMessageBundle.message("toolwindow.MyToolWindow.refresh.button")).apply {
                addActionListener { refreshAction() }
            }, BorderLayout.SOUTH)
        }

        fun getContent(): JBPanel<JBPanel<*>> = content
    }
}
