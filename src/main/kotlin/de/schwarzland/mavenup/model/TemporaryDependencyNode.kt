package de.schwarzland.mavenup.model

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
