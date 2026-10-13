package de.schwarzland.mavenup.service

import de.schwarzland.mavenup.model.TemporaryArtifactCoordinate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Prüft pfadlokalen Baumaufbau und konservative Diagnosen bei abgeschnittenen Pfaden. */
class TemporaryDependencyTreeBuilderTest {
    /** Prüft gemeinsame Koordinaten ohne globale Trunkierung sowie echte Koordinatenzyklen. */
    @Test
    fun sharedCoordinatesAreExpandedAndCyclesReported() {
        val issues = mutableListOf<String>()
        val dependencies = mapOf("root" to listOf("left", "core"), "left" to listOf("core"), "core" to listOf("target"))
        val builder = builder(dependencies, issues)
        val tree = builder.build(coordinate("root"), 10)
        assertEquals("target", tree.dependencies[0].dependencies[0].dependencies.single().coordinate.artifactId)
        assertEquals("target", tree.dependencies[1].dependencies.single().coordinate.artifactId)
        assertTrue(issues.isEmpty())
        builder(mapOf("root" to listOf("branch"), "branch" to listOf("root")), issues).build(coordinate("root"), 10)
        assertEquals(listOf("Truncated dependency tree: g:root:1"), issues)
    }

    /** Prüft Tiefenbegrenzung, unbekanntes POM und unveränderte Vorfahrenmenge. */
    @Test
    fun depthMissingPomAndAncestorInputAreHandled() {
        val issues = mutableListOf<String>()
        val ancestors = mutableSetOf("g:ancestor:1")
        val tree = builder(mapOf("root" to listOf("target")), issues).build(coordinate("root"), 1, ancestors)
        assertTrue(tree.dependencies.single().dependencies.isEmpty())
        assertEquals(listOf("Truncated dependency tree: g:target:1"), issues)
        assertEquals(setOf("g:ancestor:1"), ancestors)
        val missing = TemporaryDependencyTreeBuilder({ null }, { issues.add(it) }).build(coordinate("missing"), 10)
        assertTrue(missing.dependencies.isEmpty())
    }

    /** Prüft fehlende Versionen bei relevanten Abhängigkeiten und Ausschluss optionaler/Test-Abhängigkeiten. */
    @Test
    fun unresolvedRelevantVersionsAreReported() {
        val issues = mutableListOf<String>()
        val dependencies = listOf(
            dependency("missing", null), dependency("placeholder", "\${unknown}"),
            dependency("optional", null).copy(optional = true), dependency("test", null).copy(scope = "test")
        )
        val builder = TemporaryDependencyTreeBuilder(
            { TemporaryDependencyTreeResolver.EffectivePomModel(it, emptyMap(), mapOf("g:managed" to "2"), dependencies) },
            { issues.add(it) }
        )
        val tree = builder.build(coordinate("root"), 10)
        assertTrue(tree.dependencies.isEmpty())
        assertEquals(mapOf("g:managed" to "2"), tree.dependencyManagement)
        assertEquals(listOf("Unresolved dependency: g:missing", "Unresolved dependency: g:placeholder"), issues)
    }

    /** Erstellt einen netzwerkfreien Modelllieferanten. */
    private fun builder(
        dependencies: Map<String, List<String>>,
        issues: MutableList<String>
    ): TemporaryDependencyTreeBuilder = TemporaryDependencyTreeBuilder(
        { coordinate ->
            TemporaryDependencyTreeResolver.EffectivePomModel(
                coordinate, emptyMap(), emptyMap(), dependencies[coordinate.artifactId].orEmpty().map { dependency(it, "1") }
            )
        },
        { issues.add(it) }
    )

    /** Erstellt Rohdaten einer aktiven Abhängigkeit. */
    private fun dependency(artifactId: String, version: String?): TemporaryDependencyTreeResolver.RawDependencyData =
        TemporaryDependencyTreeResolver.RawDependencyData("g", artifactId, version, "compile", "jar", false)

    /** Erstellt eine Testkoordinate. */
    private fun coordinate(artifactId: String): TemporaryArtifactCoordinate = TemporaryArtifactCoordinate("g", artifactId, "1")
}
