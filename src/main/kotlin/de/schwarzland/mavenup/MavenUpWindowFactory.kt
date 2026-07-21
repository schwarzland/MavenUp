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
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.util.ui.AbstractTableCellEditor
import org.apache.maven.artifact.versioning.ComparableVersion
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File

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

    class UpdateConfirmationDialog(
        project: Project,
        private val updates: List<DependencyUpdate>
    ) : DialogWrapper(project) {
        init {
            title = MyMessageBundle.message("toolwindow.MyToolWindow.update.confirm.title")
            init()
        }

        override fun createCenterPanel(): JComponent {
            val panel = JBPanel<JBPanel<*>>(BorderLayout())
            panel.preferredSize = java.awt.Dimension(600, 400)
            panel.add(JLabel(MyMessageBundle.message("toolwindow.MyToolWindow.update.confirm.message")), BorderLayout.NORTH)

            val tableModel = DefaultTableModel().apply {
                addColumn(MyMessageBundle.message("toolwindow.MyToolWindow.table.header.groupId"))
                addColumn(MyMessageBundle.message("toolwindow.MyToolWindow.table.header.artifactId"))
                addColumn(MyMessageBundle.message("toolwindow.MyToolWindow.table.header.currentVersion"))
                addColumn(MyMessageBundle.message("toolwindow.MyToolWindow.table.header.newVersion"))
            }

            updates.forEach { update ->
                tableModel.addRow(arrayOf(update.groupId, update.artifactId, update.oldVersion, update.newVersion))
            }

            val table = JBTable(tableModel)
            panel.add(JBScrollPane(table), BorderLayout.CENTER)
            return panel
        }

        data class DependencyUpdate(
            val groupId: String,
            val artifactId: String,
            val oldVersion: String,
            val newVersion: String
        )
    }

    class MyToolWindow(private val project: Project) {
        private val availableVersions = mutableMapOf<String, List<String>>()
        private val selectedVersions = mutableMapOf<String, String>()
        private val dependencyToProperty = mutableMapOf<String, String>()
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
            
            table.addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (e.clickCount == 2) {
                        val row = table.rowAtPoint(e.point)
                        if (row >= 0) {
                            val rawGroupId = table.getValueAt(row, 0) as? String ?: ""
                            val groupId = if (rawGroupId.contains(" (")) rawGroupId.substringBefore(" (") else rawGroupId
                            val artifactId = table.getValueAt(row, 1) as? String ?: ""
                            
                            navigateToDependency(groupId, artifactId)
                        }
                    }
                }
            })
            
            val refreshButton = JButton(MyMessageBundle.message("toolwindow.MyToolWindow.refresh.button"))
            val checkUpdatesButton = JButton(MyMessageBundle.message("toolwindow.MyToolWindow.checkUpdates.button"))
            val updateButton = JButton(MyMessageBundle.message("toolwindow.MyToolWindow.update.button"))

            fun updateUpdateButtonState() {
                val mavenProjectsManager = MavenProjectsManager.getInstance(project)
                val projects = mavenProjectsManager.projects
                var hasUpdate = false
                
                projects.forEach { mavenProject ->
                    mavenProject.dependencyTree.forEach { node ->
                        val dependency = node.artifact
                        val key = "${dependency.groupId}:${dependency.artifactId}"
                        val newVersion = selectedVersions[key]
                        val currentVersion = dependency.version ?: ""
                        
                        if (newVersion != null && newVersion != currentVersion) {
                            hasUpdate = true
                        }
                    }
                }
                updateButton.isEnabled = hasUpdate && !isUpdating
            }
            
            updateUpdateButtonState()
                    
            // Force commit editor when focus lost
                    table.putClientProperty("terminateEditOnFocusLost", true)
                    
                    // Custom Renderer and Editor for the "New Version" column
            table.columnModel.getColumn(3).cellRenderer = object : TableCellRenderer {
                override fun getTableCellRendererComponent(
                    table: JTable?, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int
                ): Component {
                    val rawGroupId = table?.getValueAt(row, 0) as? String ?: ""
                    val groupId = if (rawGroupId.contains(" (")) rawGroupId.substringBefore(" (") else rawGroupId
                    val artifactId = table?.getValueAt(row, 1) as? String ?: ""
                    val key = "$groupId:$artifactId"
                    
                    @Suppress("UNCHECKED_CAST")
                    val versions = value as? List<String> ?: emptyList()
                    if (versions.isEmpty()) return JLabel("")
                    
                    val selectedVersion = selectedVersions[key]
                    
                    return JComboBox(versions.toTypedArray()).apply {
                        if (selectedVersion != null) {
                            selectedItem = selectedVersion
                        }
                        if (isSelected) {
                            background = table?.selectionBackground
                            foreground = table?.selectionForeground
                        }
                    }
                }
            }

            table.columnModel.getColumn(3).cellEditor = object : AbstractTableCellEditor() {
                private var currentComboBox: JComboBox<String>? = null
                private var currentKey: String? = null
                
                override fun getTableCellEditorComponent(
                    table: JTable?, value: Any?, isSelected: Boolean, row: Int, column: Int
                ): Component {
                    val rawGroupId = table?.getValueAt(row, 0) as? String ?: ""
                    val groupId = if (rawGroupId.contains(" (")) rawGroupId.substringBefore(" (") else rawGroupId
                    val artifactId = table?.getValueAt(row, 1) as? String ?: ""
                    currentKey = "$groupId:$artifactId"
                    
                    @Suppress("UNCHECKED_CAST")
                    val versions = value as? List<String> ?: emptyList()
                    val combo = JComboBox(versions.toTypedArray())
                    
                    val selectedVersion = if (currentKey != null) selectedVersions[currentKey!!] else null
                    if (selectedVersion != null) {
                        combo.selectedItem = selectedVersion
                    }
                    
                    currentComboBox = combo
                    return combo
                }

                override fun getCellEditorValue(): Any? {
                    val selected = currentComboBox?.selectedItem as? String
                    if (currentKey != null && selected != null) {
                        selectedVersions[currentKey!!] = selected
                        
                        // Synchronize other dependencies using the same property
                        val property = dependencyToProperty[currentKey!!]
                        if (property != null) {
                            dependencyToProperty.forEach { (key, prop) ->
                                if (prop == property) {
                                    selectedVersions[key] = selected
                                }
                            }
                        }
                    }
                    updateUpdateButtonState()
                    val groupId = currentKey?.substringBefore(":")
                    val artifactId = currentKey?.substringAfter(":")
                    return availableVersions["$groupId:$artifactId"]
                }
            }

            val refreshAction = object : (Boolean) -> Unit {
                override fun invoke(checkUpdates: Boolean) {
                    if (isUpdating) return
                    
                    tableModel.setRowCount(0)
                    dependencyToProperty.clear()
                    val mavenProjectsManager = MavenProjectsManager.getInstance(project)
                    val projects = mavenProjectsManager.projects
                    updateUpdateButtonState()

                    if (projects.isNotEmpty()) {
                        projects.forEach { mavenProject ->
                            val pomFile = mavenProject.file
                            val psiFile = ApplicationManager.getApplication().runReadAction<XmlFile?> {
                                PsiManager.getInstance(project).findFile(pomFile) as? XmlFile
                            }
                            
                            mavenProject.dependencyTree.forEach { node ->
                                val dependency = node.artifact
                                val key = "${dependency.groupId}:${dependency.artifactId}"
                                
                                // Identify if version is a property
                                if (psiFile != null) {
                                    ApplicationManager.getApplication().runReadAction {
                                        val documentElement = psiFile.document?.rootTag
                                        val dependenciesTag = documentElement?.findFirstSubTag("dependencies")
                                        dependenciesTag?.findSubTags("dependency")?.forEach { depTag ->
                                            val g = depTag.findFirstSubTag("groupId")?.value?.text
                                            val a = depTag.findFirstSubTag("artifactId")?.value?.text
                                            if (g == dependency.groupId && a == dependency.artifactId) {
                                                val versionText = depTag.findFirstSubTag("version")?.value?.text
                                                if (versionText != null && versionText.startsWith("${") && versionText.endsWith("}")) {
                                                    dependencyToProperty[key] = versionText.substring(2, versionText.length - 1)
                                                }
                                            }
                                        }
                                    }
                                }

                                val currentVersion = dependency.version ?: ""
                                val versions = availableVersions[key] ?: emptyList()
                                
                                val displayGroupId = if (dependencyToProperty.containsKey(key)) {
                                    "${dependency.groupId} (${dependencyToProperty[key]})"
                                } else {
                                    dependency.groupId ?: ""
                                }

                                tableModel.addRow(arrayOf(
                                    displayGroupId,
                                    dependency.artifactId ?: "",
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
                        updateButton.isEnabled = false
                        
                        checkForUpdates {
                            ApplicationManager.getApplication().invokeLater {
                                isUpdating = false
                                refreshButton.isEnabled = true
                                checkUpdatesButton.isEnabled = true
                                this(false)
                                updateUpdateButtonState()
                            }
                        }
                    }
                }
            }

            val updateAction = {
                if (!isUpdating && selectedVersions.isNotEmpty()) {
                    val mavenProjectsManager = MavenProjectsManager.getInstance(project)
                    val projects = mavenProjectsManager.projects
                    val updates = mutableListOf<UpdateConfirmationDialog.DependencyUpdate>()
                    
                    projects.forEach { mavenProject ->
                        mavenProject.dependencyTree.forEach { node ->
                            val dependency = node.artifact
                            val key = "${dependency.groupId}:${dependency.artifactId}"
                            val newVersion = selectedVersions[key]
                            val currentVersion = dependency.version ?: ""
                            
                            if (newVersion != null && newVersion != currentVersion) {
                                updates.add(UpdateConfirmationDialog.DependencyUpdate(
                                    dependency.groupId ?: "",
                                    dependency.artifactId ?: "",
                                    currentVersion,
                                    newVersion
                                ))
                            }
                        }
                    }
                    
                    if (updates.isNotEmpty()) {
                        val dialog = UpdateConfirmationDialog(project, updates)
                        if (dialog.showAndGet()) {
                            ProgressManager.getInstance().run(object : Task.Backgroundable(project, MyMessageBundle.message("toolwindow.MyToolWindow.update.progress"), true) {
                                override fun run(indicator: ProgressIndicator) {
                                    projects.forEach { mavenProject ->
                                        val pomFile = mavenProject.file
                                        val psiFile = ApplicationManager.getApplication().runReadAction<XmlFile?> {
                                            PsiManager.getInstance(project).findFile(pomFile) as? XmlFile
                                        } ?: return@forEach
                                        
                                        WriteCommandAction.runWriteCommandAction(project) {
                                            val documentElement = psiFile.document?.rootTag ?: return@runWriteCommandAction
                                            val dependenciesTag = documentElement.findFirstSubTag("dependencies") ?: return@runWriteCommandAction
                                            val propertiesTag = documentElement.findFirstSubTag("properties")
                                            
                                            updates.forEach { update ->
                                                dependenciesTag.findSubTags("dependency").forEach { depTag ->
                                                    val g = depTag.findFirstSubTag("groupId")?.value?.text
                                                    val a = depTag.findFirstSubTag("artifactId")?.value?.text
                                                    
                                                    if (g == update.groupId && a == update.artifactId) {
                                                        val versionTag = depTag.findFirstSubTag("version")
                                                        if (versionTag != null) {
                                                            val versionText = versionTag.value.text
                                                            if (versionText.startsWith("\${") && versionText.endsWith("}")) {
                                                                val propertyName = versionText.substring(2, versionText.length - 1)
                                                                val propertyTag = propertiesTag?.findFirstSubTag(propertyName)
                                                                if (propertyTag != null) {
                                                                    propertyTag.value.text = update.newVersion
                                                                } else {
                                                                    // Falls Property nicht in <properties> gefunden wurde, überschreiben wir doch den Tag
                                                                    versionTag.value.text = update.newVersion
                                                                }
                                                            } else {
                                                                versionTag.value.text = update.newVersion
                                                            }
                                                        } else {
                                                            val newTag = depTag.createChildTag("version", null, update.newVersion, false)
                                                            depTag.addSubTag(newTag, false)
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    
                                    ApplicationManager.getApplication().invokeLater {
                                        selectedVersions.clear()
                                        refreshAction(false)
                                    }
                                }
                            })
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
                add(updateButton.apply {
                    addActionListener { updateAction() }
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
                                    val key = "$groupId:$artifactId"
                                    availableVersions[key] = versions
                                    // Pre-select the newest version if it's different from the current one
                                    if (versions.first() != currentVersion) {
                                        selectedVersions[key] = versions.first()
                                    }
                                }
                            }
                        }
                    }

                    // Post-process grouped versions (intersection)
                    val propertyToDependencies = mutableMapOf<String, MutableList<String>>()
                    dependencyToProperty.forEach { (depKey, prop) ->
                        propertyToDependencies.getOrPut(prop) { mutableListOf() }.add(depKey)
                    }

                    propertyToDependencies.forEach { (prop, depKeys) ->
                        if (depKeys.size > 1) {
                            var commonVersions: List<String>? = null
                            depKeys.forEach { depKey ->
                                val versions = availableVersions[depKey] ?: emptyList()
                                commonVersions = if (commonVersions == null) {
                                    versions
                                } else {
                                    commonVersions!!.intersect(versions.toSet()).toList()
                                }
                            }
                            
                            val sortedCommonVersions = commonVersions?.sortedWith { v1, v2 ->
                                ComparableVersion(v2).compareTo(ComparableVersion(v1))
                            } ?: emptyList()

                            depKeys.forEach { depKey ->
                                availableVersions[depKey] = sortedCommonVersions
                                if (sortedCommonVersions.isNotEmpty()) {
                                    val mavenProject = projects.find { p -> p.dependencyTree.any { it.artifact.groupId == depKey.substringBefore(":") && it.artifact.artifactId == depKey.substringAfter(":") } }
                                    val artifact = mavenProject?.dependencyTree?.find { it.artifact.groupId == depKey.substringBefore(":") && it.artifact.artifactId == depKey.substringAfter(":") }?.artifact
                                    val currentVersion = artifact?.version ?: ""
                                    if (sortedCommonVersions.first() != currentVersion) {
                                        selectedVersions[depKey] = sortedCommonVersions.first()
                                    }
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
                connection.connectTimeout = 10000
                connection.readTimeout = 10000

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

        private fun navigateToDependency(groupId: String, artifactId: String) {
            val mavenProjectsManager = MavenProjectsManager.getInstance(project)
            val projects = mavenProjectsManager.projects

            projects.forEach { mavenProject ->
                val dependency = mavenProject.dependencyTree.find { 
                    it.artifact.groupId == groupId && it.artifact.artifactId == artifactId 
                }
                
                if (dependency != null) {
                    val pomFile = mavenProject.file
                    val psiFile = ApplicationManager.getApplication().runReadAction<XmlFile?> {
                        PsiManager.getInstance(project).findFile(pomFile) as? XmlFile
                    } ?: return@forEach
                    
                    val depTag = ApplicationManager.getApplication().runReadAction<XmlTag?> {
                        val documentElement = psiFile.document?.rootTag
                        val dependenciesTag = documentElement?.findFirstSubTag("dependencies")
                        
                        dependenciesTag?.findSubTags("dependency")?.find { depTag ->
                            val g = depTag.findFirstSubTag("groupId")?.value?.text
                            val a = depTag.findFirstSubTag("artifactId")?.value?.text
                            g == groupId && a == artifactId
                        }
                    }

                    ApplicationManager.getApplication().invokeLater {
                        val offset = depTag?.textOffset ?: 0
                        val descriptor = OpenFileDescriptor(project, pomFile, offset)
                        FileEditorManager.getInstance(project).openTextEditor(descriptor, true)
                    }
                    return
                }
            }
        }
    }
}
