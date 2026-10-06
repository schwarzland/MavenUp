package de.schwarzland.mavenup.service

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
}
