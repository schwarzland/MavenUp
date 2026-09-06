# Junie Guidelines

Für dieses Projekt gelten zusätzliche Anweisungen, die ursprünglich für GitHub Copilot erstellt wurden. Junie muss diese Anweisungen ebenfalls strikt befolgen.

## Referenzen

- [AGENTS.md](../AGENTS.md): herstellerübergreifender Einstiegspunkt, der auf die verbindlichen Repository-Instruktionen verweist.
- [.github/copilot-instructions.md](../.github/copilot-instructions.md): Verbindliche Arbeitsanweisungen für Änderungen an README, CHANGELOG, FEATURES, plugin.xml, Unittests, KDoc, etc.
- [.github/copilot-project-context.md](../.github/copilot-project-context.md): Projektkontext-Übersicht, Paketstruktur und Verweise auf die Komponentenreferenzen.
- [.github/context/components-ui.md](../.github/context/components-ui.md), [.github/context/components-ui-toolwindow.md](../.github/context/components-ui-toolwindow.md), [.github/context/components-ui-dialogs.md](../.github/context/components-ui-dialogs.md) und [.github/context/components-service.md](../.github/context/components-service.md): ausführliche Komponentenbeschreibungen je Package.
- [.github/agents/](../.github/agents/): Definitionen spezialisierter Agenten (z. B. [release-doc-check.md](../.github/agents/release-doc-check.md)), die nur in ihrem jeweiligen Einsatzkontext gelten; die Junie-Pendants liegen unter [.junie/agents/](agents/).

## Wichtige Regeln (Zusammenfassung)

Maßgeblich ist immer der Volltext in `.github/copilot-instructions.md`; diese Liste ist nur eine Kurzfassung.

1. **README.md** schlank halten (Landing Page auf Englisch) und die Dokumentationsliste sowie den Abschnitt *AI instructions* aktuell halten.
2. **CHANGELOG.md** auf Englisch pflegen: auf einem Feature-Branch im Block `## [Unreleased]`, pro Version genau ein `### Added`-, `### Changed`- und `### Fixed`-Block.
3. **FEATURES.md** ist nur der Index; die Feature-Beschreibungen stehen in der thematisch passenden Datei unter `docs/features/`.
4. **Dokumentation unter `docs/`**: jede Änderung gehört in genau eine Datei (`usage.md`, `configuration.md`, `privacy-and-security.md`, `architecture.md`, `development.md`, `release-and-ci.md`, `licenses.md`) – keine Dopplungen.
5. **plugin.xml** Description aktuell halten (Core Features vor Advanced Capabilities).
6. **getting_started.html** aktualisieren, wenn sich die Bedienung für Einsteiger ändert.
7. **Hohe Testabdeckung**: Jede neue/geänderte Logik benötigt Tests; keine Reflection auf private Methoden.
8. **KDoc**: Alle berührten Klassen/Methoden müssen korrektes KDoc auf Deutsch haben.
9. **Projektkontext** (`.github/copilot-project-context.md` und `.github/context/`) bei neuen, umbenannten oder entfernten Klassen und bei jeder neuen Einstellung mitpflegen.
10. **Lizenzen**: Neue oder aktualisierte Abhängigkeiten im `implementation`-Scope in `docs/licenses.md` dokumentieren.
11. **Refactoring**: Dateien über 800–1000 Zeilen aufteilen – vorher das Risiko benennen und den Anwender fragen.
12. **UI**: Dialoge und Einstellungen folgen den IntelliJ Platform UI Guidelines (Kotlin UI DSL v2, Bindings, Unterseiten statt Scrollen, Read-only-Dialoge nur mit *Close*).
13. **Diese Dateien unter `.junie/`** enthalten keine eigenständigen Regeln: Ändern sich die referenzierten Instruktions-, Kontext- oder Agentendateien, sind `guidelines.md` und `agents/` anzugleichen.
14. **Kein git commit**: Junie soll keine Commits selbstständig ausführen.
