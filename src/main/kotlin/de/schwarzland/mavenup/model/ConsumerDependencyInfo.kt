package de.schwarzland.mavenup.model

/**
 * Beschreibt einen Konsumenten bzw. einen Abhängigkeitspfad im Projekt, der eine verwaltete Abhängigkeit verwendet.
 *
 * @property groupId Die Group-ID des Konsumenten (z. B. der direkten Abhängigkeit).
 * @property artifactId Die Artefakt-ID des Konsumenten.
 * @property resolvedVersion Die aufgelöste transitive Version unter der Zielversion bzw. im Ist-Zustand.
 * @property pathDescription Die lesbare Pfadbeschreibung des Abhängigkeitsbaums.
 */
data class ConsumerDependencyInfo(
    val groupId: String,
    val artifactId: String,
    val resolvedVersion: String,
    val pathDescription: String
)
