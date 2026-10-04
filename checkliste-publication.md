Für die Publikation des Plugins sind wichtige Vorbereitungen im Repository vorhanden. Diese Checkliste beschreibt die finalen Schritte für den JetBrains Marketplace.

### ✅ Erledigte Aufgaben (Vorbereitung)
- **Version und Changelog**: `gradle.properties` und der oberste veröffentlichungsrelevante Block in `CHANGELOG.md` müssen dieselbe Version enthalten.
- **Plugin-Beschreibung**: Die `plugin.xml` enthält die Marketplace-Beschreibung der aktuellen Kern- und erweiterten Funktionen.
- **Technische Validierung**: Die Plugin-Struktur und die Projektkonfiguration werden über `verifyPlugin` geprüft; dabei wird auch die Kompatibilität mit den unterstützten IntelliJ-IDE-Builds über den IntelliJ Plugin Verifier validiert.
- **CI-Schutz**: Alle Workflow-Jobs sind auf 30 Minuten begrenzt. Der Marketplace-Publish besitzt eine Concurrency-Gruppe gegen parallele Doppel-Uploads; bei einem fehlgeschlagenen Plugin-Verifier wird der Report sieben Tage als Artifact aufbewahrt.
- **Icon**: Ein Plugin-Icon (`pluginIcon.svg` sowie `pluginIcon_dark.svg`) ist bereits im Projekt vorhanden.

### 📋 Checkliste für die Publikation (JetBrains Marketplace)
Um das Plugin nun offiziell zu veröffentlichen, sind folgende Schritte erforderlich:

1.  **Plugin-Archiv erstellen**:
    Führen Sie den Befehl `./gradlew buildPlugin` aus. Das fertige ZIP-Archiv finden Sie anschließend unter `build/distributions/MavenUp-<version>.zip`.
2.  **Marketplace-Account**:
    Falls noch nicht geschehen, erstellen Sie einen Account auf [JetBrains Marketplace](https://plugins.jetbrains.com/) und legen Sie ein Vendor-Profil an.
3.  **Upload**:
    Laden Sie das ZIP-Archiv manuell über das Marketplace-Portal hoch.
4.  **Review-Prozess**:
    Nach dem Upload prüft JetBrains das Plugin manuell (dauert meist 1–3 Werktage).

### 💡 Empfehlungen
- **Dokumentation**: Die `README.md` ist bereits auf einem aktuellen Stand und dient als gute Basis für die Marketplace-Seite.
- **Screenshots & visuelle Dokumentation**: Erstellen und pflegen Sie aussagekräftige Screenshots mit Callouts/Sprechblasen im JetBrains-Design direkt im Repository:
  - **Tooling & Format**: Verwenden Sie das IntelliJ-Plugin *Diagrams.net Integration* (Draw.io) und speichern Sie bearbeitbare Diagramme als `.drawio.svg` (Vektor-SVG mit eingebettetem XML-Modell als Single Source of Truth).
  - **Ordnerstruktur**: Legen Sie Roh-Screenshots unter `docs/assets/marketplace/raw/`, editierbare Quelldateien unter `docs/assets/marketplace/src/` und exportierte PNG-Bilder unter `docs/assets/marketplace/dist/` ab.
  - **Design & Layout**: Screenshots in 2x-Auflösung (HiDPI/Retina, mind. 1280×800 px) anfertigen, 2–4 fokussierte Callouts (nummerierte Badges oder Sprechblasen) setzen und JetBrains-Farben nutzen (Akzent: `#3574F0`, Warnung/CVE: `#F28B25`/`#E55765`, Text: `#FFFFFF`).
  - **Detaillierte Schritt-für-Schritt-Anleitung**: Siehe [Marketplace Screenshots & Visual Assets Guide](docs/assets/marketplace/README.md) sowie die Übersicht in [docs/release-and-ci.md](docs/release-and-ci.md#marketplace-screenshots-and-visual-documentation).
- **Zukünftige Updates**: Bei weiteren Änderungen sollten Sie die Version in `gradle.properties` erhöhen und den neuen Eintrag im `CHANGELOG.md` unter `## [Unreleased]` pflegen, bevor Sie das `patchChangelog`-Task nutzen.
