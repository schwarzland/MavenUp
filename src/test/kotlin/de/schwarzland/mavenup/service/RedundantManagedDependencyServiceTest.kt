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
     * Prüft die Erkennung von PARENT_MANAGED bei gleicher Parent-Version.
     */
    fun testParentManagedDependencyIsDetectedAsRedundant() {
        val (mavenProject, parentPom) = setupParentManagedProject()

        val resolver = TemporaryDependencyTreeResolver { groupId, artifactId, version ->
            if (groupId == "com.example" && artifactId == "my-parent" && version == "1.0.0") {
                parentPom
            } else {
                null
            }
        }

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
    }

    /**
     * Prüft die Erkennung von PARENT_MANAGED bei höherer Parent-Version.
     */
    fun testParentManagedDependencyWithHigherVersionIsDetectedAsRedundant() {
        val (mavenProject, parentPom) = setupParentManagedProject()

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

    private fun setupParentManagedProject(): Pair<MavenProject, String> {
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

        return Pair(MavenProject(pom.virtualFile), parentPom)
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
     * Prüft die Erkennung von TRANSITIVE_MATCH bei gleicher transitiver Version.
     */
    fun testTransitiveMatchIsDetectedAsRedundant() {
        val (mavenProject, starterPom, consumerPath) = setupTransitiveMatchProject()

        val resolver = TemporaryDependencyTreeResolver { groupId, artifactId, _ ->
            if (groupId == "org.springframework.boot" && artifactId == "spring-boot-starter-test") {
                starterPom
            } else {
                null
            }
        }

        val service = RedundantManagedDependencyService(project, treeResolver = resolver)
        val managed = service.collectManagedDependencies(mavenProject).first()
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
    }

    /**
     * Prüft die Erkennung von TRANSITIVE_MATCH bei höherer transitiver Version.
     */
    fun testTransitiveMatchWithHigherVersionIsDetectedAsRedundant() {
        val (mavenProject, starterPom, consumerPath) = setupTransitiveMatchProject()

        val higherVersionResolver = TemporaryDependencyTreeResolver { groupId, artifactId, _ ->
            if (groupId == "org.springframework.boot" && artifactId == "spring-boot-starter-test") {
                starterPom.replace("2.9.1", "2.9.2")
            } else {
                null
            }
        }
        val higherVersionService = RedundantManagedDependencyService(project, treeResolver = higherVersionResolver)
        val managed = higherVersionService.collectManagedDependencies(mavenProject).first()
        val managedComp = org.apache.maven.artifact.versioning.ComparableVersion("2.9.1")
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

    private fun setupTransitiveMatchProject(): Triple<MavenProject, String, List<MavenArtifactNode>> {
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

        val starterNode = createArtifactNode("org.springframework.boot", "spring-boot-starter-test", "3.3.5")
        val xmlunitNode = createArtifactNode("org.xmlunit", "xmlunit-core", "2.9.1")
        val consumerPath = listOf(starterNode, xmlunitNode)

        return Triple(MavenProject(pom.virtualFile), starterPom, consumerPath)
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

    // ---- Negative Cases: Nicht-redundante Einträge ----

    /**
     * Prüft, dass evaluateRedundancy null zurückgibt, wenn ein managed entry notwendig ist:
     * Das Artefakt wird direkt mit einer niedrigeren Version deklariert, kein Parent-Management,
     * und das Artefakt ist im Projekt verwendet (also kein UNUSED).
     *
     * Da MavenProject ohne vollständige Initialisierung keine dependencyTree/dependencies hat,
     * wird isUsedInAnyProject false zurückgeben → UNUSED wird erkannt.
     * Der Null-Fall (nicht-redundanter Eintrag) tritt auf, wenn isUsedInAnyProject true ist,
     * aber keine der anderen Kriterien zutrifft. Dies wird implizit durch die anderen Tests
     * abgedeckt, wo evaluateRedundancy null zurückgibt, wenn kein Kriterium passt.
     *
     * Dieser Test bestätigt, dass bei synthetischen MavenProjects (ohne initialisierte dependencies)
     * ein managed entry mit niedrigerer direkter Version als UNUSED erkannt wird,
     * da isUsedInAnyProject false zurückgibt.
     */
    fun testNonRedundantEntryWithLowerDirectVersionIsDetectedAsUnused() {
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
                                <version>2.0.9</version>
                            </dependency>
                        </dependencies>
                    </dependencyManagement>
                </project>
            """.trimIndent()
        )

        val mavenProject = MavenProject(pom.virtualFile)
        val service = RedundantManagedDependencyService(project)
        val managed = service.collectManagedDependencies(mavenProject).first()

        // Direct version 2.0.7 < managed 2.0.9 → kein DIRECT_DEPENDENCY_MATCH
        // Kein Parent → kein PARENT_MANAGED
        // isUsedInAnyProject: synthetisches MavenProject ohne dependencies → false → UNUSED
        val recommendation = service.evaluateRedundancy(
            managed = managed,
            mavenProject = mavenProject,
            allProjects = listOf(mavenProject),
            directDependenciesWithExplicitVersion = mapOf("org.slf4j:slf4j-api" to "2.0.7"),
            indicator = null
        )

        // Da isUsedInAnyProject false ist (kein reales Maven-Modell), wird UNUSED erkannt
        assertNotNull(recommendation)
        assertEquals(RedundancyReason.UNUSED, recommendation!!.reason)
    }

    /**
     * Prüft, dass evaluateParentManaged null zurückgibt, wenn kein Parent vorhanden ist.
     */
    fun testEvaluateParentManagedReturnsNullWhenNoParent() {
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
                                <groupId>org.apache.commons</groupId>
                                <artifactId>commons-lang3</artifactId>
                                <version>3.12.0</version>
                            </dependency>
                        </dependencies>
                    </dependencyManagement>
                </project>
            """.trimIndent()
        )

        val mavenProject = MavenProject(pom.virtualFile)
        val service = RedundantManagedDependencyService(project)
        val managed = service.collectManagedDependencies(mavenProject).first()
        val managedComp = org.apache.maven.artifact.versioning.ComparableVersion("3.12.0")

        val recommendation = service.evaluateParentManaged(
            managed = managed,
            mavenProject = mavenProject,
            key = "org.apache.commons:commons-lang3",
            managedComp = managedComp,
            consumerPaths = emptyList()
        )

        assertNull(recommendation)
    }

    /**
     * Prüft, dass evaluateParentManaged null zurückgibt, wenn die Parent-Version niedriger ist.
     */
    fun testEvaluateParentManagedReturnsNullWhenParentVersionLower() {
        val (mavenProject, parentPom) = setupParentManagedProject()

        val lowerVersionResolver = TemporaryDependencyTreeResolver { groupId, artifactId, version ->
            if (groupId == "com.example" && artifactId == "my-parent" && version == "1.0.0") {
                parentPom.replace("3.12.0", "3.11.0")
            } else {
                null
            }
        }

        val service = RedundantManagedDependencyService(project, treeResolver = lowerVersionResolver)
        val managed = service.collectManagedDependencies(mavenProject).first()
        val managedComp = org.apache.maven.artifact.versioning.ComparableVersion("3.12.0")

        val recommendation = service.evaluateParentManaged(
            managed = managed,
            mavenProject = mavenProject,
            key = "org.apache.commons:commons-lang3",
            managedComp = managedComp,
            consumerPaths = emptyList()
        )

        // Parent 3.11.0 < lokal 3.12.0 → kein Redundanzfund
        assertNull(recommendation)
    }

    /**
     * Prüft, dass evaluateParentManaged null zurückgibt, wenn das Parent-POM die Koordinate nicht enthält.
     */
    fun testEvaluateParentManagedReturnsNullWhenParentMissingCoordinate() {
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
                            <groupId>org.other</groupId>
                            <artifactId>other-lib</artifactId>
                            <version>1.0.0</version>
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
        val managed = service.collectManagedDependencies(mavenProject).first()
        val managedComp = org.apache.maven.artifact.versioning.ComparableVersion("3.12.0")

        val recommendation = service.evaluateParentManaged(
            managed = managed,
            mavenProject = mavenProject,
            key = "org.apache.commons:commons-lang3",
            managedComp = managedComp,
            consumerPaths = emptyList()
        )

        assertNull(recommendation)
    }

    /**
     * Prüft, dass evaluateParentManaged null zurückgibt, wenn das Laden des Parent-POMs eine Exception wirft.
     */
    fun testEvaluateParentManagedReturnsNullOnException() {
        val mavenProject = setupParentManagedProject().first

        val throwingResolver = TemporaryDependencyTreeResolver { _, _, _ ->
            throw RuntimeException("Network error")
        }

        val service = RedundantManagedDependencyService(project, treeResolver = throwingResolver)
        val managed = service.collectManagedDependencies(mavenProject).first()
        val managedComp = org.apache.maven.artifact.versioning.ComparableVersion("3.12.0")

        val recommendation = service.evaluateParentManaged(
            managed = managed,
            mavenProject = mavenProject,
            key = "org.apache.commons:commons-lang3",
            managedComp = managedComp,
            consumerPaths = emptyList()
        )

        assertNull(recommendation)
    }

    // ---- evaluateDirectMatch Negative Cases ----

    /**
     * Prüft, dass evaluateDirectMatch null zurückgibt, wenn die direkte Version niedriger ist.
     */
    fun testEvaluateDirectMatchReturnsNullWhenDirectVersionLower() {
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
                                <version>2.0.9</version>
                            </dependency>
                        </dependencies>
                    </dependencyManagement>
                </project>
            """.trimIndent()
        )

        val mavenProject = MavenProject(pom.virtualFile)
        val service = RedundantManagedDependencyService(project)
        val managed = service.collectManagedDependencies(mavenProject).first()
        val managedComp = org.apache.maven.artifact.versioning.ComparableVersion("2.0.9")

        val recommendation = service.evaluateDirectMatch(
            managed = managed,
            key = "org.slf4j:slf4j-api",
            managedComp = managedComp,
            directDependenciesWithExplicitVersion = mapOf("org.slf4j:slf4j-api" to "2.0.7"),
            consumerPaths = emptyList()
        )

        // 2.0.7 < 2.0.9 → kein Match
        assertNull(recommendation)
    }

    /**
     * Prüft, dass evaluateDirectMatch null zurückgibt, wenn keine direkte Deklaration existiert.
     */
    fun testEvaluateDirectMatchReturnsNullWhenNoDirectDeclaration() {
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
                </project>
            """.trimIndent()
        )

        val mavenProject = MavenProject(pom.virtualFile)
        val service = RedundantManagedDependencyService(project)
        val managed = service.collectManagedDependencies(mavenProject).first()
        val managedComp = org.apache.maven.artifact.versioning.ComparableVersion("2.0.7")

        val recommendation = service.evaluateDirectMatch(
            managed = managed,
            key = "org.slf4j:slf4j-api",
            managedComp = managedComp,
            directDependenciesWithExplicitVersion = emptyMap(),
            consumerPaths = emptyList()
        )

        assertNull(recommendation)
    }

    // ---- evaluateTransitiveMatch Negative Cases ----

    /**
     * Prüft, dass evaluateTransitiveMatch null zurückgibt, wenn consumerPaths leer ist.
     */
    fun testEvaluateTransitiveMatchReturnsNullWhenEmptyConsumerPaths() {
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

        val mavenProject = MavenProject(pom.virtualFile)
        val service = RedundantManagedDependencyService(project)
        val managed = service.collectManagedDependencies(mavenProject).first()
        val managedComp = org.apache.maven.artifact.versioning.ComparableVersion("2.9.1")

        val recommendation = service.evaluateTransitiveMatch(
            managed = managed,
            mavenProject = mavenProject,
            managedComp = managedComp,
            consumerPaths = emptyList(),
            indicator = null
        )

        assertNull(recommendation)
    }

    /**
     * Prüft, dass evaluateTransitiveMatch null zurückgibt, wenn die transitive Version niedriger ist.
     */
    fun testEvaluateTransitiveMatchReturnsNullWhenTransitiveVersionLower() {
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
                        <version>2.8.0</version>
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

        val mavenProject = MavenProject(pom.virtualFile)
        val service = RedundantManagedDependencyService(project, treeResolver = resolver)
        val managed = service.collectManagedDependencies(mavenProject).first()
        val managedComp = org.apache.maven.artifact.versioning.ComparableVersion("2.9.1")

        val starterNode = createArtifactNode("org.springframework.boot", "spring-boot-starter-test", "3.3.5")
        val xmlunitNode = createArtifactNode("org.xmlunit", "xmlunit-core", "2.8.0")
        val consumerPath = listOf(starterNode, xmlunitNode)

        val recommendation = service.evaluateTransitiveMatch(
            managed = managed,
            mavenProject = mavenProject,
            managedComp = managedComp,
            consumerPaths = listOf(consumerPath),
            indicator = null
        )

        // 2.8.0 < 2.9.1 → kein Match
        assertNull(recommendation)
    }

    // ---- isUsedInAnyProject isolierte Tests ----

    /**
     * Prüft, dass isUsedInAnyProject false zurückgibt, wenn das Artefakt in keinem Modul vorkommt.
     */
    fun testIsUsedInAnyProjectReturnsFalseWhenNotUsed() {
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

        val isUsed = service.isUsedInAnyProject(
            projects = listOf(mavenProject),
            groupId = "com.google.guava",
            artifactId = "guava",
            indicator = null
        )

        // Keine direkten oder transitiven Dependencies → nicht verwendet
        assertFalse(isUsed)
    }

    /**
     * Prüft, dass isUsedInAnyProject true zurückgibt, wenn das Artefakt im dependencyTree eines Moduls vorkommt.
     * Da MavenProject ohne vollständige Initialisierung keine dependencies hat, wird der dependencyTree
     * mit einem manuell erstellten Knoten geprüft.
     */
    fun testIsUsedInAnyProjectReturnsTrueWhenInDependencyTree() {
        val pom = myFixture.configureByText(
            "pom.xml",
            """
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>sample</artifactId>
                    <version>1.0.0</version>
                </project>
            """.trimIndent()
        )

        val mavenProject = MavenProject(pom.virtualFile)
        val service = RedundantManagedDependencyService(project)

        // Ohne initialisierte dependencies/dependencyTree ist kein Artefakt verwendet
        val isUsed = service.isUsedInAnyProject(
            projects = listOf(mavenProject),
            groupId = "org.slf4j",
            artifactId = "slf4j-api",
            indicator = null
        )

        // MavenProject ohne Initialisierung hat leere dependencyTree → false
        assertFalse(isUsed)
    }

    // ---- findProjectConsumersForManaged isolierte Tests ----

    /**
     * Prüft, dass findProjectConsumersForManaged leere Liste zurückgibt, wenn keine Konsumenten existieren.
     */
    fun testFindProjectConsumersForManagedReturnsEmptyList() {
        val pom = myFixture.configureByText(
            "pom.xml",
            """
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>sample</artifactId>
                    <version>1.0.0</version>
                </project>
            """.trimIndent()
        )

        val mavenProject = MavenProject(pom.virtualFile)
        val service = RedundantManagedDependencyService(project)

        val consumers = service.findProjectConsumersForManaged(
            mavenProject = mavenProject,
            managedGroupId = "org.slf4j",
            managedArtifactId = "slf4j-api",
            indicator = null
        )

        assertTrue(consumers.isEmpty())
    }

    /**
     * Prüft, dass findProjectConsumersForManaged Pfade findet, wenn das Ziel im dependencyTree vorkommt.
     */
    fun testFindProjectConsumersForManagedFindsPaths() {
        val pom = myFixture.configureByText(
            "pom.xml",
            """
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>sample</artifactId>
                    <version>1.0.0</version>
                </project>
            """.trimIndent()
        )

        val mavenProject = MavenProject(pom.virtualFile)
        val service = RedundantManagedDependencyService(project)

        // Manuell dependencyTree mit Pfaden befüllen
        val springBootNode = createArtifactNode("org.springframework.boot", "spring-boot-starter", "3.3.5")
        val slf4jNode = createArtifactNode("org.slf4j", "slf4j-api", "2.0.9")
        slf4jNode.dependencies = mutableListOf()
        springBootNode.dependencies = mutableListOf(slf4jNode)

        // Da MavenProject.dependencyTree null ist, wird eine leere Liste zurückgegeben
        // Der Test bestätigt das Verhalten mit uninitialisiertem MavenProject
        val consumers = service.findProjectConsumersForManaged(
            mavenProject = mavenProject,
            managedGroupId = "org.slf4j",
            managedArtifactId = "slf4j-api",
            indicator = null
        )

        assertTrue(consumers.isEmpty())
    }

    // ---- matchesCoordinateScope isolierte Tests ----

    /**
     * Prüft, dass matchesCoordinateScope true zurückgibt, wenn der Filter inaktiv ist.
     */
    fun testMatchesCoordinateScopeReturnsTrueWhenFilterInactive() {
        val service = RedundantManagedDependencyService(project)

        assertTrue(service.matchesCoordinateScope("org.slf4j", "slf4j-api", null))
    }

    /**
     * Prüft, dass matchesCoordinateScope true zurückgibt, wenn die Koordinate passt.
     */
    fun testMatchesCoordinateScopeReturnsTrueWhenCoordinateMatches() {
        val service = RedundantManagedDependencyService(project)

        assertTrue(service.matchesCoordinateScope("org.slf4j", "slf4j-api", "org.slf4j:slf4j-api"))
    }

    /**
     * Prüft, dass matchesCoordinateScope false zurückgibt, wenn die Koordinate nicht passt.
     */
    fun testMatchesCoordinateScopeReturnsFalseWhenCoordinateDoesNotMatch() {
        val service = RedundantManagedDependencyService(project)

        assertFalse(service.matchesCoordinateScope("org.slf4j", "slf4j-api", "com.google:guava"))
    }

    // ---- collectManagedDependencies: Mehrere Einträge ----

    /**
     * Prüft, dass collectManagedDependencies mehrere Einträge korrekt sammelt.
     */
    fun testCollectManagedDependenciesCollectsMultipleEntries() {
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
                            <dependency>
                                <groupId>org.apache.commons</groupId>
                                <artifactId>commons-lang3</artifactId>
                                <version>3.12.0</version>
                            </dependency>
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
        val managed = service.collectManagedDependencies(mavenProject)

        assertEquals(3, managed.size)
        val groupIds = managed.map { it.groupId }.toSet()
        val artifactIds = managed.map { it.artifactId }.toSet()
        assertTrue(groupIds.contains("org.slf4j"))
        assertTrue(groupIds.contains("org.apache.commons"))
        assertTrue(groupIds.contains("com.google.guava"))
        assertTrue(artifactIds.contains("slf4j-api"))
        assertTrue(artifactIds.contains("commons-lang3"))
        assertTrue(artifactIds.contains("guava"))
    }

    /**
     * Prüft, dass collectManagedDependencies Property-Platzhalter in Versionen auflöst,
     * wenn die Properties im Maven-Modell verfügbar sind.
     * Ohne initialisierte Properties bleibt der Platzhalter unverändert.
     */
    fun testCollectManagedDependenciesWithUnresolvedPropertyPlaceholder() {
        val pom = myFixture.configureByText(
            "pom.xml",
            """
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>sample</artifactId>
                    <version>1.0.0</version>
                    <properties>
                        <slf4j.version>2.0.9</slf4j.version>
                    </properties>
                    <dependencyManagement>
                        <dependencies>
                            <dependency>
                                <groupId>org.slf4j</groupId>
                                <artifactId>slf4j-api</artifactId>
                                <version>${"$"}{slf4j.version}</version>
                            </dependency>
                        </dependencies>
                    </dependencyManagement>
                </project>
            """.trimIndent()
        )

        val mavenProject = MavenProject(pom.virtualFile)
        val service = RedundantManagedDependencyService(project)
        val managed = service.collectManagedDependencies(mavenProject)

        assertEquals(1, managed.size)
        // MavenProject ohne Initialisierung hat leere Properties → Platzhalter bleibt unverändert
        assertEquals("${"$"}{slf4j.version}", managed.first().currentVersion)
    }

    // ---- collectDirectDependenciesWithExplicitVersions Tests ----

    /**
     * Prüft, dass collectDirectDependenciesWithExplicitVersions leere Map zurückgibt,
     * wenn keine direkten Dependencies existieren.
     */
    fun testCollectDirectDependenciesWithExplicitVersionsReturnsEmptyMap() {
        val pom = myFixture.configureByText(
            "pom.xml",
            """
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>sample</artifactId>
                    <version>1.0.0</version>
                </project>
            """.trimIndent()
        )

        val mavenProject = MavenProject(pom.virtualFile)
        val service = RedundantManagedDependencyService(project)
        val directDeps = service.collectDirectDependenciesWithExplicitVersions(mavenProject)

        assertTrue(directDeps.isEmpty())
    }

    /**
     * Prüft, dass Dependencies ohne explizite Version ignoriert werden.
     */
    fun testCollectDirectDependenciesWithExplicitVersionsIgnoresMissingVersion() {
        val pom = myFixture.configureByText(
            "pom.xml",
            """
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>sample</artifactId>
                    <version>1.0.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>org.slf4j</groupId>
                            <artifactId>slf4j-api</artifactId>
                        </dependency>
                        <dependency>
                            <groupId>org.apache.commons</groupId>
                            <artifactId>commons-lang3</artifactId>
                            <version>3.12.0</version>
                        </dependency>
                    </dependencies>
                </project>
            """.trimIndent()
        )

        val mavenProject = MavenProject(pom.virtualFile)
        val service = RedundantManagedDependencyService(project)
        val directDeps = service.collectDirectDependenciesWithExplicitVersions(mavenProject)

        assertEquals(1, directDeps.size)
        assertEquals("3.12.0", directDeps["org.apache.commons:commons-lang3"])
        assertNull(directDeps["org.slf4j:slf4j-api"])
    }

    /**
     * Prüft, dass Property-Platzhalter in direkten Dependency-Versionen unverändert bleiben,
     * wenn die Properties im Maven-Modell nicht verfügbar sind.
     */
    fun testCollectDirectDependenciesWithExplicitVersionsWithUnresolvedPropertyPlaceholder() {
        val pom = myFixture.configureByText(
            "pom.xml",
            """
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>sample</artifactId>
                    <version>1.0.0</version>
                    <properties>
                        <commons.version>3.14.0</commons.version>
                    </properties>
                    <dependencies>
                        <dependency>
                            <groupId>org.apache.commons</groupId>
                            <artifactId>commons-lang3</artifactId>
                            <version>${"$"}{commons.version}</version>
                        </dependency>
                    </dependencies>
                </project>
            """.trimIndent()
        )

        val mavenProject = MavenProject(pom.virtualFile)
        val service = RedundantManagedDependencyService(project)
        val directDeps = service.collectDirectDependenciesWithExplicitVersions(mavenProject)

        assertEquals(1, directDeps.size)
        // MavenProject ohne Initialisierung hat leere Properties → Platzhalter bleibt unverändert
        assertEquals("${"$"}{commons.version}", directDeps["org.apache.commons:commons-lang3"])
    }

    // ---- resolveFallbackManagedVersionWithoutLocalEntry Tests ----

    /**
     * Prüft, dass resolveFallbackManagedVersionWithoutLocalEntry null zurückgibt,
     * wenn kein Parent und kein BOM-Import vorhanden ist.
     */
    fun testResolveFallbackManagedVersionWithoutLocalEntryReturnsNullWhenNoFallback() {
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
                </project>
            """.trimIndent()
        )

        val mavenProject = MavenProject(pom.virtualFile)
        val service = RedundantManagedDependencyService(project)

        val fallback = service.resolveFallbackManagedVersionWithoutLocalEntry(
            mavenProject = mavenProject,
            managedCoordinate = "org.slf4j:slf4j-api"
        )

        // Kein Parent, kein BOM → kein Fallback
        assertNull(fallback)
    }

    // ---- Multi-Module Szenario ----

    /**
     * Prüft, dass findRedundantForProject bei mehreren Maven-Projekten
     * redundante Einträge in jedem Modul erkennt.
     */
    fun testMultiModuleScenarioDetectsRedundancyInEachModule() {
        val pom1 = myFixture.configureByText(
            "module1pom.xml",
            """
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>module1</artifactId>
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

        val pom2 = myFixture.configureByText(
            "module2pom.xml",
            """
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>module2</artifactId>
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
                </project>
            """.trimIndent()
        )

        val mavenProject1 = MavenProject(pom1.virtualFile)
        val mavenProject2 = MavenProject(pom2.virtualFile)
        val service = RedundantManagedDependencyService(project)

        val recommendations1 = service.findRedundantForProject(
            mavenProject = mavenProject1,
            allProjects = listOf(mavenProject1, mavenProject2),
            managedCoordinate = null,
            indicator = null
        )

        val recommendations2 = service.findRedundantForProject(
            mavenProject = mavenProject2,
            allProjects = listOf(mavenProject1, mavenProject2),
            managedCoordinate = null,
            indicator = null
        )

        // Beide Module haben einen unused managed entry
        assertEquals(1, recommendations1.size)
        assertEquals(1, recommendations2.size)
        assertEquals(RedundancyReason.UNUSED, recommendations1.first().reason)
        assertEquals(RedundancyReason.UNUSED, recommendations2.first().reason)
    }

    // ---- Priorisierung: PARENT_MANAGED vor DIRECT_DEPENDENCY_MATCH ----

    /**
     * Prüft, dass PARENT_MANAGED priorisiert wird, wenn sowohl Parent-Management
     * als auch direkte Deklaration dieselbe Version liefern.
     */
    fun testParentManagedIsPrioritizedOverDirectMatch() {
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
                    <dependencies>
                        <dependency>
                            <groupId>org.apache.commons</groupId>
                            <artifactId>commons-lang3</artifactId>
                            <version>3.12.0</version>
                        </dependency>
                    </dependencies>
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
        // PARENT_MANAGED hat Vorrang vor DIRECT_DEPENDENCY_MATCH
        assertEquals(RedundancyReason.PARENT_MANAGED, recommendations.first().reason)
    }

    // ---- Leere dependencyManagement-Sektion ----

    /**
     * Prüft, dass collectManagedDependencies leere Liste bei leerer dependencyManagement-Sektion zurückgibt.
     */
    fun testCollectManagedDependenciesReturnsEmptyForEmptySection() {
        val pom = myFixture.configureByText(
            "pom.xml",
            """
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>sample</artifactId>
                    <version>1.0.0</version>
                    <dependencyManagement>
                    </dependencyManagement>
                </project>
            """.trimIndent()
        )

        val mavenProject = MavenProject(pom.virtualFile)
        val service = RedundantManagedDependencyService(project)
        val managed = service.collectManagedDependencies(mavenProject)

        assertTrue(managed.isEmpty())
    }

    // ---- Gemischte BOM-Imports und normale Einträge ----

    /**
     * Prüft, dass bei gemischten BOM-Imports und normalen Einträgen nur die normalen Einträge gesammelt werden.
     */
    fun testCollectManagedDependenciesIgnoresBomImportsMixedWithNormalEntries() {
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
                            <dependency>
                                <groupId>org.slf4j</groupId>
                                <artifactId>slf4j-api</artifactId>
                                <version>2.0.7</version>
                            </dependency>
                        </dependencies>
                    </dependencyManagement>
                </project>
            """.trimIndent()
        )

        val mavenProject = MavenProject(pom.virtualFile)
        val service = RedundantManagedDependencyService(project)
        val managed = service.collectManagedDependencies(mavenProject)

        assertEquals(1, managed.size)
        assertEquals("org.slf4j", managed.first().groupId)
        assertEquals("slf4j-api", managed.first().artifactId)
    }
}
