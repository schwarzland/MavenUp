package de.schwarzland.mavenup.ui

import com.intellij.openapi.vfs.VirtualFile

/**
 * Repräsentiert eine einzelne Zeile in der Abhängigkeitstabelle.
 *
 * @property groupId Die GroupId der Abhängigkeit.
 * @property artifactId Die ArtifactId der Abhängigkeit.
 * @property propertyName Der Name der Version-Property oder ein leerer String.
 * @property type Der Typ der Abhängigkeit (z. B. Dependency, Parent, managed plugin).
 * @property currentVersion Die aktuell im Projekt verwendete Version.
 * @property versionInherited `true`, wenn die `pom.xml` für diesen Eintrag kein eigenes
 * `<version>`-Tag deklariert und die Version daher vom Parent-POM oder einem importierten
 * BOM geerbt wird.
 * @property pomFile Die konkrete `pom.xml`-Datei (`VirtualFile`), aus der dieser Eintrag stammt, oder `null`.
 * @property moduleName Der Name des Maven-Moduls, aus dem dieser Eintrag stammt, oder ein leerer String.
 */
internal data class RefreshRow @JvmOverloads constructor(
    val groupId: String,
    val artifactId: String,
    val propertyName: String,
    val type: String,
    val currentVersion: String,
    val versionInherited: Boolean = false,
    val pomFile: VirtualFile? = null,
    val moduleName: String = ""
) {
    /** Eindeutiger Schlüssel der Zeile in der Form `groupId:artifactId`. */
    val key: String = "$groupId:$artifactId"

    /** Eindeutiger zeilen- und modulspezifischer Schlüssel unter Berücksichtigung von POM-Pfad und Typ. */
    val entryKey: String
        get() = if (pomFile != null) "${pomFile.path}|$type|$key" else key
}

/**
 * Enthält einen Schnappschuss aller relevanten Projektdaten für die Anzeige im Tool Window.
 *
 * @property rows Die darzustellenden Abhängigkeitszeilen.
 * @property dependencyProperties Zuordnung von Abhängigkeitsschlüssel zu Property-Name.
 */
internal data class RefreshSnapshot(
    val rows: List<RefreshRow>,
    val dependencyProperties: Map<String, String>
)
