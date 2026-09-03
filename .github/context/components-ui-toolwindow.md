# MavenUp – Komponenten: Tool-Window (`ui`)

Teil des Projektkontexts – Einstieg: [`.github/copilot-project-context.md`](../copilot-project-context.md).
Beschreibt das Tool Window `MavenUpWindowFactory` samt Tabelle, Aktionsleiste, Kontextmenü,
Filterzeile, Sortierung und Sammelaktionen. Übrige UI-Komponenten:
[`components-ui.md`](components-ui.md) und [`components-ui-dialogs.md`](components-ui-dialogs.md).

## Tool-Window (`MavenUpWindowFactory`)
Zentrale `ToolWindowFactory` + `MyToolWindow`: Tabelle der Dependencies/Plugins, Navigation zur
pom.xml-Definition sowie Multi-Source-Vulnerability-Checks für direkte und transitive Dependencies
in Hintergrund-Tasks.

### Hintergrundverarbeitung und Empty State
- Maven-/PSI-Daten für Refreshes werden über eine nicht blockierende Read-Action außerhalb des EDT erfasst.
- Während Refresh oder Update-Check laufen, bleibt der Vulnerability-Check deaktiviert, um konkurrierende Hintergrundaktionen zu vermeiden.
- Solange ein Refresh (`isRefreshing`) oder eine Versionssuche (`isSearchingVersions`) läuft, bleibt die Haupttabelle bewusst leer: `refreshAction` baut die Zeilen bei `checkUpdates = true` erst im abschließenden Folge-Refresh nach der Versionssuche auf.
- `updateTableEmptyText` setzt über `dependencyTableEmptyTextKey` den erklärenden Hinweistext (laufender Vorgang, keine Dependencies geladen oder alle Zeilen ausgefiltert) – analog zum Empty State der `TransitiveVulnerabilitiesView`.
- `updateScanHint` blendet nach einem Scan ohne jeden Befund (`isNoVulnerabilitiesHintVisible` aus `directVulnerabilityCount`/`transitiveVulnerabilityCount`) einen schließbaren `InlineBanner` mit `EditorNotificationPanel.Status.Success` im `scanHintPanel` direkt oberhalb der Tabelle ein; `hideScanHint` entfernt ihn beim Schließen, bei einem Refresh mit `clearVulnerabilities` und bei einem Scan mit Befunden. `lastScannedCount` (Größe von `VulnerabilityScanTargets.dependencies`) liefert die im Text genannte Anzahl geprüfter Koordinaten.

### Aktionsleiste
- Die Aktionen liegen in einer `ActionToolbar` (Icon-Actions mit Tooltip) oberhalb der jeweiligen Tabelle – je Tab eine eigene Instanz über dieselbe Aktionsgruppe – und wirken stets auf die Tabelle des aktiven Tabs.
- Anordnung: links die Kernaktionen **Refresh and Search for New Versions**, **Scan for Vulnerabilities** und **Update**; durch einen Trenner abgesetzt das Aufklappmenü **Select Highest Version** (Icon `VersionUpdateArrowIcon`, dasselbe Aufwärtspfeil-Glyph „↑" wie die New-Version-Spalte) mit den Aktionen **Select Highest Major Version**, **Select Highest Minor Version** und **Select Recommended Version**, gefolgt von der eigenständigen Aktion **Reset All to Current Versions**; durch einen weiteren Trenner abgesetzt die selektionsabhängigen Aktionen **Open on [Browser]** und **Vulnerability Details**; am Ende **Settings**.
- **Refresh and Search for New Versions** lädt die `pom.xml`-Daten neu, verwirft Versionsauswahlen und Scan-Ergebnisse und startet anschließend die Versionssuche; `isRefreshEnabled` deaktiviert die Aktion nur während einer laufenden Aktualisierung und lässt sie in beiden Tabs zu. Dieselbe Kombination läuft automatisch beim Aufbau des Tool-Window-Inhalts und bei jedem abgeschlossenen Maven-Import, sofern `isAutoVersionSearchEnabled` die Einstellung `autoSearchVersions` als aktiv meldet.
- **Select Recommended Version** ist aktiv, sobald die aktive Ansicht mindestens eine empfohlene Fix-Version anbietet.
- **Open on [Browser]** wird aktiv, sobald eine Dependency-Zeile selektiert ist, und zeigt dynamisch den konfigurierten Browser-Namen im Tooltip (z. B. **Open on MVN Repository** oder **Open on Sonatype Central**).
- **Vulnerability Details** ist erst aktiv, wenn eine Dependency-Zeile mit Befunden selektiert ist, und zeigt ausschließlich die Befunde der selektierten Dependency (direkte und transitive).
- Beide selektionsabhängigen Aktionen wirken je nach aktivem Tab (Haupttabelle oder `TransitiveVulnerabilitiesView`) auf die dort selektierte Zeile; die Umschaltung erfolgt über `showingTransitiveView` in `isOpenInRepositoryEnabled`/`isVulnerabilityDetailsEnabled`/`openInMavenRepositoryForSelectedRow`/`openVulnerabilityDetailsForSelectedRow`, und ein Selektions-Listener der transitiven Tabelle stößt `refreshToolbar` an.
- Die Aktionsleiste kann laut Einstellung `toolbarShowText` wahlweise Icon- oder Text-Buttons darstellen und wird bei geänderten Einstellungen über den `MAVEN_UP_SETTINGS_TOPIC`-Message-Bus sofort neu aufgebaut.
- Bei aktivierten Textbeschriftungen zeigen die Buttons gekürzte Labels (z. B. **Refresh**, **Scan**, **Update**, **Highest**, **Reset**, **Open**, **Details**), während die vollständige Beschriftung als Tooltip erhalten bleibt.
- Der Tooltip des **Select Highest Version**-Menüs weist darauf hin, dass nur die aktuell sichtbaren Dependencies geändert werden; der Tooltip von **Reset All to Current Versions** benennt, dass ohne aktiven Filter alle Dependencies zurückgesetzt werden und bei aktivem Filter zwischen allen und nur den gefilterten gewählt werden kann.

### Designentscheidung: horizontale Toolbar
Die horizontale Ausrichtung ist eine bewusste Entscheidung und nicht zu einer vertikalen Toolbar am linken Rand umzubauen:
- Der JetBrains-Styleguide („Toolbar → Location") empfiehlt für standardmäßig horizontale Tool-Windows (MavenUp ist `anchor="bottom"`) zwar eine linke vertikale Toolbar, nennt aber ausdrücklich die Ausnahme „items that need horizontal space, like a search field and drop-down lists" (Vorbild Git-Tool-Window) – genau das trifft hier zu, weil die Aktionsleiste untrennbar mit der Filterzeile aus `SearchTextField` und Combo-Boxen zusammenspielt.
- Die Einstellung `toolbarShowText` zeigt standardmäßig Textbeschriftungen, was vertikal rund 150 px Breite kosten würde.
- 9 Aktionen samt Trennern würden vertikal die Höhe eines Bottom-Tool-Windows sprengen und im Chevron verschwinden.
- Eine vertikale Toolbar neben der horizontalen Filterzeile ergäbe die vom Styleguide abgeratene Doppel-Toolbar-Optik.

### Kontextmenü der Tabellenzeile
Per Rechtsklick auf eine Zeile öffnet sich ein Kontextmenü. Alle Einträge bleiben stets sichtbar und ändern nur ihren Aktivierungszustand (kein Ein-/Ausblenden):
- **Filter by "..."**: nur beim Rechtsklick auf die Spalten GroupId, ArtifactId oder Property mit nicht-leerem Wert; setzt den angeklickten Wert als alleinigen Textfilter, ersetzt vorhandenen Text und wendet ihn sofort an (`filterBy`).
- **Navigate to pom.xml**: springt zur Definition der Zeile in der `pom.xml`.
- **Navigate to Property Definition**: springt über `navigateToProperty` zur Property-Definition der Zeile; nur aktiv, wenn die Zeile eine Version-Property besitzt.
- **Open on [Browser]**: öffnet die Dependency im konfigurierten Repository-Browser.
- **Set Highest Major Version** / **Set Highest Minor Version**: setzen ausschließlich für die angeklickte Dependency die höchste verfügbare Version bzw. die höchste Version der aktuellen Major-Linie; nur aktiv, wenn die verfügbaren Versionen dieser Dependency bereits abgerufen wurden (`selectHighestMajorVersionForDependency`/`selectHighestMinorVersionForDependency`/`hasSelectableVersionsForDependency`).
- **Set Recommended Version**: setzt für die angeklickte Dependency die aus ihren **eigenen** Sicherheitswarnungen abgeleitete Fix-Version; nur aktiv, wenn die Dependency selbst betroffen ist und eine abgerufene Version die Empfehlung erreicht (`selectRecommendedVersionForDependency`/`hasRecommendedVersionForDependency`/`recommendedVersionForDependency`).
- **Reset to Current Version**: verwirft ausschließlich für die angeklickte Dependency die Auswahl; nur aktiv bei abweichender Auswahl (`resetVersionForDependency`/`isVersionResetEnabledForDependency`).
- **Show Vulnerability Details**: deaktiviert, wenn die Dependency keine Befunde hat.

Der verwendete Repository-Browser (**MVN Repository** oder **Sonatype Central**) ist in den Einstellungen
konfigurierbar und gilt einheitlich für das Kontextmenü sowie das zeilenbezogene Rechtsklick-Menü im
Vulnerability-Details-Dialog. Die Kontextmenüs werden über IntelliJs `ActionSystem` beziehungsweise
plattformkonforme `JBPopupMenu`/`JBMenuItem`-Komponenten erzeugt, damit Theme, Abstände und
Auswahlfarben der IDE verwendet werden.

### Tabellenspalten
- **Vulnerabilities** steht direkt vor **Current Version** und ordnet transitive Befunde über den Maven-Dependency-Tree der jeweiligen direkten Dependency zu; die Spalte zeigt Gesamtzahl, transitive Anzahl und höchste Severity, der zeilenbezogene Detaildialog markiert die zugehörigen transitiven Komponenten.
- **Current Version** nutzt den über `createCurrentVersionRenderer` gesetzten Renderer: Einträge ohne eigenes `<version>`-Tag (Version aus Parent-POM oder importiertem BOM) stehen in `inheritedVersionDependencies` und werden kursiv, in `JBColor.GRAY` und mit dem Marker „(inherited)" samt erklärendem Tooltip dargestellt. Der Renderer setzt die Vordergrundfarbe bei jedem Aufruf explizit, weil `DefaultTableCellRenderer` dieselbe Komponenteninstanz wiederverwendet und eine gesetzte Farbe sonst auf alle folgenden Zeilen durchschlägt. Alle Kontext- und Toolbar-Aktionen bleiben für diese Zeilen unverändert nutzbar.
- **New Version** zeigt über die Helper-Funktionen `isVersionUpToDate()`, `versionStatusText()`, `versionStatusColor()` und `versionStatusTooltip()` ein Status-Glyph und farbcodierten Text: grüner Haken „✓", wenn die ausgewählte Version die neueste ist, sonst ein Pfeil nach oben „↑". Das Glyph ist ein einfärbbares Text-Label (kein IntelliJ-Icon) und übernimmt dieselbe Farbe wie die Versionsnummer: Es wird nur eingefärbt (grün bzw. orange), wenn eine von der aktuellen abweichende Version ausgewählt ist; andernfalls verwendet es die Standardfarbe. Glyph und Farbe richten sich immer nach der **ausgewählten** Version im Dropdown.
- Bei einer ausstehenden Änderung (ausgewählte ≠ aktuelle Version) wird der Dropdown-Text fett dargestellt. In der aufgeklappten Liste markiert der gemeinsame Renderer `applyVersionDropdownRenderer()` die aktuelle Version mit „(current)" und die über `recommendedVersionForDependency()` ermittelte Fix-Version mit „(recommended)", jeweils fett – identisch zur `TransitiveVulnerabilitiesView`.
- `createVersionPanel()` baut das JPanel mit Status-Glyph und ComboBox zusammen; Farben verwenden `JBColor`-Doppelwerte für Light-/Dark-Mode-Kompatibilität.
- Die abgerufenen Versionen liegen ungefiltert in `rawAvailableVersions` bzw. `rawTransitiveAvailableVersions`; `availableVersions`/`transitiveAvailableVersions` enthalten die daraus über `DependencyApiService.applyVersionSettings` abgeleitete Anzeige. Ändern sich `hideUnstableVersions`, `hiddenVersionQualifiers` oder `offerAllVersions`, berechnet `applyVersionVisibilitySettingsIfChanged` (Vergleich gegen `lastVersionVisibilitySettings`) über `applyVersionVisibilitySettings` die Spalte **New Version** beider Ansichten sofort neu, ohne erneute Netzwerkabfrage; nicht mehr angebotene Auswahlen werden verworfen.

### Filterzeile
- Unterhalb der Aktionsleiste liegt eine Filterzeile mit fünf `ComboBox`-Elementen (Typ, Versionsherkunft via `TriStateFilter` [All/Yes/No], verfügbare Updates via `TriStateFilter` [All/Yes/No], anstehende Änderungen via `TriStateFilter` [All/Yes/No], Sicherheitslücken via `VulnerabilityFilter` [ALL/VULNERABLE/SELF_VULNERABLE/TRANSITIVE_VULNERABLE/NOT_VULNERABLE]) und einem `SearchTextField` (Textfilter über GroupId, ArtifactId und Property, case-insensitiv; kann zusätzlich über den Kontextmenü-Eintrag **Filter by "..."** per `filterBy` befüllt werden).
- Die `TriStateFilter`-Comboboxen zeigen über `triStateFilterRenderer`/`triStateFilterOptionLabel` und die `TriStateFilterLabels`-Konstanten (`CHANGES_FILTER_LABELS`, `UPDATES_FILTER_LABELS`, `VERSION_SOURCE_FILTER_LABELS`) kontextspezifische Optionstexte statt generischer Yes/No-Werte; die Vulnerabilities-Combobox nutzt `vulnerabilityFilterRenderer` und die Labels des `VulnerabilityFilter`-Enums.
- Alle Filterlabels und Optionstexte sind bewusst kurz gehalten (z. B. `CVEs:` mit `Any`/`Own`/`Transitive`/`None`), damit die Filterzeile wenig Breite belegt; die vollständige Bedeutung steht jeweils im Tooltip.
- Alle Filter werden über einen `TableRowSorter` mittels der Top-Level-Funktion `rowMatchesFilter` kombiniert.
- Der Changes-Filter ist nur aktiv, wenn mindestens eine abweichende Version ausgewählt wurde (`isChangesFilterAvailable`/`updateChangesFilterState`, ausgelöst über `updateUpdateButtonState`).
- Der Versionsherkunft-Filter ist nur aktiv, solange `inheritedVersionDependencies` nicht leer ist (`isVersionSourceFilterAvailable`/`updateVersionSourceFilterState`), und blendet wahlweise nur geerbte oder nur im `pom.xml` deklarierte Zeilen ein.
- Der Updates-Filter ist nur nach einer erfolgreichen Versionssuche aktiv (`isUpdatesFilterAvailable`/`updateUpdatesFilterState`) und nutzt die Top-Level-Funktion `hasNewerVersion`.
- Der Vulnerabilities-Filter ist nur nach einer erfolgreichen Sicherheitsprüfung aktiv (`vulnerabilityScanPerformed` via `isVulnerabilitiesFilterAvailable`/`updateVulnerabilitiesFilterState`) und unterscheidet über `VulnerabilityCell.hasDirectAdvisories`/`hasTransitiveAdvisories` zwischen eigenen und transitiven Befunden.
- Am Ende der Filterzeile setzt eine `ActionToolbar` mit einer einzelnen Reset-Aktion (`resetAllFilters`) alle Filter zurück; sie ist nur aktiv, solange `isResetFiltersEnabled` mindestens einen aktiven Filter meldet.

### Sortierung
- Derselbe `TableRowSorter` übernimmt zusätzlich die spaltenweise Sortierung über die Kopfzeile: ein überschriebenes `toggleSortOrder` schaltet zyklisch zwischen aufsteigend, absteigend und unsortiert (pom.xml-Reihenfolge) um.
- Die Spalten **Current Version** und **New Version** sind nicht sortierbar.
- Die Spalte **Vulnerabilities** ist über den `vulnerabilityCellComparator` (in `VulnerabilityCellModel.kt`) sortierbar: primär nach dem höchsten Schweregrad der Zelle, sekundär nach der Anzahl der Warnungen.
- Ein über `installSortableHeaderRenderer` gesetzter Kopfzeilen-Renderer zeigt für sortierbare Spalten über die Top-Level-Funktion `sortableHeaderIcon` ein Indikator-Icon an (gedämpfter `AllIcons.General.ArrowSplitCenterV`-Doppelpfeil im unsortierten Zustand, `ArrowUp`/`ArrowDown` bei aktiver Sortierung).

### Sammelaktionen für die Versionsauswahl
- `selectHighestMajorVersionForAll()` wählt die höchste verfügbare Version, `selectHighestMinorVersionForAll()` die höchste Version innerhalb der aktuellen Major-Linie, `selectRecommendedVersionForAll()` die über `recommendedVersionForDependency()` ermittelte Fix-Version aller Dependencies mit eigenen Sicherheitswarnungen – alle drei nur für die aktuell sichtbaren (nicht ausgefilterten) Zeilen (`collectVisibleDependencyKeys()`).
- `resetAllVersionsToCurrent()` verwirft alle Auswahlen unabhängig vom Filter, `resetVisibleVersionsToCurrent()` setzt nur die sichtbaren (gefilterten) Zeilen zurück.
- Die Aktivierung der Sammelaktionen steuern `isBulkVersionSelectionEnabled()`, `hasRecommendedVersions()` und `isResetVersionsEnabled()`.
- `confirmAndResetAllVersionsToCurrent()` verzweigt anhand von `isResetFiltersEnabled()`: ohne aktiven Filter zeigt es bei aktivem `confirmVersionReset` einen Ja/Nein-Bestätigungsdialog (`MessageDialogBuilder` mit `DoNotAskOption`, dessen „Don't ask again" die Einstellung deaktiviert) und ruft `resetAllVersionsToCurrent()`; bei aktivem Filter zeigt `confirmAndResetWithActiveFilter()` stattdessen einen Auswahldialog (`Messages.showDialog` mit den Optionen alle/gefiltert/abbrechen, ohne „Don't ask again").
- `isRowFilterHidingEntries()` und `bulkSelectionActionDescription()` erweitern den Tooltip der **Select Highest**-Aktionen bei aktivem Filter um einen Hinweis.

### Verhalten bei sichtbarer transitiver Ansicht
- Während die `TransitiveVulnerabilitiesView` sichtbar ist (`showingTransitiveView`), wirken das **Select Highest Version**-Menü (inkl. dritter Aktion **Select Recommended Version**) und **Reset All to Current Versions** auf die transitiven Koordinaten.
- `isBulkVersionSelectionEnabledForCurrentView()`/`isRecommendedSelectionEnabledForCurrentView()`/`isResetVersionsEnabledForCurrentView()` steuern die Aktivierung und routen die Ausführung an die `TransitiveVulnerabilitiesView`; in der Haupttabelle greift `isRecommendedSelectionEnabledForCurrentView()` auf `hasRecommendedVersions()` zurück.
- `confirmAndResetTransitiveSelections()` zeigt bei aktivem Filter der transitiven Ansicht (`filterPanel.isResetFiltersEnabled()`) über `askResetScopeWithActiveFilter()` die Auswahl „alle/nur gefilterte" (`resetSelections()`/`resetVisibleSelections()`) und sonst denselben Ja/Nein-Bestätigungsdialog (`confirmVersionReset`).
- `isRowFilterHidingEntries()` berücksichtigt die jeweils sichtbare Ansicht.
