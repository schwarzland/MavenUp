package de.schwarzland.mavenup

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import org.jetbrains.idea.maven.project.MavenImportListener
import org.jetbrains.idea.maven.project.MavenProject
import org.jetbrains.idea.maven.project.MavenProjectsManager
import com.intellij.ui.table.JBTable
import javax.swing.table.DefaultTableModel
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import java.net.HttpURLConnection
import java.net.URI
import javax.swing.*
import javax.swing.table.TableCellRenderer
import javax.xml.parsers.DocumentBuilderFactory
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.util.ui.AbstractTableCellEditor
import org.apache.maven.artifact.versioning.ComparableVersion
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent

private const val MANAGED_PLUGIN = "managed plugin"

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
            panel.add(
                JLabel(MyMessageBundle.message("toolwindow.MyToolWindow.update.confirm.message")),
                BorderLayout.NORTH
            )

            val tableModel = DefaultTableModel().apply {
                addColumn(MyMessageBundle.message("toolwindow.MyToolWindow.table.header.groupId"))
                addColumn(MyMessageBundle.message("toolwindow.MyToolWindow.table.header.artifactId"))
                addColumn(MyMessageBundle.message("toolwindow.MyToolWindow.table.header.type"))
                addColumn(MyMessageBundle.message("toolwindow.MyToolWindow.table.header.currentVersion"))
                addColumn(MyMessageBundle.message("toolwindow.MyToolWindow.table.header.newVersion"))
            }

            updates.forEach { update ->
                tableModel.addRow(
                    arrayOf(
                        update.groupId,
                        update.artifactId,
                        update.type,
                        update.oldVersion,
                        update.newVersion
                    )
                )
            }

            val table = JBTable(tableModel)
            panel.add(JBScrollPane(table), BorderLayout.CENTER)
            return panel
        }

        data class DependencyUpdate(
            val groupId: String,
            val artifactId: String,
            val type: String,
            val oldVersion: String,
            val newVersion: String
        )
    }

    class MyToolWindow(private val project: Project) {
        private val availableVersions = mutableMapOf<String, List<String>>()
        private val selectedVersions = mutableMapOf<String, String>()
        private val dependencyToProperty = mutableMapOf<String, String>()
        private val knownDependencies = mutableMapOf<String, String>() // key to current version
        private var isUpdating = false

        private val content = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            val tableModel = object : DefaultTableModel() {
                override fun isCellEditable(row: Int, column: Int): Boolean = column == 4
            }.apply {
                addColumn(MyMessageBundle.message("toolwindow.MyToolWindow.table.header.groupId"))
                addColumn(MyMessageBundle.message("toolwindow.MyToolWindow.table.header.artifactId"))
                addColumn(MyMessageBundle.message("toolwindow.MyToolWindow.table.header.type"))
                addColumn(MyMessageBundle.message("toolwindow.MyToolWindow.table.header.currentVersion"))
                addColumn(MyMessageBundle.message("toolwindow.MyToolWindow.table.header.newVersion"))
            }

            val table = JBTable(tableModel)

            table.addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    val settings = MavenUpSettings.getInstance(project)
                    val requiredClickCount = if (settings.state.jumpOnSingleClick) 1 else 2

                    if (e.clickCount == requiredClickCount) {
                        val row = table.rowAtPoint(e.point)
                        if (row >= 0) {
                            val rawGroupId = table.getValueAt(row, 0) as? String ?: ""
                            val groupId =
                                if (rawGroupId.contains(" (")) rawGroupId.substringBefore(" (") else rawGroupId
                            val artifactId = table.getValueAt(row, 1) as? String ?: ""
                            val type = table.getValueAt(row, 2) as? String ?: "dependency"

                            navigateToDependency(groupId, artifactId, type)
                        }
                    }
                }
            })

            val refreshButton = JButton(MyMessageBundle.message("toolwindow.MyToolWindow.refresh.button"))
            val checkUpdatesButton = JButton(MyMessageBundle.message("toolwindow.MyToolWindow.checkUpdates.button"))
            val updateButton = JButton(MyMessageBundle.message("toolwindow.MyToolWindow.update.button"))

            fun updateUpdateButtonState() {
                var hasUpdate = false
                knownDependencies.forEach { (key, currentVersion) ->
                    val newVersion = selectedVersions[key]
                    if (newVersion != null && newVersion != currentVersion) {
                        hasUpdate = true
                    }
                }
                updateButton.isEnabled = hasUpdate && !isUpdating
            }

            updateUpdateButtonState()

            // Force commit editor when focus lost
            table.putClientProperty("terminateEditOnFocusLost", true)

            // Custom Renderer and Editor for the "New Version" column
            table.columnModel.getColumn(4).cellRenderer = object : TableCellRenderer {
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

                    return ComboBox(versions.toTypedArray()).apply {
                        if (selectedVersion != null) {
                            selectedItem = selectedVersion
                        }

                        val currentVersion = table?.getValueAt(row, 3) as? String ?: ""
                        val newestVersion = versions.firstOrNull() ?: ""
                        if (currentVersion == newestVersion && currentVersion.isNotEmpty()) {
                            foreground = com.intellij.ui.JBColor.GREEN
                        }

                        if (isSelected) {
                            background = table?.selectionBackground
                            if (currentVersion != newestVersion) {
                                foreground = table?.selectionForeground
                            }
                        }
                    }
                }
            }

            table.columnModel.getColumn(4).cellEditor = object : AbstractTableCellEditor() {
                private var currentComboBox: ComboBox<String>? = null
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
                    val combo = ComboBox(versions.toTypedArray())

                    val currentVersion = table?.getValueAt(row, 3) as? String ?: ""
                    val newestVersion = versions.firstOrNull() ?: ""
                    if (currentVersion == newestVersion && currentVersion.isNotEmpty()) {
                        combo.foreground = com.intellij.ui.JBColor.GREEN
                    }

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
                    knownDependencies.clear()
                    val mavenProjectsManager = MavenProjectsManager.getInstance(project)
                    val projects = mavenProjectsManager.projects
                    updateUpdateButtonState()

                    if (projects.isNotEmpty()) {
                        projects.forEach { mavenProject ->
                            val pomFile = mavenProject.file
                            val psiFile = ApplicationManager.getApplication().runReadAction<XmlFile?> {
                                PsiManager.getInstance(project).findFile(pomFile) as? XmlFile
                            }

                            val localDependencies = mutableMapOf<String, String>()
                            val managedDependencies = mutableMapOf<String, String>()
                            val localPlugins = mutableMapOf<String, String>()
                            val managedPlugins = mutableMapOf<String, String>()

                            if (psiFile != null) {
                                ApplicationManager.getApplication().runReadAction {
                                    val documentElement = psiFile.document?.rootTag

                                    // Collect local dependencies and their properties
                                    val dependenciesTag = documentElement?.findFirstSubTag("dependencies")
                                    dependenciesTag?.findSubTags("dependency")?.forEach { depTag ->
                                        val g = depTag.findFirstSubTag("groupId")?.value?.text ?: ""
                                        val a = depTag.findFirstSubTag("artifactId")?.value?.text ?: ""
                                        val v = depTag.findFirstSubTag("version")?.value?.text ?: ""
                                        val key = "$g:$a"
                                        localDependencies[key] = v

                                        val versionText = depTag.findFirstSubTag("version")?.value?.trimmedText
                                        if (versionText != null && versionText.startsWith("\${") && versionText.endsWith(
                                                "}"
                                            )
                                        ) {
                                            dependencyToProperty[key] = versionText.substring(2, versionText.length - 1)
                                        }
                                    }

                                    // Collect dependencyManagement dependencies
                                    val dmTag = documentElement?.findFirstSubTag("dependencyManagement")
                                    val dmDepsTag = dmTag?.findFirstSubTag("dependencies")
                                    dmDepsTag?.findSubTags("dependency")?.forEach { depTag ->
                                        val g = depTag.findFirstSubTag("groupId")?.value?.text ?: ""
                                        val a = depTag.findFirstSubTag("artifactId")?.value?.text ?: ""
                                        val v = depTag.findFirstSubTag("version")?.value?.text ?: ""
                                        val key = "$g:$a"
                                        managedDependencies[key] = v

                                        val versionText = depTag.findFirstSubTag("version")?.value?.trimmedText
                                        if (versionText != null && versionText.startsWith("\${") && versionText.endsWith(
                                                "}"
                                            )
                                        ) {
                                            dependencyToProperty[key] = versionText.substring(2, versionText.length - 1)
                                        }
                                    }

                                    // Collect local plugins and their properties
                                    val buildTag = documentElement?.findFirstSubTag("build")
                                    val pluginsTag = buildTag?.findFirstSubTag("plugins")
                                    pluginsTag?.findSubTags("plugin")?.forEach { pluginTag ->
                                        val g = pluginTag.findFirstSubTag("groupId")?.value?.text ?: ""
                                        val a = pluginTag.findFirstSubTag("artifactId")?.value?.text ?: ""
                                        val v = pluginTag.findFirstSubTag("version")?.value?.text ?: ""
                                        val key = "$g:$a"
                                        localPlugins[key] = v

                                        val versionText = pluginTag.findFirstSubTag("version")?.value?.trimmedText
                                        if (versionText != null && versionText.startsWith("\${") && versionText.endsWith(
                                                "}"
                                            )
                                        ) {
                                            dependencyToProperty[key] = versionText.substring(2, versionText.length - 1)
                                        }
                                    }

                                    // Collect pluginManagement plugins
                                    val pmTag = buildTag?.findFirstSubTag("pluginManagement")
                                    val pmPluginsTag = pmTag?.findFirstSubTag("plugins")
                                    pmPluginsTag?.findSubTags("plugin")?.forEach { pluginTag ->
                                        val g = pluginTag.findFirstSubTag("groupId")?.value?.text ?: ""
                                        val a = pluginTag.findFirstSubTag("artifactId")?.value?.text ?: ""
                                        val v = pluginTag.findFirstSubTag("version")?.value?.text ?: ""
                                        val key = "$g:$a"
                                        managedPlugins[key] = v

                                        val versionText = pluginTag.findFirstSubTag("version")?.value?.trimmedText
                                        if (versionText != null && versionText.startsWith("\${") && versionText.endsWith(
                                                "}"
                                            )
                                        ) {
                                            dependencyToProperty[key] = versionText.substring(2, versionText.length - 1)
                                        }
                                    }
                                }
                            }

                            // Collect all dependencies (resolved ones from model + declared ones from PSI)
                            val allDependencyKeys = localDependencies.keys + managedDependencies.keys
                            val resolvedDependencies =
                                mavenProject.dependencyTree.associateBy { "${it.artifact.groupId}:${it.artifact.artifactId}" }

                            allDependencyKeys.forEach { key ->
                                val isManaged = managedDependencies.containsKey(key)
                                val groupId = key.substringBefore(":")
                                val artifactId = key.substringAfter(":")

                                val currentVersion = resolvedDependencies[key]?.artifact?.version
                                    ?: managedDependencies[key]
                                    ?: localDependencies[key]
                                    ?: ""

                                knownDependencies[key] = currentVersion

                                val versions = availableVersions[key] ?: emptyList()

                                val displayGroupId = if (dependencyToProperty.containsKey(key)) {
                                    "$groupId (${dependencyToProperty[key]})"
                                } else {
                                    groupId
                                }

                                val type = if (isManaged) {
                                    MyMessageBundle.message("toolwindow.MyToolWindow.type.managedDependency")
                                } else {
                                    "dependency"
                                }

                                tableModel.addRow(
                                    arrayOf(
                                        displayGroupId,
                                        artifactId,
                                        type,
                                        currentVersion,
                                        versions
                                    )
                                )
                            }

                            // Collect all plugins
                            val allPluginKeys = localPlugins.keys + managedPlugins.keys
                            val resolvedPlugins = mavenProject.plugins.associateBy { "${it.groupId}:${it.artifactId}" }

                            allPluginKeys.forEach { key ->
                                val isManaged = managedPlugins.containsKey(key)
                                val groupId = key.substringBefore(":")
                                val artifactId = key.substringAfter(":")

                                val currentVersion = resolvedPlugins[key]?.version
                                    ?: managedPlugins[key]
                                    ?: localPlugins[key]
                                    ?: ""

                                knownDependencies[key] = currentVersion

                                val versions = availableVersions[key] ?: emptyList()

                                val displayGroupId = if (dependencyToProperty.containsKey(key)) {
                                    "$groupId (${dependencyToProperty[key]})"
                                } else {
                                    groupId
                                }

                                val type = if (isManaged) MANAGED_PLUGIN else "plugin"

                                tableModel.addRow(
                                    arrayOf(
                                        displayGroupId,
                                        artifactId,
                                        type,
                                        currentVersion,
                                        versions
                                    )
                                )
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
                        val pomFile = mavenProject.file
                        val psiFile = ApplicationManager.getApplication().runReadAction<XmlFile?> {
                            PsiManager.getInstance(project).findFile(pomFile) as? XmlFile
                        }

                        val localDependencies = mutableMapOf<String, String>() // key to version
                        val managedDependencies = mutableMapOf<String, String>()
                        val localPlugins = mutableMapOf<String, String>()
                        val managedPlugins = mutableMapOf<String, String>()

                        if (psiFile != null) {
                            ApplicationManager.getApplication().runReadAction {
                                val documentElement = psiFile.document?.rootTag

                                // Collect local dependencies
                                val dependenciesTag = documentElement?.findFirstSubTag("dependencies")
                                dependenciesTag?.findSubTags("dependency")?.forEach { depTag ->
                                    val g = depTag.findFirstSubTag("groupId")?.value?.text ?: ""
                                    val a = depTag.findFirstSubTag("artifactId")?.value?.text ?: ""
                                    val v = depTag.findFirstSubTag("version")?.value?.text ?: ""
                                    localDependencies["$g:$a"] = v
                                }

                                // Collect dependencyManagement dependencies
                                val dmTag = documentElement?.findFirstSubTag("dependencyManagement")
                                val dmDepsTag = dmTag?.findFirstSubTag("dependencies")
                                dmDepsTag?.findSubTags("dependency")?.forEach { depTag ->
                                    val g = depTag.findFirstSubTag("groupId")?.value?.text ?: ""
                                    val a = depTag.findFirstSubTag("artifactId")?.value?.text ?: ""
                                    val v = depTag.findFirstSubTag("version")?.value?.text ?: ""
                                    managedDependencies["$g:$a"] = v
                                }

                                // Collect local plugins
                                val buildTag = documentElement?.findFirstSubTag("build")
                                val pluginsTag = buildTag?.findFirstSubTag("plugins")
                                pluginsTag?.findSubTags("plugin")?.forEach { pluginTag ->
                                    val g = pluginTag.findFirstSubTag("groupId")?.value?.text ?: ""
                                    val a = pluginTag.findFirstSubTag("artifactId")?.value?.text ?: ""
                                    val v = pluginTag.findFirstSubTag("version")?.value?.text ?: ""
                                    localPlugins["$g:$a"] = v
                                }

                                // Collect pluginManagement plugins
                                val pmTag = buildTag?.findFirstSubTag("pluginManagement")
                                val pmPluginsTag = pmTag?.findFirstSubTag("plugins")
                                pmPluginsTag?.findSubTags("plugin")?.forEach { pluginTag ->
                                    val g = pluginTag.findFirstSubTag("groupId")?.value?.text ?: ""
                                    val a = pluginTag.findFirstSubTag("artifactId")?.value?.text ?: ""
                                    val v = pluginTag.findFirstSubTag("version")?.value?.text ?: ""
                                    managedPlugins["$g:$a"] = v
                                }
                            }
                        }

                        val resolvedDependencies =
                            mavenProject.dependencyTree.associateBy { "${it.artifact.groupId}:${it.artifact.artifactId}" }
                        val resolvedPlugins = mavenProject.plugins.associateBy { "${it.groupId}:${it.artifactId}" }

                        (localDependencies.keys + managedDependencies.keys).forEach { key ->
                            val newVersion = selectedVersions[key]
                            val resolvedVersion = resolvedDependencies[key]?.artifact?.version
                            val managedVersion = managedDependencies[key]
                            val localVersion = localDependencies[key]

                            val currentVersion = resolvedVersion
                                ?: managedVersion
                                ?: localVersion
                                ?: ""

                            if (newVersion != null && newVersion != currentVersion) {
                                val isManaged = managedDependencies.containsKey(key)
                                val type = if (isManaged) {
                                    MyMessageBundle.message("toolwindow.MyToolWindow.type.managedDependency")
                                } else {
                                    "dependency"
                                }

                                updates.add(
                                    UpdateConfirmationDialog.DependencyUpdate(
                                        key.substringBefore(":"),
                                        key.substringAfter(":"),
                                        type,
                                        currentVersion,
                                        newVersion
                                    )
                                )
                            }
                        }

                        (localPlugins.keys + managedPlugins.keys).forEach { key ->
                            val newVersion = selectedVersions[key]
                            val resolvedVersion = resolvedPlugins[key]?.version
                            val managedVersion = managedPlugins[key]
                            val localVersion = localPlugins[key]

                            val currentVersion = resolvedVersion
                                ?: managedVersion
                                ?: localVersion
                                ?: ""

                            if (newVersion != null && newVersion != currentVersion) {
                                val isManaged = managedPlugins.containsKey(key)
                                val type = if (isManaged) MANAGED_PLUGIN else "plugin"

                                updates.add(
                                    UpdateConfirmationDialog.DependencyUpdate(
                                        key.substringBefore(":"),
                                        key.substringAfter(":"),
                                        type,
                                        currentVersion,
                                        newVersion
                                    )
                                )
                            }
                        }
                    }

                    if (updates.isNotEmpty()) {
                        val dialog = UpdateConfirmationDialog(project, updates)
                        if (dialog.showAndGet()) {
                            ProgressManager.getInstance().run(object : Task.Backgroundable(
                                project,
                                MyMessageBundle.message("toolwindow.MyToolWindow.update.progress"),
                                true
                            ) {
                                override fun run(indicator: ProgressIndicator) {
                                    projects.forEach { mavenProject ->
                                        val pomFile = mavenProject.file
                                        val psiFile = ApplicationManager.getApplication().runReadAction<XmlFile?> {
                                            PsiManager.getInstance(project).findFile(pomFile) as? XmlFile
                                        } ?: return@forEach

                                        WriteCommandAction.runWriteCommandAction(project) {
                                            val documentElement =
                                                psiFile.document?.rootTag ?: return@runWriteCommandAction
                                            val dependenciesTag = documentElement.findFirstSubTag("dependencies")
                                            val dependencyManagementTag =
                                                documentElement.findFirstSubTag("dependencyManagement")
                                            val dmDependenciesTag =
                                                dependencyManagementTag?.findFirstSubTag("dependencies")
                                            val buildTag = documentElement.findFirstSubTag("build")
                                            val pluginsTag = buildTag?.findFirstSubTag("plugins")
                                            val pluginManagementTag = buildTag?.findFirstSubTag("pluginManagement")
                                            val pmPluginsTag = pluginManagementTag?.findFirstSubTag("plugins")
                                            val propertiesTag = documentElement.findFirstSubTag("properties")

                                            updates.forEach { update ->
                                                val managedDependencyType =
                                                    MyMessageBundle.message("toolwindow.MyToolWindow.type.managedDependency")
                                                if (update.type == "dependency" || update.type == managedDependencyType) {
                                                    // Search in <dependencies>
                                                    dependenciesTag?.findSubTags("dependency")?.forEach { depTag ->
                                                        val g = depTag.findFirstSubTag("groupId")?.value?.text
                                                        val a = depTag.findFirstSubTag("artifactId")?.value?.text

                                                        if (g == update.groupId && a == update.artifactId) {
                                                            updateXmlTagVersion(
                                                                depTag,
                                                                update.newVersion,
                                                                propertiesTag
                                                            )
                                                        }
                                                    }
                                                    // Search in <dependencyManagement><dependencies>
                                                    dmDependenciesTag?.findSubTags("dependency")?.forEach { depTag ->
                                                        val g = depTag.findFirstSubTag("groupId")?.value?.text
                                                        val a = depTag.findFirstSubTag("artifactId")?.value?.text

                                                        if (g == update.groupId && a == update.artifactId) {
                                                            updateXmlTagVersion(
                                                                depTag,
                                                                update.newVersion,
                                                                propertiesTag
                                                            )
                                                        }
                                                    }
                                                } else if (update.type == "plugin" || update.type == MANAGED_PLUGIN) {
                                                    // Search in <build><plugins>
                                                    pluginsTag?.findSubTags("plugin")?.forEach { pluginTag ->
                                                        val g = pluginTag.findFirstSubTag("groupId")?.value?.text
                                                        val a = pluginTag.findFirstSubTag("artifactId")?.value?.text

                                                        if (g == update.groupId && a == update.artifactId) {
                                                            updateXmlTagVersion(
                                                                pluginTag,
                                                                update.newVersion,
                                                                propertiesTag
                                                            )
                                                        }
                                                    }
                                                    // Search in <build><pluginManagement><plugins>
                                                    pmPluginsTag?.findSubTags("plugin")?.forEach { pluginTag ->
                                                        val g = pluginTag.findFirstSubTag("groupId")?.value?.text
                                                        val a = pluginTag.findFirstSubTag("artifactId")?.value?.text

                                                        if (g == update.groupId && a == update.artifactId) {
                                                            updateXmlTagVersion(
                                                                pluginTag,
                                                                update.newVersion,
                                                                propertiesTag
                                                            )
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

            project.messageBus.connect().subscribe(MavenImportListener.TOPIC, object : MavenImportListener {
                override fun importFinished(
                    importedProjects: Collection<MavenProject>,
                    newModules: List<com.intellij.openapi.module.Module>
                ) {
                    ApplicationManager.getApplication().invokeLater {
                        availableVersions.clear()
                        selectedVersions.clear()
                        refreshAction(false)
                    }
                }
            })
        }

        private fun checkForUpdates(onFinished: () -> Unit) {
            ProgressManager.getInstance().run(object : Task.Backgroundable(
                project,
                MyMessageBundle.message("toolwindow.MyToolWindow.checkUpdates.progress"),
                true
            ) {
                override fun run(indicator: ProgressIndicator) {
                    val mavenProjectsManager = MavenProjectsManager.getInstance(project)
                    val projects = mavenProjectsManager.projects

                    projects.forEach { mavenProject ->
                        val pomFile = mavenProject.file
                        val psiFile = ApplicationManager.getApplication().runReadAction<XmlFile?> {
                            PsiManager.getInstance(project).findFile(pomFile) as? XmlFile
                        }

                        val allKeysWithVersions = mutableMapOf<String, String>()

                        // Collect from dependency tree
                        mavenProject.dependencyTree.forEach { node ->
                            val dep = node.artifact
                            allKeysWithVersions["${dep.groupId}:${dep.artifactId}"] = dep.version ?: ""
                        }

                        // Collect from plugins
                        mavenProject.plugins.forEach { plugin ->
                            allKeysWithVersions["${plugin.groupId}:${plugin.artifactId}"] = plugin.version ?: ""
                        }

                        // Collect from PSI for managed or unused dependencies/plugins
                        if (psiFile != null) {
                            ApplicationManager.getApplication().runReadAction {
                                val documentElement = psiFile.document?.rootTag

                                // dependencies
                                documentElement?.findFirstSubTag("dependencies")?.findSubTags("dependency")
                                    ?.forEach { tag ->
                                        val g = tag.findFirstSubTag("groupId")?.value?.text ?: ""
                                        val a = tag.findFirstSubTag("artifactId")?.value?.text ?: ""
                                        val v = tag.findFirstSubTag("version")?.value?.text ?: ""
                                        if (g.isNotEmpty() && a.isNotEmpty() && !allKeysWithVersions.containsKey("$g:$a")) {
                                            allKeysWithVersions["$g:$a"] = v
                                        }
                                    }

                                // dependencyManagement
                                documentElement?.findFirstSubTag("dependencyManagement")
                                    ?.findFirstSubTag("dependencies")?.findSubTags("dependency")?.forEach { tag ->
                                    val g = tag.findFirstSubTag("groupId")?.value?.text ?: ""
                                    val a = tag.findFirstSubTag("artifactId")?.value?.text ?: ""
                                    val v = tag.findFirstSubTag("version")?.value?.text ?: ""
                                    if (g.isNotEmpty() && a.isNotEmpty() && !allKeysWithVersions.containsKey("$g:$a")) {
                                        allKeysWithVersions["$g:$a"] = v
                                    }
                                }

                                // build/plugins
                                documentElement?.findFirstSubTag("build")?.findFirstSubTag("plugins")
                                    ?.findSubTags("plugin")?.forEach { tag ->
                                    val g = tag.findFirstSubTag("groupId")?.value?.text ?: ""
                                    val a = tag.findFirstSubTag("artifactId")?.value?.text ?: ""
                                    val v = tag.findFirstSubTag("version")?.value?.text ?: ""
                                    if (g.isNotEmpty() && a.isNotEmpty() && !allKeysWithVersions.containsKey("$g:$a")) {
                                        allKeysWithVersions["$g:$a"] = v
                                    }
                                }

                                // build/pluginManagement
                                documentElement?.findFirstSubTag("build")?.findFirstSubTag("pluginManagement")
                                    ?.findFirstSubTag("plugins")?.findSubTags("plugin")?.forEach { tag ->
                                    val g = tag.findFirstSubTag("groupId")?.value?.text ?: ""
                                    val a = tag.findFirstSubTag("artifactId")?.value?.text ?: ""
                                    val v = tag.findFirstSubTag("version")?.value?.text ?: ""
                                    if (g.isNotEmpty() && a.isNotEmpty() && !allKeysWithVersions.containsKey("$g:$a")) {
                                        allKeysWithVersions["$g:$a"] = v
                                    }
                                }
                            }
                        }

                        allKeysWithVersions.forEach { (key, version) ->
                            if (indicator.isCanceled) return@run
                            val groupId = key.substringBefore(":")
                            val artifactId = key.substringAfter(":")
                            checkArtifactUpdate(groupId, artifactId, version, indicator)
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
                                    selectedVersions[depKey] = sortedCommonVersions.first()
                                }
                            }
                        }
                    }

                    onFinished()
                }
            })
        }

        private fun checkArtifactUpdate(
            groupId: String?,
            artifactId: String?,
            currentVersion: String?,
            indicator: ProgressIndicator
        ) {
            indicator.text2 = "$groupId:$artifactId"

            if (groupId != null && artifactId != null) {
                val version = currentVersion ?: ""
                val versions = fetchVersions(groupId, artifactId, version)
                if (versions.isNotEmpty()) {
                    val key = "$groupId:$artifactId"
                    availableVersions[key] = versions
                    // Pre-select the newest version if it's different from the current one
                    if (versions.first() != version) {
                        selectedVersions[key] = versions.first()
                    }
                }
            }
        }

        private fun updateXmlTagVersion(tag: XmlTag, newVersion: String, propertiesTag: XmlTag?) {
            val versionTag = tag.findFirstSubTag("version")
            if (versionTag != null) {
                // Use versionTag.value.trimmedText for recognition
                val versionContent = versionTag.value.trimmedText

                if (versionContent.startsWith("\${") && versionContent.endsWith("}")) {
                    val propertyName = versionContent.substring(2, versionContent.length - 1)
                    val propertyTag = propertiesTag?.findFirstSubTag(propertyName)
                    if (propertyTag != null) {
                        propertyTag.value.text = newVersion
                        return // Property updated, don't overwrite version tag
                    }
                }
                // Fallback or direct update: overwrite version tag
                versionTag.value.text = newVersion
            } else {
                val newTag = tag.createChildTag("version", null, newVersion, false)
                tag.addSubTag(newTag, false)
            }
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

        private fun findTag(parentTag: XmlTag?, tagName: String, groupId: String, artifactId: String): XmlTag? {
            return parentTag?.findSubTags(tagName)?.find { tag ->
                val g = tag.findFirstSubTag("groupId")?.value?.text
                val a = tag.findFirstSubTag("artifactId")?.value?.text
                g == groupId && a == artifactId
            }
        }

        private fun findDependency(rootTag: XmlTag?, groupId: String, artifactId: String, isManaged: Boolean): XmlTag? {
            if (!isManaged) {
                val dependenciesTag = rootTag?.findFirstSubTag("dependencies")
                val localDep = findTag(dependenciesTag, "dependency", groupId, artifactId)
                if (localDep != null) return localDep
            }

            val dmTag = rootTag?.findFirstSubTag("dependencyManagement")
            val dmDepsTag = dmTag?.findFirstSubTag("dependencies")
            return findTag(dmDepsTag, "dependency", groupId, artifactId)
        }

        private fun findPlugin(rootTag: XmlTag?, groupId: String, artifactId: String, isManaged: Boolean): XmlTag? {
            val buildTag = rootTag?.findFirstSubTag("build")
            if (!isManaged) {
                val pluginsTag = buildTag?.findFirstSubTag("plugins")
                val localPlugin = findTag(pluginsTag, "plugin", groupId, artifactId)
                if (localPlugin != null) return localPlugin
            }

            val pmTag = buildTag?.findFirstSubTag("pluginManagement")
            val pmPluginsTag = pmTag?.findFirstSubTag("plugins")
            return findTag(pmPluginsTag, "plugin", groupId, artifactId)
        }

        private fun navigateToDependency(groupId: String, artifactId: String, type: String = "dependency") {
            val projects = MavenProjectsManager.getInstance(project).projects

            for (mavenProject in projects) {
                val pomFile = mavenProject.file
                val psiFile = ApplicationManager.getApplication().runReadAction<XmlFile?> {
                    PsiManager.getInstance(project).findFile(pomFile) as? XmlFile
                } ?: continue

                val targetTag = ApplicationManager.getApplication().runReadAction<XmlTag?> {
                    val rootTag = psiFile.document?.rootTag
                    val managedDepType = MyMessageBundle.message("toolwindow.MyToolWindow.type.managedDependency")
                    val isManaged = type == managedDepType || type == MANAGED_PLUGIN

                    if (type == "dependency" || type == managedDepType) {
                        findDependency(rootTag, groupId, artifactId, isManaged)
                    } else {
                        findPlugin(rootTag, groupId, artifactId, isManaged)
                    }
                }

                if (targetTag != null) {
                    ApplicationManager.getApplication().invokeLater {
                        val descriptor = OpenFileDescriptor(project, pomFile, targetTag.textOffset)
                        FileEditorManager.getInstance(project).openTextEditor(descriptor, true)
                    }
                    return
                }
            }
        }
    }
}
