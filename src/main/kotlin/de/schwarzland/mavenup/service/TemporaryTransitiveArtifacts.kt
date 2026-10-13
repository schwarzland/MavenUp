package de.schwarzland.mavenup.service

import de.schwarzland.mavenup.model.TemporaryDependencyNode

/**
 * Sammelt eindeutig beobachtete transitive Versionen, ohne Maven-Versionsvermittlung zu behaupten.
 * Bei mehreren Versionen derselben Koordinate wird keine Version als wirksam ausgegeben.
 */
internal object TemporaryTransitiveArtifacts {

    /**
     * Erfasst alle Kindpfade mit Identitätszyklenprüfung und lässt widersprüchliche Koordinaten aus.
     *
     * @param onConflict Diagnose-Callback je Koordinate mit mehreren beobachteten Versionen.
     * @return Nur Koordinaten mit genau einer nicht leeren beobachteten Version.
     */
    fun collect(
        root: TemporaryDependencyNode,
        onConflict: (String, Set<String>) -> Unit = { _, _ -> }
    ): Map<String, String> {
        val versions = linkedMapOf<String, Set<String>>()
        collectVersions(root, listOf(root), versions)
        return versions.mapNotNull { (key, observed) ->
            if (observed.size > 1) onConflict(key, observed.toSet())
            observed.singleOrNull()?.takeIf { it.isNotBlank() }?.let { key to it }
        }.toMap()
    }

    /** Besucht jeden Kindpfad unabhängig; gleiche Koordinaten ersetzen keine Knotenidentität. */
    private fun collectVersions(
        node: TemporaryDependencyNode,
        currentPath: List<TemporaryDependencyNode>,
        versions: MutableMap<String, Set<String>>
    ) {
        for (child in node.dependencies) {
            if (currentPath.any { it === child }) continue
            val coordinate = child.coordinate
            val key = "${coordinate.groupId}:${coordinate.artifactId}"
            versions[key] = versions[key].orEmpty() + coordinate.version
            collectVersions(child, currentPath + child, versions)
        }
    }
}
