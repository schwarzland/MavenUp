package de.schwarzland.mavenup.ui

/**
 * Repräsentiert eine einzelne Zeile in der Abhängigkeitstabelle.
 *
 * @property groupId Die GroupId der Abhängigkeit.
 * @property artifactId Die ArtifactId der Abhängigkeit.
 * @property propertyName Der Name der Version-Property oder ein leerer String.
 * @property type Der Typ der Abhängigkeit (z. B. Dependency, Parent, managed plugin).
 * @property currentVersion Die aktuell im Projekt verwendete Version.
 */
internal data class RefreshRow(
    val groupId: String,
    val artifactId: String,
    val propertyName: String,
    val type: String,
    val currentVersion: String
) {
    /** Eindeutiger Schlüssel der Zeile in der Form `groupId:artifactId`. */
    val key: String = "$groupId:$artifactId"
}

/**
 * Enthält einen Schnappschuss aller relevanten Projektdaten für die Anzeige im Tool Window.
 *
 * @property rows Die darzustellenden Abhängigkeitszeilen.
 * @property dependencyProperties Zuordnung von Abhängigkeitsschlüssel zu Property-Name.
 */
internal data class RefreshSnapshot(
    val rows: List<RefreshRow>,
    val dependencyProperties: Map<String, String>
)
