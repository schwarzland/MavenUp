package de.schwarzland.mavenup

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import org.jetbrains.idea.maven.project.MavenProjectsManager
import com.intellij.ui.table.JBTable
import javax.swing.table.DefaultTableModel
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.net.HttpURLConnection
import java.net.URL
import javax.swing.JButton
import javax.swing.JPanel
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task

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
        private var isUpdating = false

        private val content = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            val tableModel = object : DefaultTableModel() {
                override fun isCellEditable(row: Int, column: Int): Boolean = false
            }.apply {
                addColumn(MyMessageBundle.message("toolwindow.MyToolWindow.table.header.groupId"))
                addColumn(MyMessageBundle.message("toolwindow.MyToolWindow.table.header.artifactId"))
                addColumn(MyMessageBundle.message("toolwindow.MyToolWindow.table.header.currentVersion"))
                addColumn(MyMessageBundle.message("toolwindow.MyToolWindow.table.header.newVersion"))
            }

            val table = JBTable(tableModel)

            val refreshButton = JButton(MyMessageBundle.message("toolwindow.MyToolWindow.refresh.button"))
            val checkUpdatesButton = JButton(MyMessageBundle.message("toolwindow.MyToolWindow.checkUpdates.button"))

            val refreshAction = object : (Boolean) -> Unit {
                override fun invoke(checkUpdates: Boolean) {
                    if (isUpdating) return
                    
                    tableModel.setRowCount(0)
                    val mavenProjectsManager = MavenProjectsManager.getInstance(project)
                    val projects = mavenProjectsManager.projects

                    if (projects.isNotEmpty()) {
                        projects.forEach { mavenProject ->
                            mavenProject.dependencyTree.forEach { node ->
                                val dependency = node.artifact
                                val currentVersion = dependency.version ?: ""
                                val key = "${dependency.groupId}:${dependency.artifactId}"
                                val latest = latestVersions[key] ?: ""
                                
                                tableModel.addRow(arrayOf(
                                    dependency.groupId,
                                    dependency.artifactId,
                                    currentVersion,
                                    if (latest.isNotEmpty() && latest != currentVersion) latest else ""
                                ))
                            }
                        }
                    }

                    if (checkUpdates) {
                        isUpdating = true
                        refreshButton.isEnabled = false
                        checkUpdatesButton.isEnabled = false
                        
                        checkForUpdates {
                            ApplicationManager.getApplication().invokeLater {
                                isUpdating = false
                                refreshButton.isEnabled = true
                                checkUpdatesButton.isEnabled = true
                                this(false)
                            }
                        }
                    }
                }
            }

            refreshAction(false)

            add(JBScrollPane(table), BorderLayout.CENTER)

            val buttonPanel = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
                add(refreshButton.apply {
                    addActionListener { refreshAction(false) }
                })
                add(checkUpdatesButton.apply {
                    addActionListener { refreshAction(true) }
                })
            }
            add(buttonPanel, BorderLayout.SOUTH)
        }

        private fun checkForUpdates(onFinished: () -> Unit) {
            ProgressManager.getInstance().run(object : Task.Backgroundable(project, MyMessageBundle.message("toolwindow.MyToolWindow.checkUpdates.progress"), true) {
                override fun run(indicator: ProgressIndicator) {
                    val mavenProjectsManager = MavenProjectsManager.getInstance(project)
                    val projects = mavenProjectsManager.projects
                    
                    projects.forEach { mavenProject ->
                        mavenProject.dependencyTree.forEach { node ->
                            if (indicator.isCanceled) return@run
                            val dependency = node.artifact
                            
                            val groupId = dependency.groupId
                            val artifactId = dependency.artifactId
                            
                            indicator.text2 = "$groupId:$artifactId"
                            
                            if (groupId != null && artifactId != null) {
                                val latest = fetchLatestVersion(groupId, artifactId)
                                if (latest != null) {
                                    latestVersions["$groupId:$artifactId"] = latest
                                }
                            }
                        }
                    }
                    onFinished()
                }
            })
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
