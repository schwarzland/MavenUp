package de.schwarzland.mavenup.service

import org.jetbrains.idea.maven.model.MavenArtifact
import org.jetbrains.idea.maven.model.MavenArtifactNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Prüft identitätsbasierte Pfadsuche und Vorkommensprüfung ohne Projektzustand. */
class MavenArtifactTraversalTest {
    /** Prüft gleiche Blatt-/Teilbaumkoordinaten sowie gleiche Vorfahreninstanzen als getrennte Knoten. */
    @Test
    fun repeatedCoordinatesDoNotHideDescendants() {
        val target = node("target")
        val leaf = node("core")
        val full = node("core", target)
        val root = node("core", node("client", leaf), full)
        assertEquals(leaf, full)
        assertTrue(MavenArtifactTraversal.containsArtifact(root, "g", "target"))
        val paths = paths(root)
        assertEquals(1, paths.size)
        assertSame(full, paths.single()[1])
    }

    /** Prüft alle Geschwisterpfade zu einem gemeinsam genutzten Teilbaum. */
    @Test
    fun sharedNodesRemainReachableOnEveryPath() {
        val shared = node("shared", node("target"))
        val root = node("root", node("first", shared), node("second", shared))
        assertEquals(listOf("first", "second"), paths(root).map { it[1].artifact.artifactId })
        assertTrue(MavenArtifactTraversal.containsArtifact(root, "g", "target"))
    }

    /** Prüft echte Zyklen, fehlendes Ziel und nicht initialisierte Kindlisten. */
    @Test
    fun cyclesAndNullChildrenTerminateSafely() {
        val branch = node("branch")
        val root = node("root", branch)
        branch.dependencies = listOf(root, node("target"))
        assertEquals(1, paths(root).size)
        assertFalse(MavenArtifactTraversal.containsArtifact(root, "", "missing"))
        assertTrue(paths(root, "missing").isEmpty())
        val uninitialized = MavenArtifactNode(null, root.artifact, null, null, null, null, null)
        assertTrue(paths(uninitialized).isEmpty())
        assertFalse(MavenArtifactTraversal.containsArtifact(uninitialized, "g", "target"))
        assertTrue(MavenArtifactTraversal.containsArtifact(node("target"), "g", "target"))
        assertEquals(1, paths(node("target")).single().size)
    }

    /** Prüft die Abbruchprüfung innerhalb des Baums für beide Suchvarianten. */
    @Test
    fun cancellationIsCheckedForEachVisitedNode() {
        val root = node("root", node("branch", node("target")))
        for (searchPaths in listOf(false, true)) {
            var checks = 0
            val cancellation = { checks++; if (checks == 2) error("cancelled") }
            try {
                if (searchPaths) {
                    MavenArtifactTraversal.findPathsToTarget(root, "g", "target", emptyList(), mutableListOf(), cancellation)
                } else {
                    MavenArtifactTraversal.containsArtifact(root, "g", "target", cancellation)
                }
                fail("Expected cancellation")
            } catch (exception: IllegalStateException) {
                assertEquals("cancelled", exception.message)
                assertEquals(2, checks)
            }
        }
    }

    /** Sammelt die Pfade mit leerem Startpfad. */
    private fun paths(root: MavenArtifactNode, target: String = "target"): List<List<MavenArtifactNode>> =
        mutableListOf<List<MavenArtifactNode>>().also {
            MavenArtifactTraversal.findPathsToTarget(root, "g", target, emptyList(), it)
        }

    /** Erzeugt Knoten über den öffentlichen Setter der Maven-API. */
    private fun node(artifactId: String, vararg children: MavenArtifactNode): MavenArtifactNode {
        val artifact = MavenArtifact("g", artifactId, "1", null, "jar", null, "compile", false, "jar", null, null, true, false)
        return MavenArtifactNode(null, artifact, null, null, null, null, null).apply {
            dependencies = children.toList()
        }
    }
}
