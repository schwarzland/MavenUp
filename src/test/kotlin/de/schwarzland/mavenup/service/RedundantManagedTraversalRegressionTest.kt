package de.schwarzland.mavenup.service

import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.util.ProgressIndicatorBase
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.apache.maven.artifact.versioning.ComparableVersion
import org.jetbrains.idea.maven.model.MavenArtifact
import org.jetbrains.idea.maven.model.MavenArtifactNode
import org.jetbrains.idea.maven.model.MavenExplicitProfiles
import org.jetbrains.idea.maven.model.MavenId
import org.jetbrains.idea.maven.model.MavenModel
import org.jetbrains.idea.maven.project.MavenProject
import java.nio.file.Path

/** Prüft vollständige Verbrauchersuche und konservative Redundanzentscheidungen bei Versionskonflikten. */
class RedundantManagedTraversalRegressionTest : BasePlatformTestCase() {
    /** Prüft wiederholte Blatt-/Teilbaumkoordinaten, gleiche Vorfahren und gemeinsam genutzte Knoten. */
    fun testRepeatedAndSharedNodesAreUsedAndAllConsumersFound() {
        val target = node("target", "2")
        val full = node("core", "1", target)
        val root = node("core", "1", node("client", "1", node("core", "1")), full, node("other", "1", full))
        val mavenProject = mavenProject(root)
        val service = RedundantManagedDependencyService(project, TemporaryDependencyTreeResolver { _, _, _ -> null })
        assertTrue(service.isUsedInAnyProject(listOf(mavenProject), "g", "target", null))
        val paths = service.findProjectConsumersForManaged(mavenProject, "g", "target", null)
        assertEquals(2, paths.size)
        assertEquals(listOf(3, 4), paths.map { it.size })
        assertTrue(service.findRedundantForProject(mavenProject, listOf(mavenProject), null, null).isEmpty())
    }

    /** Prüft echte Zyklen, leere Projektlisten, fehlende Ziele und Abbruch bei der Verbrauchersuche. */
    fun testCyclesEmptyInputAndCancellation() {
        val branch = node("branch", "1")
        val root = node("root", "1", branch)
        branch.dependencies = listOf(root, node("target", "2"))
        val mavenProject = mavenProject(root)
        val service = RedundantManagedDependencyService(project)
        assertEquals(1, service.findProjectConsumersForManaged(mavenProject, "g", "target", null).size)
        assertTrue(service.findProjectConsumersForManaged(mavenProject, "", "", null).isEmpty())
        assertFalse(service.isUsedInAnyProject(emptyList(), "g", "target", null))
        assertFalse(service.isUsedInAnyProject(listOf(mavenProject), "g", "missing", null))
        val indicator = ProgressIndicatorBase().apply { cancel() }
        try {
            service.findProjectConsumersForManaged(mavenProject, "g", "target", indicator)
            fail("Expected cancellation")
        } catch (_: ProcessCanceledException) {
            assertTrue(indicator.isCanceled)
        }
    }

    /** Prüft, dass Konflikte bei jeder Reihenfolge/Tiefe weder TRANSITIVE_MATCH noch UNUSED erzeugen. */
    fun testConflictingVersionsNeverRecommendRemovalWithoutFallback() {
        for (nested in listOf(false, true)) {
            for (reversed in listOf(false, true)) {
                val versions = listOf("1", "3").let { if (reversed) it.reversed() else it }
                val rootPom = pom("root", "1", listOf("target" to versions[0], "branch" to "1"))
                val branchPom = if (nested) pom("branch", "1", listOf("middle" to "1"))
                    else pom("branch", "1", listOf("target" to versions[1]))
                val resolver = resolver(
                    mapOf(
                        "root:1" to rootPom, "branch:1" to branchPom,
                        "middle:1" to pom("middle", "1", listOf("target" to versions[1])),
                        "target:1" to pom("target", "1"), "target:3" to pom("target", "3")
                    )
                )
                val root = node("root", "1", node("core", "1"), node("core", "1", node("target", "2")))
                val mavenProject = mavenProject(root)
                val service = RedundantManagedDependencyService(project, resolver)
                assertTrue(service.findRedundantForProject(mavenProject, listOf(mavenProject), null, null).isEmpty())
                assertFalse(resolver.resolveTransitiveDependencies("g", "root", "1").containsKey("g:target"))
                assertTrue(resolver.transitiveResolutionIssues.isEmpty())
            }
        }
    }

    /** Prüft, dass autoritatives Parent-Management trotz transitiver Versionskonflikte verfügbar bleibt. */
    fun testParentFallbackSurvivesConflictingTransitives() {
        val root = node("root", "1", node("target", "2"))
        val mavenProject = mavenProject(root, withParent = true)
        val projectXml = mavenProject.file.contentsToByteArray().toString(Charsets.UTF_8)
        val resolver = resolver(
            mapOf(
                "app:1" to projectXml,
                "parent:1" to pom("parent", "1").replace(
                    "</project>",
                    "<dependencyManagement><dependencies>${dependency("target", "2")}</dependencies></dependencyManagement></project>"
                ),
                "root:1" to pom("root", "1", listOf("target" to "2", "target" to "3")),
                "target:2" to pom("target", "2"), "target:3" to pom("target", "3")
            )
        )
        assertFalse(resolver.resolveTransitiveDependencies("g", "root", "1").containsKey("g:target"))
        val service = RedundantManagedDependencyService(project, resolver)
        val managed = service.collectManagedDependencies(mavenProject).single()
        val recommendation = service.evaluateTransitiveMatch(
            managed, mavenProject, ComparableVersion("2"), listOf(listOf(root, root.dependencies.single())), null
        )
        assertNotNull(recommendation)
        assertEquals("2", recommendation!!.providedVersion)
        assertEquals("2", service.resolveFallbackManagedVersionWithoutLocalEntry(mavenProject, "g:target"))
    }

    /** Prüft fehlende/zyklische Teilbäume und Versionskonflikte aus unterschiedlichen direkten Wurzeln. */
    fun testIncompleteTreeAndCrossRootConflictsAreRejected() {
        val first = node("first", "1", node("target", "2"))
        val second = node("second", "1", node("target", "3"))
        val mavenProject = mavenProject(first, second)
        for (mode in listOf("conflict", "missing", "cycle")) {
            val poms = mutableMapOf(
                "first:1" to pom("first", "1", listOf("target" to "2")),
                "second:1" to pom("second", "1", listOf("target" to "3")),
                "target:2" to pom("target", "2"), "target:3" to pom("target", "3")
            )
            if (mode == "missing") poms["first:1"] = pom("first", "1", listOf("target" to "2", "missing" to "1"))
            if (mode == "cycle") poms["first:1"] = pom("first", "1", listOf("target" to "2", "first" to "1"))
            val service = RedundantManagedDependencyService(project, resolver(poms))
            assertTrue(service.findRedundantForProject(mavenProject, listOf(mavenProject), null, null).isEmpty())
        }
    }

    /** Initialisiert Maven-Projekt und Baum ausschließlich über öffentliche APIs. */
    private fun mavenProject(vararg roots: MavenArtifactNode, withParent: Boolean = false): MavenProject {
        val parent = if (withParent) "<parent><groupId>g</groupId><artifactId>parent</artifactId><version>1</version></parent>" else ""
        val xml = pom("app", "1").replace(
            "</project>",
            "$parent<dependencyManagement><dependencies>${dependency("target", "2")}</dependencies></dependencyManagement></project>"
        )
        val file = myFixture.configureByText("pom.xml", xml).virtualFile
        val model = MavenModel().apply {
            mavenId = MavenId("g", "app", "1")
            dependencyTree = roots.toList()
            dependencies = roots.map { it.artifact }
        }
        return MavenProject(file).apply {
            updateState(
                model, emptyList(), null, emptyList(), MavenExplicitProfiles.NONE, emptySet(),
                emptyMap(), Path.of("build", "test-maven-repository").toAbsolutePath(), false
            )
        }
    }

    /** Erstellt einen Knoten mit expliziten Kindern. */
    private fun node(artifactId: String, version: String, vararg children: MavenArtifactNode): MavenArtifactNode {
        val artifact = MavenArtifact("g", artifactId, version, null, "jar", null, "compile", false, "jar", null, null, true, false)
        return MavenArtifactNode(null, artifact, null, null, null, null, null).apply { dependencies = children.toList() }
    }

    /** Liefert Test-POMs ohne Netzwerkzugriff. */
    private fun resolver(poms: Map<String, String>): TemporaryDependencyTreeResolver =
        TemporaryDependencyTreeResolver { _, artifactId, version -> poms["$artifactId:$version"] }

    /** Erstellt ein minimales POM mit aktiven Abhängigkeiten. */
    private fun pom(artifactId: String, version: String, dependencies: List<Pair<String, String>> = emptyList()): String =
        "<project><groupId>g</groupId><artifactId>$artifactId</artifactId><version>$version</version>" +
            "<dependencies>${dependencies.joinToString("") { dependency(it.first, it.second) }}</dependencies></project>"

    /** Erstellt die XML-Deklaration einer Abhängigkeit. */
    private fun dependency(artifactId: String, version: String): String =
        "<dependency><groupId>g</groupId><artifactId>$artifactId</artifactId><version>$version</version></dependency>"
}
