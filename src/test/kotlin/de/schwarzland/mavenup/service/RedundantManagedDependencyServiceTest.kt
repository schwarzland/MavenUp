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
     * Prüft die Erkennung von PARENT_MANAGED bei gleicher und höherer Parent-Version.
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
        assertTrue(rec.reasonDetail.contains("com.example:my-parent:1.0.0"))
        assertTrue(rec.reasonDetail.contains("org.apache.commons:commons-lang3"))
        assertTrue(rec.reasonDetail.contains("equal to the local version"))

        val higherVersionResolver = TemporaryDependencyTreeResolver { groupId, artifactId, version ->
            if (groupId == "com.example" && artifactId == "my-parent" && version == "1.0.0") {
                parentPom.replace("3.12.0", "3.13.0")
            } else {
                null
            }
        }
        val higherVersionService = RedundantManagedDependencyService(project, treeResolver = higherVersionResolver)
        val managed = higherVersionService.collectManagedDependencies(mavenProject).first()
        val higherParentRecommendation = higherVersionService.evaluateParentManaged(
                managed = managed,
                mavenProject = mavenProject,
                key = "org.apache.commons:commons-lang3",
                managedComp = org.apache.maven.artifact.versioning.ComparableVersion("3.12.0"),
                consumerPaths = emptyList()
            )
        assertNotNull(higherParentRecommendation)
        assertEquals("3.13.0", higherParentRecommendation!!.providedVersion)
        assertTrue(higherParentRecommendation.reasonDetail.contains("higher than the local version 3.12.0"))
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
        assertTrue(rec.reasonDetail.contains("org.slf4j:slf4j-api"))
        assertTrue(rec.reasonDetail.contains("equal to the managed version"))

        val rootNode = createArtifactNode("org.example", "consumer", "1.0.0")
        val managedNode = createArtifactNode("org.slf4j", "slf4j-api", "2.0.7")
        assertNull(
            service.evaluateDirectMatch(
                managed = service.collectManagedDependencies(mavenProject).first(),
                key = "org.slf4j:slf4j-api",
                managedComp = org.apache.maven.artifact.versioning.ComparableVersion("2.0.7"),
                directDependenciesWithExplicitVersion = mapOf("org.slf4j:slf4j-api" to "2.0.7"),
                consumerPaths = listOf(listOf(rootNode, managedNode))
            )
        )
        val higherDirectRecommendation = service.evaluateDirectMatch(
            managed = service.collectManagedDependencies(mavenProject).first(),
            key = "org.slf4j:slf4j-api",
            managedComp = org.apache.maven.artifact.versioning.ComparableVersion("2.0.7"),
            directDependenciesWithExplicitVersion = mapOf("org.slf4j:slf4j-api" to "2.1.0"),
            consumerPaths = emptyList()
        )
        assertNotNull(higherDirectRecommendation)
        assertEquals("2.1.0", higherDirectRecommendation!!.providedVersion)
        assertTrue(higherDirectRecommendation.reasonDetail.contains("higher than the managed version 2.0.7"))
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
     * Prüft die Erkennung von TRANSITIVE_MATCH bei gleicher und höherer transitiver Version.
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

        val resolver = TemporaryDependencyTreeResolver { groupId, artifactId, _ ->
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

        // Prüft die transitive Version anhand eines repräsentativen Konsumentenpfads.
        val managedComp = org.apache.maven.artifact.versioning.ComparableVersion("2.9.1")

        val transRec = service.evaluateTransitiveMatch(
            managed = managed,
            mavenProject = mavenProject,
            managedComp = managedComp,
            consumerPaths = listOf(consumerPath),
            indicator = null
        )

        assertNotNull(transRec)
        assertEquals("org.xmlunit", transRec!!.groupId)
        assertEquals("xmlunit-core", transRec.artifactId)
        assertEquals(RedundancyReason.TRANSITIVE_MATCH, transRec.reason)
        assertEquals("2.9.1", transRec.providedVersion)
        assertTrue(transRec.reasonDetail.contains("org.xmlunit:xmlunit-core"))
        assertFalse(transRec.reasonDetail.contains("org.springframework.boot:spring-boot-starter-test:3.3.5"))
        assertTrue(transRec.consumers.single().pathDescription.contains("org.springframework.boot:spring-boot-starter-test:3.3.5"))

        val higherVersionResolver = TemporaryDependencyTreeResolver { groupId, artifactId, _ ->
            if (groupId == "org.springframework.boot" && artifactId == "spring-boot-starter-test") {
                starterPom.replace("2.9.1", "2.9.2")
            } else {
                null
            }
        }
        val higherVersionService = RedundantManagedDependencyService(project, treeResolver = higherVersionResolver)
        val higherTransitiveRecommendation = higherVersionService.evaluateTransitiveMatch(
                managed = managed,
                mavenProject = mavenProject,
                managedComp = managedComp,
                consumerPaths = listOf(consumerPath),
                indicator = null
            )
        assertNotNull(higherTransitiveRecommendation)
        assertEquals("2.9.2", higherTransitiveRecommendation!!.providedVersion)
        assertTrue(higherTransitiveRecommendation.reasonDetail.contains("2.9.2"))
        assertTrue(higherTransitiveRecommendation.reasonDetail.contains("may change the resolved version"))
    }

    /**
     * Prüft, dass TRANSITIVE_MATCH verworfen wird, wenn ohne lokalen Managed-Entry eine niedrigere
     * Fallback-Version aus Parent-/BOM-Management wirksam wäre.
     */
    fun testTransitiveMatchIsRejectedWhenFallbackManagementWouldDowngradeVersion() {
        val projectPom = """
            <project>
                <modelVersion>4.0.0</modelVersion>
                <parent>
                    <groupId>com.example</groupId>
                    <artifactId>parent</artifactId>
                    <version>1.0.0</version>
                </parent>
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
        val pom = myFixture.configureByText("pom.xml", projectPom)

        val parentPom = """
            <project>
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.example</groupId>
                <artifactId>parent</artifactId>
                <version>1.0.0</version>
                <dependencyManagement>
                    <dependencies>
                        <dependency>
                            <groupId>org.xmlunit</groupId>
                            <artifactId>xmlunit-core</artifactId>
                            <version>2.9.0</version>
                        </dependency>
                    </dependencies>
                </dependencyManagement>
            </project>
        """.trimIndent()

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
            when ("$groupId:$artifactId:$version") {
                "com.example:sample:1.0.0" -> projectPom
                "com.example:parent:1.0.0" -> parentPom
                "org.springframework.boot:spring-boot-starter-test:3.3.5" -> starterPom
                else -> null
            }
        }

        val mavenProject = MavenProject(pom.virtualFile)
        val service = RedundantManagedDependencyService(project, treeResolver = resolver)
        val managed = service.collectManagedDependencies(mavenProject).first()
        val managedComp = org.apache.maven.artifact.versioning.ComparableVersion("2.9.1")

        val starterNode = createArtifactNode("org.springframework.boot", "spring-boot-starter-test", "3.3.5")
        val xmlunitNode = createArtifactNode("org.xmlunit", "xmlunit-core", "2.9.1")
        val recommendation = service.evaluateTransitiveMatch(
            managed = managed,
            mavenProject = mavenProject,
            managedComp = managedComp,
            consumerPaths = listOf(listOf(starterNode, xmlunitNode)),
            indicator = null
        )

        assertNull(recommendation)
    }

    /**
     * Prüft den Jackson-Fall: Ohne lokalen Managed-Override für `jackson-core` fällt die wirksame
     * Version auf 3.1.5 zurück, daher darf kein `TRANSITIVE_MATCH` vorgeschlagen werden.
     */
    fun testTransitiveMatchIsRejectedForJacksonCoreFallbackDowngrade() {
        val projectPom = """
            <project>
                <modelVersion>4.0.0</modelVersion>
                <parent>
                    <groupId>com.example</groupId>
                    <artifactId>parent</artifactId>
                    <version>1.0.0</version>
                </parent>
                <groupId>com.example</groupId>
                <artifactId>sample</artifactId>
                <version>1.0.0</version>
                <dependencyManagement>
                    <dependencies>
                        <dependency>
                            <groupId>tools.jackson.core</groupId>
                            <artifactId>jackson-core</artifactId>
                            <version>3.1.7</version>
                        </dependency>
                    </dependencies>
                </dependencyManagement>
            </project>
        """.trimIndent()
        val pom = myFixture.configureByText("pom.xml", projectPom)

        val parentPom = """
            <project>
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.example</groupId>
                <artifactId>parent</artifactId>
                <version>1.0.0</version>
                <dependencyManagement>
                    <dependencies>
                        <dependency>
                            <groupId>tools.jackson.core</groupId>
                            <artifactId>jackson-core</artifactId>
                            <version>3.1.5</version>
                        </dependency>
                    </dependencies>
                </dependencyManagement>
            </project>
        """.trimIndent()

        val consumerPom = """
            <project>
                <modelVersion>4.0.0</modelVersion>
                <groupId>tools.jackson.dataformat</groupId>
                <artifactId>jackson-dataformat-xml</artifactId>
                <version>3.1.7</version>
                <dependencies>
                    <dependency>
                        <groupId>tools.jackson.core</groupId>
                        <artifactId>jackson-core</artifactId>
                        <version>3.1.7</version>
                    </dependency>
                </dependencies>
            </project>
        """.trimIndent()

        val resolver = TemporaryDependencyTreeResolver { groupId, artifactId, version ->
            when ("$groupId:$artifactId:$version") {
                "com.example:sample:1.0.0" -> projectPom
                "com.example:parent:1.0.0" -> parentPom
                "tools.jackson.dataformat:jackson-dataformat-xml:3.1.7" -> consumerPom
                else -> null
            }
        }

        val mavenProject = MavenProject(pom.virtualFile)
        val service = RedundantManagedDependencyService(project, treeResolver = resolver)
        val managed = service.collectManagedDependencies(mavenProject).first()
        val managedComp = org.apache.maven.artifact.versioning.ComparableVersion("3.1.7")

        val consumerNode = createArtifactNode("tools.jackson.dataformat", "jackson-dataformat-xml", "3.1.7")
        val jacksonCoreNode = createArtifactNode("tools.jackson.core", "jackson-core", "3.1.7")
        val recommendation = service.evaluateTransitiveMatch(
            managed = managed,
            mavenProject = mavenProject,
            managedComp = managedComp,
            consumerPaths = listOf(listOf(consumerNode, jacksonCoreNode)),
            indicator = null
        )

        assertNull(recommendation)
        assertEquals("3.1.5", service.resolveFallbackManagedVersionWithoutLocalEntry(mavenProject, "tools.jackson.core:jackson-core"))
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
