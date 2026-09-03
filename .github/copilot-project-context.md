# Projektkontext: MavenUp (Referenz für Copilot-Agenten)

## Was ist MavenUp?
IntelliJ-Plugin (Kotlin/Gradle, IntelliJ Platform Plugin) für **Maven-Projekte**.
Zeigt alle Dependencies/Plugins aus `pom.xml`-Dateien (inkl. `<parent>`, `dependencyManagement`/
`pluginManagement`) in einer Tabelle im MavenUp-Tool-Window, prüft verfügbare neue
Versionen gegen Maven Central + private Repositories (aus `settings.xml`,
inkl. Credential-Placeholder-Auflösung), und schreibt gewählte Versions-Updates
nach Bestätigung zurück in die `pom.xml` (Property-aware).

- Group: `de.schwarzland`, aktuelle Version: siehe `gradle.properties` (`version=...`)
- Sprache Code: Kotlin; Build: Gradle (`build.gradle.kts`, `settings.gradle.kts`)
- Codequalität: **detekt** (statische Analyse, Config `config/detekt/detekt.yml`, Bestandsbefunde in
  `config/detekt/baseline.xml`; läuft via `check`/`build`) und **Kover** (Testabdeckung,
  `koverHtmlReport`/`koverXmlReport`). CI: `.github/workflows/ci.yml` (`build verifyPlugin detekt koverXmlReport`).
- Supply-Chain-Sicherheit: `.github/workflows/dependency-graph.yml` erzeugt über
  `gradle/actions/dependency-submission` den aufgelösten Gradle-Abhängigkeitsgraph (inkl. transitiver
  Abhängigkeiten) und meldet ihn bei Pushes auf `main` direkt an GitHub, damit Dependabot
  Sicherheitswarnungen dazu erzeugen kann; bei Pull Requests wird der Graph nur als Artefakt abgelegt und
  von `.github/workflows/dependency-graph-submit.yml` per `workflow_run` gemeldet (fork-sicher).
  `.github/workflows/dependency-review.yml` prüft Pull Requests mit
  `actions/dependency-review-action` gegen die GitHub Advisory Database.
- Plugin-Descriptor: `src/main/resources/META-INF/plugin.xml`
- Tool-Window-Icon: `src/main/resources/icons/mavenUpToolWindow.svg` (Light) und `mavenUpToolWindow_dark.svg` (Dark), in `plugin.xml` über das `icon`-Attribut des `<toolWindow>` referenziert.

## Kernkomponenten (`src/main/kotlin/de/schwarzland/mavenup/`)

### Paketstruktur
- **`model`**: `DependencyUpdate`, `VulnerabilityAdvisory`, `VulnerabilitySeverity`, `AffectedVersionRange` – reine Daten-DTOs ohne Logik.
- **`service`**: Alle externen API-Zugriffe, Settings, Startup-Logik und Hilfsfunktionen.
- **`ui`**: Tool-Window, Dialoge, Settings-UI, I18n-Bundle sowie ausgelagerte, zustandslose UI-Hilfsdateien.

### Komponentenreferenz
Die ausführliche Beschreibung aller Klassen ist nach Package aufgeteilt:
- [`context/components-ui.md`](context/components-ui.md): UI-Hilfsdateien und I18n, mit Verweisen auf die beiden folgenden Dateien.
- [`context/components-ui-toolwindow.md`](context/components-ui-toolwindow.md): Tool-Window (Hintergrundverarbeitung, Aktionsleiste, Kontextmenü, Tabellenspalten, Filterzeile, Sortierung, Sammelaktionen).
- [`context/components-ui-dialogs.md`](context/components-ui-dialogs.md): Dialoge, transitive Sicherheitslücken-Ansicht und Settings-UI.
- [`context/components-service.md`](context/components-service.md): Startup/Tool-Window-Aktivierung, Einstellungen und Message-Bus, API- und Sicherheitsservices, Sicherheitsdatenmodell, PSI- und Maven-Services.

Wird eine Klasse hinzugefügt, umbenannt oder entfernt, ist sie in der thematisch passenden dieser beiden Dateien zu pflegen – nicht in dieser Übersicht.

## Tests
`src/test/kotlin/de/schwarzland/mavenup/` spiegelt die Paketstruktur (`model`, `service`, `ui`).
Reine Logik nutzt JUnit (z. B. `VulnerabilityApiServiceTest`, `VersionAutoSelectionTest`), Tests mit
Projekt-/PSI-Umgebung erben von `BasePlatformTestCase` (z. B. `MavenUpWindowFactoryTest`,
`RefreshSnapshotCollectorTest`, `PomNavigationServiceTest`, `PomUpdateServiceTest`, `VersionStatusUiTest`,
`InheritedVersionUiTest`,
`DependencyVersionServiceTest`, `VulnerabilityScanServiceTest`). Netzwerklastige Services werden über
injizierte Seams/Interfaces netzwerkfrei getestet.

## KI-Agenten (Copilot / Junie)
- Für KI-Agenten gelten die verbindlichen Arbeitsanweisungen in `.github/copilot-instructions.md`.
- `AGENTS.md` im Repository-Wurzelverzeichnis ist der herstellerübergreifende Einstiegspunkt (z. B. OpenAI Codex / Codex CLI, GitHub Copilot CLI, Cursor, Aider, Jules, Zed) und verweist auf `.github/copilot-instructions.md` sowie diesen Kontext.
- Junie nutzt zusätzlich die Datei `.junie/guidelines.md` als explizite Referenz auf diesen Kontext.

## Doku- und Prozesspflichten
Siehe `.github/copilot-instructions.md` für die verbindliche Arbeitsanweisung
(README/CHANGELOG/FEATURES/plugin.xml-Description/Unittests pflegen, kein `git commit`).
Die README ist eine schlanke, englische Landing Page; die ausführliche Dokumentation liegt (Englisch) unter
`docs/` (`usage.md`, `configuration.md`, `privacy-and-security.md`, `architecture.md`, `development.md`,
`release-and-ci.md`, `licenses.md`).

## Sonstiges
- Proxy-Einstellungen gehören NICHT in die projektweite `gradle.properties`, sondern
  in die user-lokale `~/.gradle/gradle.properties`.
- `checkliste-publication.md` enthält Checkliste für Plugin-Veröffentlichung.
- `getting_started.html` ist die *Getting Started*-Seite im JetBrains Marketplace / Plugin Manager. Sie erklärt neuen Nutzern in einem kompakten HTML-Kurzleitfaden die ersten Schritte: Tool-Window öffnen, Refresh, Update-Check, Version auswählen, Update anwenden. Muss bei jeder Bedienungsänderung (neue Aktionen, umbenannte Buttons, neue Dialoge) aktuell gehalten werden.
