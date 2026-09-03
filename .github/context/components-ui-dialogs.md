# MavenUp – Komponenten: Dialoge, Ansichten und Settings-UI (`ui`)

Teil des Projektkontexts – Einstieg: [`.github/copilot-project-context.md`](../copilot-project-context.md).
Beschreibt die Dialoge, die transitive Sicherheitslücken-Ansicht und das Settings-UI. Übrige
UI-Komponenten: [`components-ui.md`](components-ui.md) und
[`components-ui-toolwindow.md`](components-ui-toolwindow.md).

## Dialoge und Ansichten
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
  Typ „managed dependency" (inkl. `fixedVulnerabilities` und `fixedVulnerabilityAliases`, die über
  `commentAdvisories`/`advisoriesBySeverity` aus `advisoriesByKey` absteigend nach Schweregrad für den
  pom-Kommentar sortiert werden – die Zeilensortierung der Tabelle bleibt davon unberührt – und
  `transitive = true` für Koordinaten aus `transitiveOnlyKeys`, also solche, die nicht in der `pom.xml` deklariert sind);
  die verfügbaren Versionen stammen aus der Vereinigung von `availableVersions`
  (normale Versionssuche) und der persistenten `transitiveAvailableVersions`-Map, die nach einem
  Vulnerability-Scan über `fetchVulnerableTransitiveVersions` (nur für die verwundbaren transitiven
  Koordinaten, via `DependencyVersionService.fetchAvailableVersions`) befüllt wird. Die Trennung
  verhindert, dass eine erneute Versionssuche der Haupttabelle (die `availableVersions` leert) die
  New-Version-Spalte der transitiven Ansicht leert; `transitiveAvailableVersions` wird nur beim Leeren der
  Vulnerabilities (`clearVulnerabilities`) bzw. bei einem neuen Scan zurückgesetzt. `dropUnavailableSelections`
  verwirft dabei Auswahlen, die durch geänderte Anzeigeeinstellungen nicht mehr angeboten werden. Die Ansicht ist ein eigener
  `Content` **Transitive CVEs** des `ContentManager` des Tool Windows und wird daher von der IDE als Tab in der
  Kopfzeile gerendert (der JetBrains-Styleguide „Tabs" fordert für Tool Windows automatisch generierte Tabs statt einer
  eingebetteten Tab-Leiste); `MyToolWindow.bindTabs` merkt sich `ContentManager` und beide `Content`-Objekte, ein
  `ContentManagerListener` synchronisiert die Auswahl über `applySelectedTab` nach `showingTransitiveView`, und
  `setTransitiveViewVisible` wählt den Content programmatisch (ohne gebundenen `ContentManager` – z. B. in Tests – wird
  nur der interne Zustand gesetzt). `updateTransitiveVulnerabilitiesView` schreibt über `updateTransitiveTabTitle` die
  Anzahl betroffener Koordinaten (`transitiveVulnerabilityCount`/`hasTransitiveVulnerabilities`) in den Tab-Titel. Der Tab
  wird bewusst weder deaktiviert noch entfernt und es findet kein automatischer Rückwechsel statt, da der Styleguide
  vorschreibt: „Do not remove or disable a tab when its functions are unavailable. Explain why a tab's content is
  unavailable in the body of the tab." Stattdessen erklärt der Empty State der Tabelle, warum nichts angezeigt wird:
  `updateEmptyText` leitet den Zustand über die reine Funktion `transitiveEmptyState` (Enum `TransitiveEmptyState`)
  aus `scanPerformed`, dem Zeilenbestand und `hasDirectFindings` ab – `NOT_SCANNED`, `NO_MATCHES`, `NO_FINDINGS`
  (Scan ohne jeden Befund) oder `ONLY_DIRECT` (nur direkt deklarierte Befunde). Im Fall `ONLY_DIRECT` hängt
  `appendLine` eine Link-Zeile an, deren `onShowDirectVulnerabilities`-Callback über
  `MyToolWindow.showDirectVulnerabilitiesInDependencies` in den Tab **Dependencies** wechselt und dort auf
  `VulnerabilityFilter.SELF_VULNERABLE` filtert. Da eine
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
- **VulnerabilityDetailDialog**: Master-Detail-Detailansicht für direkte und transitive Befunde. Rein informativer Dialog – zeigt ausschließlich einen **Close**-Button (kein OK/Cancel), entsprechend den JetBrains UI-Richtlinien für read-only Dialoge. Der obere Bereich (`OnePixelSplitter`) enthält eine Tabelle mit den Spalten Component, **Origin**, Source, Advisory, Aliases und **Severity**; die **Origin**-Spalte steht unmittelbar hinter Component und zeigt über `VulnerabilityCell.detailOrigins()` die Herkunft (`direct`, `transitive`, `transitive, also declared directly`), sodass der Komponentenname unverändert bleibt. Der untere Detailbereich ist ein `VulnerabilityInfoPanel`, das zur selektierten Zeile Komponente, Zusammenfassung und Referenzen anzeigt. Die **Severity**-Spalte ist per `vulnerabilityColor` nach Schweregrad farblich hinterlegt (gleiches Farbschema wie die Vulnerability-Spalte im Hauptfenster). Alle Tabellenspalten sind über einen `TableRowSorter` sortierbar (aufsteigend → absteigend → unsortiert) mit denselben `sortableHeaderIcon`-Indikatoren wie im Hauptfenster; die **Severity**-Spalte nutzt `severityCellComparator` (Kritikalität, dann CVSS-Score, beide absteigend – erster Klick zeigt die kritischsten Befunde oben), alle übrigen Spalten `cellTextComparator`. Die Spaltenbreiten werden beim Öffnen über `trimColumnWidthsToContent` inhaltsbasiert getrimmt und skalieren via `AUTO_RESIZE_SUBSEQUENT_COLUMNS` proportional mit der Dialoggröße. Der Dialog besitzt keine eigene Aktionsleiste mehr; das Öffnen im Repository-Browser erfolgt über einen Hyperlink im Detailbereich (top-level `artifactBrowserUrl`). Zusätzlich öffnet ein Rechtsklick auf die selektierte Zeile über IntelliJs `ActionSystem` ein plattformkonformes Kontextmenü mit **Open on [Browser]** (Label mit konfiguriertem Browser-Namen). Die Kontextmenüs verwenden `JBPopupMenu`/`JBMenuItem`, damit Theme, Abstände und Auswahlfarben der IDE verwendet werden.
- **VulnerabilityInfoPanel**: Detailbereich der Master-Detail-Ansicht. Zeigt zur selektierten Sicherheitswarnung (`showRow(coordinate, advisory)`) die betroffene Komponente inkl. **Open on ...**-Hyperlink zum konfigurierten Repository-Browser, Advisory-ID, Aliase, Schweregrad inkl. CVSS-Score, CVSS-Vektor, CWE-Kennungen, Veröffentlichungs- und Änderungsdatum, Quellen, betroffene Versionsbereiche, die Fixed-in-Versionen, die Zusammenfassung, die ausführliche Beschreibung und die Referenzen als anklickbare Hyperlinks (öffnen im Browser über `BrowserUtil`) in einem `JEditorPane` mit HTML-Inhalt (Word-Wrap-View-Factory für weichen Umbruch überlanger Zeilen, horizontaler Scrollbalken deaktiviert); ohne Selektion wird ein Platzhaltertext angezeigt.

## Settings-UI
- **MavenUpConfigurable**: Settings-UI unter `Settings > Tools > MavenUp`.
  Die Optionen sind in vier Gruppen (`group`) gegliedert, die dem Arbeitsablauf folgen:
  **Appearance and Behavior**, **Versions and Updates**, **Vulnerability Check** und **Pom.xml Changes**.
  Bietet u.a. die Checkbox für Text-Buttons in der Aktionsleiste (`toolbarShowText`) und veröffentlicht
  beim Speichern den `MAVEN_UP_SETTINGS_TOPIC`.
  Die Gruppe **Versions and Updates** enthält zusätzlich die Option `stopAfterCentralSuccess` zur Steuerung,
  ob nach erfolgreicher Maven-Central-Abfrage weitere private Repositories abgefragt werden, die
  Combobox `versionAutoSelectionMode` mit drei Zuständen für die Auto-Auswahl bei Update-Prüfungen
  (nach den Versionsfiltern platziert, da die Vorauswahl auf der gefilterten Liste arbeitet) sowie
  `confirmVersionReset`.
  Die Gruppe **Pom.xml Changes** bündelt Einstellungen zum Schreibverhalten beim Anwenden von Updates:
  `syncMavenAfterUpdate` (automatischer Maven-Sync nach dem Schreiben der `pom.xml`), die Combobox
  `vulnerabilityCommentMode` (Auswahl der Kennungen im erklärenden XML-Kommentar beim Anlegen eines gepinnten
  `dependencyManagement`-Eintrags zur Schwachstellenbehebung, siehe `PomUpdateService.addManagedDependency`)
  sowie die eingerückten Felder `vulnerabilityCommentPrefix` (Kommentartext) und `vulnerabilityCommentMaxIds`
  (Spinner `0..99`), die über `updateVulnerabilityCommentControlsEnabled` je nach Modus aktiviert werden.
  Die OSS-Index-Sektion kennzeichnet das Token bei Aktivierung als Pflichtfeld und
  verlinkt auf die Sonatype-Kontoeinstellungen zur Token-Erzeugung. Das Token wird außerhalb des EDT
  aus dem Password Safe geladen und für `isModified()` im UI-Modell gecacht.
