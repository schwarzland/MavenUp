package de.schwarzland.mavenup.service

import com.intellij.psi.xml.XmlFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Testet das Erfassen von Abhängigkeiten, Plugins, Properties und Parent-Definitionen durch den
 * [RefreshSnapshotCollector] sowie das Auflösen von Versions-Platzhaltern.
 */
class RefreshSnapshotCollectorTest : BasePlatformTestCase() {

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

        val collector = RefreshSnapshotCollector(project)
        val targetMap = mutableMapOf<String, String>()
        val dependencyToProperty = mutableMapOf<String, String>()

        collector.collectDependenciesAndProperties(
            rootTag,
            "dependencies",
            "dependency",
            targetMap,
            dependencyToProperty
        )

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

        val collector = RefreshSnapshotCollector(project)
        val targetMap = mutableMapOf<String, String>()

        collector.collectDependenciesAndProperties(
            buildTag,
            "plugins",
            "plugin",
            targetMap,
            mutableMapOf()
        )

        assertEquals("1.0.0", targetMap["org.example:valid-plugin"])
        assertNull(targetMap[":missing-group-plugin"])
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

        val properties = mutableMapOf<String, String>()

        val parentRow = RefreshSnapshotCollector(project).collectParentDependency(rootTag, properties)

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

        val properties = mutableMapOf<String, String>()

        val parentRow = RefreshSnapshotCollector(project).collectParentDependency(rootTag, properties)

        assertNotNull(parentRow)
        assertEquals("boot.version", parentRow!!.propertyName)
        assertEquals("boot.version", properties["org.springframework.boot:spring-boot-starter-parent"])
    }

    fun testResolveVersionPlaceholderResolvesProperty() {
        val properties = mapOf("netty-bom.version" to "4.1.100.Final")

        assertEquals(
            "4.1.100.Final",
            RefreshSnapshotCollector(project).resolveVersionPlaceholder("\${netty-bom.version}", properties)
        )
    }

    fun testResolveVersionPlaceholderReturnsLiteralVersionUnchanged() {
        assertEquals(
            "1.2.3",
            RefreshSnapshotCollector(project).resolveVersionPlaceholder("1.2.3", mapOf("some.version" to "9.9.9"))
        )
    }

    fun testResolveVersionPlaceholderKeepsPlaceholderWhenPropertyMissing() {
        assertEquals(
            "\${unknown.version}",
            RefreshSnapshotCollector(project).resolveVersionPlaceholder("\${unknown.version}", emptyMap())
        )
    }

    fun testResolveVersionPlaceholderKeepsPlaceholderWhenPropertyBlank() {
        assertEquals(
            "\${blank.version}",
            RefreshSnapshotCollector(project).resolveVersionPlaceholder("\${blank.version}", mapOf("blank.version" to "   "))
        )
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

        val properties = mutableMapOf<String, String>()

        val parentRow = RefreshSnapshotCollector(project).collectParentDependency(rootTag, properties)
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

        val parentRow = RefreshSnapshotCollector(project).collectParentDependency(rootTag, mutableMapOf())
        assertNull("Parent ohne groupId sollte übersprungen werden", parentRow)
    }
}
