package de.schwarzland.mavenup.model

/**
 * Beschreibt die Wirkung einer Zielversion auf eine verwaltete Abhängigkeit.
 *
 * @property version Die geprüfte Zielversion der auslösenden Komponente.
 * @property transitiveVersionInTarget Die von dieser Zielversion bereitgestellte Version.
 * @property consumers Die zu dieser Zielversion ermittelten Konsumenten-Pfade.
 */
data class ManagedDependencyTargetVersion(
    val version: String,
    val transitiveVersionInTarget: String,
    val consumers: List<ConsumerDependencyInfo>
)

/**
 * Repräsentiert eine Empfehlung zur Bereinigung und Entfernung einer verwalteten Abhängigkeit
 * aus `<dependencyManagement>`, wenn ein übergeordnetes POM (`<parent>`) oder eine direkte
 * Abhängigkeit auf eine neuere Version aktualisiert wird, die das Artefakt transitiv in einer
 * kompatiblen Version bereitstellt.
 *
 * @property managedGroupId Die Group-ID der verwalteten Abhängigkeit.
 * @property managedArtifactId Die Artefakt-ID der verwalteten Abhängigkeit.
 * @property managedCurrentVersion Die aktuell im Projekt gepinnte Version der verwalteten Abhängigkeit.
 * @property triggerGroupId Die Group-ID der auslösenden Komponente (Parent oder direkte Dependency).
 * @property triggerArtifactId Die Artefakt-ID der auslösenden Komponente.
 * @property triggerType Der Typ der auslösenden Komponente (`"parent"` oder `"dependency"`).
 * @property triggerCurrentVersion Die aktuelle Version der auslösenden Komponente.
 * @property triggerTargetVersion Die empfohlene Zielversion der auslösenden Komponente.
 * @property transitiveVersionInTarget Die durch die Zielversion transitiv bereitgestellte Version der verwalteten Abhängigkeit.
 * @property consumers Die Liste aller Konsumenten bzw. Abhängigkeitspfade im Projekt, die die verwaltete Abhängigkeit nutzen.
 * @property isSatisfiedAcrossAllConsumers `true`, wenn alle Konsumenten eine kompatible Version (`>= managedCurrentVersion`) erhalten.
 * @property targetVersionOptions Alle geprüften Zielversionen, die diese Empfehlung einzeln erfüllen;
 * leer bedeutet, dass nur `triggerTargetVersion` geprüft wurde.
 */
data class ManagedDependencyRemovalRecommendation(
    val managedGroupId: String,
    val managedArtifactId: String,
    val managedCurrentVersion: String,
    val triggerGroupId: String,
    val triggerArtifactId: String,
    val triggerType: String,
    val triggerCurrentVersion: String,
    val triggerTargetVersion: String,
    val transitiveVersionInTarget: String,
    val consumers: List<ConsumerDependencyInfo>,
    val isSatisfiedAcrossAllConsumers: Boolean,
    val targetVersionOptions: List<ManagedDependencyTargetVersion> = emptyList()
)

/**
 * Beschreibt einen Konsumenten bzw. einen Abhängigkeitspfad im Projekt, der eine verwaltete Abhängigkeit verwendet.
 *
 * @property groupId Die Group-ID des Konsumenten (z. B. der direkten Abhängigkeit).
 * @property artifactId Die Artefakt-ID des Konsumenten.
 * @property resolvedVersion Die aufgelöste transitive Version unter der Zielversion.
 * @property pathDescription Die lesbare Pfadbeschreibung des Abhängigkeitsbaums.
 */
data class ConsumerDependencyInfo(
    val groupId: String,
    val artifactId: String,
    val resolvedVersion: String,
    val pathDescription: String
)

/**
 * Repräsentiert eine Maven-Koordinate im temporären Abhängigkeitsgraphen.
 *
 * @property groupId Die Group-ID des Artefakts.
 * @property artifactId Die Artefakt-ID des Artefakts.
 * @property version Die Version des Artefakts.
 */
data class TemporaryArtifactCoordinate(
    val groupId: String,
    val artifactId: String,
    val version: String
)

/**
 * Ein leichtgewichtiger Knoten im temporären Abhängigkeitsgraphen zur Analyse von Kandidaten-POMs.
 *
 * @property coordinate Die Maven-Koordinate des Knotens.
 * @property dependencies Die Liste der direkten Kind-Abhängigkeiten.
 * @property dependencyManagement Durch dieses POM verwaltete Abhängigkeiten (`"groupId:artifactId"` -> Version).
 * @property scope Der deklarierte Maven-Scope (z. B. `"compile"`, `"test"`, `"import"`).
 * @property optional `true`, wenn die Abhängigkeit als optional markiert ist.
 */
data class TemporaryDependencyNode(
    val coordinate: TemporaryArtifactCoordinate,
    val dependencies: MutableList<TemporaryDependencyNode> = mutableListOf(),
    val dependencyManagement: MutableMap<String, String> = mutableMapOf(),
    val scope: String? = null,
    val optional: Boolean = false
)
