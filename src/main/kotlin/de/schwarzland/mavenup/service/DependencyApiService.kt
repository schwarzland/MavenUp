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

private const val CENTRAL_REPOSITORY_ID = "central"
private const val CENTRAL_REPOSITORY_URL = "https://repo1.maven.org/maven2"
private val LOG = Logger.getInstance(DependencyApiService::class.java)

class DependencyApiService(private val project: Project) {
    fun normalizeSettingsId(rawId: String?): String? {
        return rawId?.trim()?.takeIf { it.isNotEmpty() }
    }

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

    fun getMavenServerCredentials(): Map<String, Pair<String?, String?>> {
        val credentials = mutableMapOf<String, Pair<String?, String?>>()
        val generalSettings = MavenProjectsManager.getInstance(project).generalSettings
        val userSettingsPath = generalSettings.userSettingsFile

        if (userSettingsPath.isNotBlank()) {
            val userSettingsFile = File(userSettingsPath)
            if (userSettingsFile.exists()) {
                try {
                    val factory = DocumentBuilderFactory.newInstance()
                    val builder = factory.newDocumentBuilder()
                    val doc = builder.parse(userSettingsFile)
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
                } catch (e: Exception) {
                    LOG.error("Failed to parse Maven settings file for credentials: ${userSettingsFile.path}", e)
                }
            }
        }
        return credentials
    }

    fun getMavenRepositoryInfos(): List<Pair<String?, String>> {
        val infos = mutableListOf<Pair<String?, String>>(Pair(CENTRAL_REPOSITORY_ID, CENTRAL_REPOSITORY_URL))

        val generalSettings = MavenProjectsManager.getInstance(project).generalSettings
        val userSettingsPath = generalSettings.userSettingsFile

        if (userSettingsPath.isNotBlank()) {
            val userSettingsFile = File(userSettingsPath)
            if (userSettingsFile.exists()) {
                try {
                    val factory = DocumentBuilderFactory.newInstance()
                    val builder = factory.newDocumentBuilder()
                    val doc = builder.parse(userSettingsFile)
                    val repoNodes = doc.getElementsByTagName("repository")
                    for (i in 0 until repoNodes.length) {
                        val repoNode = repoNodes.item(i) as? org.w3c.dom.Element ?: continue
                        val id = normalizeSettingsId(repoNode.getElementsByTagName("id").item(0)?.textContent)
                        val url = repoNode.getElementsByTagName("url").item(0)?.textContent
                        if (url != null && url.isNotBlank()) {
                            infos.add(Pair(id, url.trim().trimEnd('/')))
                        }
                    }
                } catch (e: Exception) {
                    LOG.error("Failed to parse Maven settings file for repositories: ${userSettingsFile.path}", e)
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

    fun findServerCredentials(
        repositoryInfo: Pair<String?, String>,
        serverCredentials: Map<String, Pair<String?, String?>>
    ): Pair<String?, String?>? {
        repositoryInfo.first?.let { serverCredentials[it] }?.let { return it }
        serverCredentials[repositoryInfo.second]?.let { return it }
        val host = URI(repositoryInfo.second).host ?: return null
        return serverCredentials[host]
    }

    private fun createMetadataConnection(repositoryUrl: String, groupId: String, artifactId: String): HttpURLConnection {
        val urlString = "$repositoryUrl/${groupId.replace('.', '/')}/$artifactId/maven-metadata.xml"
        val url = URI(urlString).toURL()
        return (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10000
            readTimeout = 10000
        }
    }

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
                "Current version: $currentComparable, Versions: ${versions.joinToString(", ")}"
        )
        return Pair(true, versions)
    }

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

    fun filterVersionsBySettings(versions: List<String>): List<String> {
        val settings = MavenUpSettings.getInstance(project).state
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

    fun versionHasQualifier(version: String, qualifier: String): Boolean {
        val qualifierPattern = Regex(
            "(?i)(?:^|[._\\-]|\\d)${Regex.escape(qualifier)}(?:[._\\-]?\\d*)?(?:$|[._\\-])"
        )
        return qualifierPattern.containsMatchIn(version)
    }

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
