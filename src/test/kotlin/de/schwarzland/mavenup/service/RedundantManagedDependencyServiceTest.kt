package de.schwarzland.mavenup.service

import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.util.ProgressIndicatorBase
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.schwarzland.mavenup.model.RedundancyReason
import org.jetbrains.idea.maven.model.MavenArtifact
import org.jetbrains.idea.maven.model.MavenArtifactNode
import org.jetbrains.idea.maven.project.MavenProject

/**
 * Tests für [RedundantManagedDependencyService].
 */
class RedundantManagedDependencyServiceTest : BasePlatformTestCase() {

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
            false,
            true
        )
    }

    private fun createArtifactNode(groupId: String, artifactId: String, version: String): MavenArtifactNode {
        val artifact = createArtifact(groupId, artifactId, version)
        return MavenArtifactNode(null, artifact, null, null, null, null, null)
    }

    /**
     * Prüft, dass eine abgebrochene Prüfung die Exception weitergibt.
     */
    fun testFindRedundantDependenciesPropagatesCancellation() {
        val service = RedundantManagedDependencyService(project)
        val indicator = ProgressIndicatorBase().apply { cancel() }

        try {
            service.findRedundantManagedDependencies(null, indicator)
            fail("Expected cancellation to be thrown")
        } catch (_: ProcessCanceledException) {
            // Expected
        }
    }

    /**
     * Prüft die Erkennung von PARENT_MANAGED, wenn das übergeordnete POM dieselbe oder eine neuere Version verwaltet.
     */
    fun testParentManagedDependencyIsDetectedAsRedundant() {
        val pom = myFixture.configureByText(
            "pom.xml",
            """
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <parent>
                        <groupId>com.example</groupId>
                        <artifactId>my-parent</artifactId>
                        <version>1.0.0</version>
                    </parent>
                    <groupId>com.example</groupId>
                    <artifactId>child</artifactId>
                    <version>1.0.0</version>
                    <dependencyManagement>
                        <dependencies>
                            <dependency>
                                <groupId>org.apache.commons</groupId>
                                <artifactId>commons-lang3</artifactId>
                                <version>3.12.0</version>
                            </dependency>
                        </dependencies>
                    </dependencyManagement>
                </project>
            """.trimIndent()
        )

        val parentPom = """
            <project>
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.example</groupId>
                <artifactId>my-parent</artifactId>
                <version>1.0.0</version>
                <dependencyManagement>
                    <dependencies>
                        <dependency>
                            <groupId>org.apache.commons</groupId>
                            <artifactId>commons-lang3</artifactId>
                            <version>3.12.0</version>
                        </dependency>
                    </dependencies>
                </dependencyManagement>
            </project>
        """.trimIndent()

        val resolver = TemporaryDependencyTreeResolver { groupId, artifactId, version ->
            if (groupId == "com.example" && artifactId == "my-parent" && version == "1.0.0") {
                parentPom
            } else {
                null
            }
        }

        val mavenProject = MavenProject(pom.virtualFile)

        val service = RedundantManagedDependencyService(project, treeResolver = resolver)
        val recommendations = service.findRedundantForProject(
            mavenProject = mavenProject,
            allProjects = listOf(mavenProject),
            managedCoordinate = null,
            indicator = null
        )

        assertEquals(1, recommendations.size)
        val rec = recommendations.first()
        assertEquals("org.apache.commons", rec.groupId)
        assertEquals("commons-lang3", rec.artifactId)
        assertEquals("3.12.0", rec.currentVersion)
        assertEquals(RedundancyReason.PARENT_MANAGED, rec.reason)
        assertEquals("3.12.0", rec.providedVersion)
    }

    /**
     * Prüft die Erkennung von DIRECT_DEPENDENCY_MATCH, wenn im selben POM eine direkte Abhängigkeit mit expliziter Version deklariert ist.
     */
    fun testDirectDependencyMatchIsDetectedAsRedundant() {
        val pom = myFixture.configureByText(
            "pom.xml",
            """
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>sample</artifactId>
                    <version>1.0.0</version>
                    <dependencyManagement>
                        <dependencies>
                            <dependency>
                                <groupId>org.slf4j</groupId>
                                <artifactId>slf4j-api</artifactId>
                                <version>2.0.7</version>
                            </dependency>
                        </dependencies>
                    </dependencyManagement>
                    <dependencies>
                        <dependency>
                            <groupId>org.slf4j</groupId>
                            <artifactId>slf4j-api</artifactId>
                            <version>2.0.7</version>
                        </dependency>
                    </dependencies>
                </project>
            """.trimIndent()
        )

        val mavenProject = MavenProject(pom.virtualFile)
        val service = RedundantManagedDependencyService(project)
        val recommendations = service.findRedundantForProject(
            mavenProject = mavenProject,
            allProjects = listOf(mavenProject),
            managedCoordinate = null,
            indicator = null
        )

        assertEquals(1, recommendations.size)
        val rec = recommendations.first()
        assertEquals("org.slf4j", rec.groupId)
        assertEquals("slf4j-api", rec.artifactId)
        assertEquals(RedundancyReason.DIRECT_DEPENDENCY_MATCH, rec.reason)
        assertEquals("2.0.7", rec.providedVersion)
    }

    /**
     * Prüft die Erkennung von UNUSED, wenn das Artefakt im gesamten Projekt weder direkt noch transitiv genutzt wird.
     */
    fun testUnusedDependencyIsDetectedAsRedundant() {
        val pom = myFixture.configureByText(
            "pom.xml",
            """
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>sample</artifactId>
                    <version>1.0.0</version>
                    <dependencyManagement>
                        <dependencies>
                            <dependency>
                                <groupId>com.google.guava</groupId>
                                <artifactId>guava</artifactId>
                                <version>32.1.2-jre</version>
                            </dependency>
                        </dependencies>
                    </dependencyManagement>
                </project>
            """.trimIndent()
        )

        val mavenProject = MavenProject(pom.virtualFile)
        val service = RedundantManagedDependencyService(project)
        val recommendations = service.findRedundantForProject(
            mavenProject = mavenProject,
            allProjects = listOf(mavenProject),
            managedCoordinate = null,
            indicator = null
        )

        assertEquals(1, recommendations.size)
        val rec = recommendations.first()
        assertEquals("com.google.guava", rec.groupId)
        assertEquals("guava", rec.artifactId)
        assertEquals(RedundancyReason.UNUSED, rec.reason)
        assertNull(rec.providedVersion)
    }

    /**
     * Prüft die Erkennung von TRANSITIVE_MATCH, wenn direkte Abhängigkeiten das Artefakt in gleicher Version transitiv bereitstellen.
     */
    fun testTransitiveMatchIsDetectedAsRedundant() {
        val pom = myFixture.configureByText(
            "pom.xml",
            """
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>sample</artifactId>
                    <version>1.0.0</version>
                    <dependencyManagement>
                        <dependencies>
                            <dependency>
                                <groupId>org.xmlunit</groupId>
                                <artifactId>xmlunit-core</artifactId>
                                <version>2.9.1</version>
                            </dependency>
                        </dependencies>
                    </dependencyManagement>
                </project>
            """.trimIndent()
        )

        val starterPom = """
            <project>
                <modelVersion>4.0.0</modelVersion>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-starter-test</artifactId>
                <version>3.3.5</version>
                <dependencies>
                    <dependency>
                        <groupId>org.xmlunit</groupId>
                        <artifactId>xmlunit-core</artifactId>
                        <version>2.9.1</version>
                    </dependency>
                </dependencies>
            </project>
        """.trimIndent()

        val resolver = TemporaryDependencyTreeResolver { groupId, artifactId, version ->
            if (groupId == "org.springframework.boot" && artifactId == "spring-boot-starter-test") {
                starterPom
            } else {
                null
            }
        }

        val starterNode = createArtifactNode("org.springframework.boot", "spring-boot-starter-test", "3.3.5")
        val xmlunitNode = createArtifactNode("org.xmlunit", "xmlunit-core", "2.9.1")
        val consumerPath = listOf(starterNode, xmlunitNode)

        val mavenProject = MavenProject(pom.virtualFile)
        val service = RedundantManagedDependencyService(project, treeResolver = resolver)

        val managed = service.collectManagedDependencies(mavenProject).first()
        val rec = service.evaluateRedundancy(
            managed = managed,
            mavenProject = mavenProject,
            allProjects = listOf(mavenProject),
            directDependenciesWithExplicitVersion = emptyMap(),
            indicator = null
        )

        // Da spring-boot-starter-test im pom nicht direkt als <dependency> eingetragen ist, fällt es auf UNUSED zurück,
        // außer wenn es als Consumer-Pfad übergeben wird:
        // Testen wir nun direkt mit transitivem Pfad:
        val managedComp = org.apache.maven.artifact.versioning.ComparableVersion("2.9.1")
        
        val transRec = service.javaClass.getDeclaredMethod(
            "evaluateTransitiveMatch",
            RedundantManagedDependencyService.ManagedDependencyDeclaration::class.java,
            org.apache.maven.artifact.versioning.ComparableVersion::class.java,
            List::class.java,
            com.intellij.openapi.progress.ProgressIndicator::class.java
        ).apply { isAccessible = true }.invoke(
            service,
            managed,
            managedComp,
            listOf(consumerPath),
            null
        ) as de.schwarzland.mavenup.model.RedundantManagedDependencyRecommendation?

        assertNotNull(transRec)
        assertEquals("org.xmlunit", transRec!!.groupId)
        assertEquals("xmlunit-core", transRec.artifactId)
        assertEquals(RedundancyReason.TRANSITIVE_MATCH, transRec.reason)
        assertEquals("2.9.1", transRec.providedVersion)
    }

    /**
     * Prüft, dass BOM-Imports (<type>pom</type> mit <scope>import</scope>) ignoriert werden.
     */
    fun testBomImportsAreIgnored() {
        val pom = myFixture.configureByText(
            "pom.xml",
            """
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>sample</artifactId>
                    <version>1.0.0</version>
                    <dependencyManagement>
                        <dependencies>
                            <dependency>
                                <groupId>org.springframework.boot</groupId>
                                <artifactId>spring-boot-dependencies</artifactId>
                                <version>3.3.5</version>
                                <type>pom</type>
                                <scope>import</scope>
                            </dependency>
                        </dependencies>
                    </dependencyManagement>
                </project>
            """.trimIndent()
        )

        val mavenProject = MavenProject(pom.virtualFile)
        val service = RedundantManagedDependencyService(project)
        val managed = service.collectManagedDependencies(mavenProject)

        assertTrue(managed.isEmpty())
    }
}
