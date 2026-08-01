# Copilot-Arbeitsanweisung (verbindlich)

Bei **jeder** Änderung im Repository sind diese Punkte immer zu berücksichtigen:

1. **README.md** ergänzen/aktualisieren, wenn sich Verhalten, Nutzung, Konfiguration oder Architektur ändert.
2. **CHANGELOG.md** ergänzen/aktualisieren mit einem passenden Eintrag zur Änderung. Dabei gelten folgende Regeln:
   - Sprache: Englisch.
   - Versionsnummer aus `gradle.properties` übernehmen.
   - Struktur: pro Version **genau einen** `### Added`-, `### Changed`- und `### Fixed`-Block – niemals doppelte Kategorien innerhalb derselben Version.
   - Neue Einträge immer in den bestehenden Block der aktuellen Version einsortieren, nicht als neuen Block anhängen.
   - Jeder Eintrag ist ein prägnanter Satz (ein Gedanke pro Zeile); keine Redundanzen, keine Dopplungen.
   - Nach dem Hinzufügen die gesamte Version überfliegen und sicherstellen, dass keine Kategorie doppelt vorkommt und alle Einträge thematisch korrekt zugeordnet sind.
3. **FEATURES.md** ergänzen/aktualisieren, wenn neue Funktionen hinzugefügt oder bestehende Funktionen geändert werden. Sprache: Englisch.
4. **src/main/resources/META-INF/plugin.xml** ergänze/aktualisiere die Sektion <description> in einer kompakten Form, wenn neue Funktionen hinzugefügt oder bestehende Funktionen geändert werden. Sprache: Englisch.
5. **Unittest** ergänzen/aktualisieren, wenn sich Verhalten, Nutzung, Konfiguration oder Architektur ändert.
6. **.github/copilot-project-context.md** ergänzen/aktualisieren, wenn sich Verhalten, Nutzung, Konfiguration oder Architektur ändert.
7. **Methoden und Funktionen** der Klassen ergänzen/aktualisieren und dokumentieren, wenn sich Verhalten, Nutzung, Konfiguration oder Architektur ändert.
8. **KDoc-Qualität prüfen und sicherstellen** – bei jeder Änderung an einer Klasse oder Methode sind **alle** berührten Klassen und Methoden auf vollständige und korrekte KDoc zu prüfen. Konkret:
   - Jede Klasse, jedes `object`, jedes `enum` und jede `data class` muss einen KDoc-Kommentar haben.
   - Jede `public`, `internal` und `private` Funktion oder Methode muss mit KDoc dokumentiert sein (Ausnahme: triviale Getter/Setter ohne eigene Logik).
   - KDoc muss auf Deutsch verfasst sein und die tatsächliche Implementierung korrekt beschreiben.
   - Tippfehler, veraltete Beschreibungen und sprachliche Fehler (z. B. englische Verbformen wie „Parsed" statt „Parst") sind zu korrigieren.
   - Parameter (`@param`), Rückgabewerte (`@return`) und Ausnahmen (`@throws`) sind zu dokumentieren, wenn sie nicht selbsterklärend sind.
9. **kein git commit** ausführen.

Diese Vorgaben gelten standardmäßig für alle Copilot-Änderungen in diesem Projekt.
