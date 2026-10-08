package de.schwarzland.mavenup.service

import de.schwarzland.mavenup.model.ManagedDependencyRemovalRecommendation

/**
 * Ergebnis einer Bereinigungsanalyse einschließlich erkannter Lücken in den Quelldaten.
 *
 * @property recommendations Vollständig validierte Bereinigungsempfehlungen.
 * @property incompleteLookups Koordinaten, deren Versions- oder POM-Daten nicht verfügbar waren.
 */
internal data class ManagedDependencyRecommendationResult(
    val recommendations: List<ManagedDependencyRemovalRecommendation>,
    val incompleteLookups: Set<String>
) {
    /** Gibt an, ob mindestens eine Datenabfrage fehlgeschlagen oder unvollständig war. */
    val isIncomplete: Boolean
        get() = incompleteLookups.isNotEmpty()
}
