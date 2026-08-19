# Projektkontext: MavenUp (Referenz für Copilot-Agenten)

## Was ist MavenUp?
IntelliJ-Plugin (Kotlin/Gradle, IntelliJ Platform Plugin) für **Maven-Projekte**.
Zeigt alle Dependencies/Plugins aus `pom.xml`-Dateien (inkl. `<parent>`, `dependencyManagement`/
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
- **MavenUpStartupActivity**: `ProjectActivity`, macht das Tool-Window beim Projektstart
  verfügbar, sobald bereits Maven-Projekte vorhanden sind (wartet auf `MavenProjectsManager`).
- **MavenUpMavenImportListener**: deklarativ über `<projectListeners>` registrierter
  `MavenImportListener`, der das Tool-Window nach abgeschlossenem Maven-Import verfügbar macht;
  die deklarative Registrierung ermöglicht Plugin-Updates ohne IDE-Neustart.
- **MavenUpToolWindowActivator**: gemeinsames, idempotentes Hilfsobjekt zum Verfügbarmachen
  des Tool-Windows, genutzt von Startup-Aktivität und Import-Listener.
- **MavenUpWindowFactory**: Zentrale `ToolWindowFactory` + `MyToolWindow`.
  Navigation zur pom.xml-Definition sowie Multi-Source-Vulnerability-Checks für direkte und transitive
  Dependencies in Hintergrund-Tasks. Per Rechtsklick auf eine Zeile öffnet sich ein Kontextmenü mit
  **Navigate to pom.xml**, **Open in Maven Repository** und ggf. **Show Vulnerability Details** (falls Vulnerabilities vorhanden sind). Die Aktionen liegen in einer oberen `ActionToolbar` (Icon-Actions mit Tooltip): links die Kernaktionen **Refresh**, **Find New Versions**, **Scan for Vulnerabilities** und **Update**, durch einen Trenner abgesetzt die selektionsabhängigen Aktionen **Open on [Browser]** und **Vulnerability Details**, am Ende **Settings**. Die **Open on [Browser]**-Aktion wird aktiv, sobald eine Dependency-Zeile selektiert ist, und zeigt dynamisch den konfigurierten Browser-Namen im Tooltip (z.B. **Open on MVN Repository** oder **Open on Sonatype Central**). Die **Vulnerability Details**-Aktion ist erst aktiv, wenn eine Dependency-Zeile mit Befunden selektiert ist, und zeigt ausschließlich die Befunde der selektierten Dependency (direkte und transitive). Der verwendete Repository-Browser
  (**MVN Repository** oder **Sonatype Central**) ist in den Einstellungen
  konfigurierbar und gilt einheitlich für das Kontextmenü sowie das zeilenbezogene Rechtsklick-Menü
  (alle Spalten außer References) im
  Vulnerability-Details-Dialog. Die Tabellenspalte **Vulnerabilities (Current)** steht direkt
  hinter **Current Version** und ordnet transitive Befunde über den Maven-Dependency-Tree der
  jeweiligen direkten Dependency zu. Sie zeigt Gesamtzahl, transitive Anzahl und höchste Severity;
  der zeilenbezogene Detaildialog markiert die zugehörigen transitiven Komponenten.
  Maven-/PSI-Daten für Refreshes werden über eine
  nicht blockierende Read-Action außerhalb des EDT erfasst. Während Refresh oder Update-Check
  laufen, bleibt der Vulnerability-Check deaktiviert, um konkurrierende Hintergrundaktionen zu vermeiden.
  Die Aktionsleiste kann laut Einstellung (`toolbarShowText`) wahlweise Icon- oder Text-Buttons darstellen und
  wird bei geänderten Einstellungen über den `MAVEN_UP_SETTINGS_TOPIC`-Message-Bus sofort neu aufgebaut.
  Unterhalb der Aktionsleiste liegt eine Filterzeile mit drei `ComboBox`-Elementen (Typ, anstehende Änderungen via `TriStateFilter` [All/Yes/No], Sicherheitslücken via `TriStateFilter` [All/Yes/No]) und einem `SearchTextField` (Textfilter über
  GroupId, ArtifactId und Property, case-insensitiv); alle Filter werden über
  einen `TableRowSorter` (nur Filtern, kein Sortieren) mittels der Top-Level-Funktion `rowMatchesFilter`
  kombiniert.
  Die Spalte **New Version** zeigt über die Helper-Funktionen `isVersionUpToDate()`, `versionStatusText()`,
  `versionStatusColor()` und `versionStatusTooltip()` ein Status-Glyph und farbcodierten Text:
  grüner Haken „✓" wenn die ausgewählte Version die neueste ist, ein Pfeil nach oben „↑" sonst.
  Das Glyph ist ein einfärbbares Text-Label (kein IntelliJ-Icon) und übernimmt dieselbe Farbe wie die
  Versionsnummer: Es wird nur eingefärbt (grün bzw. orange), wenn eine von der aktuellen abweichende
  Version ausgewählt ist; andernfalls verwendet es die Standardfarbe. Glyph und Farbe richten sich
  immer nach der **ausgewählten** Version im Dropdown.
  Bei einer ausstehenden Änderung (ausgewählte ≠ aktuelle Version) wird der Dropdown-Text fett dargestellt.
  `createVersionPanel()` baut das JPanel mit Status-Glyph und ComboBox zusammen.
  Farben verwenden `JBColor`-Doppelwerte für Light-/Dark-Mode-Kompatibilität.
- **MavenUpSettings**: `PersistentStateComponent` auf Anwendungsebene (`Service.Level.APP`), global für alle Projekte gespeichert in `mavenup_settings.xml`
  (`jumpOnSingleClick`, `versionAutoSelectionMode` mit `DISABLED`, `LATEST`, `LATEST_MINOR`, `hideUnstableVersions`, `hiddenVersionQualifiers`,
  `ossIndexEnabled`, `checkTransitiveDependencies`, `repositoryBrowser`, `toolbarShowText`,
  `syncMavenAfterUpdate`, `stopAfterCentralSuccess`; Legacy-Migrationsfelder: `selectLatestVersion`, `selectLatestMinorVersion`).
  Für die OSS-Index-Abfrage ist nur das Token erforderlich; Sonatype wertet bei der HTTP-Basic-Authentifizierung
  nur das Token aus, weshalb ein fester Platzhalter-Benutzername verwendet wird.
  Das Token liegt ausschließlich im IntelliJ Password Safe; fehlt es, wird
  keine OSS-Index-Abfrage gesendet.
- **MAVEN_UP_SETTINGS_TOPIC**: `Topic<Runnable>` in `service`, über das `MavenUpConfigurable.apply()`
  Einstellungsänderungen veröffentlicht, damit offene UI-Komponenten (z.B. die Tool-Window-Aktionsleiste
  und die Versionsvorauswahl) sofort reagieren können. Beim Empfang wird die Toolbar neu aufgebaut und
  `applySelectLatestVersionSetting()` nur dann aufgerufen, wenn sich `versionAutoSelectionMode`
  tatsächlich geändert hat, damit andere Einstellungsänderungen die bereits getroffene **New Version**-Auswahl
  nicht zurücksetzen.
- **MavenRepositoryBrowser**: Enum in `service`, definiert die zwei konfigurierbaren
  Repository-Browser-Optionen (`MVN_REPOSITORY`, `SONATYPE_CENTRAL`) und erzeugt die jeweilige
  Versions-URL für groupId/artifactId/version.
- **MavenUpConfigurable**: Settings-UI unter `Settings > Tools > MavenUp`.
  Die Optionen sind in drei Gruppen (`group`) gegliedert: **Appearance**, **Versions & Updates** und **Vulnerability Check**.
  Bietet u.a. die Checkbox für Text-Buttons in der Aktionsleiste (`toolbarShowText`) und veröffentlicht
  beim Speichern den `MAVEN_UP_SETTINGS_TOPIC`.
  Die Gruppe **Versions & Updates** enthält zusätzlich die Option `stopAfterCentralSuccess` zur Steuerung,
  ob nach erfolgreicher Maven-Central-Abfrage weitere private Repositories abgefragt werden, sowie die
  Combobox `versionAutoSelectionMode` mit drei Zuständen für die Auto-Auswahl bei Update-Prüfungen.
  Die OSS-Index-Sektion kennzeichnet das Token bei Aktivierung als Pflichtfeld und
  verlinkt auf die Sonatype-Kontoeinstellungen zur Token-Erzeugung. Das Token wird außerhalb des EDT
  aus dem Password Safe geladen und für `isModified()` im UI-Modell gecacht.
- **VulnerabilityApiService**: OSV-Batchabfrage plus Detailanreicherung und Filterung
  zurückgezogener Advisories. Umfangreiche Komponenten- und Versionslisten werden nur gekürzt auf
  DEBUG-Ebene protokolliert, um starkes Wachstum der von der IDE überwachten `idea.log` zu vermeiden.
- **DependencyApiService**: Liest Maven-Repository-Infos und Server-Credentials aus `settings.xml`,
  nutzt bei fehlendem explizitem IDE-Pfad automatisch `${user.home}/.m2/settings.xml`, protokolliert
  den verwendeten Settings-Pfad auf DEBUG-Ebene, fragt `maven-metadata.xml` für Versionslisten ab,
  löst Credential-Platzhalter auf, filtert Versionen gemäß Plugin-Einstellungen (Qualifier-Filter, Sortierung)
  und berücksichtigt die konfigurierbare Central-first-Short-Circuit-Strategie (`stopAfterCentralSuccess`).
  Die neueste Version wird über `extractNewestFromMetadata` aus den `<release>`/`<latest>`-Feldern bestimmt
  (Central bevorzugt) und via `orderWithNewestFirst` an den Listenanfang gestellt; Rückgabetypen sind
  `RepositoryVersions` (pro Repository) und `CollectedVersions` (aggregiert).
- **OssIndexApiService / OssIndexCredentialService**: optionale Sonatype-Abfrage über Maven-purl
  und sichere Zugangsdatenablage; wirft `OssIndexAuthenticationException` bei ungültigem/abgelaufenem
  Token (HTTP 401/403) für eine qualifizierte Fehlermeldung.
- **VulnerabilityMerger / VulnerabilityAdvisory**: normalisiertes Security-Datenmodell und
  quellenübergreifende Deduplizierung anhand von IDs/Aliasen; CVSS-Vektoren werden über
  `us.springett:cvss-calculator` normalisiert, bei nicht unterstützten CVSS-Versionen wird auf
  den Schweregrad der Quelle zurückgefallen.
- **VulnerabilityDetailDialog**: Detailansicht für direkte und transitive Befunde. Rein informativer Dialog – zeigt ausschließlich einen **Close**-Button (kein OK/Cancel), entsprechend den JetBrains UI-Richtlinien für read-only Dialoge. Die Aktionen **Open in ...** und **References...** liegen in einer oberen `ActionToolbar` des Dialogs, sind initial deaktiviert und werden erst bei selektierter Vulnerability-Zeile aktiviert; zusätzlich öffnet ein Rechtsklick auf die selektierte Zeile in allen Spalten außer **References** das Kontextmenü mit **Open in Maven Repository** und **References...**.
- **ReferencesListDialog**: Zeigt alle Referenz-Links eines Advisories als klickbare Liste. Ebenfalls rein informativer Dialog mit ausschließlich einem **Close**-Button.
- **MyMessageBundle**: I18n-Wrapper (`messages.MyMessageBundle`).

Tests: `src/test/kotlin/de/schwarzland/mavenup/` mit Plattformtests sowie reinen Service-/Modelltests
für OSV, OSS Index und Advisory-Deduplizierung.

## KI-Agenten (Copilot / Junie)
- Für KI-Agenten gelten die verbindlichen Arbeitsanweisungen in `.github/copilot-instructions.md`.
- Junie nutzt zusätzlich die Datei `.junie/guidelines.md` als explizite Referenz auf diesen Kontext.

## Doku- und Prozesspflichten
Siehe `.github/copilot-instructions.md` für die verbindliche Arbeitsanweisung
(README/CHANGELOG/FEATURES/plugin.xml-Description/Unittests pflegen, kein `git commit`).

## Sonstiges
- Proxy-Einstellungen gehören NICHT in die projektweite `gradle.properties`, sondern
  in die user-lokale `~/.gradle/gradle.properties`.
- `checkliste-publication.md` enthält Checkliste für Plugin-Veröffentlichung.
- `mavenup_plugin_de.html` vermutlich Marketplace-Beschreibung (DE).
- `getting_started.html` ist die *Getting Started*-Seite im JetBrains Marketplace / Plugin Manager. Sie erklärt neuen Nutzern in einem kompakten HTML-Kurzleitfaden die ersten Schritte: Tool-Window öffnen, Refresh, Update-Check, Version auswählen, Update anwenden. Muss bei jeder Bedienungsänderung (neue Aktionen, umbenannte Buttons, neue Dialoge) aktuell gehalten werden.
