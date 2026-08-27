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
- Codequalität: **detekt** (statische Analyse, Config `config/detekt/detekt.yml`, Bestandsbefunde in
  `config/detekt/baseline.xml`; läuft via `check`/`build`) und **Kover** (Testabdeckung,
  `koverHtmlReport`/`koverXmlReport`). CI: `.github/workflows/ci.yml` (`build verifyPlugin detekt koverXmlReport`).
- Plugin-Descriptor: `src/main/resources/META-INF/plugin.xml`
- Tool-Window-Icon: `src/main/resources/icons/mavenUpToolWindow.svg` (Light) und `mavenUpToolWindow_dark.svg` (Dark), in `plugin.xml` über das `icon`-Attribut des `<toolWindow>` referenziert.

## Kernkomponenten (`src/main/kotlin/de/schwarzland/mavenup/`)

### Paketstruktur
- **`model`**: `DependencyUpdate`, `VulnerabilityAdvisory`, `VulnerabilitySeverity` – reine Daten-DTOs ohne Logik.
- **`service`**: Alle externen API-Zugriffe, Settings, Startup-Logik und Hilfsfunktionen.
- **`ui`**: Tool-Window, Dialoge, Settings-UI, I18n-Bundle sowie ausgelagerte, zustandslose
  UI-Hilfsdateien (siehe Komponente **UI-Hilfsdateien (ui)**).

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
  **Filter by "..."** (nur beim Rechtsklick auf die Spalten GroupId, ArtifactId oder Property mit nicht-leerem Wert;
  setzt den angeklickten Wert als alleinigen Textfilter, ersetzt vorhandenen Text und wendet ihn sofort an – siehe
  `filterBy`),
  **Navigate to pom.xml**, **Open in Maven Repository**, **Set Highest Major Version** und **Set Highest Minor Version**
  (setzen ausschließlich für die angeklickte Dependency die höchste verfügbare Version bzw. die höchste Version der
  aktuellen Major-Linie; nur aktiv, wenn die verfügbaren Versionen dieser Dependency bereits abgerufen wurden – siehe
  `selectHighestMajorVersionForDependency`/`selectHighestMinorVersionForDependency`/`hasSelectableVersionsForDependency`),
  **Reset to Current Version** (verwirft ausschließlich für die angeklickte Dependency die Auswahl; nur aktiv bei
  abweichender Auswahl – siehe `resetVersionForDependency`/`isVersionResetEnabledForDependency`)
  und **Show Vulnerability Details** (deaktiviert, wenn die Dependency keine Befunde hat). Alle Kontextmenü-Einträge
  bleiben stets sichtbar und ändern nur ihren Aktivierungszustand (kein Ein-/Ausblenden). Die Aktionen liegen in einer oberen `ActionToolbar` (Icon-Actions mit Tooltip): links die Kernaktionen **Refresh**, **Search for New Versions**, **Scan for Vulnerabilities** und **Update**, durch einen Trenner abgesetzt das Aufklappmenü **Select Highest Version** (Icon `VersionUpdateArrowIcon`, dasselbe Aufwärtspfeil-Glyph „↑" wie die New-Version-Spalte) mit den beiden Aktionen **Select Highest Major Version** und **Select Highest Minor Version**, gefolgt von der eigenständigen Aktion **Reset All to Current Versions**, durch einen weiteren Trenner abgesetzt die selektionsabhängigen Aktionen **Open on [Browser]** und **Vulnerability Details**, am Ende **Settings**. Bei aktivierten Textbeschriftungen zeigen die Buttons gekürzte Labels (z.B. **Search Versions**, **Scan**, **Update**, **Highest**, **Reset**, **Open**, **Details**), während die vollständige Beschriftung als Tooltip erhalten bleibt; der Tooltip des **Select Highest Version**-Menüs weist darauf hin, dass nur die aktuell sichtbaren Dependencies geändert werden, während der Tooltip von **Reset All to Current Versions** benennt, dass ohne aktiven Filter alle Dependencies zurückgesetzt werden und bei aktivem Filter zwischen allen und nur den gefilterten gewählt werden kann. Die **Open on [Browser]**-Aktion wird aktiv, sobald eine Dependency-Zeile selektiert ist, und zeigt dynamisch den konfigurierten Browser-Namen im Tooltip (z.B. **Open on MVN Repository** oder **Open on Sonatype Central**). Die **Vulnerability Details**-Aktion ist erst aktiv, wenn eine Dependency-Zeile mit Befunden selektiert ist, und zeigt ausschließlich die Befunde der selektierten Dependency (direkte und transitive). Der verwendete Repository-Browser
  (**MVN Repository** oder **Sonatype Central**) ist in den Einstellungen
  konfigurierbar und gilt einheitlich für das Kontextmenü sowie das zeilenbezogene Rechtsklick-Menü
  (alle Spalten außer References) im
  Vulnerability-Details-Dialog. Die Kontextmenüs werden über IntelliJs `ActionSystem` beziehungsweise
  plattformkonforme `JBPopupMenu`/`JBMenuItem`-Komponenten erzeugt, damit Theme, Abstände und
  Auswahlfarben der IDE verwendet werden. Die Tabellenspalte **Vulnerabilities (Current)** steht direkt
  hinter **Current Version** und ordnet transitive Befunde über den Maven-Dependency-Tree der
  jeweiligen direkten Dependency zu. Sie zeigt Gesamtzahl, transitive Anzahl und höchste Severity;
  der zeilenbezogene Detaildialog markiert die zugehörigen transitiven Komponenten.
  Maven-/PSI-Daten für Refreshes werden über eine
  nicht blockierende Read-Action außerhalb des EDT erfasst. Während Refresh oder Update-Check
  laufen, bleibt der Vulnerability-Check deaktiviert, um konkurrierende Hintergrundaktionen zu vermeiden.
  Die Aktionsleiste kann laut Einstellung (`toolbarShowText`) wahlweise Icon- oder Text-Buttons darstellen und
  wird bei geänderten Einstellungen über den `MAVEN_UP_SETTINGS_TOPIC`-Message-Bus sofort neu aufgebaut.
  Unterhalb der Aktionsleiste liegt eine Filterzeile mit vier `ComboBox`-Elementen (Typ, verfügbare Updates via `TriStateFilter` [All/Yes/No], anstehende Änderungen via `TriStateFilter` [All/Yes/No], Sicherheitslücken via `TriStateFilter` [All/Yes/No]) und einem `SearchTextField` (Textfilter über
  GroupId, ArtifactId und Property, case-insensitiv; kann zusätzlich über den Kontextmenü-Eintrag **Filter by "..."** per `filterBy` befüllt werden); die drei `TriStateFilter`-Comboboxen zeigen über `triStateFilterRenderer`/`triStateFilterOptionLabel` und die `TriStateFilterLabels`-Konstanten (`CHANGES_FILTER_LABELS`, `UPDATES_FILTER_LABELS`, `VULNERABILITIES_FILTER_LABELS`) kontextspezifische, selbsterklärende Optionstexte statt generischer Yes/No-Werte. Alle Filter werden über
  einen `TableRowSorter` mittels der Top-Level-Funktion `rowMatchesFilter`
  kombiniert. Der Changes-Filter ist nur aktiv, wenn mindestens eine abweichende Version ausgewählt wurde (`isChangesFilterAvailable`/`updateChangesFilterState`, ausgelöst über `updateUpdateButtonState`). Der Updates-Filter ist nur nach einer erfolgreichen Versionssuche aktiv (`isUpdatesFilterAvailable`/`updateUpdatesFilterState`) und nutzt die Top-Level-Funktion `hasNewerVersion`; der Vulnerabilities-Filter ist nur nach einer erfolgreichen Sicherheitsprüfung aktiv (`vulnerabilityScanPerformed` via `isVulnerabilitiesFilterAvailable`/`updateVulnerabilitiesFilterState`). Am Ende der Filterzeile setzt eine `ActionToolbar` mit einer einzelnen Reset-Aktion (`resetAllFilters`) alle Filter zurück; sie ist nur aktiv, solange `isResetFiltersEnabled` mindestens einen aktiven Filter meldet. Derselbe `TableRowSorter` übernimmt zusätzlich die spaltenweise Sortierung über die Kopfzeile:
  ein überschriebenes `toggleSortOrder` schaltet zyklisch zwischen aufsteigend, absteigend und
  unsortiert (pom.xml-Reihenfolge) um; die Spalten **Current Version** und **New Version** sind nicht
  sortierbar. Die Spalte **Vulnerabilities (Current)** ist über den `vulnerabilityCellComparator`
  (in `VulnerabilityCellModel.kt`) sortierbar: primär nach dem höchsten Schweregrad der Zelle, sekundär
  nach der Anzahl der Warnungen. Ein über `installSortableHeaderRenderer` gesetzter Kopfzeilen-Renderer
  zeigt für sortierbare Spalten über die Top-Level-Funktion `sortableHeaderIcon` ein Indikator-Icon an
  (gedämpfter `AllIcons.General.ArrowSplitCenterV`-Doppelpfeil im unsortierten Zustand, `ArrowUp`/`ArrowDown` bei aktiver Sortierung).
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
  Die Sammelaktionen setzen die **New Version**-Auswahl gemeinsam:
  `selectHighestMajorVersionForAll()` wählt die höchste verfügbare Version, `selectHighestMinorVersionForAll()`
  die höchste Version innerhalb der aktuellen Major-Linie – beide nur für die aktuell sichtbaren
  (nicht ausgefilterten) Zeilen (`collectVisibleDependencyKeys()`) – und `resetAllVersionsToCurrent()` verwirft alle
  Auswahlen unabhängig vom Filter, während `resetVisibleVersionsToCurrent()` nur die sichtbaren (gefilterten) Zeilen zurücksetzt;
  ihre Aktivierung steuern `isBulkVersionSelectionEnabled()` und `isResetVersionsEnabled()`.
  `confirmAndResetAllVersionsToCurrent()` verzweigt anhand von `isResetFiltersEnabled()`: ist kein Filter aktiv, zeigt es bei aktivem
  `confirmVersionReset` einen Ja/Nein-Bestätigungsdialog (`MessageDialogBuilder` mit `DoNotAskOption`, dessen „Don't ask again" die
  Einstellung deaktiviert) und ruft `resetAllVersionsToCurrent()`; ist ein Filter aktiv, zeigt `confirmAndResetWithActiveFilter()`
  stattdessen einen Auswahldialog (`Messages.showDialog` mit den Optionen alle/gefiltert/abbrechen, ohne „Don't ask again").
  `isRowFilterHidingEntries()` und `bulkSelectionActionDescription()` erweitern den Tooltip der "Select Highest"-Aktionen bei aktivem Filter um einen Hinweis.
- **UpdateConfirmationDialog**: eigenständiger `DialogWrapper` (Top-Level in `ui`), der vor
  dem Anwenden die anstehenden Updates in einer schreibgeschützten Tabelle bestätigen lässt und
  die Option **Sync Maven Changes** (vorbelegt aus `MavenUpSettings.syncMavenAfterUpdate`) anbietet.
- **UI-Hilfsdateien (ui)**: Zustandslose Top-Level-Helfer, die aus `MavenUpWindowFactory.kt`
  in eigene Dateien desselben Packages ausgelagert wurden (reine Logik/Icons, keine
  `MyToolWindow`-Abhängigkeit):
  - `MavenUpTableConstants.kt`: Spalten-Indizes und Message-Key-/Typ-Konstanten.
  - `VersionStatusUi.kt`: `VersionUpdateArrowIcon`, `isVersionUpToDate`, `hasNewerVersion`,
    `versionStatusText`/`versionStatusColor`/`versionStatusTooltip`, `versionDropdownItemText`,
    `createVersionPanel` samt Status-Glyphen und `JBColor`-Werten.
  - `DependencyFilterModel.kt`: `TriStateFilter`, `TriStateFilterLabels`,
    `triStateFilterOptionLabel`, die `*_FILTER_LABELS`, `FilterRow`, `FilterCriteria`,
    `rowMatchesFilter`.
  - `VulnerabilityCellModel.kt`: `VulnerabilityCell`, `buildVulnerabilityCell`,
    `vulnerabilitySummary`, `worstSeverity`, `canCheckVulnerabilities`, `vulnerabilityColor`,
    `VulnerabilityScanTargets`, `artifactNodeCoordinate`, `coordinateString`.
  - `RefreshSnapshot.kt`: `RefreshRow`, `RefreshSnapshot`.
  - `MavenRepositoryLink.kt`: `buildMavenRepositoryUrl`.
  - `SortableHeaderIcon.kt`: `sortableHeaderIcon`.
  - `VersionAutoSelection.kt`: `chooseAutoSelectedVersion`, `latestVersionWithinSameMajor`,
    `extractLeadingMajorNumber` (zustandslose Auto-Selektions-Helfer).
  - `HelpTooltipExtensions.kt`: `HelpTooltip.withWrappingDescription` (versionsunabhängige
    `setDescription`-Brücke via Reflection).
- **MavenUpSettings**: `PersistentStateComponent` auf Anwendungsebene (`Service.Level.APP`), global für alle Projekte gespeichert in `mavenup_settings.xml`
  (`jumpOnSingleClick`, `versionAutoSelectionMode` mit `DISABLED`, `LATEST`, `LATEST_MINOR`, `hideUnstableVersions`, `hiddenVersionQualifiers`,
  `ossIndexEnabled`, `checkTransitiveDependencies`, `repositoryBrowser`, `toolbarShowText`,
  `syncMavenAfterUpdate`, `stopAfterCentralSuccess`, `offerAllVersions`, `confirmVersionReset`; Legacy-Migrationsfelder: `selectLatestVersion`, `selectLatestMinorVersion`).
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
- **LogSummary**: Hilfsfunktion `summarizeForDebugLog`, die lange String-Listen (z. B. Versionslisten)
  für Debug-Logs auf maximal zehn Einträge kürzt und die Anzahl ausgelassener Elemente anhängt.
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
- **RefreshSnapshotCollector**: liest über PSI die deklarierten Dependencies, Plugins und
  Versions-Properties der `pom.xml`-Dateien und liefert einen `RefreshSnapshot`; löst
  Property-Platzhalter (auch die Version im `<parent>`-Tag) über `resolveVersionPlaceholder` auf. Zustandslos, benötigt nur das Projekt.
- **PomUpdateService**: wendet ausgewählte Updates über PSI/`WriteCommandAction` auf die
  `pom.xml` an (`applyUpdateToPom`, `updateXmlTagVersion`, Parent/Dependencies/Plugins) und
  speichert die Dateien vor dem Maven-Sync (`persistPomChanges`).
- **VulnerabilityScanService**: ermittelt direkte/transitive Scan-Ziele aus dem Maven-Modell
  (`collectVulnerabilityScanTargets`, `collectResolvedDependencyRelations`) und kapselt die
  OSS-Index-Abfrage (`resolveOssIndexResults`, Ergebnis `OssIndexScanResult`). Zugangsdaten
  (`OssIndexCredentialStore`) und die OSS-Abfrage sind für Tests per Konstruktor injizierbar.
  Die reine Farbzuordnung `vulnerabilityColor` liegt als Top-Level-Helfer in `VulnerabilityCellModel`.
- **DependencyVersionService**: fragt über `searchVersions` die verfügbaren Versionen aller
  Dependencies/Plugins ab (inkl. PSI-Erfassung verwalteter Einträge und Property-Schnittmengen)
  und liefert verfügbare Versionen samt Vorauswahl als `VersionSearchResult`. Die Versionsabfrage
  ist als Funktions-Seam per Konstruktor injizierbar (netzwerkfreie Tests). Die zustandslosen
  Auto-Selektions-Helfer (`chooseAutoSelectedVersion`, `latestVersionWithinSameMajor`,
  `extractLeadingMajorNumber`) liegen als Top-Level-Funktionen in `ui/VersionAutoSelection`.
- **PomNavigationService**: sucht Definitionen in der `pom.xml` (`findDependency`, `findParent`,
  `findPlugin`) und springt über `navigateToDependency` im Editor an die jeweilige Stelle.
- **VulnerabilityDetailDialog**: Detailansicht für direkte und transitive Befunde. Rein informativer Dialog – zeigt ausschließlich einen **Close**-Button (kein OK/Cancel), entsprechend den JetBrains UI-Richtlinien für read-only Dialoge. Die **Severity**-Spalte ist per `vulnerabilityColor` nach Schweregrad farblich hinterlegt (gleiches Farbschema wie die Vulnerability-Spalte im Hauptfenster). Alle Spalten sind über einen `TableRowSorter` sortierbar (aufsteigend → absteigend → unsortiert) mit denselben `sortableHeaderIcon`-Indikatoren wie im Hauptfenster; die **Severity**-Spalte nutzt `severityCellComparator` (Kritikalität, dann CVSS-Score, beide absteigend – erster Klick zeigt die kritischsten Befunde oben), die **References**-Spalte `referencesCellComparator` (Anzahl der Referenzen). Die Aktionen **Open in ...** und **References...** liegen in einer oberen `ActionToolbar` des Dialogs, sind initial deaktiviert und werden erst bei selektierter Vulnerability-Zeile aktiviert; zusätzlich öffnet ein Rechtsklick auf die selektierte Zeile in allen Spalten außer **References** über IntelliJs `ActionSystem` ein plattformkonformes Kontextmenü mit **Open in Maven Repository** und **References...**. Die Kontextmenüs verwenden `JBPopupMenu`/`JBMenuItem`, damit Theme, Abstände und Auswahlfarben der IDE verwendet werden.
- **ReferencesListDialog**: Zeigt alle Referenz-Links eines Advisories als klickbare Liste. Ebenfalls rein informativer Dialog mit ausschließlich einem **Close**-Button.
- **MyMessageBundle**: I18n-Wrapper (`messages.MyMessageBundle`).

Tests: `src/test/kotlin/de/schwarzland/mavenup/` spiegeln die Paketstruktur (`model`, `service`, `ui`).
Reine Logik nutzt JUnit (z. B. `VulnerabilityApiServiceTest`, `VersionAutoSelectionTest`), Tests mit
Projekt-/PSI-Umgebung erben von `BasePlatformTestCase` (z. B. `MavenUpWindowFactoryTest`,
`RefreshSnapshotCollectorTest`, `PomNavigationServiceTest`, `PomUpdateServiceTest`, `VersionStatusUiTest`,
`DependencyVersionServiceTest`, `VulnerabilityScanServiceTest`). Netzwerklastige Services werden über
injizierte Seams/Interfaces netzwerkfrei getestet.

## KI-Agenten (Copilot / Junie)
- Für KI-Agenten gelten die verbindlichen Arbeitsanweisungen in `.github/copilot-instructions.md`.
- `AGENTS.md` im Repository-Wurzelverzeichnis ist der herstellerübergreifende Einstiegspunkt (z. B. OpenAI Codex / Codex CLI, GitHub Copilot CLI, Cursor, Aider, Jules, Zed) und verweist auf `.github/copilot-instructions.md` sowie diesen Kontext.
- Junie nutzt zusätzlich die Datei `.junie/guidelines.md` als explizite Referenz auf diesen Kontext.

## Doku- und Prozesspflichten
Siehe `.github/copilot-instructions.md` für die verbindliche Arbeitsanweisung
(README/CHANGELOG/FEATURES/plugin.xml-Description/Unittests pflegen, kein `git commit`).
Die README ist eine schlanke, englische Landing Page; die ausführliche Dokumentation liegt (Englisch) unter
`docs/` (`usage.md`, `configuration.md`, `privacy-and-security.md`, `architecture.md`, `development.md`,
`release-and-ci.md`, `licenses.md`).

## Sonstiges
- Proxy-Einstellungen gehören NICHT in die projektweite `gradle.properties`, sondern
  in die user-lokale `~/.gradle/gradle.properties`.
- `checkliste-publication.md` enthält Checkliste für Plugin-Veröffentlichung.
- `getting_started.html` ist die *Getting Started*-Seite im JetBrains Marketplace / Plugin Manager. Sie erklärt neuen Nutzern in einem kompakten HTML-Kurzleitfaden die ersten Schritte: Tool-Window öffnen, Refresh, Update-Check, Version auswählen, Update anwenden. Muss bei jeder Bedienungsänderung (neue Aktionen, umbenannte Buttons, neue Dialoge) aktuell gehalten werden.
