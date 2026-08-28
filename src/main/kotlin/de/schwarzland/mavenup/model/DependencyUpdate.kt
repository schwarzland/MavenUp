package de.schwarzland.mavenup.model

/**
 * Repräsentiert ein Update für eine Maven-Abhängigkeit.
 *
 * @property groupId Die Group-ID des Maven-Artefakts (z. B. `org.springframework`).
 * @property artifactId Die Artefakt-ID (z. B. `spring-core`).
 * @property type Der Typ der Abhängigkeit (z. B. `dependency`, `plugin`, `managed dependency`).
 * @property oldVersion Die aktuell verwendete Version.
 * @property newVersion Die neu ausgewählte Zielversion.
 * @property fixedVulnerabilities IDs der durch dieses Update behobenen Sicherheitswarnungen; nur für
 * transitive Pins gesetzt und wird beim Neuanlegen eines `dependencyManagement`-Eintrags als Kommentar
 * in die `pom.xml` geschrieben.
 */
data class DependencyUpdate(
    val groupId: String,
    val artifactId: String,
    val type: String,
    val oldVersion: String,
    val newVersion: String,
    val fixedVulnerabilities: List<String> = emptyList()
)
