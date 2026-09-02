# Junie Guidelines

Für dieses Projekt gelten zusätzliche Anweisungen, die ursprünglich für GitHub Copilot erstellt wurden. Junie muss diese Anweisungen ebenfalls strikt befolgen.

## Referenzen

- [.github/copilot-instructions.md](../.github/copilot-instructions.md): Verbindliche Arbeitsanweisungen für Änderungen an README, CHANGELOG, FEATURES, plugin.xml, Unittests, KDoc, etc.
- [.github/copilot-project-context.md](../.github/copilot-project-context.md): Projektkontext-Übersicht, Paketstruktur und Verweise auf die Komponentenreferenzen.
- [.github/context/components-ui.md](../.github/context/components-ui.md) und [.github/context/components-service.md](../.github/context/components-service.md): ausführliche Komponentenbeschreibungen je Package.

## Wichtige Regeln (Zusammenfassung)

1. **README.md/CHANGELOG.md/FEATURES.md** bei Änderungen immer aktuell halten.
2. **plugin.xml** Description aktuell halten.
3. **Hohe Testabdeckung**: Jede neue/geänderte Logik benötigt Tests.
4. **KDoc**: Alle berührten Klassen/Methoden müssen korrektes KDoc auf Deutsch haben.
5. **Kein git commit**: Junie soll keine Commits selbstständig ausführen.
