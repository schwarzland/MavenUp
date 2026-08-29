package de.schwarzland.mavenup.model

import org.apache.maven.artifact.versioning.ComparableVersion

/**
 * Ein betroffener Versionsbereich einer Sicherheitswarnung.
 *
 * Der Bereich ist unten einschließend und oben ausschließend (`[introduced, fixed)`), entsprechend
 * der `introduced`/`fixed`-Semantik der OSV-Daten.
 *
 * @property introduced Die einführende Version (einschließend) oder `null`, wenn der Bereich am Anfang beginnt.
 * @property fixed Die behebende Version (ausschließend) oder `null`, wenn der Bereich nach oben offen ist.
 */
internal data class AffectedVersionRange(
    val introduced: ComparableVersion?,
    val fixed: ComparableVersion?
) {
    /**
     * Prüft, ob eine Version innerhalb dieses Bereichs liegt und damit als betroffen gilt.
     *
     * @param version Die zu prüfende Version.
     * @return `true`, wenn die Version größer oder gleich [introduced] und echt kleiner als [fixed] ist.
     */
    fun contains(version: ComparableVersion): Boolean =
        (introduced == null || version >= introduced) && (fixed == null || version < fixed)
}

/**
 * Parst die menschlich lesbaren Bereichsbeschreibungen einer Sicherheitswarnung in vergleichbare Bereiche.
 *
 * Unterstützt die von `VulnerabilityApiService` erzeugten Formate `>= x, < y`, `< y` und `>= x`.
 * Nicht interpretierbare Beschreibungen werden übersprungen, damit unbekannte Formate die
 * Versionsempfehlung nicht blockieren.
 *
 * @param ranges Die Bereichsbeschreibungen aus [VulnerabilityAdvisory.affectedRanges].
 * @return Die Liste der interpretierbaren Bereiche.
 */
internal fun parseAffectedVersionRanges(ranges: Collection<String>): List<AffectedVersionRange> =
    ranges.mapNotNull { parseAffectedVersionRange(it) }

/**
 * Parst eine einzelne Bereichsbeschreibung.
 *
 * @param range Die Beschreibung, z. B. `>= 1.0.0, < 1.2.4`.
 * @return Der geparste Bereich oder `null`, wenn die Beschreibung kein bekanntes Format hat oder
 * keine Grenze enthält.
 */
internal fun parseAffectedVersionRange(range: String): AffectedVersionRange? {
    var introduced: ComparableVersion? = null
    var fixed: ComparableVersion? = null
    range.split(",").forEach { part ->
        val trimmed = part.trim()
        when {
            trimmed.startsWith(">=") -> introduced = trimmed.removePrefix(">=").trim()
                .takeIf { it.isNotEmpty() }
                ?.let { ComparableVersion(it) }

            trimmed.startsWith("<") -> fixed = trimmed.removePrefix("<").trim()
                .takeIf { it.isNotEmpty() }
                ?.let { ComparableVersion(it) }
        }
    }
    return if (introduced == null && fixed == null) null else AffectedVersionRange(introduced, fixed)
}

/**
 * Prüft, ob eine Sicherheitswarnung in einer bestimmten Version behoben ist.
 *
 * Sind betroffene Versionsbereiche bekannt, gilt die Version als behoben, wenn sie in keinem dieser
 * Bereiche liegt. Das berücksichtigt insbesondere Warnungen mit mehreren aufeinanderfolgenden
 * Bereichen (z. B. unvollständige Fixes), bei denen die niedrigste genannte Fix-Version selbst noch
 * betroffen ist. Ohne Bereichsangaben wird ersatzweise geprüft, ob eine der bekannten Fix-Versionen
 * kleiner oder gleich der Version ist. Sind weder Bereiche noch Fix-Versionen bekannt, kann die
 * Warnung nicht bewertet werden und blockiert die Empfehlung nicht.
 *
 * @param version Die zu prüfende Version.
 * @return `true`, wenn die Version nach aktuellem Kenntnisstand nicht mehr betroffen ist.
 */
internal fun VulnerabilityAdvisory.isFixedIn(version: ComparableVersion): Boolean {
    val ranges = parseAffectedVersionRanges(affectedRanges)
    if (ranges.isNotEmpty()) return ranges.none { it.contains(version) }
    if (fixedVersions.isEmpty()) return true
    return fixedVersions.any { ComparableVersion(it) <= version }
}
