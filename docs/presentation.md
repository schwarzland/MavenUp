# MavenUp – Präsentationsleitfaden (20 Minuten, Deutsch)

> Hinweis: Diese Datei ist bewusst auf Deutsch verfasst, da sie als Sprechleitfaden für eine
> deutschsprachige Entwicklerdemo dient. Alle inhaltlichen Details stammen aus
> [FEATURES.md](../FEATURES.md), [docs/usage.md](usage.md), [docs/configuration.md](configuration.md),
> [docs/privacy-and-security.md](privacy-and-security.md) und [docs/architecture.md](architecture.md).

## Zielgruppe und Ziel

- **Zielgruppe**: Java-/Kotlin-Entwicklerinnen und -Entwickler, die täglich mit Maven-Projekten in IntelliJ arbeiten.
- **Ziel der 20 Minuten**: Die Zuhörer sollen danach wissen, *wann* sie MavenUp öffnen, *wie* sie damit Versionen aktualisieren und Sicherheitslücken schließen und *wo* sie es konfigurieren.
- **Kernbotschaft (roter Faden)**: „Vom `pom.xml`-Blindflug zur sicheren, aktuellen Abhängigkeitsbasis – in einem Tool-Window, ohne die IDE zu verlassen."
- **Format**: Live-Demo an einem echten Maven-Projekt, Folien nur als Rahmen (max. 5–6 Folien).

## Vorbereitung (vor dem Termin)

- Ein Demo-Projekt mit **bewusst veralteten** Abhängigkeiten und **mindestens einer bekannten CVE** (auch transitiv) vorbereiten.
- Projekt einmal komplett importieren und einen Probelauf machen, damit Netzwerkabfragen im Cache/schnell sind.
- Optional: OSS-Index-Token hinterlegen, um die Zwei-Quellen-Anreicherung zeigen zu können.
- `pom.xml` in einem zweiten Editor-Tab offen halten (für den Navigations- und Update-Beweis).
- IDE-Schriftgröße/Zoom erhöhen; Tool-Window-Toolbar auf Text-Labels lassen (Default), damit Aktionen benennbar sind.
- Backup-Screenshots für den Fall fehlender Netzwerkverbindung bereithalten.

---

## Ankündigungstext für die Agenda

Zum Kopieren in Agenda, Kalendereinladung oder Einladungsmail.

### Kurzfassung (Agenda-Zeile, 1 Satz)

> **MavenUp – Maven-Abhängigkeiten aktuell und sicher halten, direkt in IntelliJ (20 Min., Live-Demo)**

### Mittlere Fassung (Agenda-Eintrag)

> **MavenUp – Dependency-Updates und CVE-Checks direkt in IntelliJ** (20 Min.)
> Veraltete und verwundbare Maven-Abhängigkeiten fallen meist erst im CI-Scan auf – oder gar nicht.
> In einer Live-Demo zeige ich das IntelliJ-Plugin MavenUp: alle Dependencies, Plugins und Parent-POMs
> in einer Tabelle, Versionssuche gegen Maven Central und private Repositories, Sicherheits-Scan über
> OSV.dev und Sonatype OSS Index – inklusive transitiver CVEs und deren gezielter Behebung per
> `dependencyManagement`. Anschließend Zeit für Fragen.

### Langfassung (Einladungsmail)

> **Titel:** MavenUp – Maven-Abhängigkeiten aktuell und sicher halten, direkt in IntelliJ
> **Dauer:** 20 Minuten inkl. Fragen · **Format:** Live-Demo an einem echten Maven-Projekt
> **Zielgruppe:** Alle, die mit Maven-Projekten in IntelliJ arbeiten
>
> Dependency-Pflege ist im Alltag mühsam: `pom.xml` öffnen, Versionen nachschlagen, manuell eintragen –
> und transitive Abhängigkeiten sieht man dabei überhaupt nicht, obwohl dort viele Sicherheitslücken stecken.
>
> Ich stelle euch MavenUp vor, ein IntelliJ-Plugin speziell für Maven-Projekte. Themen der Session:
>
> - Überblick über alle Dependencies, Plugins, Parent-POMs und Managed-Einträge in einem Tool-Window
> - Versionssuche gegen Maven Central und private Repositories, inkl. property-basierter Versionen
> - Sicheres Aktualisieren mit Bestätigungsdialog und automatischem Maven-Sync
> - Schwachstellen-Scan über OSV.dev und optional den Sonatype OSS Index
> - Transitive CVEs sichtbar machen und gezielt per `dependencyManagement` schließen
> - Konfiguration und Datenschutz: Es werden ausschließlich Maven-Koordinaten übertragen
>
> Vorkenntnisse sind nicht nötig, eine Installation vorab ebenfalls nicht.

---



| Akt | Thema | Zeit | Kernaussage |
|-----|-------|------|-------------|
| 1 | Problem & Einstieg | 2 min | Abhängigkeiten veralten und werden verwundbar – meist unbemerkt. |
| 2 | Überblick verschaffen | 4 min | Ein Tool-Window zeigt alles Deklarierte, inkl. Parent und Managed Scopes. |
| 3 | Versionen aktualisieren | 5 min | Von der Versionssuche bis zum sicheren Schreiben in die `pom.xml`. |
| 4 | Sicherheitslücken finden & schließen | 6 min | Direkte *und* transitive CVEs sichtbar machen und gezielt pinnen. |
| 5 | Konfiguration, Datenschutz & Ausblick | 3 min | Alles einstellbar, nichts Vertrauliches verlässt den Rechner. |

---

## Akt 1 – Problem & Einstieg (2 min)

**Erzählstrang**: Kurz beschreiben, wie Dependency-Pflege heute typischerweise abläuft.

Mögliche Inhalte:

- Der Alltag: `pom.xml` öffnen, Version googeln, auf `mvnrepository.com` nachschauen, manuell eintippen.
- Transitive Abhängigkeiten sieht man dabei gar nicht – genau dort stecken aber viele CVEs.
- Ohne Tooling gibt es keine Antwort auf: „Welche unserer Abhängigkeiten ist gerade verwundbar und auf welche Version muss ich?"
- Überleitung: MavenUp ist ein IntelliJ-Plugin speziell für Maven-Projekte, das genau diese drei Fragen im Editor beantwortet.

**Folie**: eine Zeile Positionierung + drei Stichworte *Überblick – Update – Sicherheit*.

---

## Akt 2 – Überblick verschaffen (4 min)

**Erzählstrang**: Tool-Window öffnen und zeigen, dass alles Relevante bereits da ist.

Mögliche Inhalte:

- **Tool-Window öffnen**: erscheint nur für Maven-Projekte, mit eigenem Icon (Light-/Dark-Variante).
- **Tabelle erklären**: Dependencies, Plugins, Parent-POM sowie `dependencyManagement`- und `pluginManagement`-Einträge in einer Tabelle, mit Type-Spalte.
- **Automatik betonen**: Beim Laden der Projektdaten und nach jedem Maven-Import läuft „Refresh and Search for New Versions" automatisch – die Spalte **New Version** ist sofort gefüllt.
- **Navigation demonstrieren**: Klick (bzw. Doppelklick, je nach Einstellung) auf eine Zeile springt an die exakte Stelle in der `pom.xml`.
- **Kontextmenü kurz aufklappen**: Filter by „…", Navigate to pom.xml, Open on MVN Repository / Sonatype Central, Versions-Shortcuts, Vulnerability Details – Einträge bleiben stets sichtbar, nur enabled/disabled ändert sich.
- **Filterzeile zeigen**: Textfilter über GroupId/ArtifactId/Property plus vier Combo-Boxen (Type, Updates, Pending, Vulnerabilities) und ein Reset-Button.
- **Sortierung**: Spaltenköpfe schalten aufsteigend → absteigend → Original-Reihenfolge der `pom.xml`.

**Demo-Tipp**: Erst filtern („nur Update available"), dann sortieren – das macht den Nutzen sofort greifbar.

---

## Akt 3 – Versionen aktualisieren (5 min)

**Erzählstrang**: Von „ich sehe, was alt ist" zu „ich habe es sicher geändert".

Mögliche Inhalte:

- **Versionsherkunft**: Verfügbare Versionen kommen aus `maven-metadata.xml` von Maven Central und – bei Bedarf – aus privaten Repositories; Zugangsdaten stammen aus der Maven-`settings.xml`.
  - *Kurzerklärung für die Zuhörer*: Die `maven-metadata.xml` ist eine kleine Index-Datei, die jedes Maven-Repository pro Artefakt (`groupId`/`artifactId`) bereitstellt. Sie listet alle dort veröffentlichten Versionen auf und benennt in `<release>` bzw. `<latest>` die aktuelle Release- und die neueste Version. MavenUp liest genau diese Datei aus – deshalb sind die angebotenen Versionen exakt die, die das Repository wirklich kennt, und nicht geraten oder gecached.
- **Version wählen**: Dropdown in der Spalte **New Version**; die aktuelle Version ist mit *(current)*, die empfohlene Fix-Version mit *(recommended)* fett markiert.
- **Statusanzeige**: „✓" für die höchste bekannte Version, „↑" sonst; farbliche Hervorhebung (grün/orange, fett) für ausstehende Änderungen.
- **Auto-Auswahl-Strategie**: aus / höchste Version / höchste Minor-Version innerhalb der aktuellen Major-Linie – letzteres als „risikoarme" Default-Empfehlung erklären.
- **Massenaktionen**: Dropdown **Select Highest Version** mit *Highest Major*, *Highest Minor* und *Recommended*, dazu **Reset All to Current Versions**; alle Aktionen respektieren den aktiven Filter.
- **Properties**: Versionen wie `${spring.version}` werden erkannt – MavenUp ändert die Property, nicht die Referenz, und synchronisiert alle Einträge, die dieselbe Property nutzen. Das ist ein starkes Demo-Argument.
- **Update ausführen**: Bestätigungsdialog mit Übersichtstabelle aller anstehenden Änderungen plus Checkbox **Sync Maven Changes after update**.
- **Beweis antreten**: Anschließend die geänderte `pom.xml` im Editor zeigen.

**Demo-Tipp**: Bewusst eine Property-basierte Version aktualisieren – der Aha-Effekt ist am größten.

---

## Akt 4 – Sicherheitslücken finden und schließen (6 min, Höhepunkt)

**Erzählstrang**: Das ist der Teil, den die Zuhörer mitnehmen sollen – hier großzügig Zeit lassen.

Mögliche Inhalte:

- **Scan starten**: Aktion **Scan for Vulnerabilities**; Quelle ist [OSV.dev](https://osv.dev), optional angereichert durch den [Sonatype OSS Index](https://ossindex.sonatype.org/); Dubletten werden über CVE-/GHSA-/OSV-IDs und Aliase zusammengeführt.
- **Spalte Vulnerabilities**: Anzahl der Findings, Anteil transitiver Findings und höchster Schweregrad – farbcodiert und sortierbar nach Kritikalität.
- **Detaildialog**: Master-Detail-Ansicht mit Component, **Origin** (`direct`, `transitive`, `transitive, also declared directly`), Quelle, Advisory-ID, Aliassen und Severity; unten CVSS-Vektor, CWE-IDs, Datumsangaben, betroffene Versionsbereiche, Fixed-in-Versionen, Beschreibung und klickbare Referenzen.
- **Tab „Transitive CVEs"**: der eigentliche Mehrwert – jede verwundbare transitive Koordinate mit Anzahl, Schweregrad und eigener Filterzeile; der Tab-Titel zeigt die Anzahl betroffener Koordinaten.
- **Fix demonstrieren**: In diesem Tab eine Version wählen → MavenUp pinnt die Koordinate in `<dependencyManagement>` und legt den Eintrag bei Bedarf an.
- **Erklärender Kommentar**: Der neue Eintrag bekommt automatisch einen XML-Kommentar mit den behobenen Advisory-IDs und dem Hinweis auf MavenUp – Umfang, Text und Anzahl der IDs sind konfigurierbar.
- **Empfohlene Fix-Version erklären**: niedrigste Version, die *alle* Findings der Komponente behebt; existiert keine, die Version, die die meisten behebt.
- **Abschluss des Akts**: **Update** ausführen und den erzeugten `dependencyManagement`-Block inklusive Kommentar in der `pom.xml` zeigen.

**Demo-Tipp**: Vorher eine transitive CVE aussuchen, deren Fix-Version wirklich existiert – sonst verpufft der stärkste Moment.

---

## Akt 5 – Konfiguration, Datenschutz und Ausblick (3 min)

**Erzählstrang**: Bedenken ausräumen und den Weg zur eigenen Nutzung ebnen.

Mögliche Inhalte:

- **Einstellungen** unter `Settings > Tools > MavenUp`, gruppiert in *Appearance and Behavior*, *Versions & Updates*, *Vulnerability Check* und *Pom.xml Changes*; global für alle Projekte. Details siehe [configuration.md](configuration.md).
- Highlights nennen: Klickverhalten, automatische Versionssuche, Auto-Auswahl-Strategie, Filtern instabiler Qualifier (`rc,beta,milestone`), Central-First-Strategie, Repository-Browser (MVN Repository / Sonatype Central), Toolbar-Stil.
- **Datenschutz**: Es werden ausschließlich Maven-Koordinaten (groupId, artifactId, version) über HTTPS übertragen – kein Quellcode, keine Pfade, keine Projektdaten. Der OSS-Index-Token liegt im IntelliJ Password Safe. Siehe [privacy-and-security.md](privacy-and-security.md).
- **Betrieb**: Alle langen Operationen laufen im Hintergrund; das Plugin nutzt nur dynamische Extension Points und lässt sich ohne IDE-Neustart installieren und aktualisieren.
- **Architektur in einem Satz** (falls technisch interessiertes Publikum): saubere Trennung in `model`, `service` und `ui`; externe API-Zugriffe ausschließlich in der Service-Schicht – siehe [architecture.md](architecture.md).
- **Call to Action**: Installation aus dem JetBrains Marketplace, Einstieg über [getting_started.html](../getting_started.html) bzw. den [Usage Guide](usage.md).

---

## Zeitplan-Kurzfassung für den Spickzettel

| Minute | Was passiert |
|--------|--------------|
| 0–2 | Problem, Positionierung, Agenda |
| 2–6 | Tool-Window, Tabelle, Navigation, Filter, Sortierung |
| 6–11 | Versionssuche, Auswahl, Properties, Massenaktionen, Update mit Bestätigungsdialog |
| 11–17 | Vulnerability-Scan, Detaildialog, Tab „Transitive CVEs", Pinning mit Kommentar |
| 17–20 | Einstellungen, Datenschutz, Call to Action, Fragen |

**Puffer-Strategie**: Läuft die Zeit knapp, entfallen zuerst Sortierung/Spaltenbreiten (Akt 2) und die Architektur-Folie (Akt 5). Der Vulnerability-Teil wird nie gekürzt.

## Häufige Fragen (Backup-Material)

- *Funktioniert das mit privaten Repositories?* Ja – Credentials kommen aus der Maven-`settings.xml`, inklusive Auflösung von `${env.VAR}`-Platzhaltern und Fallback auf `${user.home}/.m2/settings.xml`.
- *Werden Downgrades unterstützt?* Ja, über die Einstellung „Offer all versions".
- *Was passiert mit Multi-Module-Projekten?* Alle `pom.xml`-Dateien des Projekts werden eingelesen, inklusive Parent-Einträgen.
- *Ersetzt das den CI-Security-Scan?* Nein – es verlagert die Erkennung nach vorn in die IDE, ergänzend zur Pipeline.
- *Wie aktuell sind die Daten?* Sie stammen live von OSV.dev und optional vom Sonatype OSS Index, nicht aus einer eingebetteten Datenbank.
