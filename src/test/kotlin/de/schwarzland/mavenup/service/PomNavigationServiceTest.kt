package de.schwarzland.mavenup.service

import de.schwarzland.mavenup.ui.MANAGED_PLUGIN
import de.schwarzland.mavenup.ui.MyMessageBundle
import de.schwarzland.mavenup.ui.PARENT_TYPE
import de.schwarzland.mavenup.ui.TOOLWINDOW_MY_TOOL_WINDOW_TYPE_MANAGED_DEPENDENCY
import com.intellij.psi.xml.XmlFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Testet das Auffinden von Abhängigkeits-, Parent- und Plugin-Definitionen in der `pom.xml`
 * durch den [PomNavigationService].
 */
class PomNavigationServiceTest : BasePlatformTestCase() {

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

        val pomNavigationService = PomNavigationService(project)

        // Test normal dependency
        val normalDep = pomNavigationService.findDependency(rootTag, "com.example", "example-core", false)
        assertNotNull("Normale Dependency sollte gefunden werden", normalDep)
        assertEquals("example-core", normalDep?.findFirstSubTag("artifactId")?.value?.text)

        // Test managed dependency
        val managedDep = pomNavigationService.findDependency(rootTag, "com.example", "example-managed", true)
        assertNotNull("Managed Dependency sollte gefunden werden", managedDep)
        assertEquals("example-managed", managedDep?.findFirstSubTag("artifactId")?.value?.text)
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

        val pomNavigationService = PomNavigationService(project)

        // Test normal plugin
        val normalPlugin = pomNavigationService.findPlugin(rootTag, "org.apache.maven.plugins", "maven-compiler-plugin", false)
        assertNotNull("Normaler Plugin sollte gefunden werden", normalPlugin)
        assertEquals("maven-compiler-plugin", normalPlugin?.findFirstSubTag("artifactId")?.value?.text)

        // Test managed plugin
        val managedPlugin = pomNavigationService.findPlugin(rootTag, "org.apache.maven.plugins", "maven-surefire-plugin", true)
        assertNotNull("Managed Plugin sollte gefunden werden", managedPlugin)
        assertEquals("maven-surefire-plugin", managedPlugin?.findFirstSubTag("artifactId")?.value?.text)
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

        val pomNavigationService = PomNavigationService(project)

        val found = pomNavigationService.findParent(rootTag, "org.springframework.boot", "spring-boot-starter-parent")
        assertNotNull("Parent-Tag sollte gefunden werden", found)
        assertEquals("spring-boot-starter-parent", found?.findFirstSubTag("artifactId")?.value?.text)

        val notFound = pomNavigationService.findParent(rootTag, "com.example", "other-artifact")
        assertNull("Nicht passendes Parent-Tag sollte null zurückgeben", notFound)
    }

    /**
     * Prüft die Auswahl der passenden Suchlogik anhand des übergebenen Definitionstyps.
     */
    fun testFindTargetTagForEachDefinitionType() {
        val pomContent = """
            <project>
                <parent>
                    <groupId>com.example</groupId>
                    <artifactId>parent</artifactId>
                </parent>
                <dependencies>
                    <dependency>
                        <groupId>com.example</groupId>
                        <artifactId>direct-dependency</artifactId>
                    </dependency>
                </dependencies>
                <dependencyManagement>
                    <dependencies>
                        <dependency>
                            <groupId>com.example</groupId>
                            <artifactId>managed-dependency</artifactId>
                        </dependency>
                    </dependencies>
                </dependencyManagement>
                <build>
                    <plugins>
                        <plugin>
                            <groupId>com.example</groupId>
                            <artifactId>direct-plugin</artifactId>
                        </plugin>
                    </plugins>
                    <pluginManagement>
                        <plugins>
                            <plugin>
                                <groupId>com.example</groupId>
                                <artifactId>managed-plugin</artifactId>
                            </plugin>
                        </plugins>
                    </pluginManagement>
                </build>
            </project>
        """.trimIndent()

        val rootTag = (myFixture.configureByText("pom.xml", pomContent) as XmlFile).document?.rootTag
        val service = PomNavigationService(project)
        val managedDependencyType = MyMessageBundle.message(TOOLWINDOW_MY_TOOL_WINDOW_TYPE_MANAGED_DEPENDENCY)

        assertEquals(
            "direct-dependency",
            service.findTargetTag(rootTag, "com.example", "direct-dependency", "dependency")
                ?.findFirstSubTag("artifactId")?.value?.text
        )
        assertEquals(
            "managed-dependency",
            service.findTargetTag(rootTag, "com.example", "managed-dependency", managedDependencyType)
                ?.findFirstSubTag("artifactId")?.value?.text
        )
        assertEquals(
            "parent",
            service.findTargetTag(rootTag, "com.example", "parent", PARENT_TYPE)
                ?.findFirstSubTag("artifactId")?.value?.text
        )
        assertEquals(
            "direct-plugin",
            service.findTargetTag(rootTag, "com.example", "direct-plugin", "plugin")
                ?.findFirstSubTag("artifactId")?.value?.text
        )
        assertEquals(
            "managed-plugin",
            service.findTargetTag(rootTag, "com.example", "managed-plugin", MANAGED_PLUGIN)
                ?.findFirstSubTag("artifactId")?.value?.text
        )
        assertNull(service.findTargetTag(null, "com.example", "missing", "dependency"))
    }

    fun testFindProperty() {
        val pomContent = """
            <project>
                <properties>
                    <example.version>1.0.0</example.version>
                </properties>
                <profiles>
                    <profile>
                        <id>ci</id>
                        <properties>
                            <profile.version>2.0.0</profile.version>
                        </properties>
                    </profile>
                </profiles>
            </project>
        """.trimIndent()

        val psiFile = myFixture.configureByText("pom.xml", pomContent) as XmlFile
        val rootTag = psiFile.document?.rootTag

        val pomNavigationService = PomNavigationService(project)

        val global = pomNavigationService.findProperty(rootTag, "example.version")
        assertNotNull("Globale Property sollte gefunden werden", global)
        assertEquals("1.0.0", global?.value?.text)

        val placeholder = pomNavigationService.findProperty(rootTag, "\${example.version}")
        assertNotNull("Property in Platzhalter-Schreibweise sollte gefunden werden", placeholder)
        assertEquals("1.0.0", placeholder?.value?.text)

        val fromProfile = pomNavigationService.findProperty(rootTag, "profile.version")
        assertNotNull("Property aus einem Profil sollte gefunden werden", fromProfile)
        assertEquals("2.0.0", fromProfile?.value?.text)

        assertNull("Unbekannte Property sollte null zurückgeben",
            pomNavigationService.findProperty(rootTag, "missing.version"))
        assertNull("Leerer Property-Name sollte null zurückgeben",
            pomNavigationService.findProperty(rootTag, "   "))
        assertNull("Fehlendes Root-Tag sollte null zurückgeben",
            pomNavigationService.findProperty(null, "example.version"))
    }

    fun testFindPropertyWithoutPropertiesTag() {
        val pomContent = """
            <project>
                <dependencies/>
            </project>
        """.trimIndent()

        val psiFile = myFixture.configureByText("pom.xml", pomContent) as XmlFile
        val rootTag = psiFile.document?.rootTag

        val pomNavigationService = PomNavigationService(project)

        assertNull("Ohne <properties> und <profiles> sollte null zurückgegeben werden",
            pomNavigationService.findProperty(rootTag, "example.version"))
    }

    fun testNavigateToDependencyWithTargetPomFileDoesNotFail() {
        val pomFile = myFixture.configureByText("pom.xml", "<project><dependencies/></project>").virtualFile
        val pomNavigationService = PomNavigationService(project)
        // Sollte ohne Fehler aufgerufen werden können
        pomNavigationService.navigateToDependency("com.example", "missing", "dependency", pomFile)
        pomNavigationService.navigateToProperty("some.prop", "com.example", "missing", "dependency", pomFile)
    }
}
