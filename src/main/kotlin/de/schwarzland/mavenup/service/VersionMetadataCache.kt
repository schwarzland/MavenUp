package de.schwarzland.mavenup.service

import com.google.gson.Gson
import com.google.gson.JsonParseException
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.readText

/**
 * Unveränderlicher Diagnose-Schnappschuss eines einzelnen [VersionMetadataCache]-Eintrags, für die
 * Anzeige im Cache-Inhalte-Dialog (siehe `VersionCacheContentsDialog`).
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

/**
 * Anwendungsweiter Zwischenspeicher für ungefilterte Versionslisten je Artefakt (`groupId:artifactId`).
 * Vermeidet wiederholte Repository-Abfragen innerhalb von [MavenUpSettings.State.versionCacheTtlMinutes],
 * unabhängig von Versionsänderungen in der POM. Leere oder fehlgeschlagene Abfragen werden nicht gespeichert.
 * Treffer, Fehltreffer, Ablauf und deaktiviertes Caching werden je Artefakt auf DEBUG-Ebene protokolliert.
 * Die Daten werden persistent im IDE-Cache-Verzeichnis abgelegt, sodass sie auch über IDE-Neustarts hinweg
 * erhalten bleiben.
 *
 * @property storagePath Der Pfad zur persistenten Cache-Datei auf der Festplatte; `null` deaktiviert die Persistierung.
 */
@Service(Service.Level.APP)
internal class VersionMetadataCache(
    private val storagePath: Path? = defaultStoragePath()
) {

    /**
     * Ein einzelner Zwischenspeicher-Eintrag mit den zuletzt abgerufenen Versionen und dem
     * Zeitpunkt der Abfrage.
     *
     * @property versions Die Liste der abgerufenen Versionen.
     * @property timestampMillis Der Zeitpunkt der Abfrage in Millisekunden seit der Epoche.
     */
    private data class CacheEntry(val versions: List<String>, val timestampMillis: Long)

    /**
     * Datenstruktur für die Serialisierung des Caches auf der Festplatte.
     *
     * @property formatVersion Die Versionsnummer des Dateiformats zur Migrationsunterstützung.
     * @property entries Die Zuordnung von Cache-Schlüsseln zu ihren Cache-Einträgen.
     */
    private data class DiskPayload(
        val formatVersion: Int = 1,
        val entries: Map<String, CacheEntry> = emptyMap()
    )

    private val entries = ConcurrentHashMap<String, CacheEntry>()
    private val saveLock = Any()

    init {
        loadFromDisk()
    }

    /**
     * Lädt zuvor gespeicherte Cache-Einträge von der Festplatte in den Arbeitsspeicher.
     */
    private fun loadFromDisk() {
        val path = storagePath ?: return
        if (!Files.isRegularFile(path)) return
        try {
            val json = path.readText(Charsets.UTF_8)
            val payload = GSON.fromJson(json, DiskPayload::class.java)
            if (payload?.entries != null) {
                payload.entries.forEach { (key, entry) ->
                    if (key.isNotBlank() && entry.versions.isNotEmpty()) {
                        entries[key] = entry
                    }
                }
                LOG.debug("Loaded ${entries.size} version cache entries from disk ($path)")
            }
        } catch (e: IOException) {
            LOG.warn("Failed to load version cache from $path", e)
        } catch (e: JsonParseException) {
            LOG.warn("Failed to parse version cache from $path", e)
        }
    }

    /**
     * Schreibt den aktuellen Cache-Zustand atomar auf die Festplatte.
     */
    private fun saveToDisk() {
        val path = storagePath ?: return
        synchronized(saveLock) {
            try {
                val parent = path.parent
                if (parent != null && !Files.exists(parent)) {
                    Files.createDirectories(parent)
                }
                val payload = DiskPayload(entries = HashMap(entries))
                val json = GSON.toJson(payload)
                val tempFile = Files.createTempFile(parent ?: Path.of("."), "version-cache-", ".tmp")
                try {
                    Files.writeString(tempFile, json, StandardCharsets.UTF_8)
                    try {
                        Files.move(tempFile, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                    } catch (e: AtomicMoveNotSupportedException) {
                        LOG.debug("Atomic move not supported for version cache, falling back to standard replace", e)
                        Files.move(tempFile, path, StandardCopyOption.REPLACE_EXISTING)
                    }
                } finally {
                    Files.deleteIfExists(tempFile)
                }
            } catch (e: IOException) {
                LOG.warn("Failed to persist version cache to $path", e)
            } catch (e: JsonParseException) {
                LOG.warn("Failed to serialize version cache for $path", e)
            }
        }
    }

    /**
     * Liefert die zwischengespeicherten Versionen für das angegebene Artefakt, sofern ein noch
     * gültiger Eintrag vorhanden ist; andernfalls wird [fetch] aufgerufen und das (nicht-leere)
     * Ergebnis bei aktiviertem Zwischenspeicher (`ttlMinutes > 0`) gespeichert.
     *
     * @param groupId Die GroupId des Artefakts.
     * @param artifactId Die ArtifactId des Artefakts.
     * @param ttlMinutes Die konfigurierte Gültigkeitsdauer in Minuten; `<= 0` deaktiviert den Zwischenspeicher.
     * @param nowMillis Der aktuelle Zeitpunkt in Millisekunden (injizierbar für Tests).
     * @param onCacheHit Wird bei einem gültigen Cache-Treffer aufgerufen.
     * @param fetch Ruft die Versionen live ab, wenn kein gültiger Eintrag vorhanden ist.
     * @return Die Versionsliste aus dem Zwischenspeicher oder von [fetch].
     */
    internal fun getOrFetch(
        groupId: String,
        artifactId: String,
        ttlMinutes: Int,
        nowMillis: Long = System.currentTimeMillis(),
        onCacheHit: () -> Unit = {},
        fetch: () -> List<String>
    ): List<String> {
        val key = keyOf(groupId, artifactId)
        if (ttlMinutes > 0) {
            val cached = entries[key]
            if (cached != null) {
                if (nowMillis - cached.timestampMillis <= ttlMinutes * MILLIS_PER_MINUTE) {
                    LOG.debug("Version cache hit for $key: using ${cached.versions.size} cached versions")
                    onCacheHit()
                    return cached.versions
                }
                LOG.debug("Version cache expired for $key: fetching live version metadata")
                entries.remove(key)
                saveToDisk()
            } else {
                LOG.debug("Version cache miss for $key: fetching live version metadata")
            }
        } else {
            LOG.debug("Version cache disabled for $key: fetching live version metadata")
        }
        val versions = fetch()
        if (ttlMinutes > 0 && versions.isNotEmpty()) {
            entries[key] = CacheEntry(versions, nowMillis)
            saveToDisk()
        }
        return versions
    }

    /**
     * Entfernt den Zwischenspeicher-Eintrag eines einzelnen Artefakts, z. B. wenn dessen Version
     * gezielt aktualisiert wurde und die zwischengespeicherten Versionen verworfen werden sollen.
     *
     * @param groupId Die GroupId des zu entfernenden Artefakts.
     * @param artifactId Die ArtifactId des zu entfernenden Artefakts.
     */
    internal fun invalidate(groupId: String, artifactId: String) {
        if (entries.remove(keyOf(groupId, artifactId)) != null) {
            saveToDisk()
        }
    }

    /** Leert den gesamten Zwischenspeicher, z. B. nach einer Änderung repository-relevanter Einstellungen. */
    internal fun clear() {
        entries.clear()
        saveToDisk()
    }

    /**
     * Liefert die Anzahl der aktuell zwischengespeicherten Artefakte (für Tests und Diagnose).
     *
     * @return Die Anzahl der zwischengespeicherten Artefakte.
     */
    internal fun size(): Int = entries.size

    /**
     * Liefert einen unveränderlichen Schnappschuss aller aktuell zwischengespeicherten Einträge, z. B.
     * für die Anzeige im Cache-Inhalte-Dialog (siehe `VersionCacheContentsDialog`). Die Reihenfolge ist nicht
     * garantiert und entspricht der internen Iterationsreihenfolge der zugrunde liegenden Map.
     *
     * @return Die Liste aller Einträge als [VersionCacheEntrySnapshot].
     */
    internal fun snapshot(): List<VersionCacheEntrySnapshot> =
        entries.map { (key, entry) ->
            val (groupId, artifactId) = splitKey(key)
            VersionCacheEntrySnapshot(groupId, artifactId, entry.versions.size, entry.timestampMillis)
        }

    /**
     * Verknüpft GroupId und ArtifactId zum versionsunabhängigen Cache-Schlüssel.
     *
     * @param groupId Die GroupId des Artefakts.
     * @param artifactId Die ArtifactId des Artefakts.
     * @return Der kombinierte Schlüssel.
     */
    private fun keyOf(groupId: String, artifactId: String): String = "$groupId:$artifactId"

    /**
     * Zerlegt den Zwischenspeicher-Schlüssel wieder in GroupId und ArtifactId (siehe [keyOf]).
     *
     * @param key Der kombinierte Cache-Schlüssel.
     * @return Ein Paar aus GroupId und ArtifactId.
     */
    private fun splitKey(key: String): Pair<String, String> {
        val parts = key.split(":", limit = 2)
        return parts[0] to parts.getOrElse(1) { "" }
    }

    /** Zugriff auf den anwendungsweiten Service und gemeinsame Diagnosekonstanten. */
    internal companion object {
        private val LOG = Logger.getInstance(VersionMetadataCache::class.java)
        private val GSON = Gson()
        /** Anzahl der Millisekunden je Minute, zur Umrechnung der konfigurierten Gültigkeitsdauer. */
        private const val MILLIS_PER_MINUTE = 60_000L

        /**
         * Ermittelt den Standard-Speicherpfad im IDE-Cache-Verzeichnis. Im Unit-Test-Modus wird standardmäßig
         * kein Pfad gesetzt, um Seiteneffekte zwischen Tests zu vermeiden.
         *
         * @return Der Standard-Pfad zur Cache-Datei oder `null`, falls der Pfad nicht ermittelt werden kann.
         */
        private fun defaultStoragePath(): Path? = runCatching {
            val app = ApplicationManager.getApplication()
            if (app?.isUnitTestMode == true) {
                null
            } else {
                Path.of(PathManager.getSystemPath(), "caches", "mavenup", "version-metadata-cache.json")
            }
        }.getOrNull()

        /** Liefert die anwendungsweite Instanz dieses Zwischenspeichers. */
        internal fun getInstance(): VersionMetadataCache =
            ApplicationManager.getApplication().getService(VersionMetadataCache::class.java)
    }
}
