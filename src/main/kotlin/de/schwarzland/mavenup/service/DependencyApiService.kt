package de.schwarzland.mavenup.service

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import de.schwarzland.mavenup.ui.MyMessageBundle
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

/** Untere Versionsgrenze, die jede vom Repository gemeldete Version einschließt. */
private val NO_VERSION_FLOOR = ComparableVersion("")

/**
 * Ergebnis einer Versionsabfrage gegen ein einzelnes Maven-Repository.
 *
 * @property requestSucceeded `true`, wenn die HTTP-Abfrage erfolgreich war (HTTP 200).
 * @property versions Die gefundenen Versionen, gefiltert auf `>= aktuelle Version`.
 * @property newestVersion Die vom Repository deklarierte neueste Version aus `<release>` bzw. `<latest>`, oder `null`.
 */
internal data class RepositoryVersions(
    val requestSucceeded: Boolean,
    val versions: List<String>,
    val newestVersion: String?,
    val errorReason: String? = null
)

/**
 * Aggregiertes Ergebnis der Versionsabfrage über mehrere Repositories.
 *
 * @property versions Die zusammengeführten Versionen aller abgefragten Repositories.
 * @property newestVersion Die als neueste bestimmte Referenzversion (bevorzugt aus Maven Central), oder `null`.
 */
internal data class CollectedVersions(
    val versions: Set<String>,
    val newestVersion: String?,
    val centralErrorReason: String? = null
)

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
     *
     * Zusätzlich wird die vom Repository deklarierte neueste Version aus den `<release>`- bzw.
     * `<latest>`-Feldern der `maven-metadata.xml` ausgelesen, da diese verlässlicher die zuletzt
     * veröffentlichte Version angeben als eine rein numerische Sortierung.
     */
    private fun readVersionsFromConnection(
        connection: HttpURLConnection,
        currentComparable: ComparableVersion,
        groupId: String,
        artifactId: String,
        repositoryUrl: String
    ): RepositoryVersions {
        val responseCode = connection.responseCode
        if (responseCode != HttpURLConnection.HTTP_OK) {
            LOG.warn(
                "Failed to fetch versions for $groupId:$artifactId from $repositoryUrl. " +
                    "HTTP $responseCode ${connection.responseMessage}"
            )
            val errorReason = if (responseCode == HttpURLConnection.HTTP_NOT_FOUND) {
                null
            } else {
                "HTTP $responseCode ${connection.responseMessage}"
            }
            return RepositoryVersions(false, emptyList(), null, errorReason)
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
        val newestVersion = extractNewestFromMetadata(doc)
        LOG.info(
            "Fetched ${versions.size} versions for $groupId:$artifactId from $repositoryUrl. " +
                "Current version: $currentComparable, declared newest: ${newestVersion ?: "n/a"}"
        )
        LOG.debug(
            "Fetched versions for $groupId:$artifactId: " +
                summarizeForDebugLog(versions)
        )
        return RepositoryVersions(true, versions, newestVersion)
    }

    /**
     * Liest die vom Repository deklarierte neueste Version aus der `maven-metadata.xml`.
     *
     * Bevorzugt wird das `<release>`-Feld (neueste Nicht-SNAPSHOT-Release) verwendet; fehlt es,
     * wird auf `<latest>` zurückgegriffen. Diese Felder spiegeln die zuletzt veröffentlichte
     * Version wider und sind damit robuster gegen numerisch irreführende Versionsschemata
     * (z. B. datumsbasierte Versionen).
     *
     * @param doc Das geparste `maven-metadata.xml`-Dokument.
     * @return Die deklarierte neueste Version oder `null`, wenn weder `<release>` noch `<latest>` gesetzt ist.
     */
    internal fun extractNewestFromMetadata(doc: Document): String? {
        val release = doc.getElementsByTagName("release").item(0)?.textContent?.trim()
        if (!release.isNullOrEmpty()) {
            return release
        }
        val latest = doc.getElementsByTagName("latest").item(0)?.textContent?.trim()
        return latest?.takeIf { it.isNotEmpty() }
    }

    /**
     * Ruft die Versionen für ein Artefakt von einem spezifischen Repository ab.
     */
    internal fun fetchVersionsFromRepository(
        repositoryInfo: Pair<String?, String>,
        groupId: String,
        artifactId: String,
        currentComparable: ComparableVersion,
        serverCredentials: Map<String, Pair<String?, String?>>
    ): RepositoryVersions {
        return try {
            val connection = createMetadataConnection(repositoryInfo.second, groupId, artifactId)
            applyCredentials(connection, repositoryInfo, serverCredentials)
            readVersionsFromConnection(connection, currentComparable, groupId, artifactId, repositoryInfo.second)
        } catch (e: Exception) {
            LOG.warn("Failed to fetch versions for $groupId:$artifactId from ${repositoryInfo.second}", e)
            RepositoryVersions(false, emptyList(), null, e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * Sammelt Versionen über mehrere Repositories hinweg und bestimmt eine neueste Referenzversion.
     *
     * Wenn [stopAfterCentralSuccess] aktiviert ist, wird die Suche nach einer erfolgreichen
     * Abfrage von Maven Central beendet, um Netzwerk-Last zu verringern. Ist die Option deaktiviert,
     * werden anschließend auch private Repositories abgefragt.
     *
     * Als neueste Referenzversion wird bevorzugt die von Maven Central deklarierte Version verwendet;
     * fehlt sie, wird die höchste deklarierte Version der übrigen Repositories herangezogen. So bleibt
     * die Bestimmung der neuesten Version auch bei numerisch irreführenden Versionsschemata robust.
     */
    internal fun collectVersionsFromRepositories(
        repositoryInfos: List<Pair<String?, String>>,
        stopAfterCentralSuccess: Boolean,
        fetchVersionsForRepository: (Pair<String?, String>) -> RepositoryVersions
    ): CollectedVersions {
        val allVersions = mutableSetOf<String>()
        var centralNewest: String? = null
        var fallbackNewest: String? = null
        var centralErrorReason: String? = null
        val orderedRepositoryInfos = repositoryInfos.sortedBy { if (it.second == CENTRAL_REPOSITORY_URL) 0 else 1 }

        for (repoInfo in orderedRepositoryInfos) {
            val result = fetchVersionsForRepository(repoInfo)
            allVersions.addAll(result.versions)
            val declaredNewest = result.newestVersion?.trim()?.takeIf { it.isNotEmpty() }
            if (declaredNewest != null) {
                if (repoInfo.second == CENTRAL_REPOSITORY_URL) {
                    centralNewest = declaredNewest
                }
                if (fallbackNewest == null ||
                    ComparableVersion(declaredNewest) > ComparableVersion(fallbackNewest)
                ) {
                    fallbackNewest = declaredNewest
                }
            }
            if (repoInfo.second == CENTRAL_REPOSITORY_URL && result.errorReason != null) {
                centralErrorReason = result.errorReason
            }
            if (stopAfterCentralSuccess && repoInfo.second == CENTRAL_REPOSITORY_URL && result.requestSucceeded) {
                break
            }
        }

        return CollectedVersions(allVersions, centralNewest ?: fallbackNewest, centralErrorReason)
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
     * Bestimmt die untere Versionsgrenze für die Filterung der angebotenen Versionen.
     *
     * Ist [offerAllVersions] aktiviert, wird eine minimale [ComparableVersion] (leerer String)
     * zurückgegeben, sodass jede vom Repository gemeldete Version die `>=`-Bedingung erfüllt und
     * auch ältere Versionen als Downgrade-Kandidaten angeboten werden. Andernfalls wird die
     * aktuelle Version als Grenze verwendet, sodass nur Upgrades angeboten werden.
     *
     * @param currentVersion Die aktuell verwendete Version des Artefakts.
     * @param offerAllVersions Ob alle Versionen (inklusive älterer) angeboten werden sollen.
     * @return Die als untere Grenze zu verwendende [ComparableVersion].
     */
    internal fun resolveVersionFloor(currentVersion: String, offerAllVersions: Boolean): ComparableVersion =
        if (offerAllVersions) ComparableVersion("") else ComparableVersion(currentVersion)

    /**
     * Prüft, ob eine GroupId gemäß der Einstellung [MavenUpSettings.State.privateGroupIds] als
     * privat/unternehmensintern gilt.
     *
     * Eine GroupId gilt als privat, wenn sie exakt einem konfigurierten Präfix entspricht oder mit
     * `<Präfix>.` beginnt, damit z. B. der Präfix `com.myCompany` auch `com.myCompany.produkt`
     * abdeckt, ohne unbeabsichtigt unabhängige GroupIds wie `com.myCompanyOther` zu erfassen.
     *
     * @param groupId Die zu prüfende GroupId.
     * @return `true`, wenn die GroupId als privat konfiguriert ist.
     */
    fun isPrivateGroupId(groupId: String): Boolean {
        val privateGroupIds = MavenUpSettings.getInstance().state.privateGroupIds
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        return privateGroupIds.any { prefix -> groupId == prefix || groupId.startsWith("$prefix.") }
    }

    /**
     * Entfernt Maven Central ([CENTRAL_REPOSITORY_URL]) aus der Liste der abzufragenden Repositories,
     * wenn [groupId] gemäß [isPrivateGroupId] als privat/unternehmensintern gilt.
     *
     * So wird sichergestellt, dass für als privat konfigurierte GroupIds keine Koordinaten an
     * `repo1.maven.org` übertragen werden; die übrigen (privaten) Repositories aus der `settings.xml`
     * werden weiterhin abgefragt.
     *
     * @param repositoryInfos Die ursprünglich ermittelten Repositories.
     * @param groupId Die GroupId des abzufragenden Artefakts.
     * @return Die gefilterte Repository-Liste.
     */
    internal fun excludeCentralForPrivateGroupId(
        repositoryInfos: List<Pair<String?, String>>,
        groupId: String
    ): List<Pair<String?, String>> {
        if (!isPrivateGroupId(groupId)) {
            return repositoryInfos
        }
        return repositoryInfos.filter { it.second != CENTRAL_REPOSITORY_URL }
    }

    /**
     * Ruft alle von den konfigurierten Repositories gemeldeten Versionen eines Artefakts ab, ohne
     * die anzeigebezogenen Einstellungen [MavenUpSettings.State.offerAllVersions] und
     * [MavenUpSettings.State.hideUnstableVersions] anzuwenden.
     *
     * Die zurückgegebene Liste ist absteigend nach [ComparableVersion] sortiert; die vom Repository
     * als neueste deklarierte Version (`<release>`/`<latest>`) wird jedoch an den Anfang gestellt,
     * damit nachgelagerte Logik (Statusanzeige, Auto-Auswahl der höchsten Version) die tatsächlich
     * zuletzt veröffentlichte Version als neueste behandelt.
     *
     * Da das Ergebnis ungefiltert ist, kann die Anzeige über [applyVersionSettings] jederzeit ohne
     * erneute Netzwerkabfrage an geänderte Einstellungen angepasst werden.
     *
     * @param groupId Die GroupId des Artefakts.
     * @param artifactId Die ArtifactId des Artefakts.
     * @return Alle gefundenen Versionen, absteigend sortiert und mit der neuesten Version zuerst.
     */
    fun fetchAllVersions(
        groupId: String,
        artifactId: String,
        onError: ((String) -> Unit)? = null
    ): List<String> {
        val settings = MavenUpSettings.getInstance().state
        val repositoryInfos = excludeCentralForPrivateGroupId(getMavenRepositoryInfos(), groupId)
        val serverCredentials = getMavenServerCredentials()
        val collected = collectVersionsFromRepositories(
            repositoryInfos,
            settings.stopAfterCentralSuccess
        ) { repoInfo ->
            fetchVersionsFromRepository(repoInfo, groupId, artifactId, NO_VERSION_FLOOR, serverCredentials)
        }
        if (collected.versions.isEmpty() &&
            collected.centralErrorReason != null &&
            repositoryInfos.any { it.second == CENTRAL_REPOSITORY_URL }
        ) {
            onError?.invoke(
                MyMessageBundle.message("dependency.central.requestFailed", collected.centralErrorReason)
            )
        }
        val sortedVersions = collected.versions.sortedWith { v1, v2 ->
            ComparableVersion(v2).compareTo(ComparableVersion(v1))
        }
        return orderWithNewestFirst(sortedVersions, collected.newestVersion)
    }

    /**
     * Wendet die anzeigebezogenen Versionseinstellungen auf eine bereits abgerufene Versionsliste an.
     *
     * Ist [MavenUpSettings.State.offerAllVersions] deaktiviert, bleiben nur Versionen `>=` der
     * aktuellen Version übrig; zusätzlich werden bei aktivem [MavenUpSettings.State.hideUnstableVersions]
     * die konfigurierten instabilen Qualifier ausgeblendet. Die Reihenfolge der Eingabeliste bleibt
     * erhalten, sodass die neueste Version weiterhin an erster Stelle steht.
     *
     * @param versions Die ungefilterte, absteigend sortierte Versionsliste.
     * @param currentVersion Die aktuell verwendete Version des Artefakts.
     * @return Die gemäß den Einstellungen gefilterte Versionsliste.
     */
    fun applyVersionSettings(versions: List<String>, currentVersion: String): List<String> {
        val floor = resolveVersionFloor(currentVersion, MavenUpSettings.getInstance().state.offerAllVersions)
        return filterVersionsBySettings(versions.filter { ComparableVersion(it) >= floor })
    }

    /**
     * Die Hauptmethode zum Abrufen aller relevanten Update-Versionen für ein Artefakt.
     * Führt Repository-Suche, Credential-Auflösung, Filterung und Sortierung zusammen.
     *
     * Entspricht [fetchAllVersions] mit anschließend angewendeten Einstellungen
     * (siehe [applyVersionSettings]).
     */
    fun fetchVersions(
        groupId: String,
        artifactId: String,
        currentVersion: String,
        onError: ((String) -> Unit)? = null
    ): List<String> =
        applyVersionSettings(fetchAllVersions(groupId, artifactId, onError), currentVersion)

    /**
     * Stellt die vom Repository als neueste deklarierte Version an den Anfang der Liste, sofern sie
     * in [versions] enthalten ist. Andernfalls bleibt die Reihenfolge unverändert.
     *
     * @param versions Die bereits gefilterte, absteigend sortierte Versionsliste.
     * @param newestVersion Die deklarierte neueste Version oder `null`.
     * @return Eine Liste, deren erstes Element die neueste Version ist, sofern bekannt und enthalten.
     */
    internal fun orderWithNewestFirst(versions: List<String>, newestVersion: String?): List<String> {
        val newest = newestVersion?.takeIf { it in versions } ?: return versions
        if (versions.firstOrNull() == newest) {
            return versions
        }
        return listOf(newest) + versions.filter { it != newest }
    }
}
