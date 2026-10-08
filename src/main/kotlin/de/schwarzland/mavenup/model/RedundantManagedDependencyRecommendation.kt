package de.schwarzland.mavenup.model

/**
 * Repräsentiert eine Empfehlung zur Entfernung einer redundanten verwalteten Abhängigkeit
 * aus `<dependencyManagement>` auf Basis des aktuellen Projekt- und Versionsstands (Ist-Zustand).
 *
 * @property groupId Die Group-ID der verwalteten Abhängigkeit.
 * @property artifactId Die Artefakt-ID der verwalteten Abhängigkeit.
 * @property currentVersion Die aktuell im Projekt gepinnte Version der verwalteten Abhängigkeit.
 * @property reason Der ermittelte Grund für die Redundanz.
 * @property reasonDetail Eine textuelle Erklärung oder Spezifikation der Redundanzursache.
 * @property providedVersion Die durch Parent, direkte oder transitive Abhängigkeiten bereitgestellte Version (oder `null` bei ungenutzten Einträgen).
 * @property consumers Die Liste aller Konsumenten bzw. Abhängigkeitspfade im Projekt, die die verwaltete Abhängigkeit nutzen.
 * @property sourceProjectId Maven-Koordinate bzw. Bezeichner des Moduls, aus dessen POM diese Empfehlung stammt.
 * @property sourcePomPath Pfad zur POM-Datei, aus der diese Empfehlung stammt.
 */
data class RedundantManagedDependencyRecommendation(
    val groupId: String,
    val artifactId: String,
    val currentVersion: String,
    val reason: RedundancyReason,
    val reasonDetail: String,
    val providedVersion: String? = null,
    val consumers: List<ConsumerDependencyInfo> = emptyList(),
    val sourceProjectId: String = "",
    val sourcePomPath: String = ""
)
