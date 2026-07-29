# Projektkontext: MavenUp (Referenz für Copilot-Agenten)

## Was ist MavenUp?
IntelliJ-Plugin (Kotlin/Gradle, IntelliJ Platform Plugin) für **Maven-Projekte**.
Zeigt alle Dependencies/Plugins aus `pom.xml`-Dateien (inkl. `dependencyManagement`/
`pluginManagement`) in einer Tabelle im MavenUp-Tool-Window, prüft verfügbare neue
Versionen gegen Maven Central + private Repositories (aus `settings.xml`,
inkl. Credential-Placeholder-Auflösung), und schreibt gewählte Versions-Updates
nach Bestätigung zurück in die `pom.xml` (Property-aware).

- Group: `de.schwarzland`, aktuelle Version: siehe `gradle.properties` (`version=...`)
- Sprache Code: Kotlin; Build: Gradle (`build.gradle.kts`, `settings.gradle.kts`)
- Plugin-Descriptor: `src/main/resources/META-INF/plugin.xml`

## Kernkomponenten (`src/main/kotlin/de/schwarzland/mavenup/`)
- **MavenUpStartupActivity**: `ProjectActivity`, steuert Verfügbarkeit des Tool-Windows
  beim Projektstart (wartet auf `MavenProjectsManager`, reagiert auf `MavenImportListener`).
- **MavenUpWindowFactory**: Zentrale `ToolWindowFactory` + `MyToolWindow`.
  Datenmodell/Tabelle, Versionsprüfung (`maven-metadata.xml`), Central-first-Strategie,
  Credential-Auflösung, Update-Schreibvorgang mit Bestätigungsdialog, Navigation zur
  pom.xml-Definition, Hintergrund-Tasks für lange Aktionen.
- **MavenUpSettings**: `PersistentStateComponent`, gespeichert in `mavenup_settings.xml`
  (`jumpOnSingleClick`, `selectLatestVersion`, Filter für instabile Versionen/Qualifier).
- **MavenUpConfigurable**: Settings-UI unter `Settings > Tools > MavenUp`.
- **MyMessageBundle**: I18n-Wrapper (`messages.MyMessageBundle`).

Tests: `src/test/kotlin/de/schwarzland/mavenup/` (`MavenUpStartupActivityTest`,
`MavenUpWindowFactoryTest`).

## Doku- und Prozesspflichten
Siehe `.github/copilot-instructions.md` für die verbindliche Arbeitsanweisung
(README/CHANGELOG/FEATURES/plugin.xml-Description/Unittests pflegen, kein `git commit`).

## Sonstiges
- Proxy-Einstellungen gehören NICHT in die projektweite `gradle.properties`, sondern
  in die user-lokale `~/.gradle/gradle.properties`.
- `checkliste-publication.md` enthält Checkliste für Plugin-Veröffentlichung.
- `mavenup_plugin_de.html` vermutlich Marketplace-Beschreibung (DE).
