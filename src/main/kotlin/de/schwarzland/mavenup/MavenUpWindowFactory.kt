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
import java.awt.FlowLayout
import java.net.HttpURLConnection
import java.net.URL
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JPanel
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import com.intellij.openapi.application.ApplicationManager

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
        private val latestVersions = mutableMapOf<String, String>()

        private val content = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            val dependenciesPanel = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
            }

            val refreshAction = object : (Boolean) -> Unit {
                override fun invoke(checkUpdates: Boolean) {
                    dependenciesPanel.removeAll()
                    val mavenProjectsManager = MavenProjectsManager.getInstance(project)
                    val projects = mavenProjectsManager.projects

                    if (projects.isEmpty()) {
                        dependenciesPanel.add(JBLabel(MyMessageBundle.message("toolwindow.MyToolWindow.noProjects.label")))
                    } else {
                        projects.forEach { mavenProject ->
                            dependenciesPanel.add(
                                JBLabel(
                                    MyMessageBundle.message(
                                        "toolwindow.MyToolWindow.project.label",
                                        mavenProject.mavenId.artifactId
                                    )
                                ).apply {
                                    font = font.deriveFont(java.awt.Font.BOLD)
                                })

                            mavenProject.dependencies.forEach { dependency ->
                                val currentVersion = dependency.version ?: ""
                                val key = "${dependency.groupId}:${dependency.artifactId}"
                                val labelText =
                                    StringBuilder("  ${dependency.groupId}:${dependency.artifactId}:${currentVersion}")

                                if (latestVersions.containsKey(key)) {
                                    val latest = latestVersions[key]
                                    if (latest != null && latest != currentVersion) {
                                        labelText.append(" ").append(
                                            MyMessageBundle.message(
                                                "toolwindow.MyToolWindow.updateAvailable.label",
                                                latest
                                            )
                                        )
                                    }
                                }

                                dependenciesPanel.add(JBLabel(labelText.toString()))
                            }
                        }
                    }
                    dependenciesPanel.revalidate()
                    dependenciesPanel.repaint()

                    if (checkUpdates) {
                        checkForUpdates {
                            ApplicationManager.getApplication().invokeLater {
                                this(false)
                            }
                        }
                    }
                }
            }

            refreshAction(false)

            add(JBScrollPane(dependenciesPanel), BorderLayout.CENTER)

            val buttonPanel = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
                add(JButton(MyMessageBundle.message("toolwindow.MyToolWindow.refresh.button")).apply {
                    addActionListener { refreshAction(false) }
                })
                add(JButton(MyMessageBundle.message("toolwindow.MyToolWindow.checkUpdates.button")).apply {
                    addActionListener { refreshAction(true) }
                })
            }
            add(buttonPanel, BorderLayout.SOUTH)
        }

        private fun checkForUpdates(onFinished: () -> Unit) {
            ApplicationManager.getApplication().executeOnPooledThread {
                val mavenProjectsManager = MavenProjectsManager.getInstance(project)
                val allDependencies = mavenProjectsManager.projects.flatMap { it.dependencies }

                allDependencies.forEach { dependency ->
                    val groupId = dependency.groupId
                    val artifactId = dependency.artifactId
                    if (groupId != null && artifactId != null) {
                        val latest = fetchLatestVersion(groupId, artifactId)
                        if (latest != null) {
                            latestVersions["$groupId:$artifactId"] = latest
                        }
                    }
                }
                onFinished()
            }
        }

        private fun fetchLatestVersion(groupId: String, artifactId: String): String? {
            try {
                val urlString =
                    "https://repo1.maven.org/maven2/${groupId.replace('.', '/')}/$artifactId/maven-metadata.xml"
                val url = URL(urlString)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 5000
                connection.readTimeout = 5000

                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val factory = DocumentBuilderFactory.newInstance()
                    val builder = factory.newDocumentBuilder()
                    val doc = builder.parse(connection.inputStream)
                    val latestNode =
                        doc.getElementsByTagName("latest").item(0) ?: doc.getElementsByTagName("release").item(0)
                    return latestNode?.textContent
                }
            } catch (e: Exception) {
                // Log error or handle it
            }
            return null
        }

        fun getContent(): JBPanel<JBPanel<*>> = content
    }
}
