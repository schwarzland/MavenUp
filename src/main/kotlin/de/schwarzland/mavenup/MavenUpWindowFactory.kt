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
import java.awt.Component
import java.awt.FlowLayout
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import javax.swing.*
import javax.swing.table.TableCellRenderer
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.util.ui.AbstractTableCellEditor
import org.apache.maven.artifact.versioning.ComparableVersion

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
        private val availableVersions = mutableMapOf<String, List<String>>()
        private var isUpdating = false

        private val content = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            val tableModel = object : DefaultTableModel() {
                override fun isCellEditable(row: Int, column: Int): Boolean = column == 3
            }.apply {
                addColumn(MyMessageBundle.message("toolwindow.MyToolWindow.table.header.groupId"))
                addColumn(MyMessageBundle.message("toolwindow.MyToolWindow.table.header.artifactId"))
                addColumn(MyMessageBundle.message("toolwindow.MyToolWindow.table.header.currentVersion"))
                addColumn(MyMessageBundle.message("toolwindow.MyToolWindow.table.header.newVersion"))
            }

            val table = JBTable(tableModel)
            
            // Custom Renderer and Editor for the "New Version" column
            table.columnModel.getColumn(3).cellRenderer = object : TableCellRenderer {
                override fun getTableCellRendererComponent(
                    table: JTable?, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int
                ): Component {
                    @Suppress("UNCHECKED_CAST")
                    val versions = value as? List<String> ?: emptyList()
                    if (versions.isEmpty()) return JLabel("")
                    return JComboBox(versions.toTypedArray()).apply {
                        if (isSelected) {
                            background = table?.selectionBackground
                            foreground = table?.selectionForeground
                        }
                    }
                }
            }

            table.columnModel.getColumn(3).cellEditor = object : AbstractTableCellEditor() {
                private var currentComboBox: JComboBox<String>? = null
                
                override fun getTableCellEditorComponent(
                    table: JTable?, value: Any?, isSelected: Boolean, row: Int, column: Int
                ): Component {
                    @Suppress("UNCHECKED_CAST")
                    val versions = value as? List<String> ?: emptyList()
                    val combo = JComboBox(versions.toTypedArray())
                    currentComboBox = combo
                    return combo
                }

                override fun getCellEditorValue(): Any? {
                    return currentComboBox?.selectedItem
                }
            }

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
                                val versions = availableVersions[key] ?: emptyList()
                                
                                tableModel.addRow(arrayOf(
                                    dependency.groupId,
                                    dependency.artifactId,
                                    currentVersion,
                                    versions
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
                                val currentVersion = dependency.version ?: ""
                                val versions = fetchVersions(groupId, artifactId, currentVersion)
                                if (versions.isNotEmpty()) {
                                    availableVersions["$groupId:$artifactId"] = versions
                                }
                            }
                        }
                    }
                    onFinished()
                }
            })
        }

        private fun fetchVersions(groupId: String, artifactId: String, currentVersion: String): List<String> {
            try {
                val urlString =
                    "https://repo1.maven.org/maven2/${groupId.replace('.', '/')}/$artifactId/maven-metadata.xml"
                val url = URI(urlString).toURL()
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 5000
                connection.readTimeout = 5000

                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val factory = DocumentBuilderFactory.newInstance()
                    val builder = factory.newDocumentBuilder()
                    val doc = builder.parse(connection.inputStream)
                    
                    val versionNodes = doc.getElementsByTagName("version")
                    val versions = mutableListOf<String>()
                    val currentComparable = ComparableVersion(currentVersion)
                    
                    for (i in 0 until versionNodes.length) {
                        val v = versionNodes.item(i).textContent
                        if (ComparableVersion(v) >= currentComparable) {
                            versions.add(v)
                        }
                    }
                    
                    // Sort versions descending (newest first)
                    return versions.sortedWith { v1, v2 ->
                        ComparableVersion(v2).compareTo(ComparableVersion(v1))
                    }
                }
            } catch (e: Exception) {
                // Log error or handle it
            }
            return emptyList()
        }

        fun getContent(): JBPanel<JBPanel<*>> = content
    }
}
