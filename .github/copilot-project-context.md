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

### Paketstruktur
- **`model`**: `DependencyUpdate`, `VulnerabilityAdvisory`, `VulnerabilitySeverity` – reine Daten-DTOs ohne Logik.
- **`service`**: Alle externen API-Zugriffe, Settings, Startup-Logik und Hilfsfunktionen.
- **`ui`**: Tool-Window, Dialoge, Settings-UI, I18n-Bundle.

### Komponenten
- **MavenUpStartupActivity**: `ProjectActivity`, steuert Verfügbarkeit des Tool-Windows
  beim Projektstart (wartet auf `MavenProjectsManager`, reagiert auf `MavenImportListener`).
- **MavenUpWindowFactory**: Zentrale `ToolWindowFactory` + `MyToolWindow`.
  Navigation zur pom.xml-Definition sowie Multi-Source-Vulnerability-Checks für direkte und transitive
  Dependencies in Hintergrund-Tasks. Per Rechtsklick auf eine Zeile öffnet sich ein Kontextmenü mit
  **Navigate to pom.xml** und **Open in Maven Repository**. Der verwendete Repository-Browser
  (**MVN Repository** oder **Sonatype Central**) ist in den Einstellungen
  konfigurierbar und gilt einheitlich für das Kontextmenü sowie die Component-Spalte im
  Vulnerability-Details-Dialog. Die Tabellenspalte **Vulnerabilities (Current)** steht direkt
  hinter **Current Version** und ordnet transitive Befunde über den Maven-Dependency-Tree der
  jeweiligen direkten Dependency zu. Sie zeigt Gesamtzahl, transitive Anzahl und höchste Severity;
  der zeilenbezogene Detaildialog markiert die zugehörigen transitiven Komponenten.
  Maven-/PSI-Daten für Refreshes werden über eine
  nicht blockierende Read-Action außerhalb des EDT erfasst. Während Refresh oder Update-Check
  laufen, bleibt der Vulnerability-Check deaktiviert, um konkurrierende Hintergrundaktionen zu vermeiden.
- **MavenUpSettings**: `PersistentStateComponent`, gespeichert in `mavenup_settings.xml`
  (`jumpOnSingleClick`, `selectLatestVersion`, `hideUnstableVersions`, `hiddenVersionQualifiers`,
  `ossIndexEnabled`, `ossIndexUsername`, `checkTransitiveDependencies`, `repositoryBrowser`).
  Für die HTTP-Basic-Authentifizierung sind OSS-Index-Benutzername und Token gemeinsam erforderlich.
  Das Token liegt ausschließlich im IntelliJ Password Safe; bei unvollständigen Credentials wird
  keine OSS-Index-Abfrage gesendet.
- **MavenRepositoryBrowser**: Enum in `service`, definiert die zwei konfigurierbaren
  Repository-Browser-Optionen (`MVN_REPOSITORY`, `SONATYPE_CENTRAL`) und erzeugt die jeweilige
  Versions-URL für groupId/artifactId/version.
- **MavenUpConfigurable**: Settings-UI unter `Settings > Tools > MavenUp`.
  Die OSS-Index-Sektion kennzeichnet Benutzername und Token bei Aktivierung als Pflichtfelder und
  verlinkt auf die Sonatype-Kontoeinstellungen zur Token-Erzeugung. Das Token wird außerhalb des EDT
  aus dem Password Safe geladen und für `isModified()` im UI-Modell gecacht.
- **VulnerabilityApiService**: OSV-Batchabfrage plus Detailanreicherung und Filterung
  zurückgezogener Advisories. Umfangreiche Komponenten- und Versionslisten werden nur gekürzt auf
  DEBUG-Ebene protokolliert, um starkes Wachstum der von der IDE überwachten `idea.log` zu vermeiden.
- **DependencyApiService**: Liest Maven-Repository-Infos und Server-Credentials aus `settings.xml`,
  fragt `maven-metadata.xml` für Versionslisten ab, löst Credential-Platzhalter auf und filtert
  Versionen gemäß Plugin-Einstellungen (Qualifier-Filter, Sortierung).
- **OssIndexApiService / OssIndexCredentialService**: optionale Sonatype-Abfrage über Maven-purl
  und sichere Zugangsdatenablage.
- **VulnerabilityMerger / VulnerabilityAdvisory**: normalisiertes Security-Datenmodell und
  quellenübergreifende Deduplizierung anhand von IDs/Aliasen; CVSS-Vektoren werden über
  `us.springett:cvss-calculator` normalisiert, bei nicht unterstützten CVSS-Versionen wird auf
  den Schweregrad der Quelle zurückgefallen.
- **VulnerabilityDetailDialog**: Detailansicht für direkte und transitive Befunde. Rein informativer Dialog – zeigt ausschließlich einen **Close**-Button (kein OK/Cancel), entsprechend den JetBrains UI-Richtlinien für read-only Dialoge. Die Buttons **Open in ...** und **References...** sind initial deaktiviert und werden erst bei selektierter Vulnerability-Zeile aktiviert; zusätzlich öffnet in der Component-Spalte ein Rechtsklick das Kontextmenü mit **Open in Maven Repository**.
- **ReferencesListDialog**: Zeigt alle Referenz-Links eines Advisories als klickbare Liste. Ebenfalls rein informativer Dialog mit ausschließlich einem **Close**-Button.
- **MyMessageBundle**: I18n-Wrapper (`messages.MyMessageBundle`).

Tests: `src/test/kotlin/de/schwarzland/mavenup/` mit Plattformtests sowie reinen Service-/Modelltests
für OSV, OSS Index und Advisory-Deduplizierung.

## Doku- und Prozesspflichten
Siehe `.github/copilot-instructions.md` für die verbindliche Arbeitsanweisung
(README/CHANGELOG/FEATURES/plugin.xml-Description/Unittests pflegen, kein `git commit`).

## Sonstiges
- Proxy-Einstellungen gehören NICHT in die projektweite `gradle.properties`, sondern
  in die user-lokale `~/.gradle/gradle.properties`.
- `checkliste-publication.md` enthält Checkliste für Plugin-Veröffentlichung.
- `mavenup_plugin_de.html` vermutlich Marketplace-Beschreibung (DE).
- `getting_started.html` ist die *Getting Started*-Seite im JetBrains Marketplace / Plugin Manager. Sie erklärt neuen Nutzern in einem kompakten HTML-Kurzleitfaden die ersten Schritte: Tool-Window öffnen, Refresh, Update-Check, Version auswählen, Update anwenden. Muss bei jeder Bedienungsänderung (neue Aktionen, umbenannte Buttons, neue Dialoge) aktuell gehalten werden.
