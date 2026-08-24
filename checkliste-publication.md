Für die Publikation des Plugins wurden bereits wichtige Vorbereitungen getroffen. Hier ist eine Zusammenfassung der erledigten Aufgaben sowie eine Checkliste für die finalen Schritte im JetBrains Marketplace.

### ✅ Erledigte Aufgaben (Vorbereitung)
- **Versionsnummer aktualisiert**: Die Version wurde in der `gradle.properties` von `1.0.0-SNAPSHOT` auf `1.0.0` gesetzt.
- **Changelog befüllt**: Die Datei `CHANGELOG.md` wurde mit den Features des initialen Release ergänzt.
- **Plugin-Beschreibung**: Die `plugin.xml` enthält nun eine detaillierte Beschreibung aller Kernfunktionen (Update-Checks, Property-Support, Navigation etc.).
- **Technische Validierung**: Die Plugin-Struktur und die Projektkonfiguration werden über `verifyPlugin` geprüft; zusätzlich validiert `runPluginVerifier` die Kompatibilität mit den unterstützten IntelliJ-IDE-Builds.
- **CI-Schutz**: Alle Workflow-Jobs sind auf 30 Minuten begrenzt. Der Marketplace-Publish besitzt eine Concurrency-Gruppe gegen parallele Doppel-Uploads; bei einem fehlgeschlagenen Plugin-Verifier wird der Report sieben Tage als Artifact aufbewahrt.
- **Icon**: Ein Plugin-Icon (`pluginIcon.svg`) ist bereits im Projekt vorhanden.

### 📋 Checkliste für die Publikation (JetBrains Marketplace)
Um das Plugin nun offiziell zu veröffentlichen, sind folgende Schritte erforderlich:

1.  **Plugin-Archiv erstellen**:
    Führen Sie den Befehl `./gradlew buildPlugin` aus. Das fertige ZIP-Archiv finden Sie anschließend unter `build/distributions/MavenUp-1.0.0.zip`.
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
