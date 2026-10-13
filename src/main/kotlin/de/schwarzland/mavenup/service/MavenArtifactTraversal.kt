package de.schwarzland.mavenup.service

import org.jetbrains.idea.maven.model.MavenArtifactNode

/**
 * Durchsucht Maven-Knoten mit pfadlokaler Identitätsprüfung statt Artefaktgleichheit.
 * Gleiche Koordinaten und gemeinsam genutzte Teilbäume bleiben auf jedem Pfad erreichbar.
 */
internal object MavenArtifactTraversal {

    /**
     * Sammelt Pfade zur Zielkoordinate und beendet nur echte Identitätszyklen.
     *
     * @param checkCanceled Abbruchprüfung, die vor jedem Knoten ausgeführt wird.
     */
    fun findPathsToTarget(
        currentNode: MavenArtifactNode,
        targetGroupId: String,
        targetArtifactId: String,
        currentPath: List<MavenArtifactNode>,
        result: MutableList<List<MavenArtifactNode>>,
        checkCanceled: () -> Unit = {}
    ) {
        checkCanceled()
        if (currentPath.any { it === currentNode }) return
        val path = currentPath + currentNode
        val artifact = currentNode.artifact
        if (artifact.groupId == targetGroupId && artifact.artifactId == targetArtifactId) {
            result.add(path)
            return
        }
        for (child in currentNode.dependencies.orEmpty()) {
            findPathsToTarget(child, targetGroupId, targetArtifactId, path, result, checkCanceled)
        }
    }

    /**
     * Prüft das Vorkommen einer Koordinate mit kurzschließender, pfadlokaler Tiefensuche.
     *
     * @param checkCanceled Abbruchprüfung, die auch innerhalb tiefer Teilbäume ausgeführt wird.
     */
    fun containsArtifact(
        node: MavenArtifactNode,
        targetGroupId: String,
        targetArtifactId: String,
        checkCanceled: () -> Unit = {},
        currentPath: List<MavenArtifactNode> = emptyList()
    ): Boolean {
        checkCanceled()
        if (currentPath.any { it === node }) return false
        val artifact = node.artifact
        if (artifact.groupId == targetGroupId && artifact.artifactId == targetArtifactId) return true
        val path = currentPath + node
        return node.dependencies.orEmpty().any {
            containsArtifact(it, targetGroupId, targetArtifactId, checkCanceled, path)
        }
    }
}
