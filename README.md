# MavenUp

MavenUp ist ein IntelliJ-Plugin, das speziell für Maven-Projekte entwickelt wurde, um die Verwaltung von Abhängigkeiten (Dependencies) und Plugins zu vereinfachen. Es bietet eine übersichtliche Tabellenansicht aller deklarierten Komponenten und ermöglicht die einfache Aktualisierung auf neuere Versionen.

Eine vollständige, englische Feature-Liste steht in `FEATURES.md`.

## Funktionen

- **Tool-Window für Maven-Projekte**: MavenUp ist nur bei Maven-Projekten verfügbar und zeigt alle gefundenen Module/`pom.xml`-Dateien in einer gemeinsamen Tabelle.
- **Übersicht für Dependencies und Plugins**: Erfasst normale Einträge sowie `dependencyManagement` und `pluginManagement` inklusive Typ-Kennzeichnung.
- **Validierung beim Einlesen**: Dependencies und Plugins ohne `groupId` werden beim Einsammeln übersprungen und nicht in der Liste angezeigt.
- **Property-Erkennung und Property-Updates**: Erkennt Versionen aus Maven-Properties (z.B. `${spring.version}`), zeigt die Property in eigener Spalte und aktualisiert beim Schreiben die Property statt des einzelnen Tags.
- **Update-Check über Repositories**: Prüft verfügbare Versionen in Maven Central und konfigurierten privaten Repositories (`settings.xml`), unterstützt Authentifizierung und dedupliziert identische Repository-URLs.
- **Credentials aus Platzhaltern**: Löst Credentials aus `settings.xml` auf, z.B. `${env.ARTIFACTORY_USERNAME}` / `${env.ARTIFACTORY_PASSWORD}` sowie `${MY_VAR}` (System-Property, danach Environment-Variable).
- **Versionsauswahl pro Zeile**: Bietet pro Dependency/Plugin ein Dropdown mit verfügbaren Versionen; die neueste Version kann optional automatisch vorausgewählt werden.
- **Filter für instabile Versionen**: Optionales Ausblenden von Versionen mit konfigurierbaren Qualifiern (standardmäßig `rc,beta`), z.B. Release Candidates oder Beta-Versionen.
- **Synchronisierte Auswahl bei gemeinsamer Property**: Wenn mehrere Dependencies dieselbe Maven-Property verwenden, wird eine geänderte Auswahl auf alle betroffenen Einträge synchronisiert.
- **Sicheres Update mit Bestätigungsdialog**: Vor dem Schreiben zeigt MavenUp eine Zusammenfassung aller geplanten Änderungen (alt/neu, Typ, Koordinaten) und aktualisiert die `pom.xml` erst nach Bestätigung.
- **Hintergrundverarbeitung für lange Aktionen**: Projekt-/PSI-Datenerfassung beim Refresh, Update-Check, Navigation und Schreiboperationen laufen im Hintergrund, damit die IDE responsiv bleibt.
- **Sichere Aktionszustände**: **Check Vulnerabilities** bleibt während eines laufenden Refreshs oder Update-Checks deaktiviert, damit keine konkurrierenden Prüfungen gestartet werden.
- **Navigation zur Definition in `pom.xml`**: Per Doppelklick (oder optional Einzelklick) springt MavenUp direkt zur passenden Dependency-/Plugin-Definition. Ein Tooltip auf jeder Zeile zeigt den konfigurierten Klick-Modus an.
- **Rechtsklick-Kontextmenü**: Ein Rechtsklick auf eine Dependency-Zeile öffnet ein Kontextmenü mit **Navigate to pom.xml** (springt zur Definition im Editor), **Open in Maven Repository** (öffnet die Versionsseite im konfigurierten Repository-Browser) und ggf. **Show Vulnerability Details** (öffnet den Dialog mit Sicherheitsbefunden, falls Vulnerabilities gefunden wurden).
- **Open-in-Repository-Button**: Der **Open on [Browser]**-Button in der Haupttoolbar steht direkt nach **Refresh** und wird aktiviert, sobald eine Dependency-Zeile selektiert ist. Die Beschriftung zeigt dynamisch den konfigurierten Browser-Namen (z.B. **Open on MVN Repository** oder **Open on Sonatype Central**) und öffnet die aktuelle Version der selektierten Dependency im konfigurierten Repository-Browser – entspricht dem Kontextmenüeintrag **Open in Maven Repository**.
- **Konfigurierbarer Repository-Browser**: Unter `Settings > Tools > MavenUp` kann zwischen **MVN Repository** (Standard, `mvnrepository.com`) und **Sonatype Central** (`central.sonatype.com`) gewählt werden. Die Auswahl gilt für das Kontextmenü im Hauptfenster und das Rechtsklick-Kontextmenü im Vulnerability-Details-Dialog (alle Spalten außer **References**).
- **Integrierte Einstellungen**: Über `Settings > Tools > MavenUp` konfigurierbar (u.a. Single-Click-Navigation, automatische Vorauswahl der neuesten Version, Repository-Browser).
- **Multi-Source-Vulnerability-Check**: **Check Vulnerabilities** prüft direkte Komponenten und standardmäßig auch aufgelöste transitive Dependencies über [OSV.dev](https://osv.dev). Optional ergänzt [Sonatype OSS Index](https://ossindex.sonatype.org/) Maven-spezifische Befunde.
- **Detaillierte Security-Befunde**: Direkt hinter **Current Version** zeigt **Vulnerabilities (Current)** die Befunde der direkten Dependency und ihrer aufgelösten transitiven Dependencies mit Gesamtzahl, transitiver Anzahl und höchstem Schweregrad. Der **Vulnerability Details**-Button ist erst aktiv, wenn eine Dependency-Zeile mit Befunden selektiert ist, und zeigt dann ausschließlich die Befunde der selektierten Dependency (direkte und transitive). Im Detaildialog ordnet die Ansicht die als transitiv markierten Komponenten der selektierten Dependency zu und zeigt IDs, Aliase, CVSS, Beschreibung, Quellen und Referenzen. Die Buttons **Open in ...** und **References...** sind anfangs deaktiviert und werden erst bei selektierter Vulnerability-Zeile aktiv; zusätzlich bietet die **Component**-Spalte per Rechtsklick ein Kontextmenü zum Öffnen der Komponente im konfigurierten Repository-Browser oder der Referenzen des selektierten Advisories. Zurückgezogene Advisories werden ignoriert und Mehrfachmeldungen anhand ihrer IDs/Aliase zusammengeführt.

### Intern
- **Gezielte Credential-Zuordnung**: Ordnet Credentials primär über Repository-ID zu, mit Fallback über Repository-URL und Hostname.
- **Versionsauflösung mit Fallbacks**: Nutzt aufgelöste Maven-Versionen aus dem Projektmodell (Dependency Tree/Plugins), damit die aktuelle Version auch bei indirekter Verwaltung korrekt angezeigt und verglichen wird.
- **Central-first mit Short-Circuit**: Fragt Maven Central priorisiert zuerst ab; ist die Abfrage dort erfolgreich, werden für dieselbe Dependency keine weiteren privaten Repositories mehr abgefragt.
- **Kompaktes Diagnose-Logging**: Protokolliert Parsing-Fehler für `settings.xml`, nicht auflösbare Credential-Variablen sowie fehlgeschlagene Repository-Abfragen (inkl. HTTP-Status). Umfangreiche Versions- und Komponentenlisten erscheinen ausschließlich gekürzt auf DEBUG-Ebene, damit `idea.log` klein und responsiv bleibt.
- **Expliziter Refresh-Flow**: Der interne Refresh unterscheidet klar zwischen „New Version zurücksetzen“ (manueller Refresh) und „New Version behalten“ (Refresh nach Update-Check).

## Benutzung

### German
Das Plugin öffnet ein Tool-Window namens **MavenUp** (meist am rechten oder unteren Rand der IDE).

1. **Refresh**: Lädt die Projektdaten neu, befüllt die Tabelle und setzt die Spalte **New Version** zurück. Währenddessen ist **Check Vulnerabilities** deaktiviert. (Beim **Check for Updates** bleiben die neu geladenen Werte erhalten.)
2. **Check for Updates**: Sucht online nach verfügbaren Versionen für alle gelisteten Einträge; **Check Vulnerabilities** bleibt bis zum Abschluss deaktiviert.
3. **New Version**: In dieser Spalte kann nach dem Update-Check eine neuere Version gewählt werden.
4. **Update**: Wendet die gewählten Versionsänderungen auf die entsprechenden `pom.xml`-Dateien an.
5. **Check Vulnerabilities**: Prüft direkte Komponenten und, sofern aktiviert, transitive Dependencies über OSV.dev sowie optional OSS Index. Die direkt hinter **Current Version** angeordnete Spalte **Vulnerabilities (Current)** zeigt pro direkter Dependency die Gesamtzahl, die davon transitive Anzahl und den höchsten Schweregrad.
6. **Vulnerability Details**: Öffnet die Befunde der selektierten Dependency-Zeile inklusive Quellen, IDs/Aliasen, CVSS, Beschreibung und Referenzen. Der Button ist erst aktiv, wenn eine Dependency-Zeile mit Befunden selektiert ist. Ein Klick auf eine befüllte Vulnerability-Zelle öffnet direkt die Befunde dieser Dependency (ebenfalls gefiltert auf die selektierte Zeile). Die Buttons **Open in ...** und **References...** sind zunächst deaktiviert und werden erst aktiv, wenn eine Vulnerability-Zeile selektiert ist; sie beziehen sich dann auf genau diese Zeile. Ein Rechtsklick auf die selektierte Zeile öffnet in allen Spalten außer **References** ein Kontextmenü mit **Open in Maven Repository** und **References...**.
7. **Open on [browser]**: Öffnet die aktuelle Version der selektierten Dependency im konfigurierten Repository-Browser. Der Button steht direkt nach **Refresh**, ist erst aktiv wenn eine Dependency-Zeile selektiert ist und zeigt den konfigurierten Browser-Namen in der Beschriftung (z.B. **Open on MVN Repository** oder **Open on Sonatype Central**).
8. **Rechtsklick-Kontextmenü**: Ein Rechtsklick auf eine Zeile bietet **Navigate to pom.xml**, **Open in Maven Repository** (öffnet die aktuelle Version im konfigurierten Repository-Browser) und ggf. **Show Vulnerability Details** (öffnet den Dialog mit den gefundenen Sicherheitsbefunden, falls vorhanden).

### English
The plugin opens a tool window named **MavenUp** (usually located at the right or bottom edge of the IDE).

1. **Refresh**: Reloads the project data and populates the table. **Check Vulnerabilities** is disabled while the refresh is running.
2. **Check for Updates**: Searches online for available versions for all listed entries; **Check Vulnerabilities** remains disabled until completion.
3. **New Version**: In this column, a newer version can be selected after the update check.
4. **Update**: Applies the selected version changes to the corresponding `pom.xml` files.
5. **Check Vulnerabilities**: Checks direct components and, when enabled, resolved transitive dependencies through OSV.dev and optionally Sonatype OSS Index. The **Vulnerabilities (Current)** column appears directly after **Current Version** and shows each direct dependency's total finding count, transitive finding count, and highest severity.
6. **Vulnerability Details**: Opens detailed findings for the selected dependency row with sources, IDs/aliases, CVSS, summaries, and references. The button is only active when a dependency row with findings is selected. Clicking a populated vulnerability cell also opens the findings for that dependency row directly. The **Open in ...** and **References...** buttons stay disabled until a vulnerability row is selected and then apply to that selected row. Right-clicking the selected row in any column except **References** opens a context menu with **Open in Maven Repository** and **References...**.
7. **Open on [browser]**: Opens the current version of the selected dependency in the configured repository browser. The button is placed directly after **Refresh**, is only active when a dependency row is selected, and its label dynamically shows the configured browser name (e.g. **Open on MVN Repository** or **Open on Sonatype Central**).
8. **Right-click context menu**: Right-clicking any row offers **Navigate to pom.xml**, **Open in Maven Repository** (opens the current version in the configured repository browser), and optionally **Show Vulnerability Details** (opens the dialog with found security findings, if any are present).

## Einstellungen

Unter `Settings > Tools > MavenUp` können folgende Optionen konfiguriert werden:

- **Maven Repository Browser**: Wählt den Browser für Artifact-Versionsseiten – **MVN Repository** (Standard, `mvnrepository.com`) oder **Sonatype Central** (`central.sonatype.com`). Die Auswahl gilt für das Rechtsklick-Kontextmenü im Hauptfenster und das Rechtsklick-Kontextmenü im Vulnerability-Details-Dialog (alle Spalten außer **References**).
- **Jump to pom.xml on single click**: Ermöglicht die Navigation zur `pom.xml` mit einem einfachen statt eines Doppelklicks.
- **Automatically select newest version**: Wählt nach einem Update-Check automatisch die jeweils neueste verfügbare Version in der Dropdown-Liste aus.
- **Hide unstable versions**: Blendet instabile Versionen (z.B. RC/Beta) aus den auswählbaren Update-Versionen aus.
- **Hidden version qualifiers (comma-separated)**: Liste der auszublendenden Typen, z.B. `rc,beta,milestone` (eingerückt dargestellt; Label und Feld sind nur aktiv, wenn der Filter eingeschaltet ist; größeres Eingabefeld für längere Listen).
- **Include resolved transitive dependencies**: Nimmt standardmäßig den aufgelösten Maven-Dependency-Tree in den Vulnerability-Check auf.
- **Use Sonatype OSS Index as an additional source**: Aktiviert die optionale zweite Datenquelle. Sonatype verwendet HTTP Basic Authentication, daher sind Benutzername/E-Mail und API-Token gemeinsam erforderlich und werden bei aktivierter Option als Pflichtfelder angezeigt. Das Token wird ausschließlich im IntelliJ Password Safe gespeichert, außerhalb des Event Dispatch Thread geladen und nicht in `mavenup_settings.xml` abgelegt. Fehlen Zugangsdaten bei einer bereits gespeicherten Konfiguration, wird die OSS-Index-Abfrage übersprungen; OSV.dev wird weiterhin abgefragt. Ein Link öffnet die Sonatype-Kontoeinstellungen zum Erzeugen oder Kopieren eines Tokens.

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


## Architektur

Das Plugin ist klar in drei Schichten gegliedert:

### Datenmodell (`de.schwarzland.mavenup.model`)
- Enthält schlanke DTOs für den UI-/Service-Datenaustausch, z. B. `DependencyUpdate` und `VulnerabilityAdvisory`.

### Service (`de.schwarzland.mavenup.service`)
- `MavenUpStartupActivity`: steuert die Verfügbarkeit des Tool-Windows beim Projektstart.
- `MavenUpSettings`: projektbezogener Persistenz-Service (`PersistentStateComponent`) in `mavenup_settings.xml`; der für HTTP Basic Authentication benötigte OSS-Index-Benutzername wird dort gespeichert, Tokens werden getrennt über `OssIndexCredentialService` im Password Safe gespeichert.
- `DependencyApiService`, `VulnerabilityApiService` und `OssIndexApiService`: kapseln externe API-Abfragen für Versionen und Vulnerabilities außerhalb der UI.
- `VulnerabilityMerger`: dedupliziert Befunde aus mehreren Quellen anhand von Advisory-IDs und Aliasen.
- Unterstützte CVSS-Vektoren aus OSV werden mit `us.springett:cvss-calculator` in vergleichbare Basisscores umgerechnet. Bei noch nicht unterstützten CVSS-Versionen bleibt der Befund erhalten und nutzt den Schweregrad der Quelle.

### UI (`de.schwarzland.mavenup.ui`)
- `MavenUpWindowFactory`: Tool-Window-Factory und UI-Interaktion für Tabelle, Update- und Vulnerability-Workflows; Refresh-Daten werden per nicht blockierender Read-Action außerhalb des EDT erfasst.
- `VulnerabilityDetailDialog`: zeigt direkte und transitive Security-Befunde mit Quellen und Referenzen; die Buttons **Open in ...** und **References...** werden über die aktuelle Zeilenselektion gesteuert, und ein Rechtsklick auf die Zeile öffnet in allen Spalten außer **References** ein Kontextmenü mit **Open in Maven Repository** sowie **References...**.
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
