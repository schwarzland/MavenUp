# MavenUp

MavenUp ist ein IntelliJ-Plugin, das speziell für Maven-Projekte entwickelt wurde, um die Verwaltung von Abhängigkeiten (Dependencies) und Plugins zu vereinfachen. Es bietet eine übersichtliche Tabellenansicht aller deklarierten Komponenten und ermöglicht die einfache Aktualisierung auf neuere Versionen.

Eine vollständige, englische Feature-Liste steht in `FEATURES.md`.

## Funktionen

- **Tool-Window für Maven-Projekte**: MavenUp ist nur bei Maven-Projekten verfügbar und zeigt alle gefundenen Module/`pom.xml`-Dateien in einer gemeinsamen Tabelle.
- **Übersicht für Dependencies und Plugins**: Erfasst normale Einträge, `dependencyManagement`, `pluginManagement` sowie die `<parent>`-Sektion inklusive Typ-Kennzeichnung.
- **Feste Spaltenreihenfolge**: In allen Tabellen des Plugins lässt sich immer nur eine Zeile auswählen, und die Spalten können nicht per Drag-and-drop umgeordnet werden, sodass die Spaltenreihenfolge fest bleibt.
- **Filter für die Dependency-Tabelle**: Oberhalb der Tabelle filtert ein Textfeld case-insensitiv über die Spalten **GroupId**, **ArtifactId** und **Property**; drei Comboboxen filtern zusätzlich nach **Type** (Dependency-Typ), **Changes** (ausstehende Versionsänderungen: All/Yes/No) und **Vulnerabilities** (Sicherheitslücken: All/Yes/No). Alle Filter werden kombiniert, sodass nur noch passende Zeilen angezeigt werden. Die Type-Combobox listet die aktuell vorhandenen Typen sowie die Option **All** (kein Typ-Filter).
- **Validierung beim Einlesen**: Dependencies und Plugins ohne `groupId` werden beim Einsammeln übersprungen und nicht in der Liste angezeigt.
- **Property-Erkennung und Property-Updates**: Erkennt Versionen aus Maven-Properties (z.B. `${spring.version}`), zeigt die Property in eigener Spalte und aktualisiert beim Schreiben die Property statt des einzelnen Tags.
- **Update-Check über Repositories**: Prüft verfügbare Versionen in Maven Central und konfigurierten privaten Repositories (`settings.xml`), unterstützt Authentifizierung und dedupliziert identische Repository-URLs.
- **Credentials aus Platzhaltern**: Löst Credentials aus `settings.xml` auf, z.B. `${env.ARTIFACTORY_USERNAME}` / `${env.ARTIFACTORY_PASSWORD}` sowie `${MY_VAR}` (System-Property, danach Environment-Variable).
- **Versionsauswahl pro Zeile**: Bietet pro Dependency/Plugin ein Dropdown mit verfügbaren Versionen; die neueste Version kann optional automatisch vorausgewählt werden.
- **Versions-Status-Anzeige**: Die Spalte **New Version** zeigt einen Pfeil nach oben („↑"), wenn eine höhere Version verfügbar ist, oder ein grünes Häkchen („✓"), wenn die ausgewählte Version der höchsten bekannten Version entspricht. Wird eine andere Version als die aktuelle gewählt, erscheint der Dropdown-Text zusätzlich fett und farbig (grün bei neuester, orange sonst), um die Änderung klar zu kennzeichnen, und das Status-Symbol nimmt dieselbe Farbe an. Entspricht die Auswahl der aktuellen Version, werden Symbol und Text in der normalen Textfarbe (wie in **Current Version**) dargestellt. Ein Tooltip zeigt die Statusdetails; die Farben passen sich an Light- und Dark-Theme an.
- **Filter für instabile Versionen**: Optionales Ausblenden von Versionen mit konfigurierbaren Qualifiern (standardmäßig `rc,beta`), z.B. Release Candidates oder Beta-Versionen.
- **Synchronisierte Auswahl bei gemeinsamer Property**: Wenn mehrere Dependencies dieselbe Maven-Property verwenden, wird eine geänderte Auswahl auf alle betroffenen Einträge synchronisiert.
- **Sicheres Update mit Bestätigungsdialog**: Vor dem Schreiben zeigt MavenUp eine Zusammenfassung aller geplanten Änderungen (alt/neu, Typ, Koordinaten). Zusätzlich kann direkt im Dialog die Option **Sync Maven Changes after update** gewählt werden (standardmäßig aktiviert), um nach dem Schreiben der `pom.xml` sofort den Maven-Sync der IDE auszulösen. Die Checkbox ist mit der Einstellung **Sync Maven changes after update** synchronisiert; die getroffene Auswahl bleibt für das nächste Mal gespeichert. Das Update erfolgt erst nach Bestätigung.
- **Hintergrundverarbeitung für lange Aktionen**: Projekt-/PSI-Datenerfassung beim Refresh, Update-Check, Navigation und Schreiboperationen laufen im Hintergrund, damit die IDE responsiv bleibt.
- **Sichere Aktionszustände**: **Scan for Vulnerabilities** bleibt während eines laufenden Refreshs oder Update-Checks deaktiviert, damit keine konkurrierenden Prüfungen gestartet werden.
- **Navigation zur Definition in `pom.xml`**: Per Doppelklick (oder optional Einzelklick) springt MavenUp direkt zur passenden Dependency-/Plugin-Definition. Ein Tooltip auf jeder Zeile zeigt den konfigurierten Klick-Modus an.
- **Rechtsklick-Kontextmenü**: Ein Rechtsklick auf eine Dependency-Zeile öffnet ein Kontextmenü mit **Navigate to pom.xml** (springt zur Definition im Editor), **Open in Maven Repository** (öffnet die Versionsseite im konfigurierten Repository-Browser) und ggf. **Show Vulnerability Details** (öffnet den Dialog mit Sicherheitsbefunden, falls Vulnerabilities gefunden wurden).
- **Open-in-Repository-Aktion**: In der oberen Aktionsleiste öffnet die selektionsabhängige Aktion die aktuelle Version der selektierten Dependency im konfigurierten Repository-Browser. Der Tooltip zeigt dynamisch den konfigurierten Browser-Namen (z.B. **Open on MVN Repository** oder **Open on Sonatype Central**) – entspricht dem Kontextmenüeintrag **Open in Maven Repository**.
- **Konfigurierbarer Repository-Browser**: Unter `Settings > Tools > MavenUp` kann zwischen **MVN Repository** (Standard, `mvnrepository.com`) und **Sonatype Central** (`central.sonatype.com`) gewählt werden. Die Auswahl gilt für das Kontextmenü im Hauptfenster und das Rechtsklick-Kontextmenü im Vulnerability-Details-Dialog (alle Spalten außer **References**).
- **Integrierte Einstellungen**: Über `Settings > Tools > MavenUp` konfigurierbar; die Optionen sind in die drei Gruppen **Appearance**, **Versions & Updates** und **Vulnerability Check** gegliedert (u.a. Single-Click-Navigation, automatische Vorauswahl der neuesten Version, Repository-Browser).
- **Zweigeteilte Aktionsleiste**: Die Aktionen liegen in einer oberen Aktionsleiste (IntelliJ-`ActionToolbar`). Links stehen die Kernaktionen **Refresh**, **Find New Versions**, **Scan for Vulnerabilities** und **Update**; durch einen Trenner abgesetzt folgen die selektionsabhängigen Aktionen **Open on [Browser]** und **Vulnerability Details**, die erst bei passend selektierter Dependency-Zeile aktiv werden; rechts folgen die **Settings**. Wahlweise werden die Aktionen als reine Icon-Buttons (Standard, mit Namen als Tooltip) oder als Buttons mit Textbeschriftung dargestellt (per Einstellung umschaltbar).
- **Multi-Source-Vulnerability-Check**: **Scan for Vulnerabilities** prüft direkte Komponenten und standardmäßig auch aufgelöste transitive Dependencies über [OSV.dev](https://osv.dev). Optional ergänzt [Sonatype OSS Index](https://ossindex.sonatype.org/) Maven-spezifische Befunde.
- **Qualifizierte OSS-Index-Token-Fehler**: Lehnt Sonatype die Anfrage wegen eines ungültigen oder abgelaufenen API-Tokens ab (HTTP 401/403), zeigt der Scan eine eindeutige Fehlermeldung mit Hinweis auf das fehlerhafte Token statt einer generischen HTTP-Meldung. Der Fehlerdialog bietet einen **Open Settings**-Button, der direkt die Plugin-Einstellungen öffnet.
- **Detaillierte Security-Befunde**: Direkt hinter **Current Version** zeigt **Vulnerabilities (Current)** die Befunde der direkten Dependency und ihrer aufgelösten transitiven Dependencies mit Gesamtzahl, transitiver Anzahl und höchstem Schweregrad. Die **Vulnerability Details**-Aktion ist erst aktiv, wenn eine Dependency-Zeile mit Befunden selektiert ist, und zeigt dann ausschließlich die Befunde der selektierten Dependency (direkte und transitive). Im Detaildialog ordnet die Ansicht die als transitiv markierten Komponenten der selektierten Dependency zu und zeigt IDs, Aliase, CVSS, Beschreibung, Quellen und Referenzen. Die Aktionen **Open in ...** und **References...** in der oberen Aktionsleiste des Dialogs sind anfangs deaktiviert und werden erst bei selektierter Vulnerability-Zeile aktiv; zusätzlich bietet die **Component**-Spalte per Rechtsklick ein Kontextmenü zum Öffnen der Komponente im konfigurierten Repository-Browser oder der Referenzen des selektierten Advisories. Zurückgezogene Advisories werden ignoriert und Mehrfachmeldungen anhand ihrer IDs/Aliase zusammengeführt.

### Intern
- **Gezielte Credential-Zuordnung**: Ordnet Credentials primär über Repository-ID zu, mit Fallback über Repository-URL und Hostname.
- **Versionsauflösung mit Fallbacks**: Nutzt aufgelöste Maven-Versionen aus dem Projektmodell (Dependency Tree/Plugins), damit die aktuelle Version auch bei indirekter Verwaltung korrekt angezeigt und verglichen wird.
- **Central-first mit Short-Circuit**: Fragt Maven Central priorisiert zuerst ab; ist die Abfrage dort erfolgreich, werden für dieselbe Dependency keine weiteren privaten Repositories mehr abgefragt.
- **Kompaktes Diagnose-Logging**: Protokolliert Parsing-Fehler für `settings.xml`, nicht auflösbare Credential-Variablen sowie fehlgeschlagene Repository-Abfragen (inkl. HTTP-Status). Umfangreiche Versions- und Komponentenlisten erscheinen ausschließlich gekürzt auf DEBUG-Ebene, damit `idea.log` klein und responsiv bleibt.
- **Expliziter Refresh-Flow**: Der interne Refresh unterscheidet klar zwischen „New Version zurücksetzen“ (manueller Refresh) und „New Version behalten“ (Refresh nach Update-Check).
- **Installation und Update ohne IDE-Neustart**: Das Plugin nutzt ausschließlich dynamische Extension-Points und registriert den `MavenImportListener` deklarativ über `<projectListeners>`, sodass es dynamisch geladen, entladen und aktualisiert werden kann, ohne die IDE neu zu starten.

## Benutzung

### German
Das Plugin öffnet ein Tool-Window namens **MavenUp** (meist am rechten oder unteren Rand der IDE).

1. **Refresh**: Lädt die Projektdaten neu, befüllt die Tabelle und setzt die Spalte **New Version** zurück. Währenddessen ist **Scan for Vulnerabilities** deaktiviert. (Beim **Find New Versions** bleiben die neu geladenen Werte erhalten.)
2. **Find New Versions**: Sucht online nach verfügbaren Versionen für alle gelisteten Einträge; **Scan for Vulnerabilities** bleibt bis zum Abschluss deaktiviert.
3. **New Version**: In dieser Spalte kann nach dem Update-Check eine neuere Version gewählt werden. Ein grünes Häkchen („✓") signalisiert, dass die gewählte Version die höchste bekannte ist; ein Pfeil nach oben („↑") zeigt an, dass es eine neuere gibt. Wird eine andere Version als die aktuelle gewählt, erscheinen Text und Symbol fett und farbig. Details liefert der Tooltip.
4. **Update**: Wendet die gewählten Versionsänderungen auf die entsprechenden `pom.xml`-Dateien an. Im Bestätigungsdialog kann zusätzlich **Sync Maven Changes after update** aktiviert werden (standardmäßig an), um die Änderungen sofort im Projektmodell der IDE wirksam zu machen. Die Auswahl wird gespeichert und ist mit der Einstellung synchronisiert.
5. **Scan for Vulnerabilities**: Prüft direkte Komponenten und, sofern aktiviert, transitive Dependencies über OSV.dev sowie optional OSS Index. Die direkt hinter **Current Version** angeordnete Spalte **Vulnerabilities (Current)** zeigt pro direkter Dependency die Gesamtzahl, die davon transitive Anzahl und den höchsten Schweregrad.
6. **Vulnerability Details**: Öffnet die Befunde der selektierten Dependency-Zeile inklusive Quellen, IDs/Aliasen, CVSS, Beschreibung und Referenzen. Die Aktion ist erst aktiv, wenn eine Dependency-Zeile mit Befunden selektiert ist. Ein Klick auf eine befüllte Vulnerability-Zelle öffnet direkt die Befunde dieser Dependency (ebenfalls gefiltert auf die selektierte Zeile). Im Dialog sind die Aktionen **Open in ...** und **References...** in der oberen Aktionsleiste zunächst deaktiviert und werden erst aktiv, wenn eine Vulnerability-Zeile selektiert ist; sie beziehen sich dann auf genau diese Zeile. Ein Rechtsklick auf die selektierte Zeile öffnet in allen Spalten außer **References** ein Kontextmenü mit **Open in Maven Repository** und **References...**.
7. **Open on [browser]**: Öffnet die aktuelle Version der selektierten Dependency im konfigurierten Repository-Browser. Die Aktion steht in der oberen Aktionsleiste hinter dem Trenner bei den selektionsabhängigen Aktionen, ist erst aktiv wenn eine Dependency-Zeile selektiert ist und zeigt den konfigurierten Browser-Namen als Tooltip (z.B. **Open on MVN Repository** oder **Open on Sonatype Central**).
8. **Rechtsklick-Kontextmenü**: Ein Rechtsklick auf eine Zeile bietet **Navigate to pom.xml**, **Open in Maven Repository** (öffnet die aktuelle Version im konfigurierten Repository-Browser) und ggf. **Show Vulnerability Details** (öffnet den Dialog mit den gefundenen Sicherheitsbefunden, falls vorhanden).
9. **Filter**: Über der Tabelle grenzt das Textfeld die Anzeige case-insensitiv nach **GroupId**, **ArtifactId** oder **Property** ein; drei Comboboxen filtern zusätzlich nach **Type** (Dependency-Typ), **Changes** (ob ausstehende Versionsänderungen vorliegen: All, Yes, No) und **Vulnerabilities** (ob Sicherheitsbefunde vorliegen: All, Yes, No). Alle Filter wirken zusammen, sodass nur noch passende Zeilen sichtbar bleiben. Mit **All** wird der jeweilige Filter aufgehoben.

### English
The plugin opens a tool window named **MavenUp** (usually located at the right or bottom edge of the IDE).

1. **Refresh**: Reloads the project data and populates the table. **Scan for Vulnerabilities** is disabled while the refresh is running.
2. **Find New Versions**: Searches online for available versions for all listed entries; **Scan for Vulnerabilities** remains disabled until completion.
3. **New Version**: In this column, a newer version can be selected after the update check. A green checkmark ("✓") indicates the selected version is the highest known version; an upwards arrow ("↑") signals that a newer version exists. When a version different from the current one is selected, the text and symbol appear in bold and color. Hover over the cell for status details.
4. **Update**: Applies the selected version changes to the corresponding `pom.xml` files. The confirmation dialog includes a **Sync Maven Changes after update** checkbox (enabled by default) to immediately trigger the IDE's Maven sync. The choice is persisted and kept in sync with the setting.
5. **Scan for Vulnerabilities**: Checks direct components and, when enabled, resolved transitive dependencies through OSV.dev and optionally Sonatype OSS Index. The **Vulnerabilities (Current)** column appears directly after **Current Version** and shows each direct dependency's total finding count, transitive finding count, and highest severity.
6. **Vulnerability Details**: Opens detailed findings for the selected dependency row with sources, IDs/aliases, CVSS, summaries, and references. The action is only active when a dependency row with findings is selected. Clicking a populated vulnerability cell also opens the findings for that dependency row directly. In the dialog, the **Open in ...** and **References...** actions in the top action toolbar stay disabled until a vulnerability row is selected and then apply to that selected row. Right-clicking the selected row in any column except **References** opens a context menu with **Open in Maven Repository** and **References...**.
7. **Open on [browser]**: Opens the current version of the selected dependency in the configured repository browser. The action sits in the top action toolbar after the separator among the selection-dependent actions, is only active when a dependency row is selected, and its tooltip dynamically shows the configured browser name (e.g. **Open on MVN Repository** or **Open on Sonatype Central**).
8. **Right-click context menu**: Right-clicking any row offers **Navigate to pom.xml**, **Open in Maven Repository** (opens the current version in the configured repository browser), and optionally **Show Vulnerability Details** (opens the dialog with found security findings, if any are present).
9. **Filter**: The text field above the table narrows the view case-insensitively by **GroupId**, **ArtifactId** or **Property**; three combo boxes additionally filter by **Type**, **Changes** (whether pending version changes exist: All, Yes, No), and **Vulnerabilities** (whether security findings are present: All, Yes, No). All filters combine so that only matching rows remain visible. Selecting **All** removes the respective filter.

## Einstellungen

Unter `Settings > Tools > MavenUp` können folgende Optionen konfiguriert werden. Die Einstellungen werden global auf Anwendungsebene gespeichert und gelten damit für alle Projekte. Sie sind zur besseren Übersicht in drei thematische Gruppen mit Überschriften gegliedert: **Appearance**, **Versions & Updates** und **Vulnerability Check**.

**Appearance**

- **Maven Repository Browser**: Wählt den Browser für Artifact-Versionsseiten – **MVN Repository** (Standard, `mvnrepository.com`) oder **Sonatype Central** (`central.sonatype.com`). Die Auswahl gilt für das Rechtsklick-Kontextmenü im Hauptfenster und das Rechtsklick-Kontextmenü im Vulnerability-Details-Dialog (alle Spalten außer **References**).
- **Show text labels on toolbar buttons instead of icons only**: Stellt die Aktionen in der oberen Aktionsleiste des Tool-Windows und des Vulnerability-Details-Dialogs wahlweise als Buttons mit Textbeschriftung statt als reine Icon-Buttons dar (Standard: an). Die Änderung wird sofort auf das offene Tool-Window angewendet.
- **Jump to pom.xml on single click**: Ermöglicht die Navigation zur `pom.xml` mit einem einfachen statt eines Doppelklicks.

**Versions & Updates**

- **Automatically select newest version**: Wählt nach einem Update-Check automatisch die jeweils neueste verfügbare Version in der Dropdown-Liste aus. Wird diese Einstellung geändert und mit **Apply** oder **OK** bestätigt, aktualisiert sich die **New Version**-Auswahl im offenen Tool-Window sofort, ohne dass ein erneuter Update-Check nötig ist. Das Ändern anderer Einstellungen setzt eine bereits getroffene Versionsauswahl nicht zurück.
- **Hide unstable versions**: Blendet instabile Versionen (z.B. RC/Beta) aus den auswählbaren Update-Versionen aus.
- **Hidden version qualifiers (comma-separated)**: Liste der auszublendenden Typen, z.B. `rc,beta,milestone` (als eingerückter Unterpunkt im IntelliJ-Settings-Style; Label und Feld nur aktiv, wenn der Filter eingeschaltet ist; das Feld passt sich an die Dialogbreite an, damit auch längere Listen gut lesbar bleiben).
- **Sync Maven changes after update**: Legt fest, ob nach dem Schreiben der `pom.xml` automatisch der Maven-Sync der IDE ausgelöst wird (Standard: an). Diese Einstellung ist mit der gleichnamigen Checkbox im Bestätigungsdialog **Confirm Changes** synchronisiert; die dort zuletzt getroffene Auswahl wird gespeichert.

**Vulnerability Check**

- **Include resolved transitive dependencies**: Nimmt standardmäßig den aufgelösten Maven-Dependency-Tree in den Vulnerability-Check auf.
- **Use Sonatype OSS Index as an additional source**: Aktiviert die optionale zweite Datenquelle. Sonatype authentifiziert Anfragen ausschließlich über das API-Token; daher wird nur das Token benötigt und bei aktivierter Option als Pflichtfeld angezeigt. Das Token wird ausschließlich im IntelliJ Password Safe gespeichert, außerhalb des Event Dispatch Thread geladen und nicht in `mavenup_settings.xml` abgelegt. Fehlt das Token bei einer bereits gespeicherten Konfiguration, wird die OSS-Index-Abfrage übersprungen; OSV.dev wird weiterhin abgefragt. Ist das Token ungültig oder abgelaufen, wird eine qualifizierte Fehlermeldung angezeigt. Ein Link öffnet die Sonatype-Kontoeinstellungen zum Erzeugen oder Kopieren eines Tokens.

## Datenschutz & Datenübertragung

MavenUp legt großen Wert auf Transparenz und Datensparsamkeit beim Zugriff auf externe Netzwerkdienste.

### Übertragene Daten
- **Ausschließlich Maven-Koordinaten:** Bei Versionsprüfungen und Vulnerability-Scans werden ausschließlich die Standard-Maven-Koordinaten (`groupId`, `artifactId`, `version`) der im Projekt deklarierten oder aufgelösten Komponenten über HTTPS übertragen.
- **Keine sensiblen Projektdaten:** Es werden **keine Quelltexte, Dateiinhalte, Dateipfade, Passwörter oder Benutzerdaten** an externe Dienste übermittelt.
- **Sichere Credential-Verwaltung:** Zugangsdaten für private Repositories (aus `settings.xml`) verbleiben lokal bzw. werden ausschließlich gegenüber dem jeweils konfigurierten Repository-Server verwendet. Das optionale API-Token für Sonatype OSS Index wird sicher im IntelliJ Password Safe gespeichert und nicht in Konfigurationsdateien abgelegt.

### Externe Dienste und Endpunkte
1. **Maven Central & Repositories (`repo1.maven.org` / konfigurierte Server):**
   - **Zweck:** Ermittlung neuerer Versionen über `maven-metadata.xml`.
   - **Übertragung:** HTTP-GET-Anfragen mit Pfaden basierend auf `groupId` und `artifactId`.
2. **OSV.dev (`api.osv.dev`):**
   - **Zweck:** Standardmäßiger Multi-Source-Vulnerability-Check (Google / OpenSSF).
   - **Übertragung:** Batch- und Detailabfragen mit `groupId`, `artifactId` und `version` (PURL / Ecosystem `Maven`).
   - **Authentifizierung:** Keine erforderlich.
3. **Sonatype OSS Index (`ossindex.sonatype.org`):**
   - **Zweck:** Optionale Anreicherung mit Maven-spezifischen Sicherheitsbefunden (Opt-In).
   - **Übertragung:** Komponentenabfragen mit Maven-PURLs (`pkg:maven/groupId/artifactId@version`).
   - **Authentifizierung:** Persönliches API-Token des Nutzers.
4. **Repository-Browser (Webbrowser):**
   - **Zweck:** Optionale benutzerinitiierte Navigation zu `mvnrepository.com` oder `central.sonatype.com` bei Klick auf Weblinks im Standard-Webbrowser des Nutzers.

### Hinweis für Unternehmensumgebungen
In vertraulichen Unternehmensumgebungen ist zu beachten, dass bei einem Versions- oder Vulnerability-Check auch die Koordinaten interner oder privater Abhängigkeiten (z. B. `com.meinefirma.intern:mein-modul:1.0.0`) in Anfragen an die konfigurierten externen Dienste auftauchen können, sofern diese im Projekt vorhanden sind.

## Gradle Proxy-Konfiguration

Wenn der Zugriff auf Maven Central im Unternehmensnetzwerk nur über Proxy funktioniert, sollten Proxy-Settings **nicht** in der projektweiten `gradle.properties` gepflegt werden.  
Verwende stattdessen die benutzerlokale Datei:

`C:\Users\<DEIN_USER>\.gradle\gradle.properties`

Beispiel:
```properties
systemProp.java.net.useSystemProxies=true
org.gradle.jvmargs=-Djava.net.useSystemProxies=true -Djava.net.preferIPv4Stack=true

# Falls ein fester Proxy erforderlich ist:
# systemProp.http.proxyHost=<proxy-host>
# systemProp.http.proxyPort=<proxy-port>
# systemProp.https.proxyHost=<proxy-host>
# systemProp.https.proxyPort=<proxy-port>
# systemProp.http.nonProxyHosts=localhost|127.0.0.1|*.local
```

Danach einmal `gradlew --stop` ausführen, damit der Gradle-Daemon die neuen Einstellungen übernimmt.


## KI-Anweisungen

Für die Weiterentwicklung dieses Projekts durch KI-Agenten (wie GitHub Copilot oder Junie) existieren verbindliche Anweisungen und Kontextinformationen:

- **[.github/copilot-instructions.md](.github/copilot-instructions.md)**: Verbindliche Arbeitsanweisungen für Dokumentation, Testing, KDoc und Prozesse.
- **[.github/copilot-project-context.md](.github/copilot-project-context.md)**: Detaillierter Projektkontext, Architekturübersicht und Komponentenbeschreibung.

Diese Dateien sind bei jeder Änderung zu berücksichtigen und aktuell zu halten.

## Architektur

Das Plugin ist klar in drei Schichten gegliedert:

### Datenmodell (`de.schwarzland.mavenup.model`)
- Enthält schlanke DTOs für den UI-/Service-Datenaustausch, z. B. `DependencyUpdate` und `VulnerabilityAdvisory`.

### Service (`de.schwarzland.mavenup.service`)
- `MavenUpStartupActivity`: macht das Tool-Window beim Projektstart verfügbar, sobald bereits Maven-Projekte vorhanden sind.
- `MavenUpMavenImportListener`: deklarativ (`<projectListeners>`) registrierter `MavenImportListener`, der das Tool-Window nach jedem abgeschlossenen Maven-Import verfügbar macht; die deklarative Registrierung wird beim Entladen des Plugins automatisch abgemeldet und ermöglicht Updates ohne IDE-Neustart.
- `MavenUpToolWindowActivator`: gemeinsames, idempotentes Hilfsobjekt, das Startup-Aktivität und Import-Listener zum Verfügbarmachen des Tool-Windows nutzen.
- `MavenUpSettings`: anwendungsweiter Persistenz-Service (`PersistentStateComponent`, `Service.Level.APP`) in `mavenup_settings.xml`; die Einstellungen gelten global für alle Projekte. Das OSS-Index-Token wird getrennt über `OssIndexCredentialService` im Password Safe gespeichert. Für die HTTP Basic Authentication wird ein fester Platzhalter-Benutzername verwendet, da Sonatype nur das Token auswertet.
- `DependencyApiService`, `VulnerabilityApiService` und `OssIndexApiService`: kapseln externe API-Abfragen für Versionen und Vulnerabilities außerhalb der UI.
- `VulnerabilityMerger`: dedupliziert Befunde aus mehreren Quellen anhand von Advisory-IDs und Aliasen.
- Unterstützte CVSS-Vektoren aus OSV werden mit `us.springett:cvss-calculator` in vergleichbare Basisscores umgerechnet. Bei noch nicht unterstützten CVSS-Versionen bleibt der Befund erhalten und nutzt den Schweregrad der Quelle.

### UI (`de.schwarzland.mavenup.ui`)
- `MavenUpWindowFactory`: Tool-Window-Factory und UI-Interaktion für Tabelle, Update- und Vulnerability-Workflows; die Aktionen liegen in einer oberen `ActionToolbar`, Refresh-Daten werden per nicht blockierender Read-Action außerhalb des EDT erfasst.
- `VulnerabilityDetailDialog`: zeigt direkte und transitive Security-Befunde mit Quellen und Referenzen; die Aktionen **Open in ...** und **References...** in der oberen Aktionsleiste des Dialogs werden über die aktuelle Zeilenselektion gesteuert, und ein Rechtsklick auf die Zeile öffnet in allen Spalten außer **References** ein Kontextmenü mit **Open in Maven Repository** sowie **References...**.
- `MavenUpConfigurable`: Einstellungs-UI unter `Settings > Tools > MavenUp`, gebunden an `MavenUpSettings`; Password-Safe-Zugriffe werden im Hintergrund geladen und für die Änderungserkennung zwischengespeichert.
- `MyMessageBundle` (weiterhin im Basispaket): zentralisierte i18n-Texte für die UI.


## Tests

Die Unittests liegen unter `src/test/kotlin` und spiegeln die Paketstruktur des Hauptcodes wider
(`model`, `service`, `ui`). Ausführung über:
```
./gradlew test
```

Hinweise:
- Tests, die reine Logik ohne IntelliJ-Plattform benötigen (z. B. `VulnerabilityApiServiceTest`), nutzen
  reines JUnit. Tests, die eine Projekt-/PSI-Umgebung benötigen (z. B. `MavenUpWindowFactoryTest`,
  `DependencyApiServiceTest`, `MavenUpConfigurableTest`), erben von `BasePlatformTestCase`.
- Im Test-Sandbox wird das gebündelte Vue.js-Plugin (`org.jetbrains.plugins.vue`) über
  `tasks.named("prepareTestSandbox") { disabledPlugins.add(...) }` in `build.gradle.kts` deaktiviert.
  MavenUp hat keine Abhängigkeit zu Vue; dessen Initialisierung führte in manchen Test-Sandbox-Setups zu
  sporadischen `TestLoggerAssertionError`-Fehlschlägen unabhängig vom eigentlichen Testcode.


---


## Third-party licenses

MavenUp bundles the following third-party library:

| Library | Version | License | Usage |
|---|---|---|---|
| [cvss-calculator](https://github.com/stevespringett/cvss-calculator) (us.springett) | 1.4.1 | [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0) | CVSS score calculation for vulnerability findings |

All other dependencies (JUnit, IntelliJ Platform, Kotlin) are either test-only or provided by the IDE at runtime and are not bundled in the plugin JAR.

The full text of the Apache License 2.0 is available at: https://www.apache.org/licenses/LICENSE-2.0


---


## Developer Stuff

### Git Buffer Size erhöhen

`git push` scheiterte mit den `/assets/*.png`weil die Git-Buffer-Size zu klein war. Lösung:
```
git config --global http.postBuffer 524288000
```

### ClassNotFoundException: MavenUpWindowFactory at plugin startup

**Symptom:** The IDE log shows `Cannot process toolwindow MavenUp` / `ClassNotFoundException: de.schwarzland.mavenup.ui.MavenUpWindowFactory`.

**Cause:** A corrupt Gradle build-cache entry for `compileKotlin`. Gradle reports the task as `FROM-CACHE` but restores an empty output, so the plugin JAR contains no compiled classes.

**Fix:**
```bash
.\gradlew.bat clean compileKotlin --rerun-tasks
.\gradlew.bat prepareSandbox --rerun-tasks
```

If the problem persists, clear the Gradle build cache completely:
```bash
.\gradlew.bat --stop
Remove-Item -Recurse "$env:USERPROFILE\.gradle\caches\build-cache-*"
.\gradlew.bat prepareSandbox
```
