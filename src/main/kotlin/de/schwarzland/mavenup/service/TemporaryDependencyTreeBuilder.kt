package de.schwarzland.mavenup.service

import de.schwarzland.mavenup.model.TemporaryArtifactCoordinate
import de.schwarzland.mavenup.model.TemporaryDependencyNode

/**
 * Baut pfadlokale temporäre Bäume und meldet nicht vollständig untersuchbare Abhängigkeiten.
 *
 * @param resolvePom Liefert das effektive POM-Modell für eine Koordinate.
 * @param onIssue Erfasst Diagnosen abgeschnittener oder nicht auflösbarer Abhängigkeiten.
 */
internal class TemporaryDependencyTreeBuilder(
    private val resolvePom: (TemporaryArtifactCoordinate) -> TemporaryDependencyTreeResolver.EffectivePomModel?,
    private val onIssue: (String) -> Unit
) {
    /**
     * Baut einen Teilbaum ohne globale Koordinatendeduplizierung.
     *
     * @param ancestors Koordinaten des aktuellen Pfads zur Begrenzung echter POM-Zyklen.
     * @param maxDepth Maximale verbleibende Tiefe; Abschneiden erzeugt eine Diagnose.
     */
    fun build(
        coordinate: TemporaryArtifactCoordinate,
        maxDepth: Int,
        ancestors: Set<String> = emptySet()
    ): TemporaryDependencyNode {
        val key = "${coordinate.groupId}:${coordinate.artifactId}:${coordinate.version}"
        val root = TemporaryDependencyNode(coordinate)
        if (maxDepth <= 0 || key in ancestors) {
            onIssue("Truncated dependency tree: $key")
            return root
        }
        val pom = resolvePom(coordinate) ?: return root
        root.dependencyManagement.putAll(pom.dependencyManagement)
        val relevantDependencies = pom.dependencies.filter {
            !it.optional && it.scope?.lowercase() !in listOf("test", "provided", "system")
        }
        for (dependency in relevantDependencies) {
            val version = dependency.version
            if (version.isNullOrBlank() || version.contains("\${")) {
                onIssue("Unresolved dependency: ${dependency.groupId}:${dependency.artifactId}")
                continue
            }
            val child = build(
                TemporaryArtifactCoordinate(dependency.groupId, dependency.artifactId, version),
                maxDepth - 1,
                ancestors + key
            )
            root.dependencies.add(child.copy(scope = dependency.scope, optional = dependency.optional))
        }
        return root
    }
}
