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
- **Screenshots**: Für die Marketplace-Seite sollten Sie 2–3 Screenshots der Tool-Window-Tabelle und des Update-Dialogs erstellen, um die Benutzung zu veranschaulichen.
- **Zukünftige Updates**: Bei weiteren Änderungen sollten Sie die Version in `gradle.properties` erhöhen und den neuen Eintrag im `CHANGELOG.md` unter `## [Unreleased]` pflegen, bevor Sie das `patchChangelog`-Task nutzen.
