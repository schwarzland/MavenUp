# Copilot-Arbeitsanweisung (verbindlich)

Bei **jeder** Änderung im Repository sind diese Punkte immer zu berücksichtigen:

1. **README.md** ergänzen/aktualisieren, wenn sich Nutzung, Konfiguration oder Architektur ändert. Dabei gelten folgende Regeln:
   - **Sprache: Englisch.** README.md und alle Dateien unter `docs/` werden auf Englisch gepflegt.
   - **README.md ist eine schlanke Landing Page.** Sie enthält nur Kurzbeschreibung, Installation, Quick Start und Verweise auf `FEATURES.md` sowie die Dateien unter `docs/`. Detailinhalte gehören ausschließlich in die jeweilige `docs/`-Datei, nicht in die README.
   - **Features: FEATURES.md ist die Single Source of Truth.** Ergänze oder aktualisiere neue/geänderte Funktionen ausschließlich in `FEATURES.md` (Englisch); README.md und die `docs/`-Dateien referenzieren auf FEATURES.md statt Features zu duplizieren.
   - **Ausgelagerte Dokumentation unter `docs/`** – jede Änderung wird in genau der thematisch passenden Datei gepflegt:
     - `docs/usage.md`: Bedienung des Tool-Windows, Filter, Aktionen.
     - `docs/configuration.md`: **alle Einstellungen** (jede neue Einstellung hier ergänzen) sowie die Gradle-Proxy-Konfiguration.
     - `docs/privacy-and-security.md`: übertragene Daten und externe Endpunkte.
     - `docs/architecture.md`: Paketstruktur und Komponenten.
     - `docs/development.md`: Tests, Codequalität, Troubleshooting.
     - `docs/release-and-ci.md`: Branching, GitHub-Actions-Workflows, Dependabot, Publishing.
     - `docs/licenses.md`: eingebettete Drittanbieter-Bibliotheken und deren Lizenzen (siehe auch Punkt 9).
   - **AGENTS.md** ist der herstellerübergreifende Agenten-Einstiegspunkt und verweist auf `.github/copilot-instructions.md` und `.github/copilot-project-context.md`; ändert sich der Speicherort oder die Rolle dieser verbindlichen Instruktionsdateien, ist AGENTS.md entsprechend anzugleichen.
   - Der Abschnitt **AI instructions** der README verlinkt `AGENTS.md`, `.github/copilot-instructions.md` und `.github/copilot-project-context.md`; diese Linkliste ist bei Umbenennung oder Ergänzung solcher Dateien aktuell zu halten.
   - Veraltete Formulierungen (z. B. „now", „new") sind beim Bearbeiten zu entfernen.
   - Keine inhaltlichen Dopplungen zwischen README und `docs/`-Dateien oder zwischen den `docs/`-Dateien – jeder Punkt gehört genau an eine Stelle.
   - Nach jeder Änderung README und die betroffenen `docs/`-Dateien auf Vollständigkeit und Konsistenz prüfen; neue `docs/`-Dateien in der Dokumentationsliste der README verlinken.
2. **CHANGELOG.md** ergänzen/aktualisieren mit einem passenden Eintrag zur Änderung. Dabei gelten folgende Regeln:
   - Ermittle zunächst mit `git rev-parse --abbrev-ref HEAD`, ob du dich in einem Feature-Branch (erkennbar an `feature/*`) befindest.
   - Wenn du dich in einem Feature-Branch befindest, prüfe, ob eine Sektion `## [Unreleased]` existiert. Fehlt sie, füge sie ganz oben in der Datei als erste Überschrift hinzu.
   - Alle Änderungen des Feature-Branches werden in dieser `## [Unreleased]`-Sektion dokumentiert, nicht als neuer Block angehängt und bevor weitere Release-Abschnitte oder andere Einträge ergänzt werden.
   - Sprache: Englisch.
   - Struktur: pro Version **genau einen** `### Added`-, `### Changed`- und `### Fixed`-Block – niemals doppelte Kategorien innerhalb derselben Version.
   - Jeder Eintrag ist ein prägnanter Satz (ein Gedanke pro Zeile); keine Redundanzen, keine Dopplungen.
   - Nach dem Hinzufügen den gesamten Bereich überfliegen und sicherstellen, dass keine Kategorie doppelt vorkommt und alle Einträge thematisch korrekt zugeordnet sind.
3. **FEATURES.md** ergänzen/aktualisieren, wenn neue Funktionen hinzugefügt oder bestehende Funktionen geändert werden. Sprache: Englisch. Dabei gelten folgende Regeln:
   - Jede Funktion wird als ein prägnanter Bullet-Point beschrieben – ein Gedanke pro Zeile.
   - Neue Features werden als neuer Bullet-Point ergänzt; geänderte Features werden im bestehenden Bullet-Point aktualisiert – keine Dopplungen.
   - Veraltete oder entfernte Funktionen werden gelöscht.
   - Nach jeder Änderung die gesamte Liste auf Dopplungen und Lücken im Vergleich zur aktuellen Implementierung prüfen.
4. **src/main/resources/META-INF/plugin.xml** – die Sektion `<description>` aktualisieren, wenn neue Funktionen hinzugefügt oder bestehende Funktionen geändert werden. Dabei gelten folgende Regeln:
   - Sprache: Englisch. Die Description wird auf dem JetBrains Marketplace und im IDE Plugin Manager angezeigt – sie muss für Endanwender verständlich formuliert sein.
   - **Struktur & Hierarchie**: Beginne mit einem prägnanten Lead-Satz (max. 2 Sätze), der das Plugin kurzfristig zusammenfasst. Danach: **Core Features** (Haupt-Features, 3–5 Punkte) oben prominent platzieren und mit `<b>` hervorheben, gefolgt von **Advanced Capabilities** (erweiterte Features) für Power-User. Diese Hierarchie macht sofort klar, wofür das Plugin ist.
   - Jeder `<li>`-Eintrag beschreibt **genau eine** Funktion kompakt in einem Satz.
   - Neue Funktionen als neuen `<li>`-Eintrag ergänzen; geänderte Funktionen im bestehenden `<li>` aktualisieren – keine Dopplungen.
   - Beschreibungen müssen die tatsächliche Implementierung korrekt widerspiegeln – keine produktspezifischen Hardcodierungen, die durch Einstellungen variieren können (z. B. nie einen festen Repository-Namen nennen, wenn der Browser konfigurierbar ist).
   - Nach jeder Änderung die vollständige `<description>` mit FEATURES.md abgleichen: jede wichtige Funktion aus FEATURES.md muss sinngemäß abgedeckt sein; veraltete oder entfernte Einträge sind zu löschen.
5. **Unittests** schreiben oder anpassen – Ziel ist eine **sehr hohe Testabdeckung**. Dabei gelten folgende Regeln:
   - **Jede neue öffentliche oder interne Methode** (`public`, `internal`) bekommt mindestens einen Unittest.
   - **Jede Änderung an bestehender Logik** macht die zugehörigen Tests ungültig – diese sind zu aktualisieren oder zu ergänzen.
   - **Teststruktur**: Testklassen liegen unter `src/test/kotlin/` und spiegeln die Paketstruktur des Produktivcodesexakt wider (`model`, `service`, `ui`).
   - **Testtyp je nach Klasse wählen**:
     - Reine Logik ohne IntelliJ-Platform (z. B. `VulnerabilityApiService`, `VulnerabilityMerger`, `LogSummary`, `OssIndexApiService`, `DependencyUpdate`): reines JUnit (`@Test`-Annotation, kein `BasePlatformTestCase`).
     - Klassen, die IntelliJ-APIs, PSI oder ein Projekt-Objekt benötigen (z. B. `DependencyApiService`, `MavenUpConfigurable`, `MavenUpWindowFactory`): erben von `BasePlatformTestCase`, Testmethoden ohne `@Test`-Annotation (JUnit 3-Stil).
   - **Testfälle je Methode** abdecken: Normalfall, Grenzfälle (leere Eingabe, null, leere Liste), Fehlerfall (HTTP-Fehler, fehlende Werte, ungültige Eingaben).
   - **Keine Reflection** für den Zugriff auf private Methoden – wenn eine Methode testbar sein soll, muss sie `internal` oder in einen Service ausgelagert werden.
   - **Keine Duplikate**: Besteht bereits ein Test für einen Fall, wird er erweitert statt ein neuer angelegt.
   - Nach jeder Implementierungsänderung prüfen, ob bestehende Tests noch korrekt sind, und fehlgeschlagene oder veraltete Tests sofort korrigieren.
6. **.github/copilot-project-context.md** ergänzen/aktualisieren, wenn sich Verhalten, Nutzung, Konfiguration oder Architektur ändert. Dabei gelten folgende Regeln:
   - Sprache: Deutsch. Die Datei dient als kompakte Referenz für Copilot-Agenten.
   - Jede neue Klasse/Komponente wird in der **Komponenten**-Liste ergänzt; veraltete oder umbenannte Klassen werden aktualisiert oder entfernt.
   - Jede neue Einstellung in `MavenUpSettings.State` muss in der Komponenten-Beschreibung von **MavenUpSettings** namentlich aufgeführt sein.
   - Enum-Werte (z. B. `MavenRepositoryBrowser`) müssen korrekt benannt sein – keine Fantasie-Namen, die nicht im Code existieren.
   - Neue Packages oder strukturelle Änderungen in der **Paketstruktur** aktualisieren.
   - Nach jeder Änderung die Komponenten-Liste mit den tatsächlich vorhandenen Klassen unter `src/main/kotlin/` abgleichen.
7. **Methoden und Funktionen** der Klassen ergänzen/aktualisieren und dokumentieren, wenn sich Verhalten, Nutzung, Konfiguration oder Architektur ändert.
8. **KDoc-Qualität prüfen und sicherstellen** – bei jeder Änderung an einer Klasse oder Methode sind **alle** berührten Klassen und Methoden auf vollständige und korrekte KDoc zu prüfen. Konkret:
   - Jede Klasse, jedes `object`, jedes `enum` und jede `data class` muss einen KDoc-Kommentar haben.
   - Jede `public`, `internal` und `private` Funktion oder Methode muss mit KDoc dokumentiert sein (Ausnahme: triviale Getter/Setter ohne eigene Logik).
   - KDoc muss auf Deutsch verfasst sein und die tatsächliche Implementierung korrekt beschreiben.
   - Tippfehler, veraltete Beschreibungen und sprachliche Fehler (z. B. englische Verbformen wie „Parsed" statt „Parst") sind zu korrigieren.
   - Parameter (`@param`), Rückgabewerte (`@return`) und Ausnahmen (`@throws`) sind zu dokumentieren, wenn sie nicht selbsterklärend sind.
9. **Lizenzbestimmungen prüfen**, wenn neue Abhängigkeiten (Dependencies) hinzugefügt oder bestehende aktualisiert werden. Dabei gelten folgende Regeln:
   - Die Lizenz jeder neu hinzugefügten Bibliothek ist zu ermitteln (z. B. aus dem POM-File im Gradle-Cache oder der offiziellen Projektseite).
   - Bibliotheken, die zur Laufzeit ins Plugin-JAR eingebettet werden (`implementation`-Scope), unterliegen den Weitergabepflichten ihrer Lizenz.
   - Bibliotheken, die nur zur Testzeit oder als provided/platform verwendet werden (`testImplementation`, `intellijPlatform`), sind davon in der Regel ausgenommen.
   - Die Datei **`docs/licenses.md`** ist entsprechend zu ergänzen oder zu aktualisieren: jede eingebettete Bibliothek mit Name, Version, Lizenz (inkl. Link) und Verwendungszweck.
   - Veraltete oder entfernte Abhängigkeiten sind aus dem Abschnitt zu entfernen.
10. **getting_started.html** ergänzen/aktualisieren, wenn sich die Bedienung des Plugins ändert (neue Schritte, umbenannte Aktionen, neue Dialoge oder Einstellungen, die für Einsteiger relevant sind). Dabei gelten folgende Regeln:
    - Sprache: Englisch. Die Datei wird als *Getting Started*-Seite im JetBrains Marketplace und im IDE Plugin Manager angezeigt.
    - Die Seite soll aus Sicht eines Users die erstmalige Verwendung des Plugins beschreiben: Einstieg, erste Aktualisierung, Auswahl einer Version, Update, Navigation, Sicherheitsprüfung und erste Konfiguration.
    - Die Beschreibung muss für neue Nutzer verständlich sein – kurze, handlungsorientierte Sätze.
    - Neue Bedienelemente oder Workflows als zusätzliche `<li>`-Einträge in der bestehenden `<ol>` ergänzen; geänderte Aktionen im bestehenden Eintrag aktualisieren.
    - Veraltete oder entfernte Aktionen sind zu löschen.
    - Nach jeder Änderung die gesamte `getting_started.html` mit der tatsächlichen Bedienung abgleichen und sicherstellen, dass alle wesentlichen Schritte für einen Einsteiger abgedeckt sind.
    - Die Inhalte müssen mit `FEATURES.md` und `src/main/resources/META-INF/plugin.xml` abgestimmt sein; veraltete oder fehlende Einsteiger-Schritte sind zu korrigieren.
11. **Dateigröße und Refactoring** – wenn eine Klasse, ein `object` oder allgemein eine `.kt`-Datei mehr als 800 bis 1000 Zeilen Code enthält, muss sie im Sinne eines Refactorings restrukturiert werden. Es ist zu prüfen, welches Risiko dabei besteht. Der Anwender muss gefragt werden, ob das Refactoring erfolgen soll. Dabei gelten folgende Regeln:
    - Die Datei ist in kleinere, thematisch klar abgegrenzte Einheiten aufzuteilen (z. B. Extraktion von Services, Helfern, UI-Komponenten oder Datenmodellen in eigene Dateien).
    - Die Aufteilung muss die bestehende Paketstruktur (`model`, `service`, `ui`) respektieren und Verantwortlichkeiten sauber trennen (Single Responsibility).
    - Öffentliches Verhalten und bestehende Tests dürfen durch das Refactoring nicht brechen; Tests sind bei Bedarf an die neue Struktur anzupassen.
    - Nach dem Refactoring sind die betroffenen Dokumentations- und Kontextdateien (insbesondere `.github/copilot-project-context.md`) an die neue Struktur anzugleichen.
12. **kein git commit** ausführen.

Diese Vorgaben gelten standardmäßig für alle KI-Änderungen (GitHub Copilot, Junie, etc.) in diesem Projekt.
