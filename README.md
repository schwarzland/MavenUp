# MavenUp

MavenUp ist ein IntelliJ-Plugin, das speziell für Maven-Projekte entwickelt wurde, um die Verwaltung von Abhängigkeiten (Dependencies) und Plugins zu vereinfachen. Es bietet eine übersichtliche Tabellenansicht aller deklarierten Komponenten und ermöglicht die einfache Aktualisierung auf neuere Versionen.

## Funktionen

- **Übersicht der Abhängigkeiten & Plugins**: Anzeige aller in den Maven-Projekten deklarierten Dependencies und Plugins.
- **Unterstützung für Dependency Management**: Erkennt automatisch, ob eine Abhängigkeit direkt oder über `dependencyManagement` gesteuert wird.
- **Maven-Properties (Variablen)**: Wenn eine Version über eine Maven-Property (z.B. `${spring.version}`) definiert ist, wird der Name dieser Property in einer eigenen Spalte ("Property") angezeigt.
- **Update-Check**: Prüft auf Knopfdruck, ob neuere Versionen für die verwendeten Bibliotheken in den konfigurierten Repositories verfügbar sind. Unterstützt werden dabei auch private Repositories (z.B. Nexus oder Artifactory), sofern diese in den Maven `settings.xml` hinterlegt sind.
- **Fehlerbehandlung**: Protokolliert Fehler beim Einlesen der Maven-Konfiguration (Credentials/Repositories) und gibt Warnungen aus, falls Versionen für bestimmte Artefakte nicht geladen werden können.
- **Versionen auswählen & aktualisieren**: Ermöglicht die Auswahl einer neuen Version direkt aus einem Dropdown-Menü in der Tabelle und aktualisiert die `pom.xml` automatisch.
- **Navigation**: Ein Doppelklick (oder optional Einzelklick) auf einen Tabelleneintrag springt direkt zur entsprechenden Definition in der `pom.xml`.

## Benutzung

### German
Das Plugin öffnet ein Tool-Window namens **MavenUp** (meist am rechten oder unteren Rand der IDE).

1. **Refresh**: Lädt die Projektdaten neu und befüllt die Tabelle.
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

## Gradle Proxy-Konfiguration (lokal, nicht versioniert)

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
Startup-Aktivität, die beim Öffnen eines IntelliJ-Projektes ausgeführt wird. Sie prüft, ob Maven-Projekte vorhanden sind und macht das MavenUp Tool Window entsprechend verfügbar:
- Falls Maven-Projekte bereits importiert sind, wird das Tool Window sofort sichtbar gemacht
- Falls der Maven-Import noch nicht abgeschlossen ist, wird auf den `MavenImportListener` gehört und das Tool Window wird verfügbar, sobald der Import fertig ist

### MavenUpWindowFactory
Die zentrale Komponente des Plugins, die das Tool Window und dessen UI verwaltet. Sie enthält:
- **Tabelle**: Zeigt alle Abhängigkeiten und Plugins mit ihren Eigenschaften an (GroupId, ArtifactId, Property, Typ, aktuelle Version, verfügbare Versionen)
- **Update-Check**: Sucht in allen konfigurierten Maven-Repositories nach verfügbaren Versionen
- **Version-Management**: Ermöglicht die Auswahl neuer Versionen über Dropdown-Menüs
- **POM-Updates**: Aktualisiert die `pom.xml`-Dateien mit den gewählten Versionen
- **Repository-Support**: Unterstützt private Repositories mit Authentifizierung basierend auf `settings.xml`
- **Navigation**: Direkter Sprung zur Abhängigkeitsdefinition in der `pom.xml` per Klick

### MavenUpSettings
Persistente Einstellungen des Plugins auf Projektebene. Speichert Benutzereinstellungen wie:
- `jumpOnSingleClick`: Aktiviert Navigation per Einzelklick
- `selectLatestVersion`: Automatische Auswahl der neuesten Version

### MavenUpConfigurable
UI-Komponente für die Einstellungen, integriert in die IntelliJ-Preferences unter `Settings > Tools > MavenUp`.

### MyMessageBundle
Zentrale Verwaltung aller UI-Texte und Labels (Internationalisierung). Ermöglicht einfache Übersetzungen und Pflege von Texten.


---

## Developer Stuff
`git push` scheiterte mit den `/assets/*.png`weil die Git-Buffer-Size zu klein war. Lösung:
```
git config --global http.postBuffer 524288000
```
