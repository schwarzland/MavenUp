package de.schwarzland.mavenup.ui

import de.schwarzland.mavenup.MyMessageBundle
import de.schwarzland.mavenup.model.DependencyUpdate
import de.schwarzland.mavenup.model.VulnerabilityAdvisory
import de.schwarzland.mavenup.model.VulnerabilitySeverity
import de.schwarzland.mavenup.service.DependencyApiService
import de.schwarzland.mavenup.service.MavenUpSettings
import de.schwarzland.mavenup.service.OssIndexApiService
import de.schwarzland.mavenup.service.OssIndexCredentialService
import de.schwarzland.mavenup.service.VulnerabilityApiService
import de.schwarzland.mavenup.service.VulnerabilityMerger
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
import com.intellij.openapi.ui.Messages
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
import org.jetbrains.idea.maven.model.MavenArtifactNode
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import javax.swing.table.DefaultTableModel
import javax.swing.table.TableCellRenderer


private const val MANAGED_PLUGIN = "managed plugin"
private const val TOOLWINDOW_MY_TOOL_WINDOW_TYPE_MANAGED_DEPENDENCY = "toolwindow.MyToolWindow.type.managedDependency"
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

    }

    internal inner class MyToolWindow(private val project: Project) {
        private val vulnerabilityApiService = VulnerabilityApiService()
        private val ossIndexApiService = OssIndexApiService()
        private val ossIndexCredentialService = OssIndexCredentialService()
        private val dependencyApiService = DependencyApiService(project)
        private val availableVersions = mutableMapOf<String, List<String>>()
        private val selectedVersions = mutableMapOf<String, String>()
        private val dependencyToProperty = mutableMapOf<String, String>()
        private val knownDependencies = mutableMapOf<String, String>() // key to current version
        private val vulnerabilityAdvisories = mutableMapOf<String, List<VulnerabilityAdvisory>>()
        private val transitiveCoordinates = mutableSetOf<String>()
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
                addColumn(MyMessageBundle.message("toolwindow.MyToolWindow.table.header.vulnerabilities"))
            }

            val table = JBTable(tableModel)

            table.addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    val row = table.rowAtPoint(e.point)
                    val column = table.columnAtPoint(e.point)
                    if (row < 0) return
                    if (column == 6 && e.clickCount == 1) {
                        @Suppress("UNCHECKED_CAST")
                        val advisories = table.getValueAt(row, 6) as? List<VulnerabilityAdvisory> ?: emptyList()
                        if (advisories.isNotEmpty()) {
                            val coordinate = listOf(0, 1, 4)
                                .joinToString(":") { table.getValueAt(row, it).toString() }
                            VulnerabilityDetailDialog(
                                project,
                                mapOf(coordinate to advisories),
                                "$coordinate - ${MyMessageBundle.message("vulnerability.details.title")}"
                            ).show()
                        }
                        return
                    }

                    val settings = MavenUpSettings.getInstance(project)
                    val requiredClickCount = if (settings.state.jumpOnSingleClick) 1 else 2

                    if (e.clickCount == requiredClickCount) {
                        val groupId = table.getValueAt(row, 0) as? String ?: ""
                        val artifactId = table.getValueAt(row, 1) as? String ?: ""
                        val type = table.getValueAt(row, 3) as? String ?: "dependency"

                        navigateToDependency(groupId, artifactId, type)
                    }
                }
            })

            val refreshButton = JButton(MyMessageBundle.message("toolwindow.MyToolWindow.refresh.button"))
            val checkUpdatesButton = JButton(MyMessageBundle.message("toolwindow.MyToolWindow.checkUpdates.button"))
            val checkVulnerabilitiesButton =
                JButton(MyMessageBundle.message("toolwindow.MyToolWindow.checkVulnerabilities.button"))
            val vulnerabilityDetailsButton =
                JButton(MyMessageBundle.message("toolwindow.MyToolWindow.vulnerabilityDetails.button")).apply {
                    isEnabled = false
                }
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

            table.columnModel.getColumn(6).cellRenderer = TableCellRenderer { currentTable, value, isSelected, _, _, _ ->
                @Suppress("UNCHECKED_CAST")
                val advisories = value as? List<VulnerabilityAdvisory> ?: emptyList()
                JLabel(vulnerabilitySummary(advisories)).apply {
                    isOpaque = true
                    border = BorderFactory.createEmptyBorder(0, 6, 0, 6)
                    toolTipText = if (advisories.isEmpty()) {
                        null
                    } else {
                        MyMessageBundle.message("vulnerability.details.title")
                    }
                    background = if (isSelected) {
                        currentTable.selectionBackground
                    } else {
                        vulnerabilityColor(worstSeverity(advisories), currentTable.background)
                    }
                    foreground = if (isSelected) currentTable.selectionForeground else currentTable.foreground
                }
            }

            fun refreshAction(checkUpdates: Boolean, clearNewVersions: Boolean) {
                if (isUpdating) return

                tableModel.setRowCount(0)
                if (clearNewVersions) {
                    availableVersions.clear()
                    selectedVersions.clear()
                }
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
                    performUpdateCheck(refreshButton, checkUpdatesButton, updateButton) {
                        refreshAction(false, false)
                    }
                }
            }


            val updateAction = {
                if (!isUpdating && selectedVersions.isNotEmpty()) {
                    val mavenProjectsManager = MavenProjectsManager.getInstance(project)
                    val projects = mavenProjectsManager.projects
                    val updates = mutableListOf<DependencyUpdate>()

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
                                    DependencyUpdate(
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
                                    DependencyUpdate(
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
                                        refreshAction(false, true)

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

            val checkVulnerabilitiesAction = {
                if (!isUpdating) {
                    isUpdating = true
                    refreshButton.isEnabled = false
                    checkUpdatesButton.isEnabled = false
                    checkVulnerabilitiesButton.isEnabled = false
                    vulnerabilityDetailsButton.isEnabled = false
                    updateButton.isEnabled = false

                    performVulnerabilityCheck {
                        isUpdating = false
                        refreshButton.isEnabled = true
                        checkUpdatesButton.isEnabled = true
                        checkVulnerabilitiesButton.isEnabled = true
                        refreshAction(false, false)
                        vulnerabilityDetailsButton.isEnabled = vulnerabilityAdvisories.values.any { it.isNotEmpty() }
                        updateUpdateButtonState(updateButton)
                    }
                }
            }

            refreshAction(false, true)

            add(JBScrollPane(table), BorderLayout.CENTER)

            val buttonPanel = JPanel(BorderLayout()).apply {
                val leftButtonPanel = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
                    add(refreshButton.apply {
                        addActionListener { refreshAction(false, true) }
                    })
                    add(checkUpdatesButton.apply {
                        addActionListener { refreshAction(true, true) }
                    })
                    add(checkVulnerabilitiesButton.apply {
                        addActionListener { checkVulnerabilitiesAction() }
                    })
                    add(vulnerabilityDetailsButton.apply {
                        addActionListener { showAllVulnerabilityDetails() }
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
                        refreshAction(false, true)
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
                val g = tag.findFirstSubTag("groupId")?.value?.text?.trim().orEmpty()
                if (g.isEmpty()) return@forEach
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
                val advisories = vulnerabilityAdvisories["$key:$currentVersion"].orEmpty()

                tableModel.addRow(arrayOf(groupId, artifactId, propertyName, type, currentVersion, versions, advisories))
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
                val advisories = vulnerabilityAdvisories["$key:$currentVersion"].orEmpty()

                tableModel.addRow(arrayOf(groupId, artifactId, propertyName, type, currentVersion, versions, advisories))
            }
        }

        private fun performUpdateCheck(
            refreshButton: JButton,
            checkUpdatesButton: JButton,
            updateButton: JButton,
            refreshAfterCheck: () -> Unit
        ) {
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
                    refreshAfterCheck()
                    updateUpdateButtonState(updateButton)
                }
            }
        }

        private fun applyUpdateToPom(
            mavenProject: MavenProject,
            updates: List<DependencyUpdate>
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
            update: DependencyUpdate,
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
            update: DependencyUpdate,
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
            update: DependencyUpdate,
            propertiesTag: XmlTag?
        ) {
            val g = tag.findFirstSubTag("groupId")?.value?.text
            val a = tag.findFirstSubTag("artifactId")?.value?.text

            if (g == update.groupId && a == update.artifactId) {
                updateXmlTagVersion(tag, update.newVersion, propertiesTag)
            }
        }

        private fun performVulnerabilityCheck(onFinished: () -> Unit) {
            ProgressManager.getInstance().run(object : Task.Backgroundable(
                project,
                MyMessageBundle.message("toolwindow.MyToolWindow.checkVulnerabilities.progress"),
                true
            ) {
                override fun run(indicator: ProgressIndicator) {
                    val directDependencies = knownDependencies.entries
                        .filter { it.value.isNotEmpty() }
                        .map { (key, version) -> Triple(key.substringBefore(":"), key.substringAfter(":"), version) }
                    val dependencies = LinkedHashSet(directDependencies)
                    val discoveredTransitiveCoordinates = mutableSetOf<String>()
                    if (MavenUpSettings.getInstance(project).state.checkTransitiveDependencies) {
                        collectResolvedDependencies(MavenProjectsManager.getInstance(project).projects)
                            .forEach { dependency ->
                                dependencies.add(dependency)
                                val coordinate = "${dependency.first}:${dependency.second}:${dependency.third}"
                                if (dependency !in directDependencies) discoveredTransitiveCoordinates.add(coordinate)
                            }
                    }
                    LOG.info("Starting vulnerability check for ${dependencies.size} dependencies/plugins.")

                    val osvResults = vulnerabilityApiService.fetchVulnerabilityAdvisories(dependencies.toList(), indicator)
                    val settings = MavenUpSettings.getInstance(project).state
                    var ossIndexError: String? = null
                    val ossIndexResults = if (settings.ossIndexEnabled && !indicator.isCanceled) {
                        try {
                            val credentials = ossIndexCredentialService.retrieve()
                            ossIndexApiService.fetchVulnerabilityAdvisories(
                                dependencies.toList(),
                                settings.ossIndexUsername.ifBlank { credentials?.userName.orEmpty() },
                                credentials?.getPasswordAsString(),
                                indicator
                            )
                        } catch (exception: Exception) {
                            LOG.warn("OSS Index vulnerability check failed", exception)
                            ossIndexError = exception.message ?: exception.javaClass.simpleName
                            emptyMap()
                        }
                    } else {
                        emptyMap()
                    }
                    val results = VulnerabilityMerger.merge(osvResults, ossIndexResults)
                    val vulnerableEntries = results.values.count { it.isNotEmpty() }
                    LOG.info(
                        "Finished vulnerability check. " +
                            "Results for ${results.size} entries, $vulnerableEntries entries with known vulnerabilities."
                    )

                    ApplicationManager.getApplication().invokeLater {
                        vulnerabilityAdvisories.clear()
                        vulnerabilityAdvisories.putAll(results)
                        transitiveCoordinates.clear()
                        transitiveCoordinates.addAll(discoveredTransitiveCoordinates)
                        ossIndexError?.let {
                            Messages.showWarningDialog(
                                project,
                                it,
                                MyMessageBundle.message("vulnerability.ossIndex.error.title")
                            )
                        }
                        onFinished()
                    }
                }
            })
        }

        internal fun collectResolvedDependencies(
            projects: Collection<MavenProject>
        ): Set<Triple<String, String, String>> {
            val dependencies = linkedSetOf<Triple<String, String, String>>()

            fun collect(nodes: Collection<MavenArtifactNode>) {
                nodes.forEach { node ->
                    val artifact = node.artifact
                    val version = artifact.version.orEmpty()
                    if (artifact.groupId.isNotEmpty() && artifact.artifactId.isNotEmpty() && version.isNotEmpty()) {
                        dependencies.add(Triple(artifact.groupId, artifact.artifactId, version))
                    }
                    collect(node.dependencies)
                }
            }

            projects.forEach { collect(it.dependencyTree) }
            return dependencies
        }

        private fun showAllVulnerabilityDetails() {
            val findings = vulnerabilityAdvisories
                .filterValues { it.isNotEmpty() }
                .mapKeys { (coordinate, _) ->
                    if (coordinate in transitiveCoordinates) "$coordinate (transitive)" else coordinate
                }
            if (findings.isNotEmpty()) VulnerabilityDetailDialog(project, findings).show()
        }

        private fun vulnerabilitySummary(advisories: List<VulnerabilityAdvisory>): String {
            if (advisories.isEmpty()) return ""
            val severity = worstSeverity(advisories)
            return if (severity == VulnerabilitySeverity.UNKNOWN) {
                advisories.size.toString()
            } else {
                "${advisories.size} (${severity.name})"
            }
        }

        private fun worstSeverity(advisories: List<VulnerabilityAdvisory>): VulnerabilitySeverity =
            advisories.maxByOrNull { it.severity.rank }?.severity ?: VulnerabilitySeverity.UNKNOWN

        private fun vulnerabilityColor(
            severity: VulnerabilitySeverity,
            defaultColor: java.awt.Color
        ): java.awt.Color = when (severity) {
            VulnerabilitySeverity.CRITICAL -> com.intellij.ui.JBColor(0xFFB3B3, 0x6E2C2C)
            VulnerabilitySeverity.HIGH -> com.intellij.ui.JBColor(0xFFD5A3, 0x714A21)
            VulnerabilitySeverity.MEDIUM -> com.intellij.ui.JBColor(0xFFF0A6, 0x665A21)
            VulnerabilitySeverity.LOW -> com.intellij.ui.JBColor(0xC7E3FF, 0x294E6B)
            VulnerabilitySeverity.UNKNOWN -> defaultColor
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

        private fun fetchVersions(groupId: String, artifactId: String, currentVersion: String): List<String> {
            return dependencyApiService.fetchVersions(groupId, artifactId, currentVersion)
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
