package de.schwarzland.mavenup.service

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import org.apache.maven.artifact.versioning.ComparableVersion
import org.jetbrains.idea.maven.project.MavenProjectsManager
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.util.Base64
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Document

private const val CENTRAL_REPOSITORY_ID = "central"
private const val CENTRAL_REPOSITORY_URL = "https://repo1.maven.org/maven2"
private val LOG = Logger.getInstance(DependencyApiService::class.java)

/**
 * Dieser Service ist für die Interaktion mit Maven-Repositories zuständig, um verfügbare
 * Versionen von Abhängigkeiten abzufragen.
 *
 * Die Hauptaufgaben dieser Klasse umfassen:
 * - Auslesen von konfigurierten Maven-Repositories und Server-Zugangsdaten aus der `settings.xml`.
 *   Wenn in den Maven-IDE-Einstellungen kein expliziter Pfad gesetzt ist, wird auf
 *   `${user.home}/.m2/settings.xml` zurückgefallen.
 * - Abfrage von `maven-metadata.xml` von verschiedenen Repositories (z. B. Maven Central oder privaten Repos).
 * - Auflösung von Platzhaltern für Zugangsdaten (Umgebungsvariablen oder System-Properties).
 * - Filtern und Sortieren der gefundenen Versionen basierend auf den Plugin-Einstellungen (z. B. Ausschluss von Beta/Snapshot-Versionen).
 *
 * Dieser Service wird benötigt, um dem Benutzer eine Liste von möglichen Update-Kandidaten für seine
 * Maven-Abhängigkeiten anzuzeigen.
 */
class DependencyApiService(private val project: Project) {
    /**
     * Bereinigt eine ID aus den Maven-Einstellungen (trimming) und stellt sicher,
     * dass sie nicht leer ist.
     */
    fun normalizeSettingsId(rawId: String?): String? {
        return rawId?.trim()?.takeIf { it.isNotEmpty() }
    }

    /**
     * Löst Platzhalter in Zugangsdaten auf. Unterstützt Umgebungsvariablen (`${env.VAR}`)
     * und System-Properties oder einfache Umgebungsvariablen (`${VAR}`).
     */
    fun resolveCredentialValue(rawValue: String?, serverId: String, fieldName: String): String? {
        val value = rawValue?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val envPlaceholder = Regex("""^\$\{env\.([^}]+)}$""").matchEntire(value)
        if (envPlaceholder != null) {
            val envName = envPlaceholder.groupValues[1]
            return System.getenv(envName).also {
                if (it == null) {
                    LOG.warn("Could not resolve $fieldName for Maven server '$serverId': missing env var '$envName'")
                }
            }
        }

        val propertyPlaceholder = Regex("""^\$\{([^}]+)}$""").matchEntire(value)
        if (propertyPlaceholder != null) {
            val key = propertyPlaceholder.groupValues[1]
            return System.getProperty(key) ?: System.getenv(key).also {
                if (it == null) {
                    LOG.warn(
                        "Could not resolve $fieldName for Maven server '$serverId': " +
                            "missing system property/env var '$key'"
                    )
                }
            }
        }

        return value
    }

    /**
     * Ermittelt die effektiv zu verwendende Maven-`settings.xml`.
     *
     * Zuerst wird der in den Maven-IDE-Einstellungen konfigurierte Pfad geprüft.
     * Falls dort kein Pfad gesetzt ist oder die Datei nicht existiert, wird auf
     * `${user.home}/.m2/settings.xml` zurückgegriffen.
     *
     * @param configuredUserSettingsPath der konfigurierte Pfad aus den IDE-Maven-Einstellungen.
     * @param userHomePath Home-Verzeichnis des Benutzers zur Auflösung des Standardpfads.
     * @return die gefundene Datei oder `null`, wenn keine lesbare `settings.xml` vorhanden ist.
     */
    fun resolveMavenUserSettingsFile(
        configuredUserSettingsPath: String?,
        userHomePath: String = System.getProperty("user.home").orEmpty()
    ): File? {
        val candidates = collectMavenUserSettingsCandidates(configuredUserSettingsPath, userHomePath)
        return candidates.firstOrNull { it.isFile }
    }

    /**
     * Extrahiert Benutzername und Passwort für Maven-Server aus der `settings.xml`.
     */
    fun getMavenServerCredentials(): Map<String, Pair<String?, String?>> {
        val credentials = mutableMapOf<String, Pair<String?, String?>>()
        val settingsFile = resolveConfiguredOrDefaultMavenSettingsFile() ?: return credentials
        val doc = parseMavenSettingsDocument(settingsFile, "credentials") ?: return credentials
        val serverNodes = doc.getElementsByTagName("server")
        for (i in 0 until serverNodes.length) {
            val serverNode = serverNodes.item(i) as? org.w3c.dom.Element ?: continue
            val id = normalizeSettingsId(serverNode.getElementsByTagName("id").item(0)?.textContent)
            if (id != null) {
                val username = resolveCredentialValue(
                    serverNode.getElementsByTagName("username").item(0)?.textContent,
                    id,
                    "username"
                )
                val password = resolveCredentialValue(
                    serverNode.getElementsByTagName("password").item(0)?.textContent,
                    id,
                    "password"
                )
                credentials[id] = Pair(username, password)
            }
        }
        return credentials
    }

    /**
     * Ermittelt die konfigurierten Maven-Repositories. Enthält standardmäßig Maven Central.
     */
    fun getMavenRepositoryInfos(): List<Pair<String?, String>> {
        val infos = mutableListOf<Pair<String?, String>>(Pair(CENTRAL_REPOSITORY_ID, CENTRAL_REPOSITORY_URL))
        val settingsFile = resolveConfiguredOrDefaultMavenSettingsFile()
        val doc = if (settingsFile != null) parseMavenSettingsDocument(settingsFile, "repositories") else null
        val repoNodes = doc?.getElementsByTagName("repository")
        if (repoNodes != null) {
            for (i in 0 until repoNodes.length) {
                val repoNode = repoNodes.item(i) as? org.w3c.dom.Element ?: continue
                val id = normalizeSettingsId(repoNode.getElementsByTagName("id").item(0)?.textContent)
                val url = repoNode.getElementsByTagName("url").item(0)?.textContent
                if (url != null && url.isNotBlank()) {
                    infos.add(Pair(id, url.trim().trimEnd('/')))
                }
            }
        }

        return infos
            .groupBy { it.second }
            .values
            .map { repositoriesWithSameUrl ->
                repositoriesWithSameUrl.firstOrNull { it.first != null } ?: repositoriesWithSameUrl.first()
            }
    }

    /**
     * Ermittelt die effektiv verwendete `settings.xml` aus IDE-Konfiguration und Standardpfad.
     */
    private fun resolveConfiguredOrDefaultMavenSettingsFile(): File? {
        val configuredPath = MavenProjectsManager.getInstance(project).generalSettings.userSettingsFile
        val userHomePath = System.getProperty("user.home").orEmpty()
        val settingsFile = resolveMavenUserSettingsFile(configuredPath, userHomePath)
        if (settingsFile != null) {
            LOG.debug("Using Maven settings file: ${settingsFile.path}")
            return settingsFile
        }

        val checkedCandidates = collectMavenUserSettingsCandidates(configuredPath, userHomePath)
            .joinToString(", ") { it.path }
        LOG.debug("No Maven settings file found. Checked: $checkedCandidates")
        return null
    }

    /**
     * Liefert mögliche Kandidatenpfade für die Maven-`settings.xml` in Prioritätsreihenfolge.
     */
    private fun collectMavenUserSettingsCandidates(
        configuredUserSettingsPath: String?,
        userHomePath: String
    ): List<File> {
        val candidates = mutableListOf<File>()
        val configuredPath = configuredUserSettingsPath?.trim().orEmpty()
        if (configuredPath.isNotEmpty()) {
            candidates.add(File(expandSettingsPath(configuredPath, userHomePath)))
        }
        if (userHomePath.isNotBlank()) {
            candidates.add(File(File(userHomePath, ".m2"), "settings.xml"))
        }
        return candidates.distinctBy { it.absolutePath.lowercase(Locale.getDefault()) }
    }

    /**
     * Löst Platzhalter in Dateipfaden auf (`~`, `%ENV%`, `${prop}`).
     */
    private fun expandSettingsPath(rawPath: String, userHomePath: String): String {
        var expandedPath = rawPath.trim()
        if (userHomePath.isNotBlank()) {
            expandedPath = when {
                expandedPath == "~" -> userHomePath
                expandedPath.startsWith("~\\") || expandedPath.startsWith("~/") -> {
                    val relativePath = expandedPath.substring(2)
                        .replace('\\', File.separatorChar)
                        .replace('/', File.separatorChar)
                    File(userHomePath, relativePath).path
                }
                else -> expandedPath
            }
        }
        expandedPath = Regex("%([^%]+)%").replace(expandedPath) { match ->
            System.getenv(match.groupValues[1]) ?: match.value
        }
        expandedPath = Regex("""\$\{([^}]+)}""").replace(expandedPath) { match ->
            val key = match.groupValues[1]
            System.getProperty(key) ?: System.getenv(key) ?: match.value
        }
        return expandedPath
    }

    /**
     * Parst die Maven-`settings.xml` und gibt das XML-Dokument zurück.
     */
    private fun parseMavenSettingsDocument(settingsFile: File, purpose: String): Document? {
        return try {
            val factory = DocumentBuilderFactory.newInstance()
            val builder = factory.newDocumentBuilder()
            builder.parse(settingsFile)
        } catch (e: Exception) {
            LOG.error("Failed to parse Maven settings file for $purpose: ${settingsFile.path}", e)
            null
        }
    }

    /**
     * Sucht passende Zugangsdaten für ein bestimmtes Repository basierend auf ID, URL oder Hostname.
     */
    fun findServerCredentials(
        repositoryInfo: Pair<String?, String>,
        serverCredentials: Map<String, Pair<String?, String?>>
    ): Pair<String?, String?>? {
        repositoryInfo.first?.let { serverCredentials[it] }?.let { return it }
        serverCredentials[repositoryInfo.second]?.let { return it }
        val host = URI(repositoryInfo.second).host ?: return null
        return serverCredentials[host]
    }

    /**
     * Erstellt eine HTTP-Verbindung zur `maven-metadata.xml` des angegebenen Artefakts.
     */
    private fun createMetadataConnection(repositoryUrl: String, groupId: String, artifactId: String): HttpURLConnection {
        val urlString = "$repositoryUrl/${groupId.replace('.', '/')}/$artifactId/maven-metadata.xml"
        val url = URI(urlString).toURL()
        return (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10000
            readTimeout = 10000
        }
    }

    /**
     * Wendet Basic-Auth-Zugangsdaten auf die HTTP-Verbindung an, falls vorhanden.
     */
    private fun applyCredentials(
        connection: HttpURLConnection,
        repositoryInfo: Pair<String?, String>,
        serverCredentials: Map<String, Pair<String?, String?>>
    ) {
        val creds = findServerCredentials(repositoryInfo, serverCredentials)
        if (creds != null && creds.first != null && creds.second != null) {
            val auth = "${creds.first}:${creds.second}"
            val encodedAuth = Base64.getEncoder().encodeToString(auth.toByteArray(Charsets.UTF_8))
            connection.setRequestProperty("Authorization", "Basic $encodedAuth")
        }
    }

    /**
     * Liest die verfügbaren Versionen aus dem InputStream der HTTP-Verbindung und filtert sie,
     * so dass nur Versionen größer oder gleich der aktuellen Version zurückgegeben werden.
     */
    private fun readVersionsFromConnection(
        connection: HttpURLConnection,
        currentComparable: ComparableVersion,
        groupId: String,
        artifactId: String,
        repositoryUrl: String
    ): Pair<Boolean, List<String>> {
        val responseCode = connection.responseCode
        if (responseCode != HttpURLConnection.HTTP_OK) {
            LOG.warn(
                "Failed to fetch versions for $groupId:$artifactId from $repositoryUrl. " +
                    "HTTP $responseCode ${connection.responseMessage}"
            )
            return Pair(false, emptyList())
        }

        val versions = mutableListOf<String>()
        val factory = DocumentBuilderFactory.newInstance()
        val builder = factory.newDocumentBuilder()
        val doc = builder.parse(connection.inputStream)
        val versionNodes = doc.getElementsByTagName("version")
        for (i in 0 until versionNodes.length) {
            val version = versionNodes.item(i).textContent
            if (ComparableVersion(version) >= currentComparable) {
                versions.add(version)
            }
        }
        LOG.info(
            "Fetched ${versions.size} versions for $groupId:$artifactId from $repositoryUrl. " +
                "Current version: $currentComparable"
        )
        LOG.debug(
            "Fetched versions for $groupId:$artifactId: " +
                summarizeForDebugLog(versions)
        )
        return Pair(true, versions)
    }

    /**
     * Ruft die Versionen für ein Artefakt von einem spezifischen Repository ab.
     */
    fun fetchVersionsFromRepository(
        repositoryInfo: Pair<String?, String>,
        groupId: String,
        artifactId: String,
        currentComparable: ComparableVersion,
        serverCredentials: Map<String, Pair<String?, String?>>
    ): Pair<Boolean, List<String>> {
        return try {
            val connection = createMetadataConnection(repositoryInfo.second, groupId, artifactId)
            applyCredentials(connection, repositoryInfo, serverCredentials)
            readVersionsFromConnection(connection, currentComparable, groupId, artifactId, repositoryInfo.second)
        } catch (e: Exception) {
            LOG.warn("Failed to fetch versions for $groupId:$artifactId from ${repositoryInfo.second}", e)
            Pair(false, emptyList())
        }
    }

    /**
     * Sammelt Versionen über mehrere Repositories hinweg. Wenn Maven Central erfolgreich abgefragt wurde,
     * wird die Suche abgebrochen, um die Last zu verringern.
     */
    fun collectVersionsFromRepositories(
        repositoryInfos: List<Pair<String?, String>>,
        fetchVersionsForRepository: (Pair<String?, String>) -> Pair<Boolean, List<String>>
    ): Set<String> {
        val allVersions = mutableSetOf<String>()
        val orderedRepositoryInfos = repositoryInfos.sortedBy { if (it.second == CENTRAL_REPOSITORY_URL) 0 else 1 }

        for (repoInfo in orderedRepositoryInfos) {
            val (requestSucceeded, versions) = fetchVersionsForRepository(repoInfo)
            allVersions.addAll(versions)
            if (repoInfo.second == CENTRAL_REPOSITORY_URL && requestSucceeded) {
                break
            }
        }

        return allVersions
    }

    /**
     * Filtert die Liste der Versionen basierend auf den konfigurierten Ausschlusskriterien
     * für instabile Versionen (z. B. "alpha", "beta", "rc").
     */
    fun filterVersionsBySettings(versions: List<String>): List<String> {
        val settings = MavenUpSettings.getInstance().state
        if (!settings.hideUnstableVersions) {
            return versions
        }

        val qualifiers = settings.hiddenVersionQualifiers
            .split(",", ";")
            .map { it.trim().lowercase(Locale.getDefault()) }
            .filter { it.isNotEmpty() }
            .distinct()

        if (qualifiers.isEmpty()) {
            return versions
        }

        return versions.filter { version ->
            !qualifiers.any { qualifier -> versionHasQualifier(version, qualifier) }
        }
    }

    /**
     * Prüft, ob eine Version einen bestimmten Qualifier (z. B. "beta") enthält.
     */
    fun versionHasQualifier(version: String, qualifier: String): Boolean {
        val qualifierPattern = Regex(
            "(?i)(?:^|[._\\-]|\\d)${Regex.escape(qualifier)}(?:[._\\-]?\\d*)?(?:$|[._\\-])"
        )
        return qualifierPattern.containsMatchIn(version)
    }

    /**
     * Die Hauptmethode zum Abrufen aller relevanten Update-Versionen für ein Artefakt.
     * Führt Repository-Suche, Credential-Auflösung, Filterung und Sortierung zusammen.
     */
    fun fetchVersions(groupId: String, artifactId: String, currentVersion: String): List<String> {
        val repositoryInfos = getMavenRepositoryInfos()
        val serverCredentials = getMavenServerCredentials()
        val currentComparable = ComparableVersion(currentVersion)
        val allVersions = collectVersionsFromRepositories(repositoryInfos) { repoInfo ->
            fetchVersionsFromRepository(repoInfo, groupId, artifactId, currentComparable, serverCredentials)
        }
        val sortedVersions = allVersions.sortedWith { v1, v2 ->
            ComparableVersion(v2).compareTo(ComparableVersion(v1))
        }
        return filterVersionsBySettings(sortedVersions)
    }
}
