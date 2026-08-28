package de.schwarzland.mavenup.service

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlComment
import com.intellij.psi.xml.XmlFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.schwarzland.mavenup.model.DependencyUpdate

/**
 * Testet das Anwenden von Versions-Updates auf `pom.xml`-Tags durch den [PomUpdateService],
 * inklusive Property-basierter Versionen und Parent-Updates.
 */
class PomUpdateServiceTest : BasePlatformTestCase() {

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

        val pomUpdateService = PomUpdateService(project)

        // Update direct version
        WriteCommandAction.runWriteCommandAction(project) {
            pomUpdateService.updateXmlTagVersion(directDep!!, "1.1.0", propertiesTag)
        }
        assertEquals("1.1.0", directDep?.findFirstSubTag("version")?.value?.text)

        // Update property version
        WriteCommandAction.runWriteCommandAction(project) {
            pomUpdateService.updateXmlTagVersion(propDep!!, "1.2.0", propertiesTag)
        }
        assertEquals("${'$'}{example.version}", propDep?.findFirstSubTag("version")?.value?.text)
        assertEquals("1.2.0", propertiesTag?.findFirstSubTag("example.version")?.value?.text)
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

        val pomUpdateService = PomUpdateService(project)

        WriteCommandAction.runWriteCommandAction(project) {
            pomUpdateService.updateXmlTagVersion(parentTag!!, "3.2.0", null)
        }
        assertEquals("3.2.0", parentTag?.findFirstSubTag("version")?.value?.text)
    }

    fun testAddManagedDependencyCreatesContainersWhenMissing() {
        val pomContent = """
            <project>
                <dependencies>
                    <dependency>
                        <groupId>com.example</groupId>
                        <artifactId>direct</artifactId>
                        <version>1.0.0</version>
                    </dependency>
                </dependencies>
            </project>
        """.trimIndent()

        val psiFile = myFixture.configureByText("pom.xml", pomContent) as XmlFile
        val rootTag = psiFile.document?.rootTag!!
        val pomUpdateService = PomUpdateService(project)

        WriteCommandAction.runWriteCommandAction(project) {
            pomUpdateService.addManagedDependency(
                rootTag,
                DependencyUpdate("org.trans", "lib", "managed dependency", "1.2.3", "1.2.4", listOf("CVE-1", "GHSA-2"))
            )
        }

        val dependenciesTag = rootTag.findFirstSubTag("dependencyManagement")
            ?.findFirstSubTag("dependencies")
        val managed = dependenciesTag
            ?.findSubTags("dependency")
            ?.find { it.findFirstSubTag("artifactId")?.value?.text == "lib" }
        assertNotNull("New managed dependency should be created", managed)
        assertEquals("org.trans", managed?.findFirstSubTag("groupId")?.value?.text)
        assertEquals("1.2.4", managed?.findFirstSubTag("version")?.value?.text)

        val comment = PsiTreeUtil.findChildOfType(managed, XmlComment::class.java)
        assertNotNull("A MavenUp comment should be added inside the dependency", comment)
        val commentText = comment!!.text
        assertTrue("Comment mentions MavenUp", commentText.contains("MavenUp"))
        assertTrue("Comment lists CVE-1", commentText.contains("CVE-1"))
        assertTrue("Comment lists GHSA-2", commentText.contains("GHSA-2"))
        val groupIdTag = managed!!.findFirstSubTag("groupId")
        assertTrue(
            "Comment is placed right after the opening <dependency> tag, before groupId",
            comment.textRange.startOffset < groupIdTag!!.textRange.startOffset
        )
        assertTrue(
            "Comment starts on its own line after the opening <dependency> tag",
            Regex("<dependency>\\s*\\R\\s*<!--").containsMatchIn(managed.text)
        )
    }

    fun testAddManagedDependencyReusesExistingContainer() {
        val pomContent = """
            <project>
                <dependencyManagement>
                    <dependencies>
                        <dependency>
                            <groupId>com.example</groupId>
                            <artifactId>existing</artifactId>
                            <version>1.0.0</version>
                        </dependency>
                    </dependencies>
                </dependencyManagement>
            </project>
        """.trimIndent()

        val psiFile = myFixture.configureByText("pom.xml", pomContent) as XmlFile
        val rootTag = psiFile.document?.rootTag!!
        val pomUpdateService = PomUpdateService(project)

        WriteCommandAction.runWriteCommandAction(project) {
            pomUpdateService.addManagedDependency(
                rootTag,
                DependencyUpdate("org.trans", "lib", "managed dependency", "2.0.0", "2.0.1")
            )
        }

        val dependencies = rootTag.findFirstSubTag("dependencyManagement")
            ?.findFirstSubTag("dependencies")
            ?.findSubTags("dependency")
        assertEquals("Existing entry should be preserved and one added", 2, dependencies?.size)
        val added = dependencies?.find { it.findFirstSubTag("artifactId")?.value?.text == "lib" }
        assertEquals("2.0.1", added?.findFirstSubTag("version")?.value?.text)
    }
}
