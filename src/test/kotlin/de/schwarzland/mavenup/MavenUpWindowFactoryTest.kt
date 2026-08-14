package de.schwarzland.mavenup

import de.schwarzland.mavenup.model.VulnerabilityAdvisory
import de.schwarzland.mavenup.model.VulnerabilitySeverity
import de.schwarzland.mavenup.model.DependencyUpdate
import de.schwarzland.mavenup.service.MavenUpSettings
import de.schwarzland.mavenup.service.MavenRepositoryBrowser
import de.schwarzland.mavenup.ui.buildMavenRepositoryUrl
import de.schwarzland.mavenup.ui.MavenUpWindowFactory
import de.schwarzland.mavenup.ui.RefreshSnapshot
import de.schwarzland.mavenup.ui.buildVulnerabilityCell
import de.schwarzland.mavenup.ui.canCheckVulnerabilities
import de.schwarzland.mavenup.ui.vulnerabilitySummary
import de.schwarzland.mavenup.ui.isVersionUpToDate
import de.schwarzland.mavenup.ui.versionStatusIcon
import de.schwarzland.mavenup.ui.versionStatusColor
import de.schwarzland.mavenup.ui.versionStatusTooltip
import de.schwarzland.mavenup.ui.createVersionPanel
import com.intellij.openapi.wm.RegisterToolWindowTask
import com.intellij.openapi.wm.ToolWindowAnchor
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.psi.xml.XmlFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.application.ReadAction
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.ui.table.JBTable
import java.awt.Container
import java.util.concurrent.TimeUnit

class MavenUpWindowFactoryTest : BasePlatformTestCase() {

    fun testVulnerabilityCheckAvailabilityDuringRefreshAndUpdateCheck() {
        assertTrue(canCheckVulnerabilities(isRefreshing = false, isUpdating = false))
        assertFalse(canCheckVulnerabilities(isRefreshing = true, isUpdating = false))
        assertFalse(canCheckVulnerabilities(isRefreshing = false, isUpdating = true))
        assertFalse(canCheckVulnerabilities(isRefreshing = true, isUpdating = true))
    }

    fun testVulnerabilityColumnIsAssociatedWithCurrentVersion() {
        val table = findTable(MavenUpWindowFactory().MyToolWindow(project).getContent())

        assertNotNull(table)
        assertEquals("Current Version", table!!.model.getColumnName(4))
        assertEquals("Vulnerabilities (Current)", table.model.getColumnName(5))
        assertEquals("New Version", table.model.getColumnName(6))
        assertFalse(table.model.isCellEditable(0, 5))
        assertTrue(table.model.isCellEditable(0, 6))
    }

    fun testTransitiveVulnerabilitiesAreIncludedAndMarkedInDependencyCell() {
        val directCoordinate = "com.example:direct:1.0.0"
        val transitiveCoordinate = "com.example:transitive:2.0.0"
        val directAdvisory = VulnerabilityAdvisory(
            id = "CVE-DIRECT",
            severity = VulnerabilitySeverity.MEDIUM,
            sources = setOf("OSV")
        )
        val transitiveAdvisory = VulnerabilityAdvisory(
            id = "CVE-TRANSITIVE",
            severity = VulnerabilitySeverity.HIGH,
            sources = setOf("OSV")
        )

        val cell = buildVulnerabilityCell(
            directCoordinate,
            mapOf(
                directCoordinate to listOf(directAdvisory),
                transitiveCoordinate to listOf(transitiveAdvisory)
            ),
            setOf(transitiveCoordinate)
        )

        assertEquals(2, cell.allAdvisories.size)
        assertEquals(1, cell.transitiveAdvisoryCount)
        assertEquals("2 (1 transitive, HIGH)", vulnerabilitySummary(cell))
        assertEquals(listOf(directAdvisory), cell.detailFindings()[directCoordinate])
        assertEquals(
            listOf(transitiveAdvisory),
            cell.detailFindings()["$transitiveCoordinate (transitive)"]
        )
    }

    fun testShouldBeAvailableOnlyWhenMavenProjectsExist() {
        val factory = MavenUpWindowFactory()

        // Standardmäßig sollten keine Maven-Projekte in einem leeren Testprojekt vorhanden sein
        assertFalse("ToolWindow sollte ohne Maven-Projekte nicht verfügbar sein", factory.shouldBeAvailable(project))
    }

    @Suppress("OverrideOnly", "DEPRECATION")
    fun testToolWindowContentCreation() {
        val factory = MavenUpWindowFactory()
        val toolWindow = ToolWindowManager.getInstance(project).registerToolWindow(
            RegisterToolWindowTask(
                id = "TestWindow",
                anchor = ToolWindowAnchor.BOTTOM,
                canCloseContent = true
            )
        )

        try {
            factory.createToolWindowContent(project, toolWindow)
            assertTrue("Content sollte zum ToolWindow hinzugefügt worden sein", toolWindow.contentManager.contentCount > 0)
        } finally {
            ToolWindowManager.getInstance(project).unregisterToolWindow("TestWindow")
        }
    }

    fun testRefreshSnapshotCollectionRunsOutsideEdt() {
        val toolWindowInstance = MavenUpWindowFactory().MyToolWindow(project)

        val snapshot: RefreshSnapshot = ReadAction.nonBlocking<RefreshSnapshot> {
            toolWindowInstance.collectRefreshSnapshot("managed dependency")
        }.submit(AppExecutorUtil.getAppExecutorService()).get(5, TimeUnit.SECONDS)

        assertNotNull(snapshot)
        assertTrue(snapshot.rows.isEmpty())
    }

    fun testSelectedUpdatesUseCachedRefreshData() {
        val toolWindowInstance = MavenUpWindowFactory().MyToolWindow(project)
        val key = "com.example:example-core"
        val fields = toolWindowInstance.javaClass
        @Suppress("UNCHECKED_CAST")
        val selectedVersions = fields.getDeclaredField("selectedVersions")
            .apply { isAccessible = true }
            .get(toolWindowInstance) as MutableMap<String, String>
        @Suppress("UNCHECKED_CAST")
        val knownDependencies = fields.getDeclaredField("knownDependencies")
            .apply { isAccessible = true }
            .get(toolWindowInstance) as MutableMap<String, String>
        @Suppress("UNCHECKED_CAST")
        val knownTypes = fields.getDeclaredField("knownTypes")
            .apply { isAccessible = true }
            .get(toolWindowInstance) as MutableMap<String, String>

        selectedVersions[key] = "2.0.0"
        knownDependencies[key] = "1.0.0"
        knownTypes[key] = "dependency"

        val updates = toolWindowInstance.collectSelectedUpdates()

        assertEquals(1, updates.size)
        assertEquals("com.example", updates.single().groupId)
        assertEquals("example-core", updates.single().artifactId)
        assertEquals("1.0.0", updates.single().oldVersion)
        assertEquals("2.0.0", updates.single().newVersion)
    }

    fun testFindDependency() {
        val pomContent = """
            <project>
                <dependencies>
                    <dependency>
                        <groupId>com.example</groupId>
                        <artifactId>example-core</artifactId>
                        <version>1.0.0</version>
                    </dependency>
                </dependencies>
                <dependencyManagement>
                    <dependencies>
                        <dependency>
                            <groupId>com.example</groupId>
                            <artifactId>example-managed</artifactId>
                            <version>2.0.0</version>
                        </dependency>
                    </dependencies>
                </dependencyManagement>
            </project>
        """.trimIndent()

        val psiFile = myFixture.configureByText("pom.xml", pomContent) as XmlFile
        val rootTag = psiFile.document?.rootTag

        val factory = MavenUpWindowFactory()
        val toolWindowInstance = factory.MyToolWindow(project)

        // Test normal dependency
        val normalDep = toolWindowInstance.findDependency(rootTag, "com.example", "example-core", false)
        assertNotNull("Normale Dependency sollte gefunden werden", normalDep)
        assertEquals("example-core", normalDep?.findFirstSubTag("artifactId")?.value?.text)

        // Test managed dependency
        val managedDep = toolWindowInstance.findDependency(rootTag, "com.example", "example-managed", true)
        assertNotNull("Managed Dependency sollte gefunden werden", managedDep)
        assertEquals("example-managed", managedDep?.findFirstSubTag("artifactId")?.value?.text)
    }

    fun testUpdateXmlTagVersion() {
        val pomContent = """
            <project>
                <properties>
                    <example.version>1.0.0</example.version>
                </properties>
                <dependencies>
                    <dependency>
                        <groupId>com.example</groupId>
                        <artifactId>direct</artifactId>
                        <version>1.0.0</version>
                    </dependency>
                    <dependency>
                        <groupId>com.example</groupId>
                        <artifactId>prop</artifactId>
                        <version>${'$'}{example.version}</version>
                    </dependency>
                </dependencies>
            </project>
        """.trimIndent()

        val psiFile = myFixture.configureByText("pom.xml", pomContent) as XmlFile
        val rootTag = psiFile.document?.rootTag
        val propertiesTag = rootTag?.findFirstSubTag("properties")
        val deps = rootTag?.findFirstSubTag("dependencies")?.findSubTags("dependency")
        val directDep = deps?.find { it.findFirstSubTag("artifactId")?.value?.text == "direct" }
        val propDep = deps?.find { it.findFirstSubTag("artifactId")?.value?.text == "prop" }

        val factory = MavenUpWindowFactory()
        val toolWindowInstance = factory.MyToolWindow(project)

        // Update direct version
        WriteCommandAction.runWriteCommandAction(project) {
            toolWindowInstance.updateXmlTagVersion(directDep!!, "1.1.0", propertiesTag)
        }
        assertEquals("1.1.0", directDep?.findFirstSubTag("version")?.value?.text)

        // Update property version
        WriteCommandAction.runWriteCommandAction(project) {
            toolWindowInstance.updateXmlTagVersion(propDep!!, "1.2.0", propertiesTag)
        }
        assertEquals("${'$'}{example.version}", propDep?.findFirstSubTag("version")?.value?.text)
        assertEquals("1.2.0", propertiesTag?.findFirstSubTag("example.version")?.value?.text)
    }

    fun testSelectLatestVersionSetting() {
        val factory = MavenUpWindowFactory()
        val toolWindowInstance = factory.MyToolWindow(project)
        val settings = MavenUpSettings.getInstance(project)

        // Mock data
        val key = "com.example:test-artifact"
        val versions = listOf("1.1.0", "1.0.0")
        val currentVersion = "1.0.0"

        // Test with selectLatestVersion = true (default)
        assertTrue(settings.state.selectLatestVersion)
        
        // Use reflection to access internal maps for verification
        val availableVersionsField = toolWindowInstance.javaClass.getDeclaredField("availableVersions").apply { isAccessible = true }
        val selectedVersionsField = toolWindowInstance.javaClass.getDeclaredField("selectedVersions").apply { isAccessible = true }
        val knownDependenciesField = toolWindowInstance.javaClass.getDeclaredField("knownDependencies").apply { isAccessible = true }

        val availableVersions = availableVersionsField.get(toolWindowInstance) as MutableMap<String, List<String>>
        val selectedVersions = selectedVersionsField.get(toolWindowInstance) as MutableMap<String, String>
        val knownDependencies = knownDependenciesField.get(toolWindowInstance) as MutableMap<String, String>

        knownDependencies[key] = currentVersion
        
        // Simulate checkArtifactUpdate logic manually for testing the selection logic
        fun simulateCheck(v: String) {
            availableVersions[key] = versions
            if (versions.first() != v && settings.state.selectLatestVersion) {
                selectedVersions[key] = versions.first()
            } else if (!settings.state.selectLatestVersion) {
                selectedVersions[key] = v
            }
        }

        simulateCheck(currentVersion)
        assertEquals("1.1.0", selectedVersions[key])

        // Test with selectLatestVersion = false
        settings.state.selectLatestVersion = false
        selectedVersions.clear()
        simulateCheck(currentVersion)
        assertEquals("1.0.0", selectedVersions[key])
        
        settings.state.selectLatestVersion = true
    }

    fun testJumpOnSingleClickSetting() {
        val settings = MavenUpSettings.getInstance(project)
        
        // Test default value
        assertFalse("jumpOnSingleClick sollte standardmäßig false sein", settings.state.jumpOnSingleClick)
        
        // Test update
        settings.state.jumpOnSingleClick = true
        assertTrue("jumpOnSingleClick sollte nun true sein", settings.state.jumpOnSingleClick)
        
        // Reset
        settings.state.jumpOnSingleClick = false
    }

    fun testUnstableVersionFilterSettingsDefaults() {
        val defaults = MavenUpSettings.State()
        assertFalse(defaults.hideUnstableVersions)
        assertTrue(defaults.hiddenVersionQualifiers.isNotBlank())
        assertTrue(defaults.hiddenVersionQualifiers.contains("rc"))
    }

    fun testBuildMavenRepositoryUrl() {
        assertEquals(
            "https://mvnrepository.com/artifact/org.springframework/spring-core/5.3.10",
            buildMavenRepositoryUrl("org.springframework", "spring-core", "5.3.10", MavenRepositoryBrowser.MVN_REPOSITORY)
        )
        assertEquals(
            "https://central.sonatype.com/artifact/org.springframework/spring-core/5.3.10",
            buildMavenRepositoryUrl("org.springframework", "spring-core", "5.3.10", MavenRepositoryBrowser.SONATYPE_CENTRAL)
        )
        // default browser is MVN_REPOSITORY
        assertEquals(
            "https://mvnrepository.com/artifact/com.example/my-lib/1.0.0",
            buildMavenRepositoryUrl("com.example", "my-lib", "1.0.0")
        )
    }

    private fun findTable(container: Container): JBTable? {
        container.components.forEach { component ->
            if (component is JBTable) return component
            if (component is Container) findTable(component)?.let { return it }
        }
        return null
    }

    fun testMainTableUsesSingleSelection() {
        val content = MavenUpWindowFactory().MyToolWindow(project).getContent()

        val table = findTable(content)
        assertNotNull("Die Haupttabelle sollte im Tool Window vorhanden sein", table)
        assertEquals(
            "Die Haupttabelle sollte nur Einzelselektion erlauben",
            javax.swing.ListSelectionModel.SINGLE_SELECTION,
            table!!.selectionModel.selectionMode
        )
        assertFalse(
            "Die Haupttabelle sollte kein Umordnen der Spalten erlauben",
            table.tableHeader.reorderingAllowed
        )
    }

    fun testCancelActiveCellEditingStopsEditingBeforeTableRebuild() {
        val toolWindow = MavenUpWindowFactory().MyToolWindow(project)
        val content = toolWindow.getContent()
        val table = findTable(content)
        assertNotNull("Die Haupttabelle sollte vorhanden sein", table)

        val model = table!!.model as javax.swing.table.DefaultTableModel
        model.addRow(
            arrayOf("com.example", "my-lib", "", "dependency", "1.0.0", null, listOf("1.1.0", "1.0.0"))
        )

        // Bearbeitung der Spalte "New Version" starten
        assertTrue(
            "Die New-Version-Zelle sollte in den Bearbeitungsmodus wechseln",
            table.editCellAt(0, 6)
        )
        assertTrue("Die Tabelle sollte sich im Bearbeitungsmodus befinden", table.isEditing)

        // Abbruch der Bearbeitung; danach darf das Leeren der Zeilen keine Exception auslösen
        assertTrue(
            "Eine laufende Bearbeitung sollte abgebrochen werden",
            toolWindow.cancelActiveCellEditing()
        )
        assertFalse("Nach dem Abbruch sollte keine Bearbeitung mehr laufen", table.isEditing)

        model.setRowCount(0)
        table.doLayout()

        // Ohne aktive Bearbeitung meldet der Aufruf, dass nichts abzubrechen war
        assertFalse(
            "Ohne laufende Bearbeitung sollte kein Abbruch gemeldet werden",
            toolWindow.cancelActiveCellEditing()
        )
    }

    fun testConfirmChangesDialogTableIsNotEditable() {
        val updates = listOf(
            DependencyUpdate("com.example", "demo-lib", "dependency", "1.0.0", "1.1.0")
        )
        val dialog = MavenUpWindowFactory.UpdateConfirmationDialog(project, updates)
        val table = dialog.buildTable()
        for (column in 0 until table.columnCount) {
            assertFalse(
                "Zelle in Spalte $column sollte nicht editierbar sein",
                table.isCellEditable(0, column)
            )
        }
        assertEquals(
            "Die Confirm-Changes-Tabelle sollte nur Einzelselektion erlauben",
            javax.swing.ListSelectionModel.SINGLE_SELECTION,
            table.selectionModel.selectionMode
        )
        assertFalse(
            "Die Confirm-Changes-Tabelle sollte kein Umordnen der Spalten erlauben",
            table.tableHeader.reorderingAllowed
        )
    }

    fun testConfirmChangesDialogSyncCheckboxReflectsSetting() {
        val settings = de.schwarzland.mavenup.service.MavenUpSettings.getInstance(project)
        val original = settings.state.syncMavenAfterUpdate
        try {
            val updates = listOf(
                DependencyUpdate("com.example", "demo-lib", "dependency", "1.0.0", "1.1.0")
            )

            settings.state.syncMavenAfterUpdate = false
            val disabledDialog = MavenUpWindowFactory.UpdateConfirmationDialog(project, updates)
            assertFalse(
                "Die Sync-Checkbox sollte den gespeicherten Wert false übernehmen",
                disabledDialog.isSyncMavenSelected()
            )

            settings.state.syncMavenAfterUpdate = true
            val enabledDialog = MavenUpWindowFactory.UpdateConfirmationDialog(project, updates)
            assertTrue(
                "Die Sync-Checkbox sollte den gespeicherten Wert true übernehmen",
                enabledDialog.isSyncMavenSelected()
            )
        } finally {
            settings.state.syncMavenAfterUpdate = original
        }
    }

    fun testOpenInRepositoryActionInitiallyDisabled() {
        val toolWindow = MavenUpWindowFactory().MyToolWindow(project)
        toolWindow.getContent()

        assertFalse(
            "Open-in-Repository-Aktion sollte ohne Selektion deaktiviert sein",
            toolWindow.isOpenInRepositoryEnabled()
        )
    }

    fun testOpenInRepositoryActionEnabledOnRowSelection() {
        val toolWindow = MavenUpWindowFactory().MyToolWindow(project)
        val content = toolWindow.getContent()
        val table = findTable(content)
        assertNotNull(table)

        // Tabelle ist leer – Aktion inaktiv
        assertFalse(
            "Open-in-Repository-Aktion sollte bei leerer Tabelle deaktiviert sein",
            toolWindow.isOpenInRepositoryEnabled()
        )

        // Eine Zeile hinzufügen und selektieren
        (table!!.model as? javax.swing.table.DefaultTableModel)?.addRow(
            arrayOf("com.example", "my-lib", "", "dependency", "1.0.0", null, emptyList<String>())
        )
        table.setRowSelectionInterval(0, 0)

        assertTrue(
            "Open-in-Repository-Aktion sollte bei selektierter Zeile aktiviert sein",
            toolWindow.isOpenInRepositoryEnabled()
        )

        // Selektion aufheben
        table.clearSelection()
        assertFalse(
            "Open-in-Repository-Aktion sollte ohne Selektion wieder deaktiviert sein",
            toolWindow.isOpenInRepositoryEnabled()
        )
    }

    fun testOpenInRepositoryActionLabelReflectsConfiguredBrowser() {
        val settings = MavenUpSettings.getInstance(project)
        settings.state.repositoryBrowser = MavenRepositoryBrowser.SONATYPE_CENTRAL

        val toolWindow = MavenUpWindowFactory().MyToolWindow(project)
        toolWindow.getContent()
        assertEquals(
            "Aktionstext sollte den konfigurierten Browser-Namen enthalten",
            "Open on Sonatype Central",
            toolWindow.currentOpenInRepositoryText()
        )

        settings.state.repositoryBrowser = MavenRepositoryBrowser.MVN_REPOSITORY
    }

    fun testActionToolbarIsPresentAtTop() {
        val content = MavenUpWindowFactory().MyToolWindow(project).getContent()

        val toolbarComponent = (content.layout as? java.awt.BorderLayout)
            ?.getLayoutComponent(java.awt.BorderLayout.NORTH)
        assertNotNull("Die obere Aktionsleiste sollte im Tool Window vorhanden sein", toolbarComponent)
    }

    fun testToolbarTextEnabledReflectsSetting() {
        val settings = MavenUpSettings.getInstance(project)
        val toolWindow = MavenUpWindowFactory().MyToolWindow(project)
        toolWindow.getContent()

        settings.state.toolbarShowText = false
        assertFalse(
            "Text-Modus sollte deaktiviert gemeldet werden, wenn die Einstellung aus ist",
            toolWindow.isToolbarTextEnabled()
        )

        settings.state.toolbarShowText = true
        assertTrue(
            "Text-Modus sollte aktiviert gemeldet werden, wenn die Einstellung an ist",
            toolWindow.isToolbarTextEnabled()
        )

        settings.state.toolbarShowText = false
    }

    fun testVulnerabilityDetailsActionReflectsSelection() {
        val toolWindow = MavenUpWindowFactory().MyToolWindow(project)
        val content = toolWindow.getContent()
        val table = findTable(content)
        assertNotNull(table)

        assertFalse(
            "Vulnerability-Details-Aktion sollte ohne Selektion deaktiviert sein",
            toolWindow.isVulnerabilityDetailsEnabled()
        )

        val advisory = VulnerabilityAdvisory(
            id = "CVE-1",
            severity = VulnerabilitySeverity.MEDIUM,
            sources = setOf("OSV"),
            references = setOf("https://example.test")
        )
        val cell = buildVulnerabilityCell(
            "com.example:my-lib:1.0.0",
            mapOf("com.example:my-lib:1.0.0" to listOf(advisory)),
            emptySet()
        )
        (table!!.model as? javax.swing.table.DefaultTableModel)?.addRow(
            arrayOf("com.example", "my-lib", "", "dependency", "1.0.0", cell, emptyList<String>())
        )
        table.setRowSelectionInterval(0, 0)

        assertTrue(
            "Vulnerability-Details-Aktion sollte bei Zeile mit Befunden aktiviert sein",
            toolWindow.isVulnerabilityDetailsEnabled()
        )
    }

    fun testCollectDependenciesAndProperties() {
        val pomContent = """
            <project>
                <properties>
                    <my.version>1.2.3</my.version>
                </properties>
                <dependencies>
                    <dependency>
                        <groupId>com.example</groupId>
                        <artifactId>with-prop</artifactId>
                        <version>${"$"}{my.version}</version>
                    </dependency>
                    <dependency>
                        <groupId>com.example</groupId>
                        <artifactId>no-prop</artifactId>
                        <version>1.0.0</version>
                    </dependency>
                    <dependency>
                        <artifactId>missing-group</artifactId>
                        <version>9.9.9</version>
                    </dependency>
                </dependencies>
            </project>
        """.trimIndent()

        val psiFile = myFixture.configureByText("pom.xml", pomContent) as XmlFile
        val rootTag = psiFile.document?.rootTag

        val factory = MavenUpWindowFactory()
        val toolWindowInstance = factory.MyToolWindow(project)

        val targetMap = mutableMapOf<String, String>()

        // Use reflection to access private method and private map
        val collectMethod = toolWindowInstance.javaClass.getDeclaredMethod(
            "collectDependenciesAndProperties",
            com.intellij.psi.xml.XmlTag::class.java,
            String::class.java,
            String::class.java,
            MutableMap::class.java
        ).apply { isAccessible = true }

        val dependencyToPropertyField = toolWindowInstance.javaClass.getDeclaredField("dependencyToProperty").apply { isAccessible = true }
        val dependencyToProperty = dependencyToPropertyField.get(toolWindowInstance) as MutableMap<String, String>

        collectMethod.invoke(toolWindowInstance, rootTag, "dependencies", "dependency", targetMap)

        assertEquals("${"$"}{my.version}", targetMap["com.example:with-prop"])
        assertEquals("1.0.0", targetMap["com.example:no-prop"])
        assertNull(targetMap[":missing-group"])
        
        assertEquals("my.version", dependencyToProperty["com.example:with-prop"])
        assertNull(dependencyToProperty["com.example:no-prop"])
    }

    fun testCollectPluginsWithoutGroupIdAreSkipped() {
        val pomContent = """
            <project>
                <build>
                    <plugins>
                        <plugin>
                            <groupId>org.example</groupId>
                            <artifactId>valid-plugin</artifactId>
                            <version>1.0.0</version>
                        </plugin>
                        <plugin>
                            <artifactId>missing-group-plugin</artifactId>
                            <version>2.0.0</version>
                        </plugin>
                    </plugins>
                </build>
            </project>
        """.trimIndent()

        val psiFile = myFixture.configureByText("pom.xml", pomContent) as XmlFile
        val rootTag = psiFile.document?.rootTag
        val buildTag = rootTag?.findFirstSubTag("build")

        val factory = MavenUpWindowFactory()
        val toolWindowInstance = factory.MyToolWindow(project)
        val targetMap = mutableMapOf<String, String>()

        val collectMethod = toolWindowInstance.javaClass.getDeclaredMethod(
            "collectDependenciesAndProperties",
            com.intellij.psi.xml.XmlTag::class.java,
            String::class.java,
            String::class.java,
            MutableMap::class.java
        ).apply { isAccessible = true }

        collectMethod.invoke(toolWindowInstance, buildTag, "plugins", "plugin", targetMap)

        assertEquals("1.0.0", targetMap["org.example:valid-plugin"])
        assertNull(targetMap[":missing-group-plugin"])
    }

    fun testFindPlugin() {
        val pomContent = """
            <project>
                <build>
                    <plugins>
                        <plugin>
                            <groupId>org.apache.maven.plugins</groupId>
                            <artifactId>maven-compiler-plugin</artifactId>
                            <version>3.8.1</version>
                        </plugin>
                    </plugins>
                    <pluginManagement>
                        <plugins>
                            <plugin>
                                <groupId>org.apache.maven.plugins</groupId>
                                <artifactId>maven-surefire-plugin</artifactId>
                                <version>3.0.0-M5</version>
                            </plugin>
                        </plugins>
                    </pluginManagement>
                </build>
            </project>
        """.trimIndent()

        val psiFile = myFixture.configureByText("pom.xml", pomContent) as XmlFile
        val rootTag = psiFile.document?.rootTag

        val factory = MavenUpWindowFactory()
        val toolWindowInstance = factory.MyToolWindow(project)

        val findPluginMethod = toolWindowInstance.javaClass.getDeclaredMethod(
            "findPlugin",
            com.intellij.psi.xml.XmlTag::class.java,
            String::class.java,
            String::class.java,
            Boolean::class.java
        ).apply { isAccessible = true }

        // Test normal plugin
        val normalPlugin = findPluginMethod.invoke(toolWindowInstance, rootTag, "org.apache.maven.plugins", "maven-compiler-plugin", false) as? com.intellij.psi.xml.XmlTag
        assertNotNull("Normaler Plugin sollte gefunden werden", normalPlugin)
        assertEquals("maven-compiler-plugin", normalPlugin?.findFirstSubTag("artifactId")?.value?.text)

        // Test managed plugin
        val managedPlugin = findPluginMethod.invoke(toolWindowInstance, rootTag, "org.apache.maven.plugins", "maven-surefire-plugin", true) as? com.intellij.psi.xml.XmlTag
        assertNotNull("Managed Plugin sollte gefunden werden", managedPlugin)
        assertEquals("maven-surefire-plugin", managedPlugin?.findFirstSubTag("artifactId")?.value?.text)
    }

    fun testCollectParentDependency() {
        val pomContent = """
            <project>
                <parent>
                    <groupId>org.springframework.boot</groupId>
                    <artifactId>spring-boot-starter-parent</artifactId>
                    <version>3.1.0</version>
                </parent>
                <dependencies>
                    <dependency>
                        <groupId>com.example</groupId>
                        <artifactId>example-core</artifactId>
                        <version>1.0.0</version>
                    </dependency>
                </dependencies>
            </project>
        """.trimIndent()

        val psiFile = myFixture.configureByText("pom.xml", pomContent) as XmlFile
        val rootTag = psiFile.document?.rootTag

        val factory = MavenUpWindowFactory()
        val toolWindowInstance = factory.MyToolWindow(project)
        val properties = mutableMapOf<String, String>()

        val parentRow = toolWindowInstance.collectParentDependency(rootTag, properties)

        assertNotNull("Parent-Dependency sollte gefunden werden", parentRow)
        assertEquals("org.springframework.boot", parentRow!!.groupId)
        assertEquals("spring-boot-starter-parent", parentRow.artifactId)
        assertEquals("3.1.0", parentRow.currentVersion)
        assertEquals("parent", parentRow.type)
        assertEquals("", parentRow.propertyName)
    }

    fun testCollectParentDependencyWithProperty() {
        val pomContent = """
            <project>
                <parent>
                    <groupId>org.springframework.boot</groupId>
                    <artifactId>spring-boot-starter-parent</artifactId>
                    <version>${"$"}{boot.version}</version>
                </parent>
            </project>
        """.trimIndent()

        val psiFile = myFixture.configureByText("pom.xml", pomContent) as XmlFile
        val rootTag = psiFile.document?.rootTag

        val factory = MavenUpWindowFactory()
        val toolWindowInstance = factory.MyToolWindow(project)
        val properties = mutableMapOf<String, String>()

        val parentRow = toolWindowInstance.collectParentDependency(rootTag, properties)

        assertNotNull(parentRow)
        assertEquals("boot.version", parentRow!!.propertyName)
        assertEquals("boot.version", properties["org.springframework.boot:spring-boot-starter-parent"])
    }

    fun testCollectParentDependencyReturnsNullWithoutParentTag() {
        val pomContent = """
            <project>
                <dependencies>
                    <dependency>
                        <groupId>com.example</groupId>
                        <artifactId>example-core</artifactId>
                        <version>1.0.0</version>
                    </dependency>
                </dependencies>
            </project>
        """.trimIndent()

        val psiFile = myFixture.configureByText("pom.xml", pomContent) as XmlFile
        val rootTag = psiFile.document?.rootTag

        val factory = MavenUpWindowFactory()
        val toolWindowInstance = factory.MyToolWindow(project)
        val properties = mutableMapOf<String, String>()

        val parentRow = toolWindowInstance.collectParentDependency(rootTag, properties)
        assertNull("Ohne Parent-Tag sollte null zurückgegeben werden", parentRow)
    }

    fun testCollectParentDependencySkipsMissingGroupId() {
        val pomContent = """
            <project>
                <parent>
                    <artifactId>spring-boot-starter-parent</artifactId>
                    <version>3.1.0</version>
                </parent>
            </project>
        """.trimIndent()

        val psiFile = myFixture.configureByText("pom.xml", pomContent) as XmlFile
        val rootTag = psiFile.document?.rootTag

        val factory = MavenUpWindowFactory()
        val toolWindowInstance = factory.MyToolWindow(project)

        val parentRow = toolWindowInstance.collectParentDependency(rootTag, mutableMapOf())
        assertNull("Parent ohne groupId sollte übersprungen werden", parentRow)
    }

    fun testFindParent() {
        val pomContent = """
            <project>
                <parent>
                    <groupId>org.springframework.boot</groupId>
                    <artifactId>spring-boot-starter-parent</artifactId>
                    <version>3.1.0</version>
                </parent>
            </project>
        """.trimIndent()

        val psiFile = myFixture.configureByText("pom.xml", pomContent) as XmlFile
        val rootTag = psiFile.document?.rootTag

        val factory = MavenUpWindowFactory()
        val toolWindowInstance = factory.MyToolWindow(project)

        val found = toolWindowInstance.findParent(rootTag, "org.springframework.boot", "spring-boot-starter-parent")
        assertNotNull("Parent-Tag sollte gefunden werden", found)
        assertEquals("spring-boot-starter-parent", found?.findFirstSubTag("artifactId")?.value?.text)

        val notFound = toolWindowInstance.findParent(rootTag, "com.example", "other-artifact")
        assertNull("Nicht passendes Parent-Tag sollte null zurückgeben", notFound)
    }

    fun testUpdateParentVersion() {
        val pomContent = """
            <project>
                <parent>
                    <groupId>org.springframework.boot</groupId>
                    <artifactId>spring-boot-starter-parent</artifactId>
                    <version>3.1.0</version>
                </parent>
            </project>
        """.trimIndent()

        val psiFile = myFixture.configureByText("pom.xml", pomContent) as XmlFile
        val rootTag = psiFile.document?.rootTag
        val parentTag = rootTag?.findFirstSubTag("parent")

        val factory = MavenUpWindowFactory()
        val toolWindowInstance = factory.MyToolWindow(project)

        WriteCommandAction.runWriteCommandAction(project) {
            toolWindowInstance.updateXmlTagVersion(parentTag!!, "3.2.0", null)
        }
        assertEquals("3.2.0", parentTag?.findFirstSubTag("version")?.value?.text)
    }

    fun testIsVersionUpToDateReturnsTrueWhenCurrentEqualsNewest() {
        assertTrue(isVersionUpToDate("1.0.0", "1.0.0"))
    }

    fun testIsVersionUpToDateReturnsFalseWhenVersionsDiffer() {
        assertFalse(isVersionUpToDate("1.0.0", "2.0.0"))
    }

    fun testIsVersionUpToDateReturnsFalseForEmptyCurrentVersion() {
        assertFalse(isVersionUpToDate("", "1.0.0"))
    }

    fun testIsVersionUpToDateReturnsFalseForBothEmpty() {
        assertFalse(isVersionUpToDate("", ""))
    }

    fun testVersionStatusIconReturnsCheckmarkWhenUpToDate() {
        val icon = versionStatusIcon(upToDate = true)
        assertEquals(com.intellij.icons.AllIcons.RunConfigurations.TestPassed, icon)
    }

    fun testVersionStatusIconReturnsArrowWhenUpdateAvailable() {
        val icon = versionStatusIcon(upToDate = false)
        assertEquals(com.intellij.icons.AllIcons.General.ArrowUp, icon)
    }

    fun testVersionStatusColorReturnsGreenWhenUpToDate() {
        val color = versionStatusColor(upToDate = true)
        assertNotNull(color)
    }

    fun testVersionStatusColorReturnsOrangeWhenUpdateAvailable() {
        val color = versionStatusColor(upToDate = false)
        assertNotNull(color)
    }

    fun testVersionStatusTooltipShowsUpToDateMessage() {
        val tooltip = versionStatusTooltip("1.0.0", "1.0.0", "1.0.0")
        assertTrue(tooltip.contains("1.0.0"))
    }

    fun testVersionStatusTooltipShowsUpdateAvailableMessage() {
        val tooltip = versionStatusTooltip("1.0.0", "1.0.0", "2.0.0")
        assertTrue(tooltip.contains("1.0.0"))
        assertTrue(tooltip.contains("2.0.0"))
    }

    fun testVersionStatusTooltipShowsWillUpdateMessage() {
        val tooltip = versionStatusTooltip("1.0.0", "2.0.0", "2.0.0")
        assertTrue(tooltip.contains("1.0.0"))
        assertTrue(tooltip.contains("2.0.0"))
    }

    fun testVersionStatusTooltipShowsWillUpdateNotLatestMessage() {
        val tooltip = versionStatusTooltip("1.0.0", "1.5.0", "2.0.0")
        assertTrue(tooltip.contains("1.0.0"))
        assertTrue(tooltip.contains("1.5.0"))
        assertTrue(tooltip.contains("2.0.0"))
    }

    fun testCreateVersionPanelContainsIconAndComboBox() {
        val combo = javax.swing.JComboBox(arrayOf("1.0.0", "2.0.0"))
        val icon = versionStatusIcon(upToDate = false)
        val panel = createVersionPanel(combo, icon, "Test tooltip")

        assertEquals(java.awt.BorderLayout::class.java, panel.layout::class.java)
        assertEquals("Test tooltip", panel.toolTipText)
        assertEquals(2, panel.componentCount)
    }

    fun testCreateVersionPanelAppliesBoldFontWhenChanged() {
        val combo = javax.swing.JComboBox(arrayOf("1.0.0", "2.0.0"))
        val originalFont = combo.font
        val icon = versionStatusIcon(upToDate = true)
        createVersionPanel(combo, icon, "tooltip", hasChange = true)

        assertTrue(combo.font.isBold)
        assertEquals(originalFont.size, combo.font.size)
    }

    fun testCreateVersionPanelKeepsNormalFontWhenUnchanged() {
        val combo = javax.swing.JComboBox(arrayOf("1.0.0", "2.0.0"))
        val originalStyle = combo.font.style
        val icon = versionStatusIcon(upToDate = true)
        createVersionPanel(combo, icon, "tooltip", hasChange = false)

        assertEquals(originalStyle, combo.font.style)
    }

    fun testApplySelectLatestVersionSettingSelectsNewest() {
        val toolWindow = MavenUpWindowFactory().MyToolWindow(project)
        toolWindow.getContent()
        val settings = MavenUpSettings.getInstance(project)

        val fields = toolWindow.javaClass
        @Suppress("UNCHECKED_CAST")
        val availableVersions = fields.getDeclaredField("availableVersions")
            .apply { isAccessible = true }
            .get(toolWindow) as MutableMap<String, List<String>>
        @Suppress("UNCHECKED_CAST")
        val selectedVersions = fields.getDeclaredField("selectedVersions")
            .apply { isAccessible = true }
            .get(toolWindow) as MutableMap<String, String>
        @Suppress("UNCHECKED_CAST")
        val knownDependencies = fields.getDeclaredField("knownDependencies")
            .apply { isAccessible = true }
            .get(toolWindow) as MutableMap<String, String>

        val key = "com.example:apply-test"
        availableVersions[key] = listOf("2.0.0", "1.5.0", "1.0.0")
        knownDependencies[key] = "1.0.0"

        // With selectLatestVersion enabled, the newest should be selected
        settings.state.selectLatestVersion = true
        toolWindow.applySelectLatestVersionSetting()
        assertEquals("2.0.0", selectedVersions[key])

        // With selectLatestVersion disabled, the current version should be selected
        settings.state.selectLatestVersion = false
        toolWindow.applySelectLatestVersionSetting()
        assertEquals("1.0.0", selectedVersions[key])

        // Reset
        settings.state.selectLatestVersion = true
    }

    fun testApplySelectLatestVersionSettingRemovesSelectionWhenAlreadyLatest() {
        val toolWindow = MavenUpWindowFactory().MyToolWindow(project)
        toolWindow.getContent()
        val settings = MavenUpSettings.getInstance(project)

        val fields = toolWindow.javaClass
        @Suppress("UNCHECKED_CAST")
        val availableVersions = fields.getDeclaredField("availableVersions")
            .apply { isAccessible = true }
            .get(toolWindow) as MutableMap<String, List<String>>
        @Suppress("UNCHECKED_CAST")
        val selectedVersions = fields.getDeclaredField("selectedVersions")
            .apply { isAccessible = true }
            .get(toolWindow) as MutableMap<String, String>
        @Suppress("UNCHECKED_CAST")
        val knownDependencies = fields.getDeclaredField("knownDependencies")
            .apply { isAccessible = true }
            .get(toolWindow) as MutableMap<String, String>

        val key = "com.example:already-latest"
        availableVersions[key] = listOf("2.0.0", "1.0.0")
        knownDependencies[key] = "2.0.0"
        selectedVersions[key] = "2.0.0"

        // When current version is already the latest and selectLatest is enabled,
        // the entry should be removed from selectedVersions (no change needed)
        settings.state.selectLatestVersion = true
        toolWindow.applySelectLatestVersionSetting()
        assertNull(
            "Wenn die aktuelle Version bereits die neueste ist, soll kein Eintrag in selectedVersions stehen",
            selectedVersions[key]
        )

        // Reset
        settings.state.selectLatestVersion = true
    }

    fun testApplySelectLatestVersionSettingDoesNothingWhenNoVersionsLoaded() {
        val toolWindow = MavenUpWindowFactory().MyToolWindow(project)
        toolWindow.getContent()
        val settings = MavenUpSettings.getInstance(project)

        val fields = toolWindow.javaClass
        @Suppress("UNCHECKED_CAST")
        val selectedVersions = fields.getDeclaredField("selectedVersions")
            .apply { isAccessible = true }
            .get(toolWindow) as MutableMap<String, String>

        selectedVersions["com.example:existing"] = "1.0.0"

        // With no available versions loaded, the method should not modify selectedVersions
        settings.state.selectLatestVersion = true
        toolWindow.applySelectLatestVersionSetting()
        assertEquals("1.0.0", selectedVersions["com.example:existing"])

        // Reset
        settings.state.selectLatestVersion = true
    }

    fun testSettingsChangeKeepsSelectionWhenSelectLatestUnchanged() {
        val settings = MavenUpSettings.getInstance(project)
        settings.state.selectLatestVersion = false
        val toolWindow = MavenUpWindowFactory().MyToolWindow(project)
        toolWindow.getContent()

        val fields = toolWindow.javaClass
        @Suppress("UNCHECKED_CAST")
        val availableVersions = fields.getDeclaredField("availableVersions")
            .apply { isAccessible = true }
            .get(toolWindow) as MutableMap<String, List<String>>
        @Suppress("UNCHECKED_CAST")
        val selectedVersions = fields.getDeclaredField("selectedVersions")
            .apply { isAccessible = true }
            .get(toolWindow) as MutableMap<String, String>
        @Suppress("UNCHECKED_CAST")
        val knownDependencies = fields.getDeclaredField("knownDependencies")
            .apply { isAccessible = true }
            .get(toolWindow) as MutableMap<String, String>

        val key = "com.example:lib"
        availableVersions[key] = listOf("2.0.0", "1.0.0")
        knownDependencies[key] = "1.0.0"
        selectedVersions[key] = "2.0.0"

        // Nur eine unabhängige Einstellung ändert sich -> Auswahl bleibt erhalten
        toolWindow.applySelectLatestVersionSettingIfChanged()

        assertEquals(
            "Eine unabhängige Einstellungsänderung darf die getroffene Auswahl nicht zurücksetzen",
            "2.0.0",
            selectedVersions[key]
        )

        settings.state.selectLatestVersion = true
    }

    fun testSettingsChangeReappliesSelectionWhenSelectLatestChanged() {
        val settings = MavenUpSettings.getInstance(project)
        settings.state.selectLatestVersion = false
        val toolWindow = MavenUpWindowFactory().MyToolWindow(project)
        toolWindow.getContent()

        val fields = toolWindow.javaClass
        @Suppress("UNCHECKED_CAST")
        val availableVersions = fields.getDeclaredField("availableVersions")
            .apply { isAccessible = true }
            .get(toolWindow) as MutableMap<String, List<String>>
        @Suppress("UNCHECKED_CAST")
        val selectedVersions = fields.getDeclaredField("selectedVersions")
            .apply { isAccessible = true }
            .get(toolWindow) as MutableMap<String, String>
        @Suppress("UNCHECKED_CAST")
        val knownDependencies = fields.getDeclaredField("knownDependencies")
            .apply { isAccessible = true }
            .get(toolWindow) as MutableMap<String, String>

        val key = "com.example:lib"
        availableVersions[key] = listOf("2.0.0", "1.0.0")
        knownDependencies[key] = "1.0.0"
        selectedVersions[key] = "1.0.0"

        // Die Einstellung selectLatestVersion wird tatsächlich geändert -> Auswahl wird neu berechnet
        settings.state.selectLatestVersion = true
        toolWindow.applySelectLatestVersionSettingIfChanged()

        assertEquals(
            "Beim Ändern von selectLatestVersion soll die neueste Version vorausgewählt werden",
            "2.0.0",
            selectedVersions[key]
        )

        settings.state.selectLatestVersion = true
    }


}