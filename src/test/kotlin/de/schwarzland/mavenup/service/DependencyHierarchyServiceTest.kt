package de.schwarzland.mavenup.service

import com.intellij.psi.xml.XmlFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.schwarzland.mavenup.model.DependencyHierarchyNode
import de.schwarzland.mavenup.model.DependencyHierarchyNodeType
import org.jetbrains.idea.maven.model.MavenArtifact
import org.jetbrains.idea.maven.model.MavenArtifactNode

/**
 * Tests für den [DependencyHierarchyService].
 */
class DependencyHierarchyServiceTest : BasePlatformTestCase() {

    fun testCollectParentPomAndBomImport() {
        val pomContent = """
            <project>
                <parent>
                    <groupId>org.springframework.boot</groupId>
                    <artifactId>spring-boot-starter-parent</artifactId>
                    <version>${"$"}{spring-boot.version}</version>
                </parent>
                <properties>
                    <spring-boot.version>3.2.0</spring-boot.version>
                </properties>
                <dependencyManagement>
                    <dependencies>
                        <dependency>
                            <groupId>org.springframework.boot</groupId>
                            <artifactId>spring-boot-dependencies</artifactId>
                            <version>3.2.0</version>
                            <type>pom</type>
                            <scope>import</scope>
                        </dependency>
                    </dependencies>
                </dependencyManagement>
            </project>
        """.trimIndent()

        val psiFile = myFixture.configureByText("pom.xml", pomContent) as XmlFile
        val rootTag = psiFile.document?.rootTag
        val service = DependencyHierarchyService(project)

        val projectNode = DependencyHierarchyNode(
            type = DependencyHierarchyNodeType.PROJECT,
            groupId = "com.example",
            artifactId = "test-app",
            pomFile = psiFile.virtualFile
        )

        service.collectDependencyManagementAndDirect(
            rootTag,
            psiFile.virtualFile,
            "com.fasterxml.jackson.core",
            "jackson-databind",
            mapOf("spring-boot.version" to "3.2.0"),
            projectNode
        )

        val parentNode = projectNode.children.find { it.type == DependencyHierarchyNodeType.PARENT_POM }
        assertNotNull(parentNode)
        assertEquals("org.springframework.boot", parentNode!!.groupId)
        assertEquals("spring-boot-starter-parent", parentNode.artifactId)
        assertEquals("3.2.0", parentNode.version)
        assertEquals("spring-boot.version", parentNode.propertyName)

        val bomNode = projectNode.children.find { it.type == DependencyHierarchyNodeType.BOM_IMPORT }
        assertNotNull(bomNode)
        assertEquals("org.springframework.boot", bomNode!!.groupId)
        assertEquals("spring-boot-dependencies", bomNode.artifactId)
        assertEquals("3.2.0", bomNode.version)
        assertEquals("import", bomNode.scope)
    }

    fun testCollectDependencyManagementAndDirectDependency() {
        val pomContent = """
            <project>
                <properties>
                    <jackson.version>2.15.2</jackson.version>
                </properties>
                <dependencyManagement>
                    <dependencies>
                        <dependency>
                            <groupId>com.fasterxml.jackson.core</groupId>
                            <artifactId>jackson-databind</artifactId>
                            <version>${"$"}{jackson.version}</version>
                            <scope>compile</scope>
                        </dependency>
                    </dependencies>
                </dependencyManagement>
                <dependencies>
                    <dependency>
                        <groupId>com.fasterxml.jackson.core</groupId>
                        <artifactId>jackson-databind</artifactId>
                    </dependency>
                </dependencies>
            </project>
        """.trimIndent()

        val psiFile = myFixture.configureByText("pom.xml", pomContent) as XmlFile
        val rootTag = psiFile.document?.rootTag
        val service = DependencyHierarchyService(project)

        val projectNode = DependencyHierarchyNode(
            type = DependencyHierarchyNodeType.PROJECT,
            groupId = "com.example",
            artifactId = "test-app",
            pomFile = psiFile.virtualFile
        )

        service.collectDependencyManagementAndDirect(
            rootTag,
            psiFile.virtualFile,
            "com.fasterxml.jackson.core",
            "jackson-databind",
            mapOf("jackson.version" to "2.15.2"),
            projectNode
        )

        val dmNode = projectNode.children.find { it.type == DependencyHierarchyNodeType.DEPENDENCY_MANAGEMENT }
        assertNotNull(dmNode)
        assertEquals("com.fasterxml.jackson.core", dmNode!!.groupId)
        assertEquals("jackson-databind", dmNode.artifactId)
        assertEquals("2.15.2", dmNode.version)
        assertEquals("jackson.version", dmNode.propertyName)
        assertTrue(dmNode.isManaged)

        val directNode = projectNode.children.find { it.type == DependencyHierarchyNodeType.DIRECT_DEPENDENCY }
        assertNotNull(directNode)
        assertEquals("com.fasterxml.jackson.core", directNode!!.groupId)
        assertEquals("jackson-databind", directNode.artifactId)
        assertTrue(directNode.isManaged)
    }

    fun testCollectPluginManagementAndDirect() {
        val pomContent = """
            <project>
                <build>
                    <pluginManagement>
                        <plugins>
                            <plugin>
                                <groupId>org.apache.maven.plugins</groupId>
                                <artifactId>maven-compiler-plugin</artifactId>
                                <version>3.11.0</version>
                            </plugin>
                        </plugins>
                    </pluginManagement>
                    <plugins>
                        <plugin>
                            <artifactId>maven-compiler-plugin</artifactId>
                        </plugin>
                    </plugins>
                </build>
            </project>
        """.trimIndent()

        val psiFile = myFixture.configureByText("pom.xml", pomContent) as XmlFile
        val rootTag = psiFile.document?.rootTag
        val service = DependencyHierarchyService(project)

        val projectNode = DependencyHierarchyNode(
            type = DependencyHierarchyNodeType.PROJECT,
            groupId = "com.example",
            artifactId = "test-app",
            pomFile = psiFile.virtualFile
        )

        service.collectPluginManagementAndDirect(
            rootTag,
            psiFile.virtualFile,
            "org.apache.maven.plugins",
            "maven-compiler-plugin",
            emptyMap(),
            projectNode
        )

        assertEquals(2, projectNode.children.size)

        val pmNode = projectNode.children.find { it.type == DependencyHierarchyNodeType.PLUGIN_MANAGEMENT }
        assertNotNull(pmNode)
        assertEquals("org.apache.maven.plugins", pmNode!!.groupId)
        assertEquals("maven-compiler-plugin", pmNode.artifactId)
        assertEquals("3.11.0", pmNode.version)
        assertTrue(pmNode.isManaged)

        val directPluginNode = projectNode.children.find { it.type == DependencyHierarchyNodeType.DIRECT_PLUGIN }
        assertNotNull(directPluginNode)
        assertEquals("org.apache.maven.plugins", directPluginNode!!.groupId)
        assertEquals("maven-compiler-plugin", directPluginNode.artifactId)
        assertTrue(directPluginNode.isManaged)
    }

    fun testFindPathsToTargetAndAttachHierarchy() {
        val targetArtifact = createArtifact("com.fasterxml.jackson.core", "jackson-databind", "2.15.2")
        val targetNode = MavenArtifactNode(null, targetArtifact, null, null, null, null, null)

        val intermediateArtifact = createArtifact("org.springframework.boot", "spring-boot-starter-json", "3.2.0")
        val intermediateNode = MavenArtifactNode(null, intermediateArtifact, null, null, null, null, null)

        val rootArtifact = createArtifact("org.springframework.boot", "spring-boot-starter-web", "3.2.0")
        val rootNode = MavenArtifactNode(null, rootArtifact, null, null, null, null, null)

        val depField = MavenArtifactNode::class.java.getDeclaredField("myDependencies").apply { isAccessible = true }
        depField.set(targetNode, mutableListOf<MavenArtifactNode>())
        depField.set(intermediateNode, mutableListOf(targetNode))
        depField.set(rootNode, mutableListOf(intermediateNode))

        val service = DependencyHierarchyService(project)
        val paths = mutableListOf<List<MavenArtifactNode>>()
        service.findPathsToTarget(rootNode, "com.fasterxml.jackson.core", "jackson-databind", emptyList(), mutableSetOf(), paths)

        assertEquals(1, paths.size)
        assertEquals(3, paths[0].size)

        val pomContent = """
            <project>
                <dependencies>
                    <dependency>
                        <groupId>org.springframework.boot</groupId>
                        <artifactId>spring-boot-starter-web</artifactId>
                        <version>${'$'}{spring.version}</version>
                    </dependency>
                </dependencies>
            </project>
        """.trimIndent()
        val psiFile = myFixture.configureByText("pom.xml", pomContent) as XmlFile
        val rootTag = psiFile.document?.rootTag
        val projectNode = DependencyHierarchyNode(
            type = DependencyHierarchyNodeType.PROJECT,
            groupId = "com.example",
            artifactId = "test-app",
            pomFile = psiFile.virtualFile
        )

        service.attachPathToHierarchy(projectNode, paths[0], psiFile.virtualFile, rootTag)

        assertEquals(1, projectNode.children.size)
        val directDep = projectNode.children[0]
        assertEquals(DependencyHierarchyNodeType.DIRECT_DEPENDENCY, directDep.type)
        assertEquals("org.springframework.boot", directDep.groupId)
        assertEquals("spring-boot-starter-web", directDep.artifactId)
        assertNotNull(directDep.xmlTag)
        assertEquals("spring.version", directDep.propertyName)

        assertEquals(1, directDep.children.size)
        val intermediateDep = directDep.children[0]
        assertEquals(DependencyHierarchyNodeType.TRANSITIVE_DEPENDENCY, intermediateDep.type)
        assertEquals("org.springframework.boot", intermediateDep.groupId)
        assertEquals("spring-boot-starter-json", intermediateDep.artifactId)

        assertEquals(1, intermediateDep.children.size)
        val targetDep = intermediateDep.children[0]
        assertEquals(DependencyHierarchyNodeType.TRANSITIVE_DEPENDENCY, targetDep.type)
        assertEquals("com.fasterxml.jackson.core", targetDep.groupId)
        assertEquals("jackson-databind", targetDep.artifactId)
        assertTrue(targetDep.isManaged)
    }

    private fun createArtifact(groupId: String, artifactId: String, version: String): MavenArtifact {
        return MavenArtifact(
            groupId,
            artifactId,
            version,
            null,
            "jar",
            null,
            "compile",
            false,
            "jar",
            null,
            null,
            true,
            false
        )
    }
}
