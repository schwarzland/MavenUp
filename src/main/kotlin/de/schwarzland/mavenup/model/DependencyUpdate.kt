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
 * @property transitive `true`, wenn das Update aus einer bislang nur transitiv aufgelösten Abhängigkeit
 * entsteht, die durch das Update erstmals in der `pom.xml` gepinnt wird. Rein informativ für die
 * Anzeige (siehe Bestätigungsdialog); die Schreiblogik richtet sich weiterhin ausschließlich nach [type].
 * @property fixedVulnerabilityAliases Aliase (z. B. `CVE-…`) der durch dieses Update behobenen
 * Sicherheitswarnungen; enthält je Warnung deren Aliase oder – falls keine vorhanden sind – ersatzweise
 * deren primäre ID.
 */
data class DependencyUpdate(
    val groupId: String,
    val artifactId: String,
    val type: String,
    val oldVersion: String,
    val newVersion: String,
    val fixedVulnerabilities: List<String> = emptyList(),
    val transitive: Boolean = false,
    val fixedVulnerabilityAliases: List<String> = emptyList()
)
