package de.schwarzland.mavenup

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.psi.PsiManager
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.AbstractTableCellEditor
import com.intellij.icons.AllIcons
import org.apache.maven.artifact.versioning.ComparableVersion
import org.jetbrains.idea.maven.project.MavenImportListener
import org.jetbrains.idea.maven.project.MavenProject
import org.jetbrains.idea.maven.project.MavenProjectsManager
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.util.*
import javax.swing.*
import javax.swing.table.DefaultTableModel
import javax.swing.table.TableCellRenderer
import javax.xml.parsers.DocumentBuilderFactory


private const val MANAGED_PLUGIN = "managed plugin"
private const val TOOLWINDOW_MY_TOOL_WINDOW_TYPE_MANAGED_DEPENDENCY = "toolwindow.MyToolWindow.type.managedDependency"
private const val CENTRAL_REPOSITORY_ID = "central"
private const val CENTRAL_REPOSITORY_URL = "https://repo1.maven.org/maven2"
private val LOG = Logger.getInstance(MavenUpWindowFactory::class.java)

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

    internal inner class MyToolWindow(private val project: Project) {
        private val availableVersions = mutableMapOf<String, List<String>>()
        private val selectedVersions = mutableMapOf<String, String>()
        private val dependencyToProperty = mutableMapOf<String, String>()
        private val knownDependencies = mutableMapOf<String, String>() // key to current version
        private var isUpdating = false

        private val content = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            val tableModel = object : DefaultTableModel() {
                override fun isCellEditable(row: Int, column: Int): Boolean = column == 5
            }.apply {
                addColumn(MyMessageBundle.message("toolwindow.MyToolWindow.table.header.groupId"))
                addColumn(MyMessageBundle.message("toolwindow.MyToolWindow.table.header.artifactId"))
                addColumn(MyMessageBundle.message("toolwindow.MyToolWindow.table.header.property"))
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
                            val groupId = table.getValueAt(row, 0) as? String ?: ""
                            val artifactId = table.getValueAt(row, 1) as? String ?: ""
                            val type = table.getValueAt(row, 3) as? String ?: "dependency"

                            navigateToDependency(groupId, artifactId, type)
                        }
                    }
                }
            })

            val refreshButton = JButton(MyMessageBundle.message("toolwindow.MyToolWindow.refresh.button"))
            val checkUpdatesButton = JButton(MyMessageBundle.message("toolwindow.MyToolWindow.checkUpdates.button"))
            val updateButton = JButton(MyMessageBundle.message("toolwindow.MyToolWindow.update.button"))
            val settingsButton = JButton(AllIcons.General.Settings).apply {
                toolTipText = MyMessageBundle.message("toolwindow.MyToolWindow.settings.button")
                isBorderPainted = false
                isContentAreaFilled = false
                isFocusPainted = false
                preferredSize = java.awt.Dimension(20, 20)
            }

            // Force commit editor when focus lost
            table.putClientProperty("terminateEditOnFocusLost", true)

            // Custom Renderer and Editor for the "New Version" column
            table.columnModel.getColumn(5).cellRenderer = TableCellRenderer { table, value, isSelected, _, row, _ ->
                val groupId = table?.getValueAt(row, 0) as? String ?: ""
                val artifactId = table?.getValueAt(row, 1) as? String ?: ""
                val key = "$groupId:$artifactId"

                @Suppress("UNCHECKED_CAST")
                val versions = value as? List<String> ?: emptyList()
                if (versions.isEmpty()) return@TableCellRenderer JLabel("")

                val selectedVersion = selectedVersions[key]

                ComboBox(versions.toTypedArray()).apply {
                    if (selectedVersion != null) {
                        selectedItem = selectedVersion
                    }

                    val currentVersion = table?.getValueAt(row, 4) as? String ?: ""
                    val newestVersion = versions.firstOrNull() ?: ""
                    if (currentVersion == newestVersion && currentVersion.isNotEmpty()) {
                        foreground = com.intellij.ui.JBColor.BLUE
                    }

                    if (isSelected) {
                        background = table?.selectionBackground
                        if (currentVersion != newestVersion) {
                            foreground = table?.selectionForeground
                        }
                    }
                }
            }

            table.columnModel.getColumn(5).cellEditor = object : AbstractTableCellEditor() {
                private var currentComboBox: ComboBox<String>? = null
                private var currentKey: String? = null

                override fun getTableCellEditorComponent(
                    table: JTable?, value: Any?, isSelected: Boolean, row: Int, column: Int
                ): Component {
                    val groupId = table?.getValueAt(row, 0) as? String ?: ""
                    val artifactId = table?.getValueAt(row, 1) as? String ?: ""
                    currentKey = "$groupId:$artifactId"

                    @Suppress("UNCHECKED_CAST")
                    val versions = value as? List<String> ?: emptyList()
                    val combo = ComboBox(versions.toTypedArray())

                    val currentVersion = table?.getValueAt(row, 4) as? String ?: ""
                    val newestVersion = versions.firstOrNull() ?: ""
                    if (currentVersion == newestVersion && currentVersion.isNotEmpty()) {
                        combo.foreground = com.intellij.ui.JBColor.BLUE
                    }

                    val selectedVersion = if (currentKey != null) selectedVersions[currentKey!!] else null
                    if (selectedVersion != null) {
                        combo.selectedItem = selectedVersion
                    }

                    combo.addActionListener {
                        val selected = combo.selectedItem as? String
                        val key = currentKey
                        if (key != null && selected != null) {
                            selectedVersions[key] = selected

                            // Synchronize other dependencies using the same property
                            val property = dependencyToProperty[key]
                            if (property != null) {
                                dependencyToProperty.forEach { (depKey, prop) ->
                                    if (prop == property) {
                                        selectedVersions[depKey] = selected
                                    }
                                }
                            }
                        }
                        updateUpdateButtonState(updateButton)
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
                    updateUpdateButtonState(updateButton)
                    val groupId = currentKey?.substringBefore(":")
                    val artifactId = currentKey?.substringAfter(":")
                    return availableVersions["$groupId:$artifactId"]
                }
            }

            val refreshAction: (Boolean) -> Unit = refreshAction@{ checkUpdates ->
                if (isUpdating) return@refreshAction

                tableModel.setRowCount(0)
                dependencyToProperty.clear()
                knownDependencies.clear()
                val mavenProjectsManager = MavenProjectsManager.getInstance(project)
                val projects = mavenProjectsManager.projects
                updateUpdateButtonState(updateButton)

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
                                collectDependenciesAndProperties(
                                    documentElement,
                                    "dependencies",
                                    "dependency",
                                    localDependencies
                                )

                                // Collect dependencyManagement dependencies
                                val dmTag = documentElement?.findFirstSubTag("dependencyManagement")
                                collectDependenciesAndProperties(
                                    dmTag,
                                    "dependencies",
                                    "dependency",
                                    managedDependencies
                                )

                                // Collect local plugins and their properties
                                val buildTag = documentElement?.findFirstSubTag("build")
                                collectDependenciesAndProperties(buildTag, "plugins", "plugin", localPlugins)

                                // Collect pluginManagement plugins
                                val pmTag = buildTag?.findFirstSubTag("pluginManagement")
                                collectDependenciesAndProperties(pmTag, "plugins", "plugin", managedPlugins)
                            }
                        }

                        addDependenciesToTable(tableModel, mavenProject, localDependencies, managedDependencies)
                        addPluginsToTable(tableModel, mavenProject, localPlugins, managedPlugins)
                    }
                }

                if (checkUpdates) {
                    performUpdateCheck(refreshButton, checkUpdatesButton, updateButton)
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
                                    MyMessageBundle.message(TOOLWINDOW_MY_TOOL_WINDOW_TYPE_MANAGED_DEPENDENCY)
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
                                        applyUpdateToPom(mavenProject, updates)
                                    }

                                    ApplicationManager.getApplication().invokeLater {
                                        selectedVersions.clear()
                                        availableVersions.clear()
                                        refreshAction(false)

                                        for (row in 0 until tableModel.rowCount) {
                                            tableModel.setValueAt("", row, 4)
                                            tableModel.setValueAt(emptyList<String>(), row, 5)
                                        }
                                    }
                                }
                            })
                        }
                    }
                }
            }

            refreshAction(false)

            add(JBScrollPane(table), BorderLayout.CENTER)

            val buttonPanel = JPanel(BorderLayout()).apply {
                val leftButtonPanel = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
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
                add(leftButtonPanel, BorderLayout.WEST)
                add(settingsButton.apply {
                    addActionListener { openSettings() }
                }, BorderLayout.EAST)
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

        private fun updateUpdateButtonState(updateButton: JButton) {
            var hasUpdate = false
            knownDependencies.forEach { (key, currentVersion) ->
                val newVersion = selectedVersions[key]
                if (newVersion != null && newVersion != currentVersion) {
                    hasUpdate = true
                }
            }
            updateButton.isEnabled = hasUpdate && !isUpdating
        }

        private fun collectDependenciesAndProperties(
            parentTag: XmlTag?,
            wrapperTagName: String,
            itemTagName: String,
            targetMap: MutableMap<String, String>
        ) {
            val wrapperTag = parentTag?.findFirstSubTag(wrapperTagName) ?: parentTag
            wrapperTag?.findSubTags(itemTagName)?.forEach { tag ->
                val g = tag.findFirstSubTag("groupId")?.value?.text ?: ""
                val a = tag.findFirstSubTag("artifactId")?.value?.text ?: ""
                val v = tag.findFirstSubTag("version")?.value?.text ?: ""
                val key = "$g:$a"
                targetMap[key] = v

                val versionText = tag.findFirstSubTag("version")?.value?.trimmedText
                if (versionText != null && versionText.startsWith("\${") && versionText.endsWith("}")) {
                    dependencyToProperty[key] = versionText.substring(2, versionText.length - 1)
                }
            }
        }

        private fun addDependenciesToTable(
            tableModel: DefaultTableModel,
            mavenProject: MavenProject,
            localDependencies: Map<String, String>,
            managedDependencies: Map<String, String>
        ) {
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
                val propertyName = dependencyToProperty[key] ?: ""
                val type = if (isManaged) {
                    MyMessageBundle.message(TOOLWINDOW_MY_TOOL_WINDOW_TYPE_MANAGED_DEPENDENCY)
                } else {
                    "dependency"
                }

                tableModel.addRow(arrayOf(groupId, artifactId, propertyName, type, currentVersion, versions))
            }
        }

        private fun addPluginsToTable(
            tableModel: DefaultTableModel,
            mavenProject: MavenProject,
            localPlugins: Map<String, String>,
            managedPlugins: Map<String, String>
        ) {
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
                val propertyName = dependencyToProperty[key] ?: ""
                val type = if (isManaged) MANAGED_PLUGIN else "plugin"

                tableModel.addRow(arrayOf(groupId, artifactId, propertyName, type, currentVersion, versions))
            }
        }

        private fun performUpdateCheck(refreshButton: JButton, checkUpdatesButton: JButton, updateButton: JButton) {
            isUpdating = true
            refreshButton.isEnabled = false
            checkUpdatesButton.isEnabled = false
            updateButton.isEnabled = false

            availableVersions.clear()
            selectedVersions.clear()
            checkForUpdates {
                ApplicationManager.getApplication().invokeLater {
                    isUpdating = false
                    refreshButton.isEnabled = true
                    checkUpdatesButton.isEnabled = true
                    // In original code, it was 'this(false)'.
                    // Trigger refresh without update check
                    refreshButton.doClick()
                    updateUpdateButtonState(updateButton)
                }
            }
        }

        private fun applyUpdateToPom(
            mavenProject: MavenProject,
            updates: List<UpdateConfirmationDialog.DependencyUpdate>
        ) {
            val pomFile = mavenProject.file
            val psiFile = ApplicationManager.getApplication().runReadAction<XmlFile?> {
                PsiManager.getInstance(project).findFile(pomFile) as? XmlFile
            } ?: return

            WriteCommandAction.runWriteCommandAction(project) {
                val documentElement = psiFile.document?.rootTag ?: return@runWriteCommandAction
                val propertiesTag = documentElement.findFirstSubTag("properties")

                updates.forEach { update ->
                    val managedDependencyType = MyMessageBundle.message(
                        TOOLWINDOW_MY_TOOL_WINDOW_TYPE_MANAGED_DEPENDENCY
                    )
                    if (update.type == "dependency" || update.type == managedDependencyType) {
                        updateDependencies(documentElement, update, propertiesTag)
                    } else if (update.type == "plugin" || update.type == MANAGED_PLUGIN) {
                        updatePlugins(documentElement, update, propertiesTag)
                    }
                }
            }
        }

        private fun updateDependencies(
            documentElement: XmlTag,
            update: UpdateConfirmationDialog.DependencyUpdate,
            propertiesTag: XmlTag?
        ) {
            val dependenciesTag = documentElement.findFirstSubTag("dependencies")
            val dependencyManagementTag = documentElement.findFirstSubTag("dependencyManagement")
            val dmDependenciesTag = dependencyManagementTag?.findFirstSubTag("dependencies")

            // Search in <dependencies>
            dependenciesTag?.findSubTags("dependency")?.forEach { depTag ->
                updateIfMatch(depTag, update, propertiesTag)
            }
            // Search in <dependencyManagement><dependencies>
            dmDependenciesTag?.findSubTags("dependency")?.forEach { depTag ->
                updateIfMatch(depTag, update, propertiesTag)
            }
        }

        private fun updatePlugins(
            documentElement: XmlTag,
            update: UpdateConfirmationDialog.DependencyUpdate,
            propertiesTag: XmlTag?
        ) {
            val buildTag = documentElement.findFirstSubTag("build")
            val pluginsTag = buildTag?.findFirstSubTag("plugins")
            val pluginManagementTag = buildTag?.findFirstSubTag("pluginManagement")
            val pmPluginsTag = pluginManagementTag?.findFirstSubTag("plugins")

            // Search in <build><plugins>
            pluginsTag?.findSubTags("plugin")?.forEach { pluginTag ->
                updateIfMatch(pluginTag, update, propertiesTag)
            }
            // Search in <build><pluginManagement><plugins>
            pmPluginsTag?.findSubTags("plugin")?.forEach { pluginTag ->
                updateIfMatch(pluginTag, update, propertiesTag)
            }
        }

        private fun updateIfMatch(
            tag: XmlTag,
            update: UpdateConfirmationDialog.DependencyUpdate,
            propertiesTag: XmlTag?
        ) {
            val g = tag.findFirstSubTag("groupId")?.value?.text
            val a = tag.findFirstSubTag("artifactId")?.value?.text

            if (g == update.groupId && a == update.artifactId) {
                updateXmlTagVersion(tag, update.newVersion, propertiesTag)
            }
        }

        private fun checkForUpdates(onFinished: () -> Unit) {
            ProgressManager.getInstance().run(object : Task.Backgroundable(
                project,
                MyMessageBundle.message("toolwindow.MyToolWindow.checkUpdates.progress"),
                true
            ) {
                override fun run(indicator: ProgressIndicator) {
                    val projects = MavenProjectsManager.getInstance(project).projects
                    projects.forEach { mavenProject ->
                        processProjectUpdates(mavenProject, indicator)
                    }

                    postProcessPropertyUpdates()

                    ApplicationManager.getApplication().invokeLater {
                        onFinished()
                    }
                }
            })
        }

        private fun processProjectUpdates(mavenProject: MavenProject, indicator: ProgressIndicator) {
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
            collectFromPsi(mavenProject, allKeysWithVersions)

            allKeysWithVersions.forEach { (key, version) ->
                if (indicator.isCanceled) return
                val groupId = key.substringBefore(":")
                val artifactId = key.substringAfter(":")
                checkArtifactUpdate(groupId, artifactId, version, indicator)
            }
        }

        private fun collectFromPsi(mavenProject: MavenProject, allKeysWithVersions: MutableMap<String, String>) {
            val psiFile = ApplicationManager.getApplication().runReadAction<XmlFile?> {
                PsiManager.getInstance(project).findFile(mavenProject.file) as? XmlFile
            } ?: return

            ApplicationManager.getApplication().runReadAction {
                val rootTag = psiFile.document?.rootTag ?: return@runReadAction

                // dependencies
                collectTags(rootTag.findFirstSubTag("dependencies"), "dependency", allKeysWithVersions)

                // dependencyManagement
                val dmTag = rootTag.findFirstSubTag("dependencyManagement")
                collectTags(dmTag?.findFirstSubTag("dependencies"), "dependency", allKeysWithVersions)

                // build/plugins
                val buildTag = rootTag.findFirstSubTag("build")
                collectTags(buildTag?.findFirstSubTag("plugins"), "plugin", allKeysWithVersions)

                // build/pluginManagement
                val pmTag = buildTag?.findFirstSubTag("pluginManagement")
                collectTags(pmTag?.findFirstSubTag("plugins"), "plugin", allKeysWithVersions)
            }
        }

        private fun collectTags(parentTag: XmlTag?, tagName: String, allKeysWithVersions: MutableMap<String, String>) {
            parentTag?.findSubTags(tagName)?.forEach { tag ->
                val g = tag.findFirstSubTag("groupId")?.value?.text ?: ""
                val a = tag.findFirstSubTag("artifactId")?.value?.text ?: ""
                val v = tag.findFirstSubTag("version")?.value?.text ?: ""
                if (g.isNotEmpty() && a.isNotEmpty() && !allKeysWithVersions.containsKey("$g:$a")) {
                    allKeysWithVersions["$g:$a"] = v
                }
            }
        }

        private fun postProcessPropertyUpdates() {
            val propertyToDependencies: Map<String, List<String>> = dependencyToProperty
                .entries
                .groupBy({ it.value }, { it.key })

            propertyToDependencies.forEach { (_, depKeys) ->
                if (depKeys.size > 1) {
                    intersectVersions(depKeys)
                }
            }
        }

        private fun intersectVersions(depKeys: List<String>) {
            var commonVersions: List<String>? = null
            depKeys.forEach { depKey ->
                val versions = availableVersions[depKey] ?: emptyList()
                commonVersions = if (commonVersions == null) {
                    versions
                } else {
                    commonVersions.intersect(versions.toSet()).toList()
                }
            }

            val sortedCommonVersions = commonVersions?.sortedWith { v1, v2 ->
                ComparableVersion(v2).compareTo(ComparableVersion(v1))
            } ?: emptyList()

            depKeys.forEach { depKey ->
                availableVersions[depKey] = sortedCommonVersions
                if (sortedCommonVersions.isNotEmpty() && MavenUpSettings.getInstance(project).state.selectLatestVersion) {
                    selectedVersions[depKey] = sortedCommonVersions.first()
                } else if (sortedCommonVersions.isNotEmpty() && !MavenUpSettings.getInstance(project).state.selectLatestVersion) {
                    selectedVersions[depKey] = knownDependencies[depKey] ?: ""
                }
            }
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
                    // Pre-select the newest version if it's different from the current one and setting is enabled
                    if (versions.first() != version && MavenUpSettings.getInstance(project).state.selectLatestVersion) {
                        selectedVersions[key] = versions.first()
                    } else if (!MavenUpSettings.getInstance(project).state.selectLatestVersion) {
                        selectedVersions[key] = version
                    }
                }
            }
        }

        internal fun updateXmlTagVersion(tag: XmlTag, newVersion: String, propertiesTag: XmlTag?) {
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


        private fun normalizeSettingsId(rawId: String?): String? {
            return rawId?.trim()?.takeIf { it.isNotEmpty() }
        }

        private fun resolveCredentialValue(rawValue: String?, serverId: String, fieldName: String): String? {
            val value = rawValue?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val envPlaceholder = Regex("""^\$\{env\.([^}]+)}$""").matchEntire(value)
            if (envPlaceholder != null) {
                val envName = envPlaceholder.groupValues[1]
                return System.getenv(envName).also {
                    if (it == null) {
                        LOG.warn("Could not resolve $fieldName for Maven server '$serverId': missing env var '$envName'")
                    }
                }
            }

            val propertyPlaceholder = Regex("""^\$\{([^}]+)}$""").matchEntire(value)
            if (propertyPlaceholder != null) {
                val key = propertyPlaceholder.groupValues[1]
                return System.getProperty(key) ?: System.getenv(key).also {
                    if (it == null) {
                        LOG.warn(
                            "Could not resolve $fieldName for Maven server '$serverId': " +
                                "missing system property/env var '$key'"
                        )
                    }
                }
            }

            return value
        }

        private fun getMavenServerCredentials(): Map<String, Pair<String?, String?>> {
            val credentials = mutableMapOf<String, Pair<String?, String?>>()
            val generalSettings = MavenProjectsManager.getInstance(project).generalSettings
            val userSettingsPath = generalSettings.userSettingsFile

            if (userSettingsPath.isNotBlank()) {
                val userSettingsFile = File(userSettingsPath)
                if (userSettingsFile.exists()) {
                    try {
                        val factory = DocumentBuilderFactory.newInstance()
                        val builder = factory.newDocumentBuilder()
                        val doc = builder.parse(userSettingsFile)
                        val serverNodes = doc.getElementsByTagName("server")
                        for (i in 0 until serverNodes.length) {
                            val serverNode = serverNodes.item(i) as? org.w3c.dom.Element ?: continue
                            val id = normalizeSettingsId(serverNode.getElementsByTagName("id").item(0)?.textContent)
                            if (id != null) {
                                val username = resolveCredentialValue(
                                    serverNode.getElementsByTagName("username").item(0)?.textContent,
                                    id,
                                    "username"
                                )
                                val password = resolveCredentialValue(
                                    serverNode.getElementsByTagName("password").item(0)?.textContent,
                                    id,
                                    "password"
                                )
                                credentials[id] = Pair(username, password)
                            }
                        }
                    } catch (e: Exception) {
                        LOG.error("Failed to parse Maven settings file for credentials: ${userSettingsFile.path}", e)
                    }
                }
            }
            return credentials
        }

        private fun getMavenRepositoryInfos(): List<Pair<String?, String>> {
            val infos = mutableListOf<Pair<String?, String>>(Pair(CENTRAL_REPOSITORY_ID, CENTRAL_REPOSITORY_URL))

            val generalSettings = MavenProjectsManager.getInstance(project).generalSettings
            val userSettingsPath = generalSettings.userSettingsFile

            if (userSettingsPath.isNotBlank()) {
                val userSettingsFile = File(userSettingsPath)
                if (userSettingsFile.exists()) {
                    try {
                        val factory = DocumentBuilderFactory.newInstance()
                        val builder = factory.newDocumentBuilder()
                        val doc = builder.parse(userSettingsFile)
                        val repoNodes = doc.getElementsByTagName("repository")
                        for (i in 0 until repoNodes.length) {
                            val repoNode = repoNodes.item(i) as? org.w3c.dom.Element ?: continue
                            val id = normalizeSettingsId(repoNode.getElementsByTagName("id").item(0)?.textContent)
                            val url = repoNode.getElementsByTagName("url").item(0)?.textContent
                            if (url != null && url.isNotBlank()) {
                                infos.add(Pair(id, url.trim().trimEnd('/')))
                            }
                        }
                    } catch (e: Exception) {
                        LOG.error("Failed to parse Maven settings file for repositories: ${userSettingsFile.path}", e)
                    }
                }
            }

            return infos
                .groupBy { it.second }
                .values
                .map { repositoriesWithSameUrl ->
                    repositoriesWithSameUrl.firstOrNull { it.first != null } ?: repositoriesWithSameUrl.first()
                }
        }

        private fun findServerCredentials(
            repositoryInfo: Pair<String?, String>,
            serverCredentials: Map<String, Pair<String?, String?>>
        ): Pair<String?, String?>? {
            repositoryInfo.first?.let { serverCredentials[it] }?.let { return it }
            serverCredentials[repositoryInfo.second]?.let { return it }
            val host = URI(repositoryInfo.second).host ?: return null
            return serverCredentials[host]
        }

        private fun createMetadataConnection(repositoryUrl: String, groupId: String, artifactId: String): HttpURLConnection {
            val urlString = "$repositoryUrl/${groupId.replace('.', '/')}/$artifactId/maven-metadata.xml"
            val url = URI(urlString).toURL()
            return (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10000
                readTimeout = 10000
            }
        }

        private fun applyCredentials(
            connection: HttpURLConnection,
            repositoryInfo: Pair<String?, String>,
            serverCredentials: Map<String, Pair<String?, String?>>
        ) {
            val creds = findServerCredentials(repositoryInfo, serverCredentials)
            if (creds != null && creds.first != null && creds.second != null) {
                val auth = "${creds.first}:${creds.second}"
                val encodedAuth = Base64.getEncoder().encodeToString(auth.toByteArray(Charsets.UTF_8))
                connection.setRequestProperty("Authorization", "Basic $encodedAuth")
            }
        }

        private fun readVersionsFromConnection(
            connection: HttpURLConnection,
            currentComparable: ComparableVersion,
            groupId: String,
            artifactId: String,
            repositoryUrl: String
        ): Pair<Boolean, List<String>> {
            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                LOG.warn(
                    "Failed to fetch versions for $groupId:$artifactId from $repositoryUrl. " +
                        "HTTP $responseCode ${connection.responseMessage}"
                )
                return Pair(false, emptyList())
            }

            val versions = mutableListOf<String>()
            val factory = DocumentBuilderFactory.newInstance()
            val builder = factory.newDocumentBuilder()
            val doc = builder.parse(connection.inputStream)
            val versionNodes = doc.getElementsByTagName("version")
            for (i in 0 until versionNodes.length) {
                val version = versionNodes.item(i).textContent
                if (ComparableVersion(version) >= currentComparable) {
                    versions.add(version)
                }
            }
            return Pair(true, versions)
        }

        private fun fetchVersionsFromRepository(
            repositoryInfo: Pair<String?, String>,
            groupId: String,
            artifactId: String,
            currentComparable: ComparableVersion,
            serverCredentials: Map<String, Pair<String?, String?>>
        ): Pair<Boolean, List<String>> {
            return try {
                val connection = createMetadataConnection(repositoryInfo.second, groupId, artifactId)
                applyCredentials(connection, repositoryInfo, serverCredentials)
                readVersionsFromConnection(connection, currentComparable, groupId, artifactId, repositoryInfo.second)
            } catch (e: Exception) {
                LOG.warn("Failed to fetch versions for $groupId:$artifactId from ${repositoryInfo.second}", e)
                Pair(false, emptyList())
            }
        }

        private fun collectVersionsFromRepositories(
            repositoryInfos: List<Pair<String?, String>>,
            fetchVersionsForRepository: (Pair<String?, String>) -> Pair<Boolean, List<String>>
        ): Set<String> {
            val allVersions = mutableSetOf<String>()
            val orderedRepositoryInfos = repositoryInfos.sortedBy { if (it.second == CENTRAL_REPOSITORY_URL) 0 else 1 }

            for (repoInfo in orderedRepositoryInfos) {
                val (requestSucceeded, versions) = fetchVersionsForRepository(repoInfo)
                allVersions.addAll(versions)
                if (repoInfo.second == CENTRAL_REPOSITORY_URL && requestSucceeded) {
                    break
                }
            }

            return allVersions
        }

        private fun fetchVersions(groupId: String, artifactId: String, currentVersion: String): List<String> {
            val repositoryInfos = getMavenRepositoryInfos()
            val serverCredentials = getMavenServerCredentials()
            val currentComparable = ComparableVersion(currentVersion)
            val allVersions = collectVersionsFromRepositories(
                repositoryInfos
            ) { repoInfo ->
                fetchVersionsFromRepository(repoInfo, groupId, artifactId, currentComparable, serverCredentials)
            }
            return allVersions.sortedWith { v1, v2 ->
                ComparableVersion(v2).compareTo(ComparableVersion(v1))
            }
        }

        fun getContent(): JBPanel<JBPanel<*>> = content

        private fun findTag(parentTag: XmlTag?, tagName: String, groupId: String, artifactId: String): XmlTag? {
            return parentTag?.findSubTags(tagName)?.find { tag ->
                val g = tag.findFirstSubTag("groupId")?.value?.text
                val a = tag.findFirstSubTag("artifactId")?.value?.text
                g == groupId && a == artifactId
            }
        }

        internal fun findDependency(
            rootTag: XmlTag?,
            groupId: String,
            artifactId: String,
            isManaged: Boolean
        ): XmlTag? {
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

            ProgressManager.getInstance().run(object : Task.Backgroundable(
                project,
                MyMessageBundle.message("toolwindow.MyToolWindow.refresh.button"),
                false
            ) {
                override fun run(indicator: ProgressIndicator) {
                    for (mavenProject in projects) {
                        val pomFile = mavenProject.file
                        val psiFile = ApplicationManager.getApplication().runReadAction<XmlFile?> {
                            PsiManager.getInstance(project).findFile(pomFile) as? XmlFile
                        } ?: continue

                        val targetTag = ApplicationManager.getApplication().runReadAction<XmlTag?> {
                            val rootTag = psiFile.document?.rootTag
                            val managedDepType =
                                MyMessageBundle.message(TOOLWINDOW_MY_TOOL_WINDOW_TYPE_MANAGED_DEPENDENCY)
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
            })
        }

        private fun openSettings() {
            com.intellij.openapi.options.ShowSettingsUtil.getInstance()
                .showSettingsDialog(project, MavenUpConfigurable::class.java)
        }
    }
}
