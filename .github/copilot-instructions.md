# Copilot-Arbeitsanweisung (verbindlich)

Bei **jeder** Änderung im Repository sind diese Punkte immer zu berücksichtigen:

1. **README.md** ergänzen/aktualisieren, wenn sich Verhalten, Nutzung, Konfiguration oder Architektur ändert. Dabei gelten folgende Regeln:
   - Jede neue oder geänderte Funktion muss im Abschnitt **Funktionen** (Deutsch) aufgeführt sein.
   - Jede neue Benutzeraktion muss im Abschnitt **Benutzung** (Deutsch **und** Englisch) beschrieben sein.
   - Jede neue Einstellung muss im Abschnitt **Einstellungen** (Deutsch) ergänzt werden.
   - Veraltete Formulierungen (z. B. „jetzt", „neu") sind beim Bearbeiten zu entfernen.
   - Keine inhaltlichen Dopplungen zwischen den Abschnitten – jeder Punkt gehört genau an eine Stelle.
   - Nach jeder Änderung alle Abschnitte (Funktionen, Benutzung DE/EN, Einstellungen, Architektur) auf Vollständigkeit und Konsistenz prüfen.
2. **CHANGELOG.md** ergänzen/aktualisieren mit einem passenden Eintrag zur Änderung. Dabei gelten folgende Regeln:
   - Sprache: Englisch.
   - Versionsnummer aus `gradle.properties` übernehmen.
   - Struktur: pro Version **genau einen** `### Added`-, `### Changed`- und `### Fixed`-Block – niemals doppelte Kategorien innerhalb derselben Version.
   - Neue Einträge immer in den bestehenden Block der aktuellen Version einsortieren, nicht als neuen Block anhängen.
   - Jeder Eintrag ist ein prägnanter Satz (ein Gedanke pro Zeile); keine Redundanzen, keine Dopplungen.
   - Nach dem Hinzufügen die gesamte Version überfliegen und sicherstellen, dass keine Kategorie doppelt vorkommt und alle Einträge thematisch korrekt zugeordnet sind.
3. **FEATURES.md** ergänzen/aktualisieren, wenn neue Funktionen hinzugefügt oder bestehende Funktionen geändert werden. Sprache: Englisch. Dabei gelten folgende Regeln:
   - Jede Funktion wird als ein prägnanter Bullet-Point beschrieben – ein Gedanke pro Zeile.
   - Neue Features werden als neuer Bullet-Point ergänzt; geänderte Features werden im bestehenden Bullet-Point aktualisiert – keine Dopplungen.
   - Veraltete oder entfernte Funktionen werden gelöscht.
   - Nach jeder Änderung die gesamte Liste auf Dopplungen und Lücken im Vergleich zur aktuellen Implementierung prüfen.
4. **src/main/resources/META-INF/plugin.xml** – die Sektion `<description>` aktualisieren, wenn neue Funktionen hinzugefügt oder bestehende Funktionen geändert werden. Dabei gelten folgende Regeln:
   - Sprache: Englisch. Die Description wird auf dem JetBrains Marketplace und im IDE Plugin Manager angezeigt – sie muss für Endanwender verständlich formuliert sein.
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
   - Der Abschnitt **`## Third-party licenses`** in der `README.md` ist entsprechend zu ergänzen oder zu aktualisieren: jede eingebettete Bibliothek mit Name, Version, Lizenz (inkl. Link) und Verwendungszweck.
   - Veraltete oder entfernte Abhängigkeiten sind aus dem Abschnitt zu entfernen.
10. **kein git commit** ausführen.

Diese Vorgaben gelten standardmäßig für alle Copilot-Änderungen in diesem Projekt.
