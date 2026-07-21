# MavenUp

### Projektbeschreibung: MavenUp

`MavenUp` ist ein IntelliJ-Plugin zur effizienten Verwaltung und Aktualisierung von Maven-Abhängigkeiten direkt innerhalb der IDE.

#### Hauptfunktionen
- **Abhängigkeitsübersicht**: Ein Tool-Fenster auf der rechten Seite zeigt alle im Projekt deklarierten Abhängigkeiten und Plugins an.
- **Unterstützung für Managed Dependencies**: Das Plugin erkennt und markiert Abhängigkeiten, die in `<dependencyManagement>` oder `<pluginManagement>` definiert sind (als "managed dependency" bzw. "managed plugin").
- **Update-Prüfung**: Sucht nach verfügbaren neueren Versionen in konfigurierten Maven-Repositories.
- **Version-Auswahl & Highlights**: Die neueste verfügbare Version wird in der Tabelle grün hervorgehoben, wenn sie bereits aktuell ist. Über eine ComboBox kann eine neue Version ausgewählt werden.
- **Bulk-Update**: Ermöglicht das gleichzeitige Aktualisieren mehrerer Abhängigkeiten in der `pom.xml` über einen Bestätigungsdialog.
- **Navigation**: Per Klick (konfigurierbar: Einzel- oder Doppelklick) springt der Editor direkt zur Definition der Abhängigkeit in der entsprechenden `pom.xml`.
- **Automatische Synchronisation**: Aktualisiert die interne Liste automatisch nach einem Maven-Import ("Sync Maven Changes").

#### Technische Architektur
- **Sprache**: Geschrieben in Kotlin.
- **Integration**: Nutzt die IntelliJ-Plattform-APIs für Maven (`org.jetbrains.idea.maven`), XML-Parsing via PSI (Program Structure Interface) und persistente Einstellungen.
- **UI**: Basiert auf Swing-Komponenten unter Verwendung von IntelliJ-spezifischen Erweiterungen wie `ComboBox` und `JBTable`.
- **Lokalisierung**: Alle Texte werden zentral über `MyMessageBundle.properties` verwaltet.

#### Projektstruktur
- `src/main/kotlin/de/schwarzland/mavenup/`: Enthält die Kernlogik.
    - `MavenUpWindowFactory.kt`: Hauptklasse für das Tool-Fenster, UI-Logik und XML-Updates.
    - `MavenUpSettings.kt` / `MavenUpConfigurable.kt`: Verwaltung der Benutzereinstellungen.
    - `MyMessageBundle.kt`: Hilfsklasse für Lokalisierung.
- `src/main/resources/META-INF/plugin.xml`: Plugin-Manifest mit Deklaration von Extensions (Tool Window, Configurable) und Abhängigkeiten.
- `src/test/kotlin/de/schwarzland/mavenup/`: Beinhaltet automatisierte Tests zur Verifizierung der Funktionalität.
- `build.gradle.kts`: Konfiguration des Build-Systems (IntelliJ Platform Gradle Plugin).

#### Abhängigkeiten des Plugins
- `com.intellij.java`
- `com.intellij.modules.xml`
- `org.jetbrains.idea.maven`


MavenUp ist ein IntelliJ-Plugin, das speziell für Maven-Projekte entwickelt wurde.

## Anforderungen
- Das Plugin kann nur in Projekten verwendet werden, die als Maven-Projekte konfiguriert sind.

[![Twitter Follow](https://img.shields.io/badge/follow-%40JBPlatform-1DA1F2?logo=twitter)](https://twitter.com/JBPlatform)
[![Developers Forum](https://img.shields.io/badge/JetBrains%20Platform-Join-blue)][jb:forum]

## Plugin structure

A generated project contains the following content structure:

```
.
├── .run/                   Predefined Run/Debug Configurations
├── build/                  Output build directory
├── gradle
│   ├── wrapper/            Gradle Wrapper
│   ├── libs.versions.toml  Version catalog
├── src                     Plugin sources
│   ├── main
│   │   ├── kotlin/         Kotlin production sources
│   │   └── resources/      Resources - plugin.xml, icons, messages
├── .gitignore              Git ignoring rules
├── build.gradle.kts        Gradle build configuration
├── gradle.properties       Gradle configuration properties
├── gradlew                 *nix Gradle Wrapper script
├── gradlew.bat             Windows Gradle Wrapper script
├── README.md               README
└── settings.gradle.kts     Gradle project settings
```

In addition to the configuration files, the most crucial part is the `src` directory, which contains our implementation
and the manifest for our plugin – [plugin.xml][file:plugin.xml].

> [!NOTE]
> To use Java in your plugin, create the `/src/main/java` directory.

## Plugin configuration file

The plugin configuration file is a [plugin.xml][file:plugin.xml] file located in the `src/main/resources/META-INF`
directory.
It provides general information about the plugin, its dependencies, extensions, and listeners.

You can read more about this file in the [Plugin Configuration File][docs:plugin.xml] section of our documentation.

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
- [JetBrains Marketplace Paid Plugins][jb:paid-plugins]
- [IntelliJ SDK Code Samples][gh:code-samples]

[docs]: https://plugins.jetbrains.com/docs/intellij

[docs:intro]: https://plugins.jetbrains.com/docs/intellij/intellij-platform.html?from=IJPluginTemplate

[docs:plugin.xml]: https://plugins.jetbrains.com/docs/intellij/plugin-configuration-file.html?from=IJPluginTemplate

[docs:publishing]: https://plugins.jetbrains.com/docs/intellij/publishing-plugin.html?from=IJPluginTemplate

[file:plugin.xml]: ./src/main/resources/META-INF/plugin.xml

[gh:code-samples]: https://github.com/JetBrains/intellij-sdk-code-samples

[gh:intellij-platform-gradle-plugin]: https://github.com/JetBrains/intellij-platform-gradle-plugin

[gh:intellij-platform-gradle-plugin-docs]: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html

[gh:intellij-platform-gradle-plugin-runIde]: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-tasks.html#runIde

[gh:intellij-platform-gradle-plugin-verifyPlugin]: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-tasks.html#verifyPlugin

[gradle:lifecycle-tasks]: https://docs.gradle.org/current/userguide/java_plugin.html#lifecycle_tasks

[jb:github]: https://github.com/JetBrains/.github/blob/main/profile/README.md

[jb:forum]: https://platform.jetbrains.com/

[jb:quality-guidelines]: https://plugins.jetbrains.com/docs/marketplace/quality-guidelines.html

[jb:paid-plugins]: https://plugins.jetbrains.com/docs/marketplace/paid-plugins-marketplace.html

[jb:ipe]: https://jb.gg/ipe

[jb:ui-guidelines]: https://jetbrains.github.io/ui
