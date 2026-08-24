package de.schwarzland.mavenup.ui

import de.schwarzland.mavenup.service.VersionAutoSelectionMode
import org.apache.maven.artifact.versioning.ComparableVersion

/**
 * Extrahiert die führende numerische Major-Version aus einem Versionsstring.
 *
 * Beispiele: `2.7.18` -> `2`, `1-RC1` -> `1`. Bei nicht-numerischem Präfix wird `null` geliefert.
 *
 * @param version Der zu untersuchende Versionsstring.
 * @return Die führende Major-Zahl oder `null`, wenn keine vorhanden ist.
 */
internal fun extractLeadingMajorNumber(version: String): Int? {
    val match = Regex("""^(\d+)""").find(version.trim()) ?: return null
    return match.groupValues[1].toIntOrNull()
}

/**
 * Sucht die höchste verfügbare Version mit derselben numerischen Major-Version wie [currentVersion].
 *
 * @param currentVersion Die aktuell verwendete Version.
 * @param versions Die verfügbaren Versionen.
 * @return Die gefundene Version oder `null`, wenn die aktuelle Version keine numerische Major-Version
 * besitzt oder keine passende Kandidaten-Version vorhanden ist.
 */
internal fun latestVersionWithinSameMajor(currentVersion: String, versions: List<String>): String? {
    val currentMajor = extractLeadingMajorNumber(currentVersion) ?: return null
    return versions
        .sortedWith { v1, v2 -> ComparableVersion(v2).compareTo(ComparableVersion(v1)) }
        .firstOrNull { extractLeadingMajorNumber(it) == currentMajor }
}

/**
 * Ermittelt die automatisch vorausgewählte Zielversion für eine Abhängigkeit.
 *
 * Die neueste Version ist das erste Element von [versions]; die Liste wird von
 * [de.schwarzland.mavenup.service.DependencyApiService.fetchVersions] so aufgebaut, dass die vom
 * Repository deklarierte neueste Version (`<release>`/`<latest>`) vorne steht.
 *
 * Bei [VersionAutoSelectionMode.LATEST_MINOR] wird die höchste Version innerhalb derselben
 * Major-Linie wie [currentVersion] verwendet. Existiert keine passende Version derselben
 * Major-Linie, bleibt die aktuelle Version erhalten (keine Fremd-Major-Vorauswahl).
 *
 * @param currentVersion Die aktuell verwendete Version.
 * @param versions Die verfügbaren Versionen (neueste zuerst).
 * @param mode Die konfigurierte Auto-Selektionsstrategie.
 * @return Die vorauszuwählende Version.
 */
internal fun chooseAutoSelectedVersion(
    currentVersion: String,
    versions: List<String>,
    mode: VersionAutoSelectionMode
): String {
    val newestVersion = versions.firstOrNull().orEmpty()
    if (newestVersion.isEmpty() || newestVersion == currentVersion) return currentVersion
    return when (mode) {
        VersionAutoSelectionMode.DISABLED -> currentVersion
        VersionAutoSelectionMode.LATEST -> newestVersion
        VersionAutoSelectionMode.LATEST_MINOR ->
            latestVersionWithinSameMajor(currentVersion, versions) ?: currentVersion
    }
}
