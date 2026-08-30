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
- **`model`**: `DependencyUpdate`, `VulnerabilityAdvisory`, `VulnerabilitySeverity`, `AffectedVersionRange` – reine Daten-DTOs ohne Logik.
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
  **Navigate to pom.xml**, **Open on [Browser]**, **Set Highest Major Version** und **Set Highest Minor Version**
  (setzen ausschließlich für die angeklickte Dependency die höchste verfügbare Version bzw. die höchste Version der
  aktuellen Major-Linie; nur aktiv, wenn die verfügbaren Versionen dieser Dependency bereits abgerufen wurden – siehe
  `selectHighestMajorVersionForDependency`/`selectHighestMinorVersionForDependency`/`hasSelectableVersionsForDependency`),
  **Set Recommended Version** (setzt für die angeklickte Dependency die aus ihren **eigenen** Sicherheitswarnungen
  abgeleitete Fix-Version; nur aktiv, wenn die Dependency selbst betroffen ist und eine abgerufene Version die
  Empfehlung erreicht – siehe `selectRecommendedVersionForDependency`/`hasRecommendedVersionForDependency`/`recommendedVersionForDependency`),
  **Reset to Current Version** (verwirft ausschließlich für die angeklickte Dependency die Auswahl; nur aktiv bei
  abweichender Auswahl – siehe `resetVersionForDependency`/`isVersionResetEnabledForDependency`)
  und **Show Vulnerability Details** (deaktiviert, wenn die Dependency keine Befunde hat). Alle Kontextmenü-Einträge
  bleiben stets sichtbar und ändern nur ihren Aktivierungszustand (kein Ein-/Ausblenden). Die Aktionen liegen in einer `ActionToolbar` (Icon-Actions mit Tooltip) oberhalb der jeweiligen Tabelle – je Tab eine eigene Instanz über dieselbe Aktionsgruppe – und wirken stets auf die Tabelle des aktiven Tabs. Die horizontale Ausrichtung ist eine bewusste Entscheidung und nicht zu einer vertikalen Toolbar am linken Rand umzubauen: Der JetBrains-Styleguide („Toolbar → Location") empfiehlt für standardmäßig horizontale Tool-Windows (MavenUp ist `anchor="bottom"`) zwar eine linke vertikale Toolbar, nennt aber ausdrücklich die Ausnahme „items that need horizontal space, like a search field and drop-down lists" (Vorbild Git-Tool-Window) – genau das trifft hier zu, weil die Aktionsleiste untrennbar mit der Filterzeile aus `SearchTextField` und Combo-Boxen zusammenspielt. Hinzu kommt, dass die Einstellung `toolbarShowText` standardmäßig Textbeschriftungen zeigt (vertikal ~150 px Breitenverlust), dass 9 Aktionen samt Trennern vertikal die Höhe eines Bottom-Tool-Windows sprengen und im Chevron verschwinden würden, und dass eine vertikale Toolbar neben der horizontalen Filterzeile die vom Styleguide abgeratene Doppel-Toolbar-Optik ergäbe. Die Anordnung lautet: links die Kernaktionen **Refresh and Search for New Versions** (lädt die `pom.xml`-Daten neu, verwirft Versionsauswahlen und Scan-Ergebnisse und startet anschließend die Versionssuche; `isRefreshEnabled` deaktiviert sie nur während einer laufenden Aktualisierung und lässt sie in beiden Tabs zu; dieselbe Kombination läuft automatisch beim Aufbau des Tool-Window-Inhalts und bei jedem abgeschlossenen Maven-Import, sofern `isAutoVersionSearchEnabled` die Einstellung `autoSearchVersions` als aktiv meldet), **Scan for Vulnerabilities** und **Update**, durch einen Trenner abgesetzt das Aufklappmenü **Select Highest Version** (Icon `VersionUpdateArrowIcon`, dasselbe Aufwärtspfeil-Glyph „↑" wie die New-Version-Spalte) mit den Aktionen **Select Highest Major Version**, **Select Highest Minor Version** und **Select Recommended Version** (letztere aktiv, sobald die aktive Ansicht mindestens eine empfohlene Fix-Version anbietet), gefolgt von der eigenständigen Aktion **Reset All to Current Versions**, durch einen weiteren Trenner abgesetzt die selektionsabhängigen Aktionen **Open on [Browser]** und **Vulnerability Details**, am Ende **Settings**. Bei aktivierten Textbeschriftungen zeigen die Buttons gekürzte Labels (z.B. **Refresh**, **Scan**, **Update**, **Highest**, **Reset**, **Open**, **Details**), während die vollständige Beschriftung als Tooltip erhalten bleibt; der Tooltip des **Select Highest Version**-Menüs weist darauf hin, dass nur die aktuell sichtbaren Dependencies geändert werden, während der Tooltip von **Reset All to Current Versions** benennt, dass ohne aktiven Filter alle Dependencies zurückgesetzt werden und bei aktivem Filter zwischen allen und nur den gefilterten gewählt werden kann. Die **Open on [Browser]**-Aktion wird aktiv, sobald eine Dependency-Zeile selektiert ist, und zeigt dynamisch den konfigurierten Browser-Namen im Tooltip (z.B. **Open on MVN Repository** oder **Open on Sonatype Central**). Die **Vulnerability Details**-Aktion ist erst aktiv, wenn eine Dependency-Zeile mit Befunden selektiert ist, und zeigt ausschließlich die Befunde der selektierten Dependency (direkte und transitive). Beide selektionsabhängigen Aktionen wirken je nach aktivem Tab (Haupttabelle oder `TransitiveVulnerabilitiesView`) auf die dort selektierte Zeile; die Umschaltung erfolgt über `showingTransitiveView` in `isOpenInRepositoryEnabled`/`isVulnerabilityDetailsEnabled`/`openInMavenRepositoryForSelectedRow`/`openVulnerabilityDetailsForSelectedRow`, und ein Selektions-Listener der transitiven Tabelle stößt `refreshToolbar` an. Der verwendete Repository-Browser
  (**MVN Repository** oder **Sonatype Central**) ist in den Einstellungen
  konfigurierbar und gilt einheitlich für das Kontextmenü sowie das zeilenbezogene Rechtsklick-Menü im
  Vulnerability-Details-Dialog. Die Kontextmenüs werden über IntelliJs `ActionSystem` beziehungsweise
  plattformkonforme `JBPopupMenu`/`JBMenuItem`-Komponenten erzeugt, damit Theme, Abstände und
  Auswahlfarben der IDE verwendet werden. Die Tabellenspalte **Vulnerabilities** steht direkt
  vor **Current Version** und ordnet transitive Befunde über den Maven-Dependency-Tree der
  jeweiligen direkten Dependency zu. Sie zeigt Gesamtzahl, transitive Anzahl und höchste Severity;
  der zeilenbezogene Detaildialog markiert die zugehörigen transitiven Komponenten.
  Maven-/PSI-Daten für Refreshes werden über eine
  nicht blockierende Read-Action außerhalb des EDT erfasst. Während Refresh oder Update-Check
  laufen, bleibt der Vulnerability-Check deaktiviert, um konkurrierende Hintergrundaktionen zu vermeiden.
  Die Aktionsleiste kann laut Einstellung (`toolbarShowText`) wahlweise Icon- oder Text-Buttons darstellen und
  wird bei geänderten Einstellungen über den `MAVEN_UP_SETTINGS_TOPIC`-Message-Bus sofort neu aufgebaut.
  Unterhalb der Aktionsleiste liegt eine Filterzeile mit vier `ComboBox`-Elementen (Typ, verfügbare Updates via `TriStateFilter` [All/Yes/No], anstehende Änderungen via `TriStateFilter` [All/Yes/No], Sicherheitslücken via `VulnerabilityFilter` [ALL/VULNERABLE/SELF_VULNERABLE/TRANSITIVE_VULNERABLE/NOT_VULNERABLE]) und einem `SearchTextField` (Textfilter über
  GroupId, ArtifactId und Property, case-insensitiv; kann zusätzlich über den Kontextmenü-Eintrag **Filter by "..."** per `filterBy` befüllt werden); die beiden `TriStateFilter`-Comboboxen zeigen über `triStateFilterRenderer`/`triStateFilterOptionLabel` und die `TriStateFilterLabels`-Konstanten (`CHANGES_FILTER_LABELS`, `UPDATES_FILTER_LABELS`) kontextspezifische, selbsterklärende Optionstexte statt generischer Yes/No-Werte, die Vulnerabilities-Combobox nutzt `vulnerabilityFilterRenderer` und die Labels des `VulnerabilityFilter`-Enums. Alle Filter werden über
  einen `TableRowSorter` mittels der Top-Level-Funktion `rowMatchesFilter`
  kombiniert. Der Changes-Filter ist nur aktiv, wenn mindestens eine abweichende Version ausgewählt wurde (`isChangesFilterAvailable`/`updateChangesFilterState`, ausgelöst über `updateUpdateButtonState`). Der Updates-Filter ist nur nach einer erfolgreichen Versionssuche aktiv (`isUpdatesFilterAvailable`/`updateUpdatesFilterState`) und nutzt die Top-Level-Funktion `hasNewerVersion`; der Vulnerabilities-Filter ist nur nach einer erfolgreichen Sicherheitsprüfung aktiv (`vulnerabilityScanPerformed` via `isVulnerabilitiesFilterAvailable`/`updateVulnerabilitiesFilterState`) und unterscheidet über `VulnerabilityCell.hasDirectAdvisories`/`hasTransitiveAdvisories` zwischen eigenen und transitiven Befunden. Am Ende der Filterzeile setzt eine `ActionToolbar` mit einer einzelnen Reset-Aktion (`resetAllFilters`) alle Filter zurück; sie ist nur aktiv, solange `isResetFiltersEnabled` mindestens einen aktiven Filter meldet. Derselbe `TableRowSorter` übernimmt zusätzlich die spaltenweise Sortierung über die Kopfzeile:
  ein überschriebenes `toggleSortOrder` schaltet zyklisch zwischen aufsteigend, absteigend und
  unsortiert (pom.xml-Reihenfolge) um; die Spalten **Current Version** und **New Version** sind nicht
  sortierbar. Die Spalte **Vulnerabilities** ist über den `vulnerabilityCellComparator`
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
  In der aufgeklappten Liste markiert der gemeinsame Renderer `applyVersionDropdownRenderer()` die aktuelle Version
  mit „(current)" und die über `recommendedVersionForDependency()` ermittelte Fix-Version mit „(recommended)",
  jeweils fett – identisch zur `TransitiveVulnerabilitiesView`.
  `createVersionPanel()` baut das JPanel mit Status-Glyph und ComboBox zusammen.
  Farben verwenden `JBColor`-Doppelwerte für Light-/Dark-Mode-Kompatibilität.
  Die Sammelaktionen setzen die **New Version**-Auswahl gemeinsam:
  `selectHighestMajorVersionForAll()` wählt die höchste verfügbare Version, `selectHighestMinorVersionForAll()`
  die höchste Version innerhalb der aktuellen Major-Linie, `selectRecommendedVersionForAll()` die über
  `recommendedVersionForDependency()` ermittelte Fix-Version aller Dependencies mit eigenen Sicherheitswarnungen – alle drei nur für die aktuell sichtbaren
  (nicht ausgefilterten) Zeilen (`collectVisibleDependencyKeys()`) – und `resetAllVersionsToCurrent()` verwirft alle
  Auswahlen unabhängig vom Filter, während `resetVisibleVersionsToCurrent()` nur die sichtbaren (gefilterten) Zeilen zurücksetzt;
  ihre Aktivierung steuern `isBulkVersionSelectionEnabled()`, `hasRecommendedVersions()` und `isResetVersionsEnabled()`.
  `confirmAndResetAllVersionsToCurrent()` verzweigt anhand von `isResetFiltersEnabled()`: ist kein Filter aktiv, zeigt es bei aktivem
  `confirmVersionReset` einen Ja/Nein-Bestätigungsdialog (`MessageDialogBuilder` mit `DoNotAskOption`, dessen „Don't ask again" die
  Einstellung deaktiviert) und ruft `resetAllVersionsToCurrent()`; ist ein Filter aktiv, zeigt `confirmAndResetWithActiveFilter()`
  stattdessen einen Auswahldialog (`Messages.showDialog` mit den Optionen alle/gefiltert/abbrechen, ohne „Don't ask again").
  `isRowFilterHidingEntries()` und `bulkSelectionActionDescription()` erweitern den Tooltip der "Select Highest"-Aktionen bei aktivem Filter um einen Hinweis.
  Während die `TransitiveVulnerabilitiesView` sichtbar ist (`showingTransitiveView`), wirken das **Select Highest Version**-Menü
  (inkl. dritter Aktion **Select Recommended Version**) und **Reset All to Current Versions** auf die transitiven Koordinaten:
  `isBulkVersionSelectionEnabledForCurrentView()`/`isRecommendedSelectionEnabledForCurrentView()`/`isResetVersionsEnabledForCurrentView()`
  steuern die Aktivierung und routen die Ausführung an die `TransitiveVulnerabilitiesView` (in der Haupttabelle greift
  `isRecommendedSelectionEnabledForCurrentView()` auf `hasRecommendedVersions()` zurück); `confirmAndResetTransitiveSelections()`
  zeigt bei aktivem Filter der transitiven Ansicht (`filterPanel.isResetFiltersEnabled()`) über `askResetScopeWithActiveFilter()`
  die Auswahl „alle/nur gefilterte" (`resetSelections()`/`resetVisibleSelections()`) und sonst denselben
  Ja/Nein-Bestätigungsdialog (`confirmVersionReset`). `isRowFilterHidingEntries()` berücksichtigt die jeweils sichtbare Ansicht.
- **UpdateConfirmationDialog**: eigenständiger `DialogWrapper` (Top-Level in `ui`), der vor
  dem Anwenden die anstehenden Updates in einer schreibgeschützten Tabelle bestätigen lässt und
  die Option **Sync Maven Changes** (vorbelegt aus `MavenUpSettings.syncMavenAfterUpdate`) anbietet.
  `typeLabel()` erzeugt den Text der **Type**-Spalte und kennzeichnet Updates mit
  `DependencyUpdate.transitive = true` über `toolwindow.MyToolWindow.update.confirm.type.transitive`
  als „transitive -> managed dependency".
  `buildRowSorter()` macht die Spalten Group Id, Artifact Id und Type über `cellTextComparator`
  sortierbar (Zyklus aufsteigend → absteigend → unsortiert, `installSortableHeaderRenderer`);
  ab `CONFIRM_CURRENT_VERSION_COLUMN` (Index 3) sind die Versionsspalten nicht sortierbar.
- **TransitiveVulnerabilitiesView**: eigenständige `JBPanel`-Ansicht (Top-Level in `ui`), die alle
  transitiven, verwundbaren Abhängigkeiten in einer sortierbaren Tabelle (GroupId, ArtifactId, Type,
  Vulnerabilities-Anzahl mit Severity-Färbung, Current Version, New Version) auflistet. Die **Type**-Spalte übernimmt für Koordinaten, die
  bereits in der `pom.xml` (z. B. im `dependencyManagement`) deklariert sind, den Typ der Haupttabelle
  (`knownTypes`, Schlüssel `groupId:artifactId`) und zeigt sonst den transitiven Standardtyp
  (`toolwindow.TransitiveVulnerabilities.type.transitive`). Die empfohlene Fix-Version wird über die reine
  Funktion `recommendedFixVersion` aus den Advisories abgeleitet: Aus allen Fixed-Versionen oberhalb der aktuellen
  Version wird die niedrigste gewählt, in der laut `isFixedIn` **alle** Advisories der Koordinate behoben sind
  (betroffene Versionsbereiche schlagen dabei die reinen Fixed-Versionen, siehe **AffectedVersionRange**); lässt sich
  keine solche Version bestimmen, dient die Fix-Version mit den meisten behobenen Advisories – bei Gleichstand die
  niedrigste – als bestmögliche Empfehlung, damit kein unnötiger Major-Sprung vorgeschlagen wird. Sie wird
  über `recommendedByKey` im New-Version-Dropdown fett mit „(recommended)"-Marker hervorgehoben (analog zum
  „(current)"-Marker; keine eigene Spalte).
  Die editierbare **New Version**-Spalte spiegelt die New-Version-Spalte der Haupttabelle (Renderer/Editor via
  `buildVersionPanel`/`applyDropdownRenderer` (delegiert an `applyVersionDropdownRenderer`), `createVersionPanel`); die Auswahl liegt in `selectedVersions`
  (nur bewusst gewählte Werte, Standard = aktuelle Version) und wird über den `onSelectionChanged`-Callback an
  `refreshToolbar` gemeldet. `collectPendingUpdates`/`hasPendingUpdates` erzeugen daraus `DependencyUpdate`s vom
  Typ „managed dependency" (inkl. `fixedVulnerabilities` aus `advisoryIdsByKey` und `fixedVulnerabilityAliases`
  aus `advisoryAliasesByKey` für den pom-Kommentar und
  `transitive = true` für Koordinaten aus `transitiveOnlyKeys`, also solche, die nicht in der `pom.xml` deklariert sind);
  die verfügbaren Versionen stammen aus der Vereinigung von `availableVersions`
  (normale Versionssuche) und der persistenten `transitiveAvailableVersions`-Map, die nach einem
  Vulnerability-Scan über `fetchVulnerableTransitiveVersions` (nur für die verwundbaren transitiven
  Koordinaten, via `DependencyVersionService.fetchAvailableVersions`) befüllt wird. Die Trennung
  verhindert, dass eine erneute Versionssuche der Haupttabelle (die `availableVersions` leert) die
  New-Version-Spalte der transitiven Ansicht leert; `transitiveAvailableVersions` wird nur beim Leeren der
  Vulnerabilities (`clearVulnerabilities`) bzw. bei einem neuen Scan zurückgesetzt. Die Ansicht ist ein eigener
  `Content` **Transitive CVEs** des `ContentManager` des Tool Windows und wird daher von der IDE als Tab in der
  Kopfzeile gerendert (der JetBrains-Styleguide „Tabs" fordert für Tool Windows automatisch generierte Tabs statt einer
  eingebetteten Tab-Leiste); `MyToolWindow.bindTabs` merkt sich `ContentManager` und beide `Content`-Objekte, ein
  `ContentManagerListener` synchronisiert die Auswahl über `applySelectedTab` nach `showingTransitiveView`, und
  `setTransitiveViewVisible` wählt den Content programmatisch (ohne gebundenen `ContentManager` – z. B. in Tests – wird
  nur der interne Zustand gesetzt). `updateTransitiveVulnerabilitiesView` schreibt über `updateTransitiveTabTitle` die
  Anzahl betroffener Koordinaten (`transitiveVulnerabilityCount`/`hasTransitiveVulnerabilities`) in den Tab-Titel. Der Tab
  wird bewusst weder deaktiviert noch entfernt und es findet kein automatischer Rückwechsel statt, da der Styleguide
  vorschreibt: „Do not remove or disable a tab when its functions are unavailable. Explain why a tab's content is
  unavailable in the body of the tab." Stattdessen erklärt der Empty State der Tabelle
  (`toolwindow.TransitiveVulnerabilities.emptyText.noScan`/`...noMatches`), warum nichts angezeigt wird. Da eine
  Swing-Komponente nur einen Container haben kann, erzeugt `installToolbars` je Tab eine eigene `ActionToolbar`-Instanz
  über dieselbe `toolbarGroup`; `refreshToolbar` aktualisiert beide. Jeder Tab bringt seine eigene Filterzeile mit; die der Haupttabelle liegt
  im Tab **Dependencies**, die transitive Ansicht nutzt `TransitiveVulnerabilitiesFilterPanel` (siehe unten). Die reine Top-Level-Funktion `collectTransitiveVulnerabilityRows`
  (samt `TransitiveVulnerabilityRow` und den `TRANSITIVE_*_COLUMN`-Konstanten) baut und sortiert die Zeilen.
  Die Tabelle nutzt – wie die Haupttabelle – einen `TableRowSorter` mit überschriebenem `toggleSortOrder`
  (zyklisch aufsteigend → absteigend → unsortiert); die **Version**- und **New Version**-Spalten sind nicht sortierbar,
  die **Vulnerabilities**-Spalte sortiert über `vulnerabilityCellComparator`. Die Zeilenhöhe wird über
  `applyRecommendedRowHeight` (siehe `TableRowHeight.kt`) fest gesetzt, damit das ComboBox-Panel die
  Zeilen nicht höher macht und alle Tabellen dieselbe Zeilenhöhe haben.
  Ein Rechtsklick öffnet über IntelliJs `ActionSystem` ein Kontextmenü (`showContextMenu`) mit
  **Filter by "..."** (nur beim Rechtsklick auf die Spalten GroupId oder ArtifactId mit nicht-leerem Wert; setzt den
  angeklickten Wert über `filterPanel.filterBy` als alleinigen Textfilter),
  **Open on [Browser]** (konfigurierter Repository-Browser), den Versionsauswahl-Aktionen **Set Highest Major Version**,
  **Set Highest Minor Version** und **Set Recommended Version** (`selectHighestMajorVersionForDependency`/
  `selectHighestMinorVersionForDependency`/`selectRecommendedVersionForDependency`, aktiv über
  `hasSelectableVersionsForDependency`/`hasRecommendedVersionForDependency`), **Reset to Current Version**
  (`resetVersionForDependency`/`isVersionResetEnabledForDependency`) und **Show Vulnerability Details** (nur bei Funden aktiv).
  Für die Sammelauswahl über die Toolbar wirken `selectHighestMajorVersionForAll`/`selectHighestMinorVersionForAll`/
  `selectRecommendedVersionForAll` auf die aktuell sichtbaren (nicht ausgefilterten) Koordinaten; ihre Aktivierung steuern
  `isBulkVersionSelectionEnabled`/`hasRecommendedVersions`, das Zurücksetzen erfolgt über `resetSelections`
  (alle) bzw. `resetVisibleSelections` (nur gefilterte). `applyRowFilter` setzt den `RowFilter` des `TableRowSorter`
  aus `filterPanel.criteria()` und `rowMatchesFilter`; `isRowFilterHidingEntries` meldet ausgeblendete Zeilen.
  Über `hasSelectedRow`/`selectedRowHasVulnerabilities`/`openSelectedInRepository`/`openSelectedVulnerabilityDetails`
  bedienen dieselben Toolbar-Aktionen der Haupt-Aktionsleiste die aktuell selektierte Zeile dieser Ansicht.
  Ein Klick auf die Vulnerabilities-Zelle öffnet den `VulnerabilityDetailDialog`. Bewusst als eigene
  Komponente ausgelegt, damit ihr künftig weitere Aktionen hinzugefügt werden können.
- **TransitiveVulnerabilitiesFilterPanel**: Filterzeile der `TransitiveVulnerabilitiesView` (Top-Level in `ui`),
  die Optik und Verhalten der Filterzeile der Haupttabelle übernimmt: `SearchTextField` (Textfilter über GroupId und
  ArtifactId), `TriStateFilter`-Comboboxen für **Updates** und **Pending** (mit `triStateFilterRenderer` und den
  `UPDATES_FILTER_LABELS`/`CHANGES_FILTER_LABELS`) sowie eine `ActionToolbar` mit Reset-Aktion. Eine Filterung nach Typ
  und Vulnerabilities entfällt bewusst. `criteria()` liefert die `FilterCriteria`, `updateAvailability()` aktiviert die
  Comboboxen über die Callbacks `updatesAvailable` (`isBulkVersionSelectionEnabled`) und `changesAvailable`
  (`hasPendingUpdates`), `isResetFiltersEnabled`/`resetAllFilters`/`filterBy`/`refreshResetAction` steuern
  Reset-Button und Textfilter.
- **UI-Hilfsdateien (ui)**: Zustandslose Top-Level-Helfer, die aus `MavenUpWindowFactory.kt`
  in eigene Dateien desselben Packages ausgelagert wurden (reine Logik/Icons, keine
  `MyToolWindow`-Abhängigkeit):
  - `MavenUpTableConstants.kt`: Spalten-Indizes und Message-Key-/Typ-Konstanten.
  - `VersionStatusUi.kt`: `VersionUpdateArrowIcon`, `isVersionUpToDate`, `hasNewerVersion`,
    `versionStatusText`/`versionStatusColor`/`versionStatusTooltip`, `versionDropdownItemDisplay`
    (liefert Anzeigetext und Fettschrift-Status in einem Durchgang), `versionDropdownItemText`
    (Textvariante darauf aufbauend), `applyVersionDropdownRenderer` (gemeinsamer Dropdown-Renderer
    beider Tabellen: markiert die aktuelle Version mit „(current)" und die empfohlene Fix-Version mit
    „(recommended)" jeweils fett, während das Anzeigefeld Farbe/Font der ComboBox behält),
    `createVersionPanel` samt Status-Glyphen und `JBColor`-Werten.
  - `DependencyFilterModel.kt`: `TriStateFilter`, `TriStateFilterLabels`,
    `triStateFilterOptionLabel`, `triStateFilterRenderer` (geteilter Combobox-Renderer von Haupttabelle
    und transitiver Filterzeile), die `*_FILTER_LABELS`, `VulnerabilityFilter` samt
    `vulnerabilityFilterRenderer`, `FilterRow`, `FilterCriteria`,
    `rowMatchesFilter`.
  - `VulnerabilityCellModel.kt`: `VulnerabilityCell` (inkl. `declaredCoordinates` sowie
    `detailFindings`/`detailOrigins`), `VulnerabilityOrigin` (`DIRECT`, `TRANSITIVE`,
    `TRANSITIVE_DECLARED`), `buildVulnerabilityCell`,
    `vulnerabilitySummary`, `worstSeverity`, `canCheckVulnerabilities`, `vulnerabilityColor`,
    `vulnerabilityCellRenderer` (geteilter Zell-Renderer von Haupttabelle und transitiver Ansicht),
    `VulnerabilityScanTargets`, `artifactNodeCoordinate`, `coordinateString`.
  - `RefreshSnapshot.kt`: `RefreshRow`, `RefreshSnapshot`.
  - `MavenRepositoryLink.kt`: `buildMavenRepositoryUrl`.
  - `SortableHeaderIcon.kt`: `sortableHeaderIcon` und `installSortableHeaderRenderer`
    (geteilter Kopfzeilen-Renderer für alle Plugin-Tabellen).
  - `TableColumnWidths.kt`: `trimColumnWidthsToContent` samt `DEFAULT_COLUMN_WIDTH_PADDING`,
    `DEFAULT_MIN_COLUMN_WIDTH`, `DEFAULT_MAX_COLUMN_WIDTH` (allgemeine Regel für alle Plugin-Tabellen:
    Spaltenbreiten werden nach Inhalt getrimmt und mit der Fenster-/Dialoggröße proportional skaliert).
  - `TableRowHeight.kt`: `recommendedTableRowHeight`, `applyRecommendedRowHeight` samt
    `RECOMMENDED_TABLE_ROW_HEIGHT` (allgemeine Regel für alle Plugin-Tabellen: einheitliche, gemäß
    IntelliJ-Styleguide auf 24 px skalierte Zeilenhöhe statt der plattformabhängigen JBTable-Berechnung).
  - `VersionAutoSelection.kt`: `chooseAutoSelectedVersion`, `latestVersionWithinSameMajor`,
    `extractLeadingMajorNumber`, `selectableRecommendedVersion` (zustandslose Auto-Selektions-Helfer;
    `selectableRecommendedVersion` bildet eine empfohlene Fix-Version auf die tatsächlich abrufbaren
    Versionen ab – exakter Treffer oder niedrigste Version, die die Empfehlung erreicht).
  - `HelpTooltipExtensions.kt`: `HelpTooltip.withWrappingDescription` (versionsunabhängige
    `setDescription`-Brücke via Reflection).
- **MavenUpSettings**: `PersistentStateComponent` auf Anwendungsebene (`Service.Level.APP`), global für alle Projekte gespeichert in `mavenup_settings.xml`
  (`jumpOnSingleClick`, `versionAutoSelectionMode` mit `DISABLED`, `LATEST`, `LATEST_MINOR`, `hideUnstableVersions`, `hiddenVersionQualifiers`,
  `ossIndexEnabled`, `checkTransitiveDependencies`, `repositoryBrowser`, `toolbarShowText`,
  `syncMavenAfterUpdate`, `stopAfterCentralSuccess`, `offerAllVersions`, `confirmVersionReset`,
  `autoSearchVersions`, `vulnerabilityCommentMode` mit `NONE`, `ADVISORY_IDS`, `ALIASES`, `ALL_IDS`;
  Legacy-Migrationsfelder: `selectLatestVersion`, `selectLatestMinorVersion`, `addVulnerabilityFixComment`).
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
  Die Optionen sind in vier Gruppen (`group`) gegliedert: **Appearance**, **Versions & Updates**,
  **Pom.xml Changes** und **Vulnerability Check**.
  Bietet u.a. die Checkbox für Text-Buttons in der Aktionsleiste (`toolbarShowText`) und veröffentlicht
  beim Speichern den `MAVEN_UP_SETTINGS_TOPIC`.
  Die Gruppe **Versions & Updates** enthält zusätzlich die Option `stopAfterCentralSuccess` zur Steuerung,
  ob nach erfolgreicher Maven-Central-Abfrage weitere private Repositories abgefragt werden, sowie die
  Combobox `versionAutoSelectionMode` mit drei Zuständen für die Auto-Auswahl bei Update-Prüfungen.
  Die Gruppe **Pom.xml Changes** bündelt Einstellungen zum Schreibverhalten beim Anwenden von Updates:
  `syncMavenAfterUpdate` (automatischer Maven-Sync nach dem Schreiben der `pom.xml`) und die Combobox
  `vulnerabilityCommentMode` (Auswahl der Kennungen im erklärenden XML-Kommentar beim Anlegen eines gepinnten
  `dependencyManagement`-Eintrags zur Schwachstellenbehebung, siehe `PomUpdateService.addManagedDependency`).
  Die OSS-Index-Sektion kennzeichnet das Token bei Aktivierung als Pflichtfeld und
  verlinkt auf die Sonatype-Kontoeinstellungen zur Token-Erzeugung. Das Token wird außerhalb des EDT
  aus dem Password Safe geladen und für `isModified()` im UI-Modell gecacht.
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
- **RefreshSnapshotCollector**: liest über PSI die deklarierten Dependencies, Plugins und
  Versions-Properties der `pom.xml`-Dateien und liefert einen `RefreshSnapshot`; löst
  Property-Platzhalter (auch die Version im `<parent>`-Tag) über `resolveVersionPlaceholder` auf. Zustandslos, benötigt nur das Projekt.
- **PomUpdateService**: wendet ausgewählte Updates über PSI/`WriteCommandAction` auf die
  `pom.xml` an (`applyUpdateToPom`, `updateXmlTagVersion`, Parent/Dependencies/Plugins) und
  speichert die Dateien vor dem Maven-Sync (`persistPomChanges`). Für „managed dependency"-Updates
  ohne vorhandenen Eintrag legt `addManagedDependency` einen neuen `<dependencyManagement>`-Eintrag an
  (Container werden bei Bedarf erzeugt) und stellt der Abhängigkeit je nach Einstellung
  `vulnerabilityCommentMode` (Standard: `ADVISORY_IDS`) über `managedDependencyCommentText` einen XML-Kommentar
  mit den behobenen Vulnerability-Kennungen (IDs, Aliase oder beides) und MavenUp-Hinweis als erste Zeile voran
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
  `findPlugin`) und springt über `navigateToDependency` im Editor an die jeweilige Stelle.
- **VulnerabilityDetailDialog**: Master-Detail-Detailansicht für direkte und transitive Befunde. Rein informativer Dialog – zeigt ausschließlich einen **Close**-Button (kein OK/Cancel), entsprechend den JetBrains UI-Richtlinien für read-only Dialoge. Der obere Bereich (`OnePixelSplitter`) enthält eine Tabelle mit den Spalten Component, **Origin**, Source, Advisory, Aliases und **Severity**; die **Origin**-Spalte steht unmittelbar hinter Component und zeigt über `VulnerabilityCell.detailOrigins()` die Herkunft (`direct`, `transitive`, `transitive, also declared directly`), sodass der Komponentenname unverändert bleibt. Der untere Detailbereich ist ein `VulnerabilityInfoPanel`, das zur selektierten Zeile Komponente, Zusammenfassung und Referenzen anzeigt. Die **Severity**-Spalte ist per `vulnerabilityColor` nach Schweregrad farblich hinterlegt (gleiches Farbschema wie die Vulnerability-Spalte im Hauptfenster). Alle Tabellenspalten sind über einen `TableRowSorter` sortierbar (aufsteigend → absteigend → unsortiert) mit denselben `sortableHeaderIcon`-Indikatoren wie im Hauptfenster; die **Severity**-Spalte nutzt `severityCellComparator` (Kritikalität, dann CVSS-Score, beide absteigend – erster Klick zeigt die kritischsten Befunde oben), alle übrigen Spalten `cellTextComparator`. Die Spaltenbreiten werden beim Öffnen über `trimColumnWidthsToContent` inhaltsbasiert getrimmt und skalieren via `AUTO_RESIZE_SUBSEQUENT_COLUMNS` proportional mit der Dialoggröße. Der Dialog besitzt keine eigene Aktionsleiste mehr; das Öffnen im Repository-Browser erfolgt über einen Hyperlink im Detailbereich (top-level `artifactBrowserUrl`). Zusätzlich öffnet ein Rechtsklick auf die selektierte Zeile über IntelliJs `ActionSystem` ein plattformkonformes Kontextmenü mit **Open on [Browser]** (Label mit konfiguriertem Browser-Namen). Die Kontextmenüs verwenden `JBPopupMenu`/`JBMenuItem`, damit Theme, Abstände und Auswahlfarben der IDE verwendet werden.
- **VulnerabilityInfoPanel**: Detailbereich der Master-Detail-Ansicht. Zeigt zur selektierten Sicherheitswarnung (`showRow(coordinate, advisory)`) die betroffene Komponente inkl. **Open on ...**-Hyperlink zum konfigurierten Repository-Browser, Advisory-ID, Aliase, Schweregrad inkl. CVSS-Score, CVSS-Vektor, CWE-Kennungen, Veröffentlichungs- und Änderungsdatum, Quellen, betroffene Versionsbereiche, die Fixed-in-Versionen, die Zusammenfassung, die ausführliche Beschreibung und die Referenzen als anklickbare Hyperlinks (öffnen im Browser über `BrowserUtil`) in einem `JEditorPane` mit HTML-Inhalt (Word-Wrap-View-Factory für weichen Umbruch überlanger Zeilen, horizontaler Scrollbalken deaktiviert); ohne Selektion wird ein Platzhaltertext angezeigt.
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
