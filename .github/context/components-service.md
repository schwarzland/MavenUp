# MavenUp – Komponenten: Service und Model (`service`, `model`)

Teil des Projektkontexts – Einstieg: [`.github/copilot-project-context.md`](../copilot-project-context.md).
Beschreibt alle Klassen in `src/main/kotlin/de/schwarzland/mavenup/service/` und `.../model/`.

## Startup und Tool-Window-Aktivierung
- **MavenUpStartupActivity**: `ProjectActivity`, macht das Tool-Window beim Projektstart
  verfügbar, sobald bereits Maven-Projekte vorhanden sind (wartet auf `MavenProjectsManager`).
- **MavenUpMavenImportListener**: deklarativ über `<projectListeners>` registrierter
  `MavenImportListener`, der das Tool-Window nach abgeschlossenem Maven-Import verfügbar macht;
  die deklarative Registrierung ermöglicht Plugin-Updates ohne IDE-Neustart.
- **MavenUpToolWindowActivator**: gemeinsames, idempotentes Hilfsobjekt zum Verfügbarmachen
  des Tool-Windows, genutzt von Startup-Aktivität und Import-Listener; nutzt die gemeinsame
  Konstante `MAVEN_UP_TOOL_WINDOW_ID`.
- **ToolWindowBadgeService**: projektgebundener Service (`Service.Level.PROJECT`), der den Badge-Punkt
  auf dem Stripe-Icon des Tool-Windows setzt. Die Icon-Varianten stammen aus
  `com.intellij.ui.BadgeIconSupplier` (Info/Warning/Error), sodass Position und Farbe des Punktes aus
  dem aktiven Theme kommen. `update` setzt das Icon auf dem EDT und ist gegen fehlendes oder
  disposed Tool-Window abgesichert, `reset` stellt das Basis-Icon wieder her (Aufruf in
  `MyToolWindow.dispose()`). Die zustandslose Funktion `determineBadgeState` leitet aus höchstem
  Schweregrad, verfügbaren Updates und `toolWindowBadgeMode` den `MavenUpBadgeState` ab; ein
  „alles in Ordnung"-Badge gibt es bewusst nicht.

## Einstellungen und Message-Bus
- **MavenUpSettings**: `PersistentStateComponent` auf Anwendungsebene (`Service.Level.APP`), global für alle Projekte gespeichert in `mavenup_settings.xml`
  (`jumpOnSingleClick`, `versionAutoSelectionMode` mit `DISABLED`, `LATEST`, `LATEST_MINOR`, `hideUnstableVersions`, `hiddenVersionQualifiers`,
  `ossIndexEnabled`, `checkTransitiveDependencies`, `repositoryBrowser`, `toolbarShowText`,
  `syncMavenAfterUpdate`, `stopAfterCentralSuccess`, `offerAllVersions`, `confirmVersionReset`,
  `autoSearchVersions`, `vulnerabilityCommentMode` mit `NONE`, `TEXT_ONLY`, `ADVISORY_IDS`, `ALIASES`, `ALL_IDS`,
  `vulnerabilityCommentPrefix`, `vulnerabilityCommentMaxIds`,
  `toolWindowBadgeMode` mit `OFF`, `VULNERABILITIES`, `VULNERABILITIES_AND_UPDATES`;
  Legacy-Migrationsfelder: `selectLatestVersion`, `selectLatestMinorVersion`, `addVulnerabilityFixComment`).
  Für die OSS-Index-Abfrage ist nur das Token erforderlich; Sonatype wertet bei der HTTP-Basic-Authentifizierung
  nur das Token aus, weshalb ein fester Platzhalter-Benutzername verwendet wird.
  Das Token liegt ausschließlich im IntelliJ Password Safe; fehlt es, wird
  keine OSS-Index-Abfrage gesendet.
- **MAVEN_UP_SETTINGS_TOPIC**: `Topic<Runnable>` in `service`, über das `MavenUpConfigurable.apply()`
  Einstellungsänderungen veröffentlicht, damit offene UI-Komponenten (z.B. die Tool-Window-Aktionsleiste
  und die Versionsvorauswahl) sofort reagieren können. Beim Empfang wird die Toolbar neu aufgebaut,
  der Tool-Window-Badge aktualisiert und
  `applySelectLatestVersionSetting()` nur dann aufgerufen, wenn sich `versionAutoSelectionMode`
  tatsächlich geändert hat, damit andere Einstellungsänderungen die bereits getroffene **New Version**-Auswahl
  nicht zurücksetzen.
- **MavenRepositoryBrowser**: Enum in `service`, definiert die zwei konfigurierbaren
  Repository-Browser-Optionen (`MVN_REPOSITORY`, `SONATYPE_CENTRAL`) und erzeugt die jeweilige
  Versions-URL für groupId/artifactId/version.
- **ToolWindowBadgeMode**: Enum in `service` mit den Anzeigeoptionen des Tool-Window-Badges
  (`OFF`, `VULNERABILITIES`, `VULNERABILITIES_AND_UPDATES`).

## API- und Sicherheitsservices
- **VulnerabilityApiService**: OSV-Batchabfrage plus Detailanreicherung und Filterung
  zurückgezogener Advisories. Betroffene Versionsbereiche und Fixed-Versionen werden über `packageNameOf`
  je Koordinate auf das tatsächlich verwendete Maven-Artefakt eingegrenzt (`parseAdvisory`/`parseAffectedRanges`/
  `parseFixedVersions` mit `packageName`), damit Advisories über mehrere Artefakte keine fremden Fix-Versionen
  einmischen; die Detailanreicherung lädt das Roh-JSON je ID einmal (`fetchAdvisoryJson`) und wertet es je
  Koordinate aus. Umfangreiche Komponenten- und Versionslisten werden nur gekürzt auf
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

## Sicherheitsdatenmodell (`model`)
- **MavenUpBadgeState**: Enum mit den Badge-Zuständen des Tool-Window-Icons (`NONE`, `UPDATES`,
  `VULNERABILITIES`, `SEVERE_VULNERABILITIES`).
- **VulnerabilityMerger / VulnerabilityAdvisory**: normalisiertes Security-Datenmodell und
  quellenübergreifende Deduplizierung anhand von IDs/Aliasen; CVSS-Vektoren werden über
  `us.springett:cvss-calculator` normalisiert, bei nicht unterstützten CVSS-Versionen wird auf
  den Schweregrad der Quelle zurückgefallen.
- **AffectedVersionRange**: parst die lesbaren Bereichsbeschreibungen (`>= x, < y`, `>= x, <= y`, `< y`, `<= y`,
  `>= x`) einer Warnung zurück in vergleichbare Grenzen (`parseAffectedVersionRange`/`parseAffectedVersionRanges`)
  und beantwortet über `VulnerabilityAdvisory.isFixedIn`, ob eine Version noch betroffen ist; `<=` entsteht aus
  dem OSV-Event `last_affected` und begrenzt den Bereich einschließend, ohne
  Bereichsangaben dienen ersatzweise die Fixed-Versionen als Kriterium. Grundlage der Empfehlungslogik
  in `recommendedFixVersion`.

## PSI- und Maven-Services
- **RefreshSnapshotCollector**: liest über PSI die deklarierten Dependencies, Plugins und
  Versions-Properties der `pom.xml`-Dateien und liefert einen `RefreshSnapshot`; löst
  Property-Platzhalter (auch die Version im `<parent>`-Tag) über `resolveVersionPlaceholder` auf. Setzt
  `RefreshRow.versionInherited`, wenn die `pom.xml` für den Eintrag kein eigenes `<version>`-Tag deklariert
  (Version stammt aus Parent-POM oder importiertem BOM). Zustandslos, benötigt nur das Projekt.
- **PomUpdateService**: wendet ausgewählte Updates über PSI/`WriteCommandAction` auf die
  `pom.xml` an (`applyUpdateToPom`, `updateXmlTagVersion`, Parent/Dependencies/Plugins) und
  speichert die Dateien vor dem Maven-Sync (`persistPomChanges`). Für „managed dependency"-Updates
  ohne vorhandenen Eintrag legt `addManagedDependency` einen neuen `<dependencyManagement>`-Eintrag an
  (Container werden bei Bedarf erzeugt) und stellt der Abhängigkeit je nach Einstellung
  `vulnerabilityCommentMode` (Standard: `ADVISORY_IDS`) über `managedDependencyCommentText` einen XML-Kommentar
  aus `vulnerabilityCommentPrefix` und den behobenen Vulnerability-Kennungen (IDs, Aliase oder beides) als erste
  Zeile voran; `joinVulnerabilityIds` begrenzt die Liste auf `vulnerabilityCommentMaxIds` (0 = unbegrenzt) und
  ersetzt die übrigen Kennungen durch „and more"; `sanitizeCommentText` normalisiert Whitespace und trennt
  Bindestrich-Folgen (`--`, `-->`) auf, damit der XML-Kommentar nicht vorzeitig endet
  (Erzeugung aus Text via `createTagFromText` + `reformat`); genutzt für das Pinnen transitiver Abhängigkeiten.
- **VulnerabilityScanService**: ermittelt direkte/transitive Scan-Ziele aus dem Maven-Modell
  (`collectVulnerabilityScanTargets`, `collectResolvedDependencyRelations`) und kapselt die
  OSS-Index-Abfrage (`resolveOssIndexResults`, Ergebnis `OssIndexScanResult`). Zugangsdaten
  (`OssIndexCredentialStore`) und die OSS-Abfrage sind für Tests per Konstruktor injizierbar.
  Die reine Farbzuordnung `vulnerabilityColor` liegt als Top-Level-Helfer in `VulnerabilityCellModel`.
- **DependencyVersionService**: fragt über `searchVersions` die verfügbaren Versionen aller
  Dependencies/Plugins ab (inkl. PSI-Erfassung verwalteter Einträge und Property-Schnittmengen)
  und liefert verfügbare Versionen samt Vorauswahl als `VersionSearchResult`. `fetchAvailableVersions`
  ruft gezielt die Versionslisten einer übergebenen Koordinatenmenge ab (ohne Vorauswahl; genutzt für die
  verwundbaren transitiven Koordinaten nach einem Scan). Die Versionsabfrage
  ist als Funktions-Seam per Konstruktor injizierbar (netzwerkfreie Tests). Die zustandslosen
  Auto-Selektions-Helfer (`chooseAutoSelectedVersion`, `latestVersionWithinSameMajor`,
  `extractLeadingMajorNumber`, `selectableRecommendedVersion`) liegen als Top-Level-Funktionen in `ui/VersionAutoSelection`.
- **PomNavigationService**: sucht Definitionen in der `pom.xml` (`findDependency`, `findParent`,
  `findPlugin`, `findProperty`) und springt über `navigateToDependency` bzw. `navigateToProperty` im Editor
  an die jeweilige Stelle. `findProperty` berücksichtigt das globale `<properties>`-Tag sowie
  `<properties>`-Blöcke innerhalb von `<profiles><profile>` und akzeptiert den Property-Namen auch in
  der Platzhalter-Schreibweise `${name}`. `navigateToProperty` fällt auf `navigateToDependency` zurück,
  wenn die Property in keiner `pom.xml` des Projekts definiert ist.

