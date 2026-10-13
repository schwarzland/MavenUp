package de.schwarzland.mavenup.service

import de.schwarzland.mavenup.model.TemporaryArtifactCoordinate
import de.schwarzland.mavenup.model.TemporaryDependencyNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Prüft konservative Versionssammlung unabhängig von Maven-Reihenfolge und Graphgleichheit. */
class TemporaryTransitiveArtifactsTest {
    /** Prüft Konflikte in beiden Reihenfolgen und bei gleicher sowie verschiedener Tiefe. */
    @Test
    fun conflictsAreOmittedRegardlessOfOrderOrDepth() {
        for (nested in listOf(false, true)) {
            for (reversed in listOf(false, true)) {
                val lower = node("target", "1")
                val higher = node("target", "2")
                val second = if (nested) node("branch", "1", higher) else higher
                val children = listOf(lower, second).let { if (reversed) it.reversed() else it }
                val conflicts = linkedMapOf<String, Set<String>>()
                val result = TemporaryTransitiveArtifacts.collect(node("root", "1", *children.toTypedArray())) { key, versions ->
                    conflicts[key] = versions
                }
                assertFalse(result.containsKey("g:target"))
                assertEquals(setOf("1", "2"), conflicts["g:target"])
                assertFalse(result.containsKey("g:root"))
            }
        }
    }

    /** Prüft, dass ein gleiches Blatt den vollständigen Teilbaum und dessen Konflikte nicht verdeckt. */
    @Test
    fun repeatedLeafAndSharedSubtreesAreFullyCollected() {
        val shared = node("shared", "1", node("target", "2"))
        val root = node(
            "root", "1", node("core", "1"), node("core", "1", shared),
            node("other", "1", shared), node("target", "1")
        )
        val result = TemporaryTransitiveArtifacts.collect(root)
        assertFalse(result.containsKey("g:target"))
        assertEquals("1", result["g:shared"])
        assertEquals("1", result["g:core"])
    }

    /** Prüft leere Bäume, fehlende Versionen, einheitliche Versionen und echte Identitätszyklen. */
    @Test
    fun emptyVersionsAndCyclesAreHandledWithoutEqualityRecursion() {
        assertTrue(TemporaryTransitiveArtifacts.collect(node("empty", "1")).isEmpty())
        val root = node("root", "1")
        val branch = node("branch", "1", root, node("target", "2"), node("missing", ""))
        root.dependencies.add(branch)
        root.dependencies.add(node("target", "2"))
        assertEquals(mapOf("g:branch" to "1", "g:target" to "2"), TemporaryTransitiveArtifacts.collect(root))
    }

    /** Erzeugt einen veränderlichen temporären Knoten. */
    private fun node(artifactId: String, version: String, vararg children: TemporaryDependencyNode): TemporaryDependencyNode =
        TemporaryDependencyNode(TemporaryArtifactCoordinate("g", artifactId, version), children.toMutableList())
}
