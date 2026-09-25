package de.schwarzland.mavenup.service

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import java.util.concurrent.ConcurrentHashMap

/**
 * Anwendungsweiter (projektübergreifender) Zwischenspeicher für die von Repositories abgerufenen,
 * ungefilterten Versionslisten je Artefakt (`groupId:artifactId`).
 *
 * Ziel des Zwischenspeichers ist es, wiederholte `maven-metadata.xml`-Abfragen für dasselbe Artefakt
 * innerhalb einer konfigurierbaren Gültigkeitsdauer ([MavenUpSettings.State.versionCacheTtlMinutes])
 * zu vermeiden, z. B. bei mehrfachen manuellen Versionssuchen oder bei der gezielten Versionsabfrage
 * für verwundbare transitive Koordinaten nach einem Sicherheitsscan. Da der Schlüssel keine Version
 * enthält, bleibt der Zwischenspeicher unabhängig von Versionsänderungen in der `pom.xml` gültig, bis
 * die Gültigkeitsdauer abläuft oder er explizit geleert wird (siehe [MavenUpSettingsPage.apply]).
 *
 * Fehlgeschlagene bzw. leere Abfragen werden bewusst nicht zwischengespeichert, damit ein
 * vorübergehender Netzwerk- oder Repository-Fehler nicht für die gesamte Gültigkeitsdauer als
 * „keine Versionen vorhanden" missverstanden wird.
 */
/**
 * Unveränderlicher Diagnose-Schnappschuss eines einzelnen [VersionMetadataCache]-Eintrags, für die
 * Anzeige im Cache-Inhalte-Dialog (siehe `CacheContentsDialog`).
 *
 * @property groupId Die GroupId des Artefakts.
 * @property artifactId Die ArtifactId des Artefakts.
 * @property versionCount Anzahl der zwischengespeicherten Versionen.
 * @property timestampMillis Zeitpunkt der zwischengespeicherten Abfrage in Millisekunden seit der Epoche.
 */
internal data class VersionCacheEntrySnapshot(
    val groupId: String,
    val artifactId: String,
    val versionCount: Int,
    val timestampMillis: Long
)

@Service(Service.Level.APP)
internal class VersionMetadataCache {

    /**
     * Ein einzelner Zwischenspeicher-Eintrag mit den zuletzt abgerufenen Versionen und dem
     * Zeitpunkt der Abfrage.
     */
    private data class CacheEntry(val versions: List<String>, val timestampMillis: Long)

    private val entries = ConcurrentHashMap<String, CacheEntry>()

    /**
     * Liefert die zwischengespeicherten Versionen für das angegebene Artefakt, sofern ein noch
     * gültiger Eintrag vorhanden ist; andernfalls wird [fetch] aufgerufen und das (nicht-leere)
     * Ergebnis bei aktiviertem Zwischenspeicher (`ttlMinutes > 0`) gespeichert.
     *
     * @param groupId Die GroupId des Artefakts.
     * @param artifactId Die ArtifactId des Artefakts.
     * @param ttlMinutes Die konfigurierte Gültigkeitsdauer in Minuten; `<= 0` deaktiviert den Zwischenspeicher.
     * @param nowMillis Der aktuelle Zeitpunkt in Millisekunden (injizierbar für Tests).
     * @param fetch Ruft die Versionen live ab, wenn kein gültiger Eintrag vorhanden ist.
     * @return Die Versionsliste aus dem Zwischenspeicher oder von [fetch].
     */
    internal fun getOrFetch(
        groupId: String,
        artifactId: String,
        ttlMinutes: Int,
        nowMillis: Long = System.currentTimeMillis(),
        fetch: () -> List<String>
    ): List<String> {
        val key = keyOf(groupId, artifactId)
        if (ttlMinutes > 0) {
            val cached = entries[key]
            if (cached != null) {
                if (nowMillis - cached.timestampMillis <= ttlMinutes * MILLIS_PER_MINUTE) {
                    return cached.versions
                }
                entries.remove(key)
            }
        }
        val versions = fetch()
        if (ttlMinutes > 0 && versions.isNotEmpty()) {
            entries[key] = CacheEntry(versions, nowMillis)
        }
        return versions
    }

    /**
     * Entfernt den Zwischenspeicher-Eintrag eines einzelnen Artefakts, z. B. wenn dessen Version
     * gezielt aktualisiert wurde und die zwischengespeicherten Versionen verworfen werden sollen.
     */
    internal fun invalidate(groupId: String, artifactId: String) {
        entries.remove(keyOf(groupId, artifactId))
    }

    /** Leert den gesamten Zwischenspeicher, z. B. nach einer Änderung repository-relevanter Einstellungen. */
    internal fun clear() {
        entries.clear()
    }

    /** Liefert die Anzahl der aktuell zwischengespeicherten Artefakte (für Tests und Diagnose). */
    internal fun size(): Int = entries.size

    /**
     * Liefert einen unveränderlichen Schnappschuss aller aktuell zwischengespeicherten Einträge, z. B.
     * für die Anzeige im Cache-Inhalte-Dialog (siehe `CacheContentsDialog`). Die Reihenfolge ist nicht
     * garantiert und entspricht der internen Iterationsreihenfolge der zugrunde liegenden Map.
     *
     * @return Die Liste aller Einträge als [VersionCacheEntrySnapshot].
     */
    internal fun snapshot(): List<VersionCacheEntrySnapshot> =
        entries.map { (key, entry) ->
            val (groupId, artifactId) = splitKey(key)
            VersionCacheEntrySnapshot(groupId, artifactId, entry.versions.size, entry.timestampMillis)
        }

    private fun keyOf(groupId: String, artifactId: String): String = "$groupId:$artifactId"

    /** Zerlegt den Zwischenspeicher-Schlüssel wieder in GroupId und ArtifactId (siehe [keyOf]). */
    private fun splitKey(key: String): Pair<String, String> {
        val parts = key.split(":", limit = 2)
        return parts[0] to parts.getOrElse(1) { "" }
    }

    internal companion object {
        /** Anzahl der Millisekunden je Minute, zur Umrechnung der konfigurierten Gültigkeitsdauer. */
        private const val MILLIS_PER_MINUTE = 60_000L

        /** Liefert die anwendungsweite Instanz dieses Zwischenspeichers. */
        internal fun getInstance(): VersionMetadataCache =
            ApplicationManager.getApplication().getService(VersionMetadataCache::class.java)
    }
}
