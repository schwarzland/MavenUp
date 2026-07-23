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

Das Plugin öffnet ein Tool-Window namens **MavenUp** (meist am rechten oder unteren Rand der IDE).

1. **Refresh**: Lädt die Projektdaten neu und befüllt die Tabelle.
2. **Check for Updates**: Sucht online nach verfügbaren Versionen für alle gelisteten Einträge.
3. **New Version**: In dieser Spalte kann nach dem Update-Check eine neuere Version gewählt werden.
4. **Update**: Wendet die gewählten Versionsänderungen auf die entsprechenden `pom.xml`-Dateien an.

The plugin opens a tool window named **MavenUp** (usually located at the right or bottom edge of the IDE).

1. **Refresh**: Reloads the project data and populates the table.
2. **Check for Updates**: Searches online for available versions for all listed entries.
3. **New Version**: In this column, a newer version can be selected after the update check.
4. **Update**: Applies the selected version changes to the corresponding `pom.xml` files.

## Einstellungen

Unter `Settings > Tools > MavenUp` können folgende Optionen konfiguriert werden:

- **Jump to pom.xml on single click**: Ermöglicht die Navigation zur `pom.xml` mit einem einfachen statt eines Doppelklicks.
- **Automatically select newest version**: Wählt nach einem Update-Check automatisch die jeweils neueste verfügbare Version in der Dropdown-Liste aus.

## Anforderungen
- Das Plugin kann nur in Projekten verwendet werden, die als Maven-Projekte konfiguriert sind.

---

## Plugin Development

If you're still not quite sure what this is all about, read [Introduction to IntelliJ Platform][docs:intro].

## Predefined Run/Debug configurations

Within the default project structure, there is a `.run` directory provided containing predefined *Run/Debug
configurations* that expose corresponding Gradle tasks:

| Configuration name | Description                                                                                                                                                                         |
|--------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Run Plugin         | Runs [`:runIde`][gh:intellij-platform-gradle-plugin-runIde] IntelliJ Platform Gradle Plugin task. Use the *Debug* icon for plugin debugging.                                        |
| Run Tests          | Runs [`:check`][gradle:lifecycle-tasks] Gradle task.                                                                                                                                |
| Run Verifications  | Runs [`:verifyPlugin`][gh:intellij-platform-gradle-plugin-verifyPlugin] IntelliJ Platform Gradle Plugin task to check the plugin compatibility against the specified IntelliJ IDEs. |

> [!NOTE]
> You can find the logs from the running task in the `idea.log` tab.

## Publishing the plugin

> [!TIP]
> Make sure to follow all guidelines listed in [Publishing a Plugin][docs:publishing] to follow all recommended and
> required steps.

Releasing a plugin to [JetBrains Marketplace](https://plugins.jetbrains.com) is a straightforward operation that uses
the `publishPlugin` Gradle task provided by
the [intellij-platform-gradle-plugin][gh:intellij-platform-gradle-plugin-docs].

You can also upload the plugin to the [JetBrains Plugin Repository](https://plugins.jetbrains.com/plugin/upload)
manually via UI.

## Useful links

- [IntelliJ Platform SDK Plugin SDK][docs]
- [IntelliJ Platform Gradle Plugin Documentation][gh:intellij-platform-gradle-plugin-docs]
- [IntelliJ Platform Explorer][jb:ipe]
- [JetBrains Marketplace Quality Guidelines][jb:quality-guidelines]
- [IntelliJ Platform UI Guidelines][jb:ui-guidelines]
- [IntelliJ SDK Code Samples][gh:code-samples]

[docs]: https://plugins.jetbrains.com/docs/intellij
[gh:code-samples]: https://github.com/JetBrains/intellij-sdk-code-samples
[gh:intellij-platform-gradle-plugin]: https://github.com/JetBrains/intellij-platform-gradle-plugin
[gh:intellij-platform-gradle-plugin-docs]: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
[gh:intellij-platform-gradle-plugin-runIde]: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-tasks.html#runIde
[gh:intellij-platform-gradle-plugin-verifyPlugin]: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-tasks.html#verifyPlugin
[gradle:lifecycle-tasks]: https://docs.gradle.org/current/userguide/java_plugin.html#lifecycle_tasks
[jb:forum]: https://platform.jetbrains.com/
[jb:quality-guidelines]: https://plugins.jetbrains.com/docs/marketplace/quality-guidelines.html
[jb:ipe]: https://jb.gg/ipe
[jb:ui-guidelines]: https://jetbrains.github.io/ui


## Developer Stuff
`git push` scheiterte mit den `/assets/*.png`weil die Git-Buffer-Size zu klein war. Lösung:
```
git config --global http.postBuffer 524288000
```
