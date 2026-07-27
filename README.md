# MavenUp

MavenUp ist ein IntelliJ-Plugin, das speziell für Maven-Projekte entwickelt wurde, um die Verwaltung von Abhängigkeiten (Dependencies) und Plugins zu vereinfachen. Es bietet eine übersichtliche Tabellenansicht aller deklarierten Komponenten und ermöglicht die einfache Aktualisierung auf neuere Versionen.

Eine vollständige, englische Feature-Liste steht in `FEATURES.MD`.

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
- **Hintergrundverarbeitung für lange Aktionen**: Update-Check, Navigation und Schreiboperationen laufen im Hintergrund, damit die IDE responsiv bleibt.
- **Navigation zur Definition in `pom.xml`**: Per Doppelklick (oder optional Einzelklick) springt MavenUp direkt zur passenden Dependency-/Plugin-Definition.
- **Integrierte Einstellungen**: Über `Settings > Tools > MavenUp` konfigurierbar (u.a. Single-Click-Navigation, automatische Vorauswahl der neuesten Version).

### Intern
- **Gezielte Credential-Zuordnung**: Ordnet Credentials primär über Repository-ID zu, mit Fallback über Repository-URL und Hostname.
- **Versionsauflösung mit Fallbacks**: Nutzt aufgelöste Maven-Versionen aus dem Projektmodell (Dependency Tree/Plugins), damit die aktuelle Version auch bei indirekter Verwaltung korrekt angezeigt und verglichen wird.
- **Central-first mit Short-Circuit**: Fragt Maven Central priorisiert zuerst ab; ist die Abfrage dort erfolgreich, werden für dieselbe Dependency keine weiteren privaten Repositories mehr abgefragt.
- **Fehler- und Warn-Logging**: Protokolliert Parsing-Fehler für `settings.xml`, nicht auflösbare Credential-Variablen sowie fehlgeschlagene Repository-Abfragen (inkl. HTTP-Status).
- **Expliziter Refresh-Flow**: Der interne Refresh unterscheidet klar zwischen „New Version zurücksetzen“ (manueller Refresh) und „New Version behalten“ (Refresh nach Update-Check).

## Benutzung

### German
Das Plugin öffnet ein Tool-Window namens **MavenUp** (meist am rechten oder unteren Rand der IDE).

1. **Refresh**: Lädt die Projektdaten neu, befüllt die Tabelle und setzt die Spalte **New Version** zurück. (Beim **Check for Updates** bleiben die neu geladenen Werte erhalten.)
2. **Check for Updates**: Sucht online nach verfügbaren Versionen für alle gelisteten Einträge.
3. **New Version**: In dieser Spalte kann nach dem Update-Check eine neuere Version gewählt werden.
4. **Update**: Wendet die gewählten Versionsänderungen auf die entsprechenden `pom.xml`-Dateien an.

### English
The plugin opens a tool window named **MavenUp** (usually located at the right or bottom edge of the IDE).

1. **Refresh**: Reloads the project data and populates the table.
2. **Check for Updates**: Searches online for available versions for all listed entries.
3. **New Version**: In this column, a newer version can be selected after the update check.
4. **Update**: Applies the selected version changes to the corresponding `pom.xml` files.

## Einstellungen

Unter `Settings > Tools > MavenUp` können folgende Optionen konfiguriert werden:

- **Jump to pom.xml on single click**: Ermöglicht die Navigation zur `pom.xml` mit einem einfachen statt eines Doppelklicks.
- **Automatically select newest version**: Wählt nach einem Update-Check automatisch die jeweils neueste verfügbare Version in der Dropdown-Liste aus.
- **Hide unstable versions**: Blendet instabile Versionen (z.B. RC/Beta) aus den auswählbaren Update-Versionen aus.
- **Hidden version qualifiers (comma-separated)**: Liste der auszublendenden Typen, z.B. `rc,beta,milestone` (eingerückt dargestellt; Label und Feld sind nur aktiv, wenn der Filter eingeschaltet ist; größeres Eingabefeld für längere Listen).

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

Das Plugin besteht aus folgenden Komponenten:

### MavenUpStartupActivity
Startup-Aktivität (`ProjectActivity`), die beim Öffnen eines Projekts die Verfügbarkeit des Tool-Windows steuert:
- wartet kurz auf einen initialisierten `MavenProjectsManager` (Race-Condition-Schutz nach IDE/Plugin-Updates),
- macht das Tool-Window sofort verfügbar, wenn Maven-Projekte bereits vorhanden sind,
- registriert andernfalls einen `MavenImportListener` und aktiviert das Tool-Window nach abgeschlossenem Maven-Import.

### MavenUpWindowFactory
Zentrale Factory (`ToolWindowFactory`) mit der inneren UI-/Logik-Klasse `MyToolWindow`. Kernaufgaben:
- **Datenmodell und Tabelle**: sammelt Dependencies/Plugins aus `pom.xml` und zeigt `groupId`, `artifactId`, Property, Typ, aktuelle Version und auswählbare Zielversionen.
- **Scope-Unterstützung**: berücksichtigt lokale Einträge sowie `dependencyManagement` und `pluginManagement`.
- **Versionsprüfung**: lädt Versionen aus Maven-Repositories (`maven-metadata.xml`) inkl. Authentifizierung über `settings.xml`.
- **Repository-Strategie**: priorisiert Maven Central zuerst; bei erfolgreicher Central-Abfrage wird für dieselbe Dependency nicht weiter in privaten Repositories gesucht.
- **Credential-Auflösung**: unterstützt Klartext sowie Platzhalter (`${env.*}`, `${...}` via System-Property/Environment) und Credential-Matching über ID, URL oder Host.
- **Änderungsworkflow**: hält gewählte Zielversionen im Speicher, zeigt vor dem Schreiben einen Bestätigungsdialog und aktualisiert anschließend die betroffenen `pom.xml`-Einträge.
- **Navigation**: springt zur konkreten Dependency-/Plugin-Definition in der passenden `pom.xml`.
- **Nebenläufigkeit/UX**: führt langlaufende Aktionen (Update-Check, Navigation, Schreibvorgänge) als Hintergrund-Tasks aus.

### MavenUpSettings
Projektbezogener Persistenz-Service (`PersistentStateComponent`), gespeichert in `mavenup_settings.xml`. Speichert u. a.:
- `jumpOnSingleClick`: Aktiviert Navigation per Einzelklick
- `selectLatestVersion`: Automatische Auswahl der neuesten Version

### MavenUpConfigurable
Einstellungs-UI (`Configurable`) unter `Settings > Tools > MavenUp`; bindet die Checkboxen an `MavenUpSettings` (`isModified`/`apply`/`reset`).

### MyMessageBundle
I18n-Wrapper auf `messages.MyMessageBundle` für zentralisierte, lokalisierbare UI-Texte (`message(...)`, `lazyMessage(...)`).


---

## Developer Stuff
`git push` scheiterte mit den `/assets/*.png`weil die Git-Buffer-Size zu klein war. Lösung:
```
git config --global http.postBuffer 524288000
```
