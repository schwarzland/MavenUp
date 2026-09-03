# MavenUp – Komponenten: UI (`ui`)

Teil des Projektkontexts – Einstieg: [`.github/copilot-project-context.md`](../copilot-project-context.md).
Beschreibt die zustandslosen Hilfsdateien und die I18n-Anbindung in
`src/main/kotlin/de/schwarzland/mavenup/ui/`. Die umfangreicheren Bereiche liegen in eigenen Dateien:

- [`components-ui-toolwindow.md`](components-ui-toolwindow.md): Tool Window `MavenUpWindowFactory`
  (Hintergrundverarbeitung, Aktionsleiste, Kontextmenü, Tabellenspalten, Filterzeile, Sortierung,
  Sammelaktionen, Verhalten bei sichtbarer transitiver Ansicht).
- [`components-ui-dialogs.md`](components-ui-dialogs.md): Dialoge, `TransitiveVulnerabilitiesView`
  und Settings-UI.

## UI-Hilfsdateien
- **Zustandslose UI-Helfer**: Top-Level-Helfer, die aus `MavenUpWindowFactory.kt`
  in eigene Dateien desselben Packages ausgelagert wurden (reine Logik/Icons, keine
  `MyToolWindow`-Abhängigkeit):
  - `MavenUpTableConstants.kt`: Spalten-Indizes und Message-Key-/Typ-Konstanten.
  - `DependencyTableEmptyText.kt`: `dependencyTableEmptyTextKey` samt den Bundle-Schlüsseln
    `EMPTY_TEXT_KEY_REFRESHING`, `EMPTY_TEXT_KEY_SEARCHING`, `EMPTY_TEXT_KEY_NO_DEPENDENCIES`
    und `EMPTY_TEXT_KEY_NO_MATCHES` – bestimmt den Hinweistext der leeren Haupttabelle
    (laufender Refresh bzw. laufende Versionssuche haben Vorrang vor Filter- und Leerzustand).
  - `TransitiveEmptyState.kt`: Enum `TransitiveEmptyState` samt Bundle-Schlüsseln und die reinen Funktionen
    `transitiveEmptyState` (Empty State des Tabs **Transitive CVEs**) und `isNoVulnerabilitiesHintVisible`
    (Sichtbarkeit des Erfolgshinweises im Tab **Dependencies**).
  - `VersionStatusUi.kt`: `VersionUpdateArrowIcon`, `isVersionUpToDate`, `hasNewerVersion`,
    `versionStatusText`/`versionStatusColor`/`versionStatusTooltip`, `versionDropdownItemDisplay`
    (liefert Anzeigetext und Fettschrift-Status in einem Durchgang), `versionDropdownItemText`
    (Textvariante darauf aufbauend), `applyVersionDropdownRenderer` (gemeinsamer Dropdown-Renderer
    beider Tabellen: markiert die aktuelle Version mit „(current)" und die empfohlene Fix-Version mit
    „(recommended)" jeweils fett, während das Anzeigefeld Farbe/Font der ComboBox behält),
    `createVersionPanel` samt Status-Glyphen und `JBColor`-Werten.
  - `DependencyFilterModel.kt`: `TriStateFilter`, `TriStateFilterLabels`,
    `triStateFilterOptionLabel`, `triStateFilterRenderer` (geteilter Combobox-Renderer von Haupttabelle
    und transitiver Filterzeile), die `*_FILTER_LABELS` (inkl. `VERSION_SOURCE_FILTER_LABELS`),
    `VulnerabilityFilter` samt
    `vulnerabilityFilterRenderer`, `FilterRow` (inkl. `versionInherited`),
    `FilterCriteria` (inkl. `versionSourceFilter`),
    `rowMatchesFilter`.
  - `VulnerabilityCellModel.kt`: `VulnerabilityCell` (inkl. `declaredCoordinates` sowie
    `detailFindings`/`detailOrigins`), `VulnerabilityOrigin` (`DIRECT`, `TRANSITIVE`,
    `TRANSITIVE_DECLARED`), `buildVulnerabilityCell`,
    `vulnerabilitySummary`, `worstSeverity`, `canCheckVulnerabilities`, `vulnerabilityColor`,
    `vulnerabilityCellRenderer` (geteilter Zell-Renderer von Haupttabelle und transitiver Ansicht),
    `VulnerabilityScanTargets`, `artifactNodeCoordinate`, `coordinateString`.
  - `RefreshSnapshot.kt`: `RefreshRow` (inkl. `versionInherited`), `RefreshSnapshot`.
  - `InheritedVersionUi.kt`: `inheritedVersionCellText`, `inheritedVersionTooltip`,
    `createCurrentVersionRenderer` samt `INHERITED_VERSION_MARKER_KEY`/`INHERITED_VERSION_TOOLTIP_KEY` –
    kennzeichnet Zeilen ohne eigenes `<version>`-Tag in der Spalte **Current Version** kursiv, in
    `JBColor.GRAY` und mit dem Marker „(inherited)"; die Vordergrundfarbe wird pro Aufruf explizit
    gesetzt (Auswahl-, Gedämpft- oder Tabellenfarbe), der Renderer hält eine lebende Referenz auf die
    Schlüsselmenge, der Modellwert der Zelle bleibt unverändert.
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

## I18n
- **MyMessageBundle**: I18n-Wrapper (`messages.MyMessageBundle`).
