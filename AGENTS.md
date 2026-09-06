# MavenUp – Agentenanweisungen

Vor jeder Analyse, Änderung, Testausführung oder Dokumentationsarbeit in diesem
Repository müssen diese beiden Dateien gelesen und berücksichtigt werden:

- `.github/copilot-instructions.md`
- `.github/copilot-project-context.md`

Beide Dateien sind verbindliche Repository-Instruktionen. Der Projektkontext
verweist zusätzlich auf die ausführlichen Komponentenbeschreibungen unter
`.github/context/` (`components-ui.md`, `components-ui-toolwindow.md`,
`components-ui-dialogs.md`, `components-service.md`), die bei
Arbeiten am jeweiligen Package heranzuziehen und zu pflegen sind.
Unter `.github/agents/` liegen zusätzlich Definitionen spezialisierter Agenten
(z. B. `release-doc-check.md` für die Release-Dokumentationsprüfung); sie gelten
nur in ihrem jeweiligen Einsatzkontext und sind bei strukturellen Änderungen
gemäß `.github/copilot-instructions.md` mitzupflegen.
Bei Widersprüchen
gelten die jeweils höherrangigen System- und Benutzeranweisungen; ansonsten
sind die dort beschriebenen Arbeits-, Dokumentations-, Test- und
Prozessvorgaben vollständig einzuhalten.
