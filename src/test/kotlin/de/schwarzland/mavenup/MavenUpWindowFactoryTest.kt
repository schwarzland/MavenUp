package de.schwarzland.mavenup

import de.schwarzland.mavenup.service.MavenUpSettings
import de.schwarzland.mavenup.ui.MavenUpWindowFactory
import com.intellij.openapi.wm.RegisterToolWindowTask
import com.intellij.openapi.wm.ToolWindowAnchor
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.psi.xml.XmlFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.openapi.command.WriteCommandAction

class MavenUpWindowFactoryTest : BasePlatformTestCase() {

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

}