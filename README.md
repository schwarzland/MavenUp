# MavenUp

MavenUp ist ein IntelliJ-Plugin, das speziell für Maven-Projekte entwickelt wurde, um die Verwaltung von Abhängigkeiten (Dependencies) und Plugins zu vereinfachen. Es bietet eine übersichtliche Tabellenansicht aller deklarierten Komponenten und ermöglicht die einfache Aktualisierung auf neuere Versionen.

Eine vollständige, englische Feature-Liste steht in `FEATURES.md`.

## Benutzung

Das Plugin öffnet ein Tool-Window namens **MavenUp** (meist am linken oder unteren Rand der IDE). In der Werkzeugleiste ist es an einem eigenen MavenUp-Icon (mit Light- und Dark-Variante) erkennbar.

1. **Refresh**: Lädt die Projektdaten neu, befüllt die Tabelle und setzt die Spalte **New Version** zurück. Währenddessen ist **Scan for Vulnerabilities** deaktiviert. (Beim **Search for New Versions** bleiben die neu geladenen Werte erhalten.)
2. **Search for New Versions**: Sucht online nach verfügbaren Versionen für alle gelisteten Einträge; **Scan for Vulnerabilities** bleibt bis zum Abschluss deaktiviert.
3. **New Version**: In dieser Spalte kann nach dem Update-Check eine neuere Version gewählt werden. Ein grünes Häkchen („✓") signalisiert, dass die gewählte Version die höchste bekannte ist; ein Pfeil nach oben („↑") zeigt an, dass es eine neuere gibt. Wird eine andere Version als die aktuelle gewählt, erscheinen Text und Symbol fett und farbig. In der Auswahlliste ist die aktuell verwendete Version mit „(current)" fett gekennzeichnet, damit sie – insbesondere bei aktivierter Option „alle Versionen anzeigen" – hervorsticht. Details liefert der Tooltip. Über die Einstellungen kann die automatische Vorauswahl als 3‑Zustands-Strategie konfiguriert werden (deaktiviert, höchste Version oder höchste Minor-Version innerhalb derselben Major-Linie).
4. **Höchste Major-/Minor-Version wählen und zurücksetzen**: Das Aktionsleisten-Aufklappmenü **Select Highest Version** bündelt zwei Aktionen, die mit einem Klick Zielversionen setzen: **Select Highest Major Version** wählt die höchste verfügbare Version (auch über Major-Linien hinweg) und **Select Highest Minor Version** die höchste Version innerhalb der aktuellen Major-Linie jeder Dependency – beide wirken nur auf die aktuell sichtbaren (nicht ausgefilterten) Zeilen und zeigen bei aktivem Filter einen Tooltip-Hinweis. Die eigenständige Aktionsleisten-Aktion **Reset All to Current Versions** verwirft alle Auswahlen und stellt die aktuellen Versionen wieder her – für alle Dependencies, unabhängig vom Filter; standardmäßig wird zuvor ein Bestätigungsdialog (mit Option „Don't ask again") angezeigt, der über die Einstellungen abschaltbar ist.
5. **Update**: Wendet die gewählten Versionsänderungen auf die entsprechenden `pom.xml`-Dateien an. Im Bestätigungsdialog kann zusätzlich **Sync Maven Changes after update** aktiviert werden (standardmäßig an), um die Änderungen sofort im Projektmodell der IDE wirksam zu machen. Die Auswahl wird gespeichert und ist mit der Einstellung synchronisiert.
6. **Scan for Vulnerabilities**: Prüft direkte Komponenten und, sofern aktiviert, transitive Dependencies über OSV.dev sowie optional OSS Index. Die direkt hinter **Current Version** angeordnete Spalte **Vulnerabilities (Current)** zeigt pro direkter Dependency die Gesamtzahl, die davon transitive Anzahl und den höchsten Schweregrad.
7. **Vulnerability Details**: Öffnet die Befunde der selektierten Dependency-Zeile inklusive Quellen, IDs/Aliasen, CVSS, Beschreibung und Referenzen. Die Aktion ist erst aktiv, wenn eine Dependency-Zeile mit Befunden selektiert ist. Ein Klick auf eine befüllte Vulnerability-Zelle öffnet direkt die Befunde dieser Dependency (ebenfalls gefiltert auf die selektierte Zeile). Im Dialog sind die Aktionen **Open in ...** und **References...** in der oberen Aktionsleiste zunächst deaktiviert und werden erst aktiv, wenn eine Vulnerability-Zeile selektiert ist; sie beziehen sich dann auf genau diese Zeile. Ein Rechtsklick auf die selektierte Zeile öffnet in allen Spalten außer **References** ein Kontextmenü mit **Open in Maven Repository** und **References...**.
8. **Open on [browser]**: Öffnet die aktuelle Version der selektierten Dependency im konfigurierten Repository-Browser. Die Aktion steht in der oberen Aktionsleiste hinter dem Trenner bei den selektionsabhängigen Aktionen, ist erst aktiv wenn eine Dependency-Zeile selektiert ist und zeigt den konfigurierten Browser-Namen als Tooltip (z.B. **Open on MVN Repository** oder **Open on Sonatype Central**).
9. **Rechtsklick-Kontextmenü**: Ein Rechtsklick auf eine Zeile bietet **Navigate to pom.xml**, **Open in Maven Repository** (öffnet die aktuelle Version im konfigurierten Repository-Browser) und ggf. **Show Vulnerability Details** (öffnet den Dialog mit den gefundenen Sicherheitsbefunden, falls vorhanden).
10. **Filter**: Über der Tabelle grenzt das Textfeld die Anzeige case-insensitiv nach **GroupId**, **ArtifactId** oder **Property** ein; vier Comboboxen filtern zusätzlich nach **Type** (Dependency-Typ), **Updates** (All / Update available / Up to date), **Pending** (All / Will update / Unchanged) und **Vulnerabilities** (All / Vulnerable / Not vulnerable). Die Optionstexte sind selbsterklärend formuliert, sodass der gewählte Wert für sich verständlich ist. Der **Updates**-Filter ist erst nach einer erfolgreichen Versionssuche (**Search for New Versions**) aktiv und zeigt dann nur Zeilen mit verfügbarem Update; der **Pending**-Filter ist erst aktiv, sobald für mindestens eine Zeile eine von der aktuellen abweichende Version ausgewählt wurde; der **Vulnerabilities**-Filter ist erst nach einer erfolgreichen Sicherheitsprüfung (**Scan for Vulnerabilities**) aktiv. Alle Filter wirken zusammen, sodass nur noch passende Zeilen sichtbar bleiben. Mit **All** wird der jeweilige Filter aufgehoben. Jedes Filter-Control zeigt einen Tooltip mit einer Kurzerklärung. Am Ende der Filterzeile setzt ein Reset-Button alle Filter auf einmal zurück; er ist nur aktiv, solange mindestens ein Filter gesetzt ist.
11. **Sortierung**: Ein Klick auf eine Spaltenüberschrift schaltet die Sortierung zyklisch weiter: aufsteigend, absteigend und zurück zur ursprünglichen Reihenfolge aus der `pom.xml`. Sortierbare Spalten tragen in der Kopfzeile ein Indikator-Icon – einen gedämpften Doppelpfeil im unsortierten Zustand und einen Auf- bzw. Ab-Pfeil bei aktiver Sortierrichtung –, sodass die Sortierbarkeit auf einen Blick erkennbar ist. Die Spalten **Current Version**, **Vulnerabilities (Current)** und **New Version** sind von der Sortierung ausgenommen.
12. **Repository-Strategie anpassen**: In den Einstellungen unter **Stop after a successful Maven Central lookup** kann festgelegt werden, ob nach einer erfolgreichen Central-Abfrage weitere private Repositories übersprungen werden (schneller) oder zusätzlich abgefragt werden (vollständiger für private-only Versionen).

## Branching-Strategie und GitHub Actions

Die Entwicklung erfolgt auf `feature/*`-Branches. Änderungen werden per Pull Request nach `main` zusammengeführt. Für die Stabilisierung eines Releases wird ein `release/*`-Branch verwendet, der ebenfalls per Pull Request nach `main` gemergt wird. Das einheitliche Schema lautet: Release-Branch `release/x.y.z`, Git-Tag `x.y.z` – jeweils ohne führendes `V`.

### Automatische Workflows

- **Build and Test** (`.github/workflows/ci.yml`): Läuft bei jedem Push auf `main` sowie bei jedem neu erstellten, aktualisierten oder erneut geöffneten Pull Request gegen `main`. Feature- und Release-Branches werden dadurch ausschließlich über ihren Pull Request geprüft. So wird verhindert, dass ein Push auf einen Branch mit offenem Pull Request zwei identische Builds auslöst. Ein Direkt-Push nach `main` (ohne Pull Request) wird weiterhin durch den Push-Trigger abgedeckt. Der Workflow kompiliert das Plugin, führt Tests und statische Analyse (detekt) aus, erzeugt den Coverage-Report (Kover), prüft die Plugin-Struktur und führt den IntelliJ Plugin Verifier gegen die konfigurierte Kompatibilität aus. Die Berichte werden bei jedem Lauf – ob erfolgreich oder fehlgeschlagen – als Artefakt gespeichert. Jeder Job ist auf 30 Minuten begrenzt. Der Trigger greift nur bei Änderungen an Quellcode, Plugin-Ressourcen oder Build-Konfiguration.
- **Manual Build and Test** (`.github/workflows/manual-build.yml`): Wird manuell über den **Actions**-Tab in GitHub gestartet (`Run workflow`) und kann auf einem beliebig gewählten Branch ausgeführt werden – etwa auf einem Feature-Branch ohne offenen Pull Request. Führt denselben Build wie **Build and Test** einschließlich Plugin Verifier aus, jedoch ohne Pfad-Filter, also bei jedem manuellen Anstoß. Der Button erscheint erst, sobald der Workflow auf `main` vorhanden ist; auch dieser Job ist auf 30 Minuten begrenzt.
- **Create Draft Release** (`.github/workflows/create-draft-release.yml`): Läuft, wenn ein Tag zu GitHub gepusht wird, zum Beispiel `2.3.0`. Der aktuelle Stand des Tags wird gebaut, mit `verifyPlugin` und dem IntelliJ Plugin Verifier geprüft und anschließend als GitHub-Draft-Release mit ZIP-Datei und Release Notes angelegt. Erst der Push des Tags zu GitHub startet den Workflow. Der Job ist auf 30 Minuten begrenzt.
- **Publish Release to Marketplace** (`.github/workflows/publish-release.yml`): Läuft, sobald ein Draft-Release im GitHub-UI manuell veröffentlicht wird (`release: published`). Vor dem Upload laufen `verifyPlugin` und der IntelliJ Plugin Verifier; bei einem Verifier-Fehler wird der Report als `plugin-verifier-report`-Artifact (7 Tage) gespeichert. Danach wird das Plugin mit `publishPlugin` in den JetBrains Marketplace hochgeladen. Dafür muss das Repository-Secret `JB_MARKETPLACE_TOKEN` gesetzt sein. Der Job ist auf 30 Minuten begrenzt und verwendet pro Release-Ref eine Concurrency-Gruppe, die laufende Publish-Läufe nicht abbricht.

### Dependabot

`.github/dependabot.yml` überwacht monatlich sowohl GitHub-Actions als auch Gradle-Abhängigkeiten. Dadurch werden unter anderem Aktualisierungen der IntelliJ Platform und der verwendeten Bibliotheken als Pull Requests vorgeschlagen.

### `publishPlugin` manuell ausführen

Für eine manuelle Veröffentlichung wird ein JetBrains-Marketplace-Token benötigt. Das Token im JetBrains-Marketplace-Konto erzeugen und niemals in `build.gradle.kts`, `gradle.properties` oder Git committen. Das Gradle-Plugin erwartet das Token in der Umgebungsvariable `PUBLISH_TOKEN`.

Im IntelliJ-Terminal kann das Plugin so als verborgenes Marketplace-Release veröffentlicht werden:

```bash
PUBLISH_TOKEN="<JETBRAINS_MARKETPLACE_TOKEN>" ./gradlew publishPlugin -PmarketplaceHidden=true --no-daemon
```

Unter Windows PowerShell:

```powershell
$env:PUBLISH_TOKEN = "<JETBRAINS_MARKETPLACE_TOKEN>"
.\gradlew.bat publishPlugin -PmarketplaceHidden=true --no-daemon
```

Alternativ in IntelliJ eine Gradle-Run-Konfiguration anlegen: **Run → Edit Configurations… → + → Gradle**, das Projekt und die Task `publishPlugin` auswählen und unter **Environment variables** `PUBLISH_TOKEN=<JETBRAINS_MARKETPLACE_TOKEN>` eintragen. Für ein verborgenes Release zusätzlich unter **Arguments/Gradle options** die Option `-PmarketplaceHidden=true` eintragen. Die Run-Konfiguration darf nicht in die Versionsverwaltung übernommen werden, wenn sie das Token im Klartext enthält.

Ohne `-PmarketplaceHidden=true` wird das Plugin regulär veröffentlicht. Der Aufruf baut das Plugin automatisch und lädt es anschließend in den JetBrains Marketplace hoch.

### Release manuell durchführen

Die folgenden Befehle werden beispielsweise Terminal ausgeführt. Der Tag sollte auf dem geprüften Release-Branch erstellt werden:

```bash
git switch release/2.3.0
git pull --ff-only origin release/2.3.0
git tag 2.3.0
git push origin 2.3.0
```
Alternativ kann in IntelliJ über **Git → New Tag** der Tag erstellt und anschließend über **Git → Push** zum Remote-Repository gepusht werden.

Der Tag-Push startet **Create Draft Release**. Anschließend in GitHub unter **Actions** den Lauf prüfen und unter **Releases** das erzeugte Draft-Release kontrollieren. Mit **Publish release** wird es veröffentlicht und dadurch **Publish Release to Marketplace** gestartet.

Für einen neuen Release-Branch können die Schritte beispielsweise so aussehen:

```bash
git switch main
git pull --ff-only origin main
git switch -c release/2.3.0
git push -u origin release/2.3.0
```

Ein Tag allein startet keinen lokalen IntelliJ-Run-Task: Die Workflows laufen auf GitHub Actions, sobald Branch, Pull Request oder Tag zum Remote-Repository gepusht wurden.

## Einstellungen

Unter `Settings > Tools > MavenUp` können folgende Optionen konfiguriert werden. Die Einstellungen werden global auf Anwendungsebene gespeichert und gelten damit für alle Projekte. Sie sind zur besseren Übersicht in drei thematische Gruppen mit Überschriften gegliedert: **Appearance**, **Versions & Updates** und **Vulnerability Check**.

**Appearance**

- **Maven Repository Browser**: Wählt den Browser für Artifact-Versionsseiten – **MVN Repository** (Standard, `mvnrepository.com`) oder **Sonatype Central** (`central.sonatype.com`). Die Auswahl gilt für das Rechtsklick-Kontextmenü im Hauptfenster und das Rechtsklick-Kontextmenü im Vulnerability-Details-Dialog (alle Spalten außer **References**).
- **Show text labels on toolbar buttons instead of icons only**: Stellt die Aktionen in der oberen Aktionsleiste des Tool-Windows und des Vulnerability-Details-Dialogs wahlweise als Buttons mit Textbeschriftung statt als reine Icon-Buttons dar (Standard: an); die Beschriftungen sind gekürzt (der vollständige Text bleibt als Tooltip erhalten), damit die Aktionsleiste auch bei niedrigen Bildschirmauflösungen passt. Die Änderung wird sofort auf das offene Tool-Window angewendet.
- **Jump to pom.xml on single click**: Ermöglicht die Navigation zur `pom.xml` mit einem einfachen statt eines Doppelklicks.
- **Confirm before resetting all version selections**: Legt fest, ob die Aktion **Reset All to Current Versions** vor dem Verwerfen aller Versionsauswahlen einen Bestätigungsdialog anzeigt (Standard: an). Der Dialog bietet eine Option „Don't ask again", die diese Einstellung ebenfalls deaktiviert.

**Versions and Updates**

- **Automatically select version after checking for updates**: Legt als Combobox mit drei Zuständen die Vorauswahl-Strategie für **New Version** fest: **Keep current version (no auto-selection)**, **Select highest available version** oder **Select latest minor version in current major line**. Die Änderung wird mit **Apply** oder **OK** sofort auf das offene Tool-Window angewendet, ohne erneuten Update-Check; andere Einstellungen verändern die aktuelle Auswahl nicht.
- **Offer all versions (including older than the current one)**: Bietet in den Versions-Dropdowns auch Versionen an, die älter als die aktuell verwendete sind, sodass Downgrades möglich werden (Standard: aus). Ist die Option deaktiviert, werden nur Versionen `>=` der aktuellen Version angeboten.
- **Hide unstable versions**: Blendet instabile Versionen (z.B. RC/Beta) aus den auswählbaren Update-Versionen aus.
- **Hidden version qualifiers (comma-separated)**: Liste der auszublendenden Typen, z.B. `rc,beta,milestone` (als eingerückter Unterpunkt im IntelliJ-Settings-Style; Label und Feld nur aktiv, wenn der Filter eingeschaltet ist; das Feld passt sich an die Dialogbreite an, damit auch längere Listen gut lesbar bleiben).
- **Stop after a successful Maven Central lookup**: Legt fest, ob nach einer erfolgreichen Abfrage von Maven Central keine weiteren privaten Repositories abgefragt werden (Standard: an). Bei deaktivierter Option werden private Repositories auch nach erfolgreicher Central-Abfrage weiterhin geprüft, um private-only Versionen zu finden.
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

`./<USER>/.gradle/gradle.properties`

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
- `MavenUpSettings`: anwendungsweiter Persistenz-Service (`PersistentStateComponent`, `Service.Level.APP`) in `mavenup_settings.xml`; die Einstellungen gelten global für alle Projekte und enthalten auch die konfigurierbare Central-first-Short-Circuit-Strategie. Das OSS-Index-Token wird getrennt über `OssIndexCredentialService` im Password Safe gespeichert. Für die HTTP Basic Authentication wird ein fester Platzhalter-Benutzername verwendet, da Sonatype nur das Token auswertet.
- `DependencyApiService`, `VulnerabilityApiService` und `OssIndexApiService`: kapseln externe API-Abfragen für Versionen und Vulnerabilities außerhalb der UI; `DependencyApiService` ermittelt die Maven-`settings.xml` robust über IDE-Pfad mit Fallback auf `${user.home}/.m2/settings.xml` und protokolliert den genutzten Pfad auf DEBUG-Ebene.
- `VulnerabilityMerger`: dedupliziert Befunde aus mehreren Quellen anhand von Advisory-IDs und Aliasen.
- `RefreshSnapshotCollector`: liest die im Projekt deklarierten Abhängigkeiten, Plugins und Versions-Properties über PSI und liefert einen `RefreshSnapshot`; löst Property-Platzhalter (`${...}`) auf.
- `PomUpdateService`: wendet ausgewählte Versions-Updates über PSI auf die `pom.xml`-Dateien an (Dependencies, dependencyManagement, Plugins, pluginManagement, Parent) und speichert die Änderungen bei aktivem Maven-Sync.
- `VulnerabilityScanService`: ermittelt direkte und transitive Scan-Ziele aus dem Maven-Modell und kapselt die Sonatype-OSS-Index-Abfrage inklusive Fehlerbehandlung.
- `DependencyVersionService`: fragt die verfügbaren Versionen aller Abhängigkeiten und Plugins ab und leitet daraus – abhängig von der Auto-Selektionsstrategie – eine Vorauswahl ab (`VersionSearchResult`); die zustandslosen Auto-Selektions-Helfer liegen in `VersionAutoSelection`.
- `PomNavigationService`: sucht Abhängigkeits-, Parent- und Plugin-Definitionen in der `pom.xml` und öffnet den Editor an der jeweiligen Stelle.
- Unterstützte CVSS-Vektoren aus OSV werden mit `us.springett:cvss-calculator` in vergleichbare Basisscores umgerechnet. Bei noch nicht unterstützten CVSS-Versionen bleibt der Befund erhalten und nutzt den Schweregrad der Quelle.

### UI (`de.schwarzland.mavenup.ui`)
- `MavenUpWindowFactory`: Tool-Window-Factory und UI-Interaktion für Tabelle, Update- und Vulnerability-Workflows; die Aktionen liegen in einer oberen `ActionToolbar`, Refresh-Daten werden per nicht blockierender Read-Action außerhalb des EDT erfasst.
- Zustandslose UI-Hilfsdateien im selben Paket kapseln reine Logik und Darstellung ohne Abhängigkeit zur Tool-Window-Instanz: `MavenUpTableConstants` (Spalten-/Message-Key-Konstanten), `VersionStatusUi` (Versionsstatus-Glyphen, -Farben und `createVersionPanel`), `DependencyFilterModel` (`TriStateFilter`, `FilterRow`/`FilterCriteria`, `rowMatchesFilter`), `VulnerabilityCellModel` (`VulnerabilityCell`, Zusammenfassung und Koordinaten-Helfer), `RefreshSnapshot`, `MavenRepositoryLink` (`buildMavenRepositoryUrl`), `SortableHeaderIcon` und `HelpTooltipExtensions`.
- `VulnerabilityDetailDialog`: zeigt direkte und transitive Security-Befunde mit Quellen und Referenzen; die Aktionen **Open in ...** und **References...** in der oberen Aktionsleiste des Dialogs werden über die aktuelle Zeilenselektion gesteuert, und ein Rechtsklick auf die Zeile öffnet in allen Spalten außer **References** ein Kontextmenü mit **Open in Maven Repository** sowie **References...**.
- `UpdateConfirmationDialog`: `DialogWrapper`, der vor dem Anwenden die anstehenden Updates (GroupId, ArtifactId, Typ, alte/neue Version) in einer schreibgeschützten Tabelle zeigt und die Option **Sync Maven Changes** (vorbelegt aus `MavenUpSettings`) anbietet.
- `MavenUpConfigurable`: Einstellungs-UI unter `Settings > Tools > MavenUp`, gebunden an `MavenUpSettings`; Password-Safe-Zugriffe werden im Hintergrund geladen und für die Änderungserkennung zwischengespeichert.
- `MyMessageBundle` (weiterhin im Basispaket): zentralisierte i18n-Texte für die UI.


## Tests

Die Unittests liegen unter `src/test/kotlin` und spiegeln die Paketstruktur des Hauptcodes wider
(`model`, `service`, `ui`). Ausführung über:
```
./gradlew test
```

Hinweise:
- Tests, die reine Logik ohne IntelliJ-Plattform benötigen (z. B. `VulnerabilityApiServiceTest`,
  `VersionAutoSelectionTest`), nutzen reines JUnit. Tests, die eine Projekt-/PSI-Umgebung benötigen
  (z. B. `MavenUpWindowFactoryTest`, `RefreshSnapshotCollectorTest`, `PomNavigationServiceTest`,
  `PomUpdateServiceTest`, `VersionStatusUiTest`, `MavenUpConfigurableTest`), erben von `BasePlatformTestCase`.
- Im Test-Sandbox wird das gebündelte Vue.js-Plugin (`org.jetbrains.plugins.vue`) über
  `tasks.named("prepareTestSandbox") { disabledPlugins.add(...) }` in `build.gradle.kts` deaktiviert.
  MavenUp hat keine Abhängigkeit zu Vue; dessen Initialisierung führte in manchen Test-Sandbox-Setups zu
  sporadischen `TestLoggerAssertionError`-Fehlschlägen unabhängig vom eigentlichen Testcode.

## Codequalität

Statische Analyse und Testabdeckung sind über Gradle eingebunden:

- **detekt** (statische Code-Analyse): läuft automatisch als Teil von `./gradlew check`/`build`.
  Die Konfiguration liegt unter `config/detekt/detekt.yml` (u. a. `LargeClass = 800` als Ausdruck
  der 800–1000-Zeilen-Regel). Bestehende Befunde sind in `config/detekt/baseline.xml` eingefroren,
  sodass nur **neu** eingeführte Verstöße den Build scheitern lassen. Manuell:
  ```
  ./gradlew detekt
  ```
  Die Baseline nach einem bewussten Refactoring neu erzeugen: `./gradlew detektBaseline`.
- **Kover** (Testabdeckung): erzeugt Coverage-Reports über
  ```
  ./gradlew koverHtmlReport   # HTML unter build/reports/kover/html
  ./gradlew koverXmlReport    # XML unter build/reports/kover/report.xml
  ```
  Ein blockierendes Mindest-Coverage-Gate kann bei Bedarf über `koverVerify` ergänzt werden.

Die CI (`.github/workflows/ci.yml`) führt `build verifyPlugin detekt koverXmlReport` sowie
`runPluginVerifier` aus und lädt die Test- und Analyse-Reports als Artefakt hoch.


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
