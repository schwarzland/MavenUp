package de.schwarzland.mavenup.service

import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.util.ProgressIndicatorBase
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.apache.maven.artifact.versioning.ComparableVersion
import org.jetbrains.idea.maven.model.MavenArtifact
import org.jetbrains.idea.maven.model.MavenArtifactNode
import org.jetbrains.idea.maven.project.MavenProject

/**
 * Unittests für [ManagedDependencyRecommendationService].
 */
class ManagedDependencyRecommendationServiceTest : BasePlatformTestCase() {

    /**
     * Prüft, dass eine bereits abgebrochene Cleanup-Analyse den Abbruch weitergibt.
     */
    fun testFindRecommendationsPropagatesCancellation() {
        val service = ManagedDependencyRecommendationService(project)
        val indicator = ProgressIndicatorBase().apply { cancel() }

        try {
            service.findRecommendations(emptyMap(), null, null, indicator)
            fail("Expected the cleanup analysis to stop when cancelled")
        } catch (_: ProcessCanceledException) {
            // Expected: cancellation must not be reported as an empty result.
        }
    }

    /**
     * Prüft den globalen, passenden und abweichenden Koordinatenfilter.
     */
    fun testCoordinateScopeMatchesOnlyTheRequestedCoordinate() {
        val service = ManagedDependencyRecommendationService(project)

        assertTrue(service.matchesCoordinateScope("com.example", "library", null))
        assertTrue(service.matchesCoordinateScope("com.example", "library", "com.example:library"))
        assertFalse(service.matchesCoordinateScope("com.example", "library", "com.example:other"))
        assertFalse(service.matchesCoordinateScope("com.example", "library", ""))
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

    private fun createArtifactNode(groupId: String, artifactId: String, version: String): MavenArtifactNode {
        val artifact = createArtifact(groupId, artifactId, version)
        return MavenArtifactNode(null, artifact, null, null, null, null, null)
    }

    fun testSingleConsumerDirectDependencyUpgradeRecommendation() {
        val directPom = """
            <project>
                <groupId>com.example</groupId>
                <artifactId>direct-lib</artifactId>
                <version>2.0.0</version>
                <dependencies>
                    <dependency>
                        <groupId>com.fasterxml.jackson.core</groupId>
                        <artifactId>jackson-databind</artifactId>
                        <version>2.15.2</version>
                    </dependency>
                </dependencies>
            </project>
        """.trimIndent()

        val resolver = TemporaryDependencyTreeResolver { g, a, v ->
            if (g == "com.example" && a == "direct-lib" && v == "2.0.0") directPom else null
        }

        val dummyMavenProject = MavenProject(myFixture.configureByText("pom.xml", "<project></project>").virtualFile)
        val service = ManagedDependencyRecommendationService(
            project = project,
            treeResolver = resolver
        )

        val managed = ManagedDependencyRecommendationService.ManagedDependencyDeclaration(
            groupId = "com.fasterxml.jackson.core",
            artifactId = "jackson-databind",
            currentVersion = "2.14.0",
            mavenProject = dummyMavenProject
        )

        val trigger = ManagedDependencyRecommendationService.TriggerCandidate(
            groupId = "com.example",
            artifactId = "direct-lib",
            currentVersion = "1.0.0",
            type = "dependency",
            candidateVersions = listOf("2.0.0"),
            mavenProject = dummyMavenProject
        )

        val directNode = createArtifactNode("com.example", "direct-lib", "1.0.0")
        val jacksonNode = createArtifactNode("com.fasterxml.jackson.core", "jackson-databind", "2.14.0")
        val consumerPath = listOf(directNode, jacksonNode)

        val rec = service.evaluateTriggerRecommendation(
            managed = managed,
            managedComparable = ComparableVersion("2.14.0"),
            trigger = trigger,
            targetVersion = "2.0.0",
            consumerPaths = listOf(consumerPath)
        )

        assertNotNull(rec)
        assertTrue(rec!!.isSatisfiedAcrossAllConsumers)
        assertEquals("com.fasterxml.jackson.core", rec.managedGroupId)
        assertEquals("jackson-databind", rec.managedArtifactId)
        assertEquals("2.14.0", rec.managedCurrentVersion)
        assertEquals("2.0.0", rec.triggerTargetVersion)
        assertEquals("2.15.2", rec.transitiveVersionInTarget)
        assertEquals(1, rec.consumers.size)
    }

    fun testParentPomUpgradeRecommendation() {
        val parentPom = """
            <project>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-starter-parent</artifactId>
                <version>3.2.0</version>
                <dependencyManagement>
                    <dependencies>
                        <dependency>
                            <groupId>com.fasterxml.jackson.core</groupId>
                            <artifactId>jackson-databind</artifactId>
                            <version>2.16.1</version>
                        </dependency>
                    </dependencies>
                </dependencyManagement>
            </project>
        """.trimIndent()

        val resolver = TemporaryDependencyTreeResolver { g, a, v ->
            if (g == "org.springframework.boot" && a == "spring-boot-starter-parent" && v == "3.2.0") parentPom else null
        }

        val dummyMavenProject = MavenProject(myFixture.configureByText("pom.xml", "<project></project>").virtualFile)
        val service = ManagedDependencyRecommendationService(
            project = project,
            treeResolver = resolver
        )

        val managed = ManagedDependencyRecommendationService.ManagedDependencyDeclaration(
            groupId = "com.fasterxml.jackson.core",
            artifactId = "jackson-databind",
            currentVersion = "2.15.0",
            mavenProject = dummyMavenProject
        )

        val trigger = ManagedDependencyRecommendationService.TriggerCandidate(
            groupId = "org.springframework.boot",
            artifactId = "spring-boot-starter-parent",
            currentVersion = "3.1.0",
            type = "parent",
            candidateVersions = listOf("3.2.0"),
            mavenProject = dummyMavenProject
        )

        val rec = service.evaluateTriggerRecommendation(
            managed = managed,
            managedComparable = ComparableVersion("2.15.0"),
            trigger = trigger,
            targetVersion = "3.2.0",
            consumerPaths = emptyList()
        )

        assertNotNull(rec)
        assertTrue(rec!!.isSatisfiedAcrossAllConsumers)
        assertEquals("2.16.1", rec.transitiveVersionInTarget)
        assertEquals("3.2.0", rec.triggerTargetVersion)
    }

    fun testMultiConsumerInconsistencyNegativeCase() {
        val directPomY = """
            <project>
                <groupId>com.example</groupId>
                <artifactId>lib-y</artifactId>
                <version>2.0.0</version>
                <dependencies>
                    <dependency>
                        <groupId>com.example</groupId>
                        <artifactId>shared-lib</artifactId>
                        <version>2.0.0</version>
                    </dependency>
                </dependencies>
            </project>
        """.trimIndent()

        val resolver = TemporaryDependencyTreeResolver { g, a, v ->
            if (g == "com.example" && a == "lib-y" && v == "2.0.0") directPomY else null
        }

        val dummyMavenProject = MavenProject(myFixture.configureByText("pom.xml", "<project></project>").virtualFile)
        val service = ManagedDependencyRecommendationService(
            project = project,
            treeResolver = resolver
        )

        val managed = ManagedDependencyRecommendationService.ManagedDependencyDeclaration(
            groupId = "com.example",
            artifactId = "shared-lib",
            currentVersion = "2.0.0",
            mavenProject = dummyMavenProject
        )

        val triggerY = ManagedDependencyRecommendationService.TriggerCandidate(
            groupId = "com.example",
            artifactId = "lib-y",
            currentVersion = "1.0.0",
            type = "dependency",
            candidateVersions = listOf("2.0.0"),
            mavenProject = dummyMavenProject
        )

        val nodeY = createArtifactNode("com.example", "lib-y", "1.0.0")
        val nodeZ = createArtifactNode("com.example", "lib-z", "1.0.0")
        val sharedNode = createArtifactNode("com.example", "shared-lib", "2.0.0")

        val pathFromY = listOf(nodeY, sharedNode)
        val pathFromZ = listOf(nodeZ, sharedNode)

        val rec = service.evaluateTriggerRecommendation(
            managed = managed,
            managedComparable = ComparableVersion("2.0.0"),
            trigger = triggerY,
            targetVersion = "2.0.0",
            consumerPaths = listOf(pathFromY, pathFromZ)
        )

        assertNotNull(rec)
        assertFalse(rec!!.isSatisfiedAcrossAllConsumers)
    }

    fun testMultiConsumerConsistencyPositiveCase() {
        val parentPom = """
            <project>
                <groupId>com.example</groupId>
                <artifactId>root-parent</artifactId>
                <version>2.0.0</version>
                <dependencyManagement>
                    <dependencies>
                        <dependency>
                            <groupId>com.example</groupId>
                            <artifactId>shared-lib</artifactId>
                            <version>2.5.0</version>
                        </dependency>
                    </dependencies>
                </dependencyManagement>
            </project>
        """.trimIndent()

        val resolver = TemporaryDependencyTreeResolver { g, a, v ->
            if (g == "com.example" && a == "root-parent" && v == "2.0.0") parentPom else null
        }

        val dummyMavenProject = MavenProject(myFixture.configureByText("pom.xml", "<project></project>").virtualFile)
        val service = ManagedDependencyRecommendationService(
            project = project,
            treeResolver = resolver
        )

        val managed = ManagedDependencyRecommendationService.ManagedDependencyDeclaration(
            groupId = "com.example",
            artifactId = "shared-lib",
            currentVersion = "2.0.0",
            mavenProject = dummyMavenProject
        )

        val triggerParent = ManagedDependencyRecommendationService.TriggerCandidate(
            groupId = "com.example",
            artifactId = "root-parent",
            currentVersion = "1.0.0",
            type = "parent",
            candidateVersions = listOf("2.0.0"),
            mavenProject = dummyMavenProject
        )

        val nodeY = createArtifactNode("com.example", "lib-y", "1.0.0")
        val nodeZ = createArtifactNode("com.example", "lib-z", "1.0.0")
        val sharedNode = createArtifactNode("com.example", "shared-lib", "2.0.0")

        val pathFromY = listOf(nodeY, sharedNode)
        val pathFromZ = listOf(nodeZ, sharedNode)

        val rec = service.evaluateTriggerRecommendation(
            managed = managed,
            managedComparable = ComparableVersion("2.0.0"),
            trigger = triggerParent,
            targetVersion = "2.0.0",
            consumerPaths = listOf(pathFromY, pathFromZ)
        )

        assertNotNull(rec)
        assertTrue(rec!!.isSatisfiedAcrossAllConsumers)
        assertEquals(2, rec.consumers.size)
        assertEquals("2.5.0", rec.transitiveVersionInTarget)
    }

    /**
     * Prüft, dass direkte Abhängigkeiten ohne explizite Versionsangabe in der `pom.xml`
     * über die aufgelösten Maven-Abhängigkeiten als Update-Kandidaten erfasst werden.
     */
    fun testCollectTriggerCandidatesWithInheritedDependencyVersion() {
        val pomFile = myFixture.configureByText(
            "pom.xml",
            """
            <project>
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.example</groupId>
                <artifactId>test-project</artifactId>
                <version>1.0.0</version>
                <dependencies>
                    <dependency>
                        <groupId>org.springframework.boot</groupId>
                        <artifactId>spring-boot-starter-test</artifactId>
                        <scope>test</scope>
                    </dependency>
                </dependencies>
            </project>
            """.trimIndent()
        ).virtualFile

        val mavenProject = MavenProject(pomFile).apply {
            updateState(
                listOf(createArtifact("org.springframework.boot", "spring-boot-starter-test", "3.3.5")),
                java.util.Properties(),
                emptyList()
            )
        }

        val service = ManagedDependencyRecommendationService(
            project = project,
            candidateVersionsProvider = { g, a, currentV ->
                if (g == "org.springframework.boot" && a == "spring-boot-starter-test" && currentV == "3.3.5") {
                    listOf("3.3.6")
                } else {
                    emptyList()
                }
            }
        )

        val candidates = service.collectTriggerCandidates(mavenProject, emptyMap())
        assertEquals(1, candidates.size)
        val trigger = candidates.first()
        assertEquals("org.springframework.boot", trigger.groupId)
        assertEquals("spring-boot-starter-test", trigger.artifactId)
        assertEquals("3.3.5", trigger.currentVersion)
        assertEquals("dependency", trigger.type)
        assertEquals(listOf("3.3.5", "3.3.6"), trigger.candidateVersions)
    }

    /**
     * Erstellt eine Beispiel-`pom.xml` für Spring-Boot-Tests mit vererbter Test-Starter-Version
     * und expliziter XMLUnit-Core-Verwaltung.
     */
    private fun createSpringBootTestPomVirtualFile(): com.intellij.openapi.vfs.VirtualFile =
        myFixture.configureByText(
            "pom.xml",
            """
            <project>
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.example</groupId>
                <artifactId>demo-app</artifactId>
                <version>1.0.0</version>
                <parent>
                    <groupId>org.springframework.boot</groupId>
                    <artifactId>spring-boot-starter-parent</artifactId>
                    <version>3.3.5</version>
                </parent>
                <dependencies>
                    <dependency>
                        <groupId>org.springframework.boot</groupId>
                        <artifactId>spring-boot-starter-test</artifactId>
                        <scope>test</scope>
                    </dependency>
                </dependencies>
                <dependencyManagement>
                    <dependencies>
                        <dependency>
                            <groupId>org.xmlunit</groupId>
                            <artifactId>xmlunit-core</artifactId>
                            <version>2.9.1</version>
                            <scope>test</scope>
                        </dependency>
                    </dependencies>
                </dependencyManagement>
            </project>
            """.trimIndent()
        ).virtualFile

    /**
     * Prüft das Nutzerszenario: Eine direkte Abhängigkeit ohne deklarierte Version (`spring-boot-starter-test`,
     * Version über Parent 3.3.5) und ein verwaltetes Artefakt (`xmlunit-core:2.9.1`).
     * Beim Update von `spring-boot-starter-test` auf 3.3.6 wird `xmlunit-core:2.9.1` transitiv bereitgestellt,
     * sodass die Entfernung von `xmlunit-core` aus `dependencyManagement` empfohlen wird.
     */
    fun testSpringBootStarterTestCleanupRecommendationWithInheritedVersion() {
        val springBootTestPom = """
            <project>
                <modelVersion>4.0.0</modelVersion>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-starter-test</artifactId>
                <version>3.3.6</version>
                <dependencies>
                    <dependency>
                        <groupId>org.xmlunit</groupId>
                        <artifactId>xmlunit-core</artifactId>
                        <version>2.9.1</version>
                    </dependency>
                </dependencies>
            </project>
        """.trimIndent()

        val resolver = TemporaryDependencyTreeResolver { g, a, v ->
            if (g == "org.springframework.boot" && a == "spring-boot-starter-test" && v == "3.3.6") springBootTestPom else null
        }

        val mavenProject = MavenProject(createSpringBootTestPomVirtualFile()).apply {
            updateState(
                listOf(createArtifact("org.springframework.boot", "spring-boot-starter-test", "3.3.5")),
                java.util.Properties(),
                emptyList()
            )
        }

        val service = ManagedDependencyRecommendationService(
            project = project,
            treeResolver = resolver,
            candidateVersionsProvider = { g, a, currentV ->
                if (g == "org.springframework.boot" && a == "spring-boot-starter-test" && currentV == "3.3.5") {
                    listOf("3.3.6")
                } else {
                    emptyList()
                }
            }
        )

        val managedList = service.collectManagedDependencies(mavenProject)
        assertEquals(1, managedList.size)
        assertEquals("org.xmlunit", managedList.first().groupId)
        assertEquals("xmlunit-core", managedList.first().artifactId)
        assertEquals("2.9.1", managedList.first().currentVersion)

        val triggerCandidates = service.collectTriggerCandidates(mavenProject, emptyMap())
        val starterCandidate = triggerCandidates.find { it.artifactId == "spring-boot-starter-test" }
        assertNotNull(starterCandidate)

        val starterNode = createArtifactNode("org.springframework.boot", "spring-boot-starter-test", "3.3.5")
        val xmlUnitNode = createArtifactNode("org.xmlunit", "xmlunit-core", "2.9.1")

        val rec = service.evaluateTriggerRecommendation(
            managed = managedList.first(),
            managedComparable = ComparableVersion("2.9.1"),
            trigger = starterCandidate!!,
            targetVersion = "3.3.6",
            consumerPaths = listOf(listOf(starterNode, xmlUnitNode))
        )

        assertNotNull(rec)
        assertTrue(rec!!.isSatisfiedAcrossAllConsumers)
        assertEquals("org.xmlunit", rec.managedGroupId)
        assertEquals("xmlunit-core", rec.managedArtifactId)
        assertEquals("2.9.1", rec.managedCurrentVersion)
        assertEquals("org.springframework.boot", rec.triggerGroupId)
        assertEquals("spring-boot-starter-test", rec.triggerArtifactId)
        assertEquals("3.3.6", rec.triggerTargetVersion)
        assertEquals("2.9.1", rec.transitiveVersionInTarget)
        assertEquals(1, rec.consumers.size)
        assertEquals("spring-boot-starter-test:3.3.5 -> xmlunit-core:2.9.1", rec.consumers.first().pathDescription)
    }

    /**
     * Prüft das Nutzerszenario für die aktuelle Version:
     * `spring-boot-starter-test` ist in Version 3.3.5 eingebunden und stellt bereits in dieser aktuellen Version
     * `org.xmlunit:xmlunit-core:2.9.1` transitiv bereit. Das in `dependencyManagement` deklarierte `xmlunit-core:2.9.1`
     * wird korrekt als redundanter Eintrag erkannt, auch wenn keine neuere Version von `spring-boot-starter-test` existiert.
     */
    fun testSpringBootStarterTestCleanupRecommendationWithCurrentVersion() {
        val springBootTestPom = """
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

        val resolver = TemporaryDependencyTreeResolver { g, a, v ->
            if (g == "org.springframework.boot" && a == "spring-boot-starter-test" && v == "3.3.5") springBootTestPom else null
        }

        val starterNode = createArtifactNode("org.springframework.boot", "spring-boot-starter-test", "3.3.5")
        val xmlUnitNode = createArtifactNode("org.xmlunit", "xmlunit-core", "2.9.1")
        val consumerPath = listOf(starterNode, xmlUnitNode)

        val mavenProject = MavenProject(createSpringBootTestPomVirtualFile()).apply {
            updateState(
                listOf(starterNode.artifact),
                java.util.Properties(),
                emptyList()
            )
        }

        val service = ManagedDependencyRecommendationService(
            project = project,
            treeResolver = resolver,
            candidateVersionsProvider = { _, _, _ -> emptyList() }
        )

        val managedList = service.collectManagedDependencies(mavenProject)
        assertEquals(1, managedList.size)
        val managed = managedList.first()

        val triggerCandidates = service.collectTriggerCandidates(mavenProject, emptyMap())
        val starterCandidate = triggerCandidates.find { it.artifactId == "spring-boot-starter-test" }
        assertNotNull(starterCandidate)
        assertEquals("3.3.5", starterCandidate!!.currentVersion)
        assertEquals(listOf("3.3.5"), starterCandidate.candidateVersions)

        val rec = service.evaluateTriggerRecommendation(
            managed = managed,
            managedComparable = ComparableVersion("2.9.1"),
            trigger = starterCandidate,
            targetVersion = "3.3.5",
            consumerPaths = listOf(consumerPath)
        )

        assertNotNull(rec)
        assertTrue(rec!!.isSatisfiedAcrossAllConsumers)
        assertEquals("org.xmlunit", rec.managedGroupId)
        assertEquals("xmlunit-core", rec.managedArtifactId)
        assertEquals("2.9.1", rec.managedCurrentVersion)
        assertEquals("org.springframework.boot", rec.triggerGroupId)
        assertEquals("spring-boot-starter-test", rec.triggerArtifactId)
        assertEquals("3.3.5", rec.triggerCurrentVersion)
        assertEquals("3.3.5", rec.triggerTargetVersion)
        assertEquals("2.9.1", rec.transitiveVersionInTarget)
        assertEquals(1, rec.consumers.size)
        assertEquals("spring-boot-starter-test:3.3.5 -> xmlunit-core:2.9.1", rec.consumers.first().pathDescription)
    }
}
