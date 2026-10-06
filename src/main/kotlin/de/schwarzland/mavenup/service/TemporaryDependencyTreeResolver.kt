package de.schwarzland.mavenup.service

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import de.schwarzland.mavenup.model.TemporaryArtifactCoordinate
import de.schwarzland.mavenup.model.TemporaryDependencyNode
import org.jetbrains.idea.maven.project.MavenProjectsManager
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.util.Base64
import javax.xml.parsers.DocumentBuilderFactory

private val LOG = Logger.getInstance(TemporaryDependencyTreeResolver::class.java)

/**
 * Löst temporäre Abhängigkeitsbäume für Kandidaten-POMs im Arbeitsspeicher auf.
 *
 * Lädt POM-Dateien aus dem lokalen Maven-Cache (`~/.m2/repository`) oder über konfigurierte
 * Remote-Repositories (Maven Central und private Repositories aus `settings.xml`), interpoliert
 * Maven-Properties, löst die `<parent>`-Hierarchie sowie BOM-Imports auf und baut einen
 * leichtgewichtigen Abhängigkeitsgraphen auf.
 *
 * @param project Das IntelliJ-Projekt, aus dem Maven-Einstellungen und Repositories bezogen werden.
 * @param pomContentFetcher Optionaler Callback zum Bereitstellen von POM-Inhalten (z. B. für netzwerkfreie Unittests).
 */
class TemporaryDependencyTreeResolver(
    private val project: Project? = null,
    private val pomContentFetcher: ((groupId: String, artifactId: String, version: String) -> String?)? = null
) {
    private val pomCache = mutableMapOf<String, String>()

    /**
     * Erstellt einen Cache-Schlüssel für ein Artefakt.
     *
     * @param groupId Die Group-ID.
     * @param artifactId Die Artefakt-ID.
     * @param version Die Version.
     * @return Der zusammengesetzte Schlüssel `groupId:artifactId:version`.
     */
    private fun artifactKey(groupId: String, artifactId: String, version: String): String =
        "$groupId:$artifactId:$version"

    /**
     * Lädt den POM-XML-Inhalt für eine Koordinate.
     * Protokolliert die verwendete Quelle auf DEBUG-Ebene, ohne POM-Inhalte auszugeben.
     *
     * @param groupId Die Group-ID.
     * @param artifactId Die Artefakt-ID.
     * @param version Die Version.
     * @return Der XML-Text oder `null`, falls das POM nicht gefunden werden konnte.
     */
    fun fetchPomXml(groupId: String, artifactId: String, version: String): String? {
        val key = artifactKey(groupId, artifactId, version)
        pomCache[key]?.let {
            LOG.debug("POM cache hit for $key")
            return it
        }
        LOG.debug("POM cache miss for $key")

        if (pomContentFetcher != null) {
            val content = pomContentFetcher.invoke(groupId, artifactId, version)
            if (content != null) {
                pomCache[key] = content
            }
            LOG.debug("POM provider lookup for $key: found=${content != null}")
            return content
        }

        // 1. Lokales Maven-Repository prüfen
        val localContent = readFromLocalRepository(groupId, artifactId, version)
        if (localContent != null) {
            LOG.debug("Local Maven POM hit for $key")
            pomCache[key] = localContent
            return localContent
        }

        LOG.debug("Local Maven POM miss for $key")
        // 2. Remote-Repositories abfragen
        val remoteContent = fetchFromRemoteRepositories(groupId, artifactId, version)
        if (remoteContent != null) {
            pomCache[key] = remoteContent
            return remoteContent
        }

        LOG.debug("POM not found for $key in configured repositories")
        return null
    }

    /**
     * Liest ein POM aus dem lokalen Maven-Repository (`~/.m2/repository`).
     *
     * @param groupId Die Group-ID.
     * @param artifactId Die Artefakt-ID.
     * @param version Die Version.
     * @return Der Inhalt der POM-Datei oder `null`, falls nicht lokal vorhanden.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun readFromLocalRepository(groupId: String, artifactId: String, version: String): String? {
        val localRepoPath = resolveLocalRepositoryPath()
        val groupPath = groupId.replace('.', File.separatorChar)
        val pomFileName = "$artifactId-$version.pom"
        val pomFile = File(
            localRepoPath,
            "$groupPath${File.separatorChar}$artifactId${File.separatorChar}$version${File.separatorChar}$pomFileName"
        )
        return if (pomFile.isFile) {
            try {
                pomFile.readText(Charsets.UTF_8)
            } catch (e: Exception) {
                LOG.warn("Could not read local POM file: ${pomFile.path}", e)
                null
            }
        } else {
            null
        }
    }

    /**
     * Ermittelt den Pfad des lokalen Maven-Repositorys.
     *
     * @return Der Pfad als [File].
     */
    private fun resolveLocalRepositoryPath(): File {
        if (project != null) {
            try {
                val repositoryPath = MavenProjectsManager.getInstance(project).repositoryPath
                val file = repositoryPath.toFile()
                if (file.isDirectory) return file
            } catch (_: Exception) {
                // Rückfall auf Standardpfad
            }
        }
        val userHome = System.getProperty("user.home").orEmpty()
        return File(File(userHome, ".m2"), "repository")
    }

    /**
     * Lädt ein POM per HTTP von den konfigurierten Remote-Repositories herunter.
     *
     * @param groupId Die Group-ID.
     * @param artifactId Die Artefakt-ID.
     * @param version Die Version.
     * @return Der XML-Text des POMs oder `null`.
     */
    private fun fetchFromRemoteRepositories(groupId: String, artifactId: String, version: String): String? {
        val apiService = project?.let { DependencyApiService(it) }
        val repos = apiService?.getMavenRepositoryInfos()
            ?: listOf(Pair("central", "https://repo1.maven.org/maven2"))
        val credentials = apiService?.getMavenServerCredentials() ?: emptyMap()

        val groupPath = groupId.replace('.', '/')
        val relativePath = "$groupPath/$artifactId/$version/$artifactId-$version.pom"

        for ((repoId, repoUrl) in repos) {
            val content = tryFetchPomFromRepository(repoId, repoUrl, relativePath, credentials)
            if (content != null) {
                return content
            }
        }
        return null
    }

    /**
     * Versucht, eine POM-Datei von einem einzelnen Repository abzurufen.
     * Protokolliert GET-Versuche, HTTP-Status und Fehlerklassen ohne Zugangsdaten oder volle URLs.
     *
     * @param repoId Repository-ID zur Auswahl der Zugangsdaten.
     * @param repoUrl Basis-URL des Repositorys; wird nicht protokolliert.
     * @param relativePath Artefaktbezogener POM-Pfad relativ zum Repository.
     * @param credentials Zugangsdaten je Repository-ID; werden nicht protokolliert.
     * @return POM-Inhalt bei HTTP 200, andernfalls `null`.
     */
    @Suppress("TooGenericExceptionCaught")
    internal fun tryFetchPomFromRepository(
        repoId: String?,
        repoUrl: String,
        relativePath: String,
        credentials: Map<String, Pair<String?, String?>>
    ): String? {
        val urlString = "${repoUrl.trimEnd('/')}/$relativePath"
        var repositoryHost = "<invalid>"
        return try {
            val uri = URI(urlString)
            repositoryHost = uri.host ?: "<unknown>"
            LOG.debug("Querying POM $relativePath from $repositoryHost via HTTP GET")
            val connection = uri.toURL().openConnection() as? HttpURLConnection
            if (connection == null) {
                LOG.debug("Unsupported POM connection for $relativePath from $repositoryHost")
                return null
            }
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", "MavenUp-IntelliJ-Plugin")

            if (repoId != null && credentials.containsKey(repoId)) {
                val (user, pass) = credentials[repoId] ?: Pair(null, null)
                if (!user.isNullOrEmpty() && !pass.isNullOrEmpty()) {
                    val auth = Base64.getEncoder().encodeToString("$user:$pass".toByteArray(Charsets.UTF_8))
                    connection.setRequestProperty("Authorization", "Basic $auth")
                }
            }

            try {
                val status = connection.responseCode
                LOG.debug("POM response for $relativePath from $repositoryHost: HTTP $status")
                if (status == HttpURLConnection.HTTP_OK) {
                    connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                } else {
                    null
                }
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            LOG.debug("Failed to fetch POM $relativePath from $repositoryHost: ${e.javaClass.simpleName}")
            null
        }
    }

    /**
     * Parst einen XML-String in ein DOM-[Document].
     *
     * @param xml Der XML-Inhalt als String.
     * @return Das geparste [Document] oder `null` bei Parsing-Fehlern.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun parseXml(xml: String): Document? {
        return try {
            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = false
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            val builder = factory.newDocumentBuilder()
            builder.parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
        } catch (e: Exception) {
            LOG.warn("Failed to parse POM XML: ${e.message}")
            null
        }
    }

    /**
     * Interne Rohdatenstruktur eines geparsten POMs vor der vollständigen Vererbung.
     *
     * @property groupId Die Group-ID des Artefakts.
     * @property artifactId Die Artefakt-ID des Artefakts.
     * @property version Die Version des Artefakts.
     * @property parentGroupId Die Group-ID des Parent-POMs.
     * @property parentArtifactId Die Artefakt-ID des Parent-POMs.
     * @property parentVersion Die Version des Parent-POMs.
     * @property properties Die im POM deklarierten Properties.
     * @property declaredDependencies Die deklarierten Abhängigkeiten unter `<dependencies>`.
     * @property declaredDependencyManagement Die deklarierten Einträge unter `<dependencyManagement>`.
     */
    internal data class RawPomData(
        val groupId: String?,
        val artifactId: String,
        val version: String?,
        val parentGroupId: String?,
        val parentArtifactId: String?,
        val parentVersion: String?,
        val properties: Map<String, String>,
        val declaredDependencies: List<RawDependencyData>,
        val declaredDependencyManagement: List<RawDependencyData>
    )

    /**
     * Rohdaten einer deklarierten Abhängigkeit.
     *
     * @property groupId Die Group-ID.
     * @property artifactId Die Artefakt-ID.
     * @property version Die Version oder der Platzhalter.
     * @property scope Der Scope (`"compile"`, `"test"`, `"import"` etc.).
     * @property type Der Typ (`"jar"`, `"pom"` etc.).
     * @property optional `true`, wenn die Abhängigkeit als optional deklariert ist.
     */
    internal data class RawDependencyData(
        val groupId: String,
        val artifactId: String,
        val version: String?,
        val scope: String?,
        val type: String?,
        val optional: Boolean
    )

    /**
     * Parst die Rohdaten aus einem DOM-[Document].
     *
     * @param doc Das geparste XML-Dokument.
     * @return Die extrahierten [RawPomData].
     */
    private fun extractRawPomData(doc: Document): RawPomData {
        val root = doc.documentElement

        val parentTag = root.getElementsByTagName("parent").item(0) as? Element
        val parentGroupId = parentTag?.getElementsByTagName("groupId")?.item(0)?.textContent?.trim()
        val parentArtifactId = parentTag?.getElementsByTagName("artifactId")?.item(0)?.textContent?.trim()
        val parentVersion = parentTag?.getElementsByTagName("version")?.item(0)?.textContent?.trim()

        val groupId = directChildContent(root, "groupId") ?: parentGroupId
        val artifactId = directChildContent(root, "artifactId").orEmpty()
        val version = directChildContent(root, "version") ?: parentVersion

        val properties = mutableMapOf<String, String>()
        val propertiesElement = directChildElement(root, "properties")
        if (propertiesElement != null) {
            val childNodes = propertiesElement.childNodes
            for (i in 0 until childNodes.length) {
                val node = childNodes.item(i)
                if (node is Element) {
                    properties[node.tagName] = node.textContent.trim()
                }
            }
        }

        val declaredDependencies = mutableListOf<RawDependencyData>()
        val dependenciesElement = directChildElement(root, "dependencies")
        if (dependenciesElement != null) {
            extractDependenciesFromElement(dependenciesElement, declaredDependencies)
        }

        val declaredDependencyManagement = mutableListOf<RawDependencyData>()
        val depMgmtElement = directChildElement(root, "dependencyManagement")
        val depMgmtDepsElement = depMgmtElement?.let { directChildElement(it, "dependencies") }
        if (depMgmtDepsElement != null) {
            extractDependenciesFromElement(depMgmtDepsElement, declaredDependencyManagement)
        }

        return RawPomData(
            groupId = groupId,
            artifactId = artifactId,
            version = version,
            parentGroupId = parentGroupId,
            parentArtifactId = parentArtifactId,
            parentVersion = parentVersion,
            properties = properties,
            declaredDependencies = declaredDependencies,
            declaredDependencyManagement = declaredDependencyManagement
        )
    }

    /**
     * Extrahiert Kind-Tags mit dem Namen `dependency` aus einem Container-Element.
     *
     * @param containerElement Das übergeordnete Element `<dependencies>`.
     * @param targetList Die Zielliste zum Sammeln der Abhängigkeiten.
     */
    private fun extractDependenciesFromElement(
        containerElement: Element,
        targetList: MutableList<RawDependencyData>
    ) {
        val childNodes = containerElement.childNodes
        for (i in 0 until childNodes.length) {
            val node = childNodes.item(i)
            if (node is Element && node.tagName == "dependency") {
                val g = directChildContent(node, "groupId").orEmpty()
                val a = directChildContent(node, "artifactId").orEmpty()
                val v = directChildContent(node, "version")
                val scope = directChildContent(node, "scope")
                val type = directChildContent(node, "type")
                val optional = directChildContent(node, "optional")?.equals("true", ignoreCase = true) ?: false
                if (g.isNotEmpty() && a.isNotEmpty()) {
                    targetList.add(RawDependencyData(g, a, v, scope, type, optional))
                }
            }
        }
    }

    /**
     * Liefert den Textinhalt eines direkten Kind-Elements.
     *
     * @param parent Das Elternelement.
     * @param tagName Der Name des gesuchten Kind-Tags.
     * @return Der getrimmte Text oder `null`.
     */
    private fun directChildContent(parent: Element, tagName: String): String? {
        val element = directChildElement(parent, tagName)
        return element?.textContent?.trim()
    }

    /**
     * Liefert das erste direkte Kind-Element mit dem angegebenen Tag-Namen.
     *
     * @param parent Das Elternelement.
     * @param tagName Der Name des Kind-Tags.
     * @return Das [Element] oder `null`.
     */
    private fun directChildElement(parent: Element, tagName: String): Element? {
        val childNodes = parent.childNodes
        for (i in 0 until childNodes.length) {
            val node = childNodes.item(i)
            if (node is Element && node.tagName == tagName) {
                return node
            }
        }
        return null
    }

    /**
     * Repräsentiert das vollständig vererbte und interpolierte Modell eines POMs.
     *
     * @property coordinate Die effektive Maven-Koordinate.
     * @property properties Die zusammengeführten und interpolierten Properties.
     * @property dependencyManagement Das effektive DependencyManagement (`"groupId:artifactId"` -> Version).
     * @property dependencies Die effektiven direkten Abhängigkeiten mit aufgelösten Versionen.
     */
    internal data class EffectivePomModel(
        val coordinate: TemporaryArtifactCoordinate,
        val properties: Map<String, String>,
        val dependencyManagement: Map<String, String>,
        val dependencies: List<RawDependencyData>
    )

    /**
     * Löst ein POM inklusive der gesamten `<parent>`-Kette und BOM-Imports rekursiv auf.
     *
     * @param groupId Die Group-ID.
     * @param artifactId Die Artefakt-ID.
     * @param version Die Version.
     * @param visitedParents Die Menge bereits besuchter Parents zur Zyklenerkennung.
     * @return Das [EffectivePomModel] oder `null` bei Fehlern.
     */
    internal fun resolveEffectivePom(
        groupId: String,
        artifactId: String,
        version: String,
        visitedParents: MutableSet<String> = mutableSetOf()
    ): EffectivePomModel? {
        val key = artifactKey(groupId, artifactId, version)
        if (!visitedParents.add(key)) {
            LOG.warn("Cycle detected in parent chain: $key")
            return null
        }

        val xml = fetchPomXml(groupId, artifactId, version) ?: return null
        val doc = parseXml(xml) ?: return null
        val raw = extractRawPomData(doc)

        val effGroupId = raw.groupId ?: groupId
        val effArtifactId = raw.artifactId.ifEmpty { artifactId }
        val effVersion = raw.version ?: version

        val parentModel = resolveParentModelIfPresent(raw, visitedParents)

        val mergedProperties = mutableMapOf<String, String>()
        parentModel?.properties?.let { mergedProperties.putAll(it) }
        mergedProperties.putAll(raw.properties)

        populateStandardProperties(mergedProperties, effGroupId, effArtifactId, effVersion, parentModel)
        val interpolatedProperties = interpolateProperties(mergedProperties)

        val effectiveDepMgmt = mutableMapOf<String, String>()
        parentModel?.dependencyManagement?.let { effectiveDepMgmt.putAll(it) }

        processBomImportsAndDeclaredDepMgmt(raw, interpolatedProperties, effectiveDepMgmt)
        val resolvedDeps = resolveDeclaredDependencies(raw, interpolatedProperties, effectiveDepMgmt)

        return EffectivePomModel(
            coordinate = TemporaryArtifactCoordinate(effGroupId, effArtifactId, effVersion),
            properties = interpolatedProperties,
            dependencyManagement = effectiveDepMgmt,
            dependencies = resolvedDeps
        )
    }

    /**
     * Löst das Parent-Modell auf, falls ein Parent-Tag vorhanden ist.
     */
    private fun resolveParentModelIfPresent(
        raw: RawPomData,
        visitedParents: MutableSet<String>
    ): EffectivePomModel? {
        return if (!raw.parentGroupId.isNullOrEmpty() &&
            !raw.parentArtifactId.isNullOrEmpty() &&
            !raw.parentVersion.isNullOrEmpty()
        ) {
            resolveEffectivePom(raw.parentGroupId, raw.parentArtifactId, raw.parentVersion, visitedParents)
        } else {
            null
        }
    }

    /**
     * Befüllt die Standard-Maven-Properties.
     */
    private fun populateStandardProperties(
        properties: MutableMap<String, String>,
        effGroupId: String,
        effArtifactId: String,
        effVersion: String,
        parentModel: EffectivePomModel?
    ) {
        properties["project.groupId"] = effGroupId
        properties["pom.groupId"] = effGroupId
        properties["groupId"] = effGroupId
        properties["project.artifactId"] = effArtifactId
        properties["pom.artifactId"] = effArtifactId
        properties["artifactId"] = effArtifactId
        properties["project.version"] = effVersion
        properties["pom.version"] = effVersion
        properties["version"] = effVersion
        if (parentModel != null) {
            properties["project.parent.groupId"] = parentModel.coordinate.groupId
            properties["parent.groupId"] = parentModel.coordinate.groupId
            properties["project.parent.version"] = parentModel.coordinate.version
            properties["parent.version"] = parentModel.coordinate.version
        }
    }

    /**
     * Verarbeitet deklarierte DependencyManagement-Einträge und BOM-Imports.
     */
    private fun processBomImportsAndDeclaredDepMgmt(
        raw: RawPomData,
        properties: Map<String, String>,
        effectiveDepMgmt: MutableMap<String, String>
    ) {
        for (dep in raw.declaredDependencyManagement) {
            val depG = interpolateText(dep.groupId, properties)
            val depA = interpolateText(dep.artifactId, properties)
            val depV = dep.version?.let { interpolateText(it, properties) }

            if (dep.type.equals("pom", ignoreCase = true) && dep.scope.equals("import", ignoreCase = true) && !depV.isNullOrEmpty()) {
                val bomModel = resolveEffectivePom(depG, depA, depV, mutableSetOf())
                bomModel?.dependencyManagement?.forEach { (mgmtKey, mgmtVersion) ->
                    if (!effectiveDepMgmt.containsKey(mgmtKey)) {
                        effectiveDepMgmt[mgmtKey] = mgmtVersion
                    }
                }
            } else if (!depV.isNullOrEmpty()) {
                effectiveDepMgmt["$depG:$depA"] = depV
            }
        }
    }

    /**
     * Löst die deklarierten Abhängigkeiten gegen Properties und DependencyManagement auf.
     */
    private fun resolveDeclaredDependencies(
        raw: RawPomData,
        properties: Map<String, String>,
        effectiveDepMgmt: Map<String, String>
    ): List<RawDependencyData> {
        return raw.declaredDependencies.map { dep ->
            val depG = interpolateText(dep.groupId, properties)
            val depA = interpolateText(dep.artifactId, properties)
            val explicitVersion = dep.version?.let { interpolateText(it, properties) }
            val finalVersion = explicitVersion?.ifEmpty { null } ?: effectiveDepMgmt["$depG:$depA"]
            RawDependencyData(
                groupId = depG,
                artifactId = depA,
                version = finalVersion,
                scope = dep.scope,
                type = dep.type,
                optional = dep.optional
            )
        }
    }

    /**
     * Interpoliert Platzhalter `${...}` in einer Map von Properties.
     *
     * @param properties Die Map der rohen Properties.
     * @return Die Map mit vollständig aufgelösten Platzhaltern.
     */
    private fun interpolateProperties(properties: Map<String, String>): Map<String, String> {
        val result = properties.toMutableMap()
        var changed = true
        var passes = 0
        while (changed && passes < 10) {
            changed = false
            passes++
            for ((key, value) in result.entries) {
                val interpolated = interpolateText(value, result)
                if (interpolated != value) {
                    result[key] = interpolated
                    changed = true
                }
            }
        }
        return result
    }

    /**
     * Ersetzt Platzhalter der Form `${propName}` im übergebenen Text.
     *
     * @param text Der Ausgangstext.
     * @param properties Die Properties zur Auflösung.
     * @return Der interpolierte Text.
     */
    internal fun interpolateText(text: String, properties: Map<String, String>): String {
        if (!text.contains("\${")) return text
        val regex = Regex("""\$\{([^}]+)}""")
        return regex.replace(text) { matchResult ->
            val propName = matchResult.groupValues[1]
            properties[propName] ?: matchResult.value
        }
    }

    /**
     * Baut einen temporären Abhängigkeitsbaum für ein Artefakt bis zu einer maximalen Tiefe auf.
     *
     * @param groupId Die Group-ID.
     * @param artifactId Die Artefakt-ID.
     * @param version Die Version.
     * @param maxDepth Die maximale Rekursionstiefe (Standard 10).
     * @param visited Bereits besuchte Koordinaten zur Zyklenerkennung.
     * @return Der Wurzelknoten [TemporaryDependencyNode].
     */
    fun resolveDependencyTree(
        groupId: String,
        artifactId: String,
        version: String,
        maxDepth: Int = 10,
        visited: MutableSet<String> = mutableSetOf()
    ): TemporaryDependencyNode {
        val nodeKey = artifactKey(groupId, artifactId, version)
        val rootNode = TemporaryDependencyNode(
            coordinate = TemporaryArtifactCoordinate(groupId, artifactId, version)
        )

        if (maxDepth <= 0 || !visited.add(nodeKey)) {
            return rootNode
        }

        val effectivePom = resolveEffectivePom(groupId, artifactId, version) ?: return rootNode
        rootNode.dependencyManagement.putAll(effectivePom.dependencyManagement)

        val activeDependencies = effectivePom.dependencies.filter { dep ->
            !dep.optional &&
                dep.scope?.lowercase() !in listOf("test", "provided", "system") &&
                !dep.version.isNullOrBlank()
        }

        for (dep in activeDependencies) {
            val childNode = resolveDependencyTree(
                groupId = dep.groupId,
                artifactId = dep.artifactId,
                version = dep.version.orEmpty(),
                maxDepth = maxDepth - 1,
                visited = visited
            )
            childNode.copy(scope = dep.scope, optional = dep.optional).let {
                rootNode.dependencies.add(childNode)
            }
        }

        return rootNode
    }

    /**
     * Ermittelt alle transitiven Abhängigkeiten eines Artefakts als Map von `groupId:artifactId` zu Version.
     *
     * @param groupId Die Group-ID.
     * @param artifactId Die Artefakt-ID.
     * @param version Die Version.
     * @return Eine Map mit allen transitiv bereitgestellten Artefakten und deren Versionen.
     */
    fun resolveTransitiveDependencies(
        groupId: String,
        artifactId: String,
        version: String
    ): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val tree = resolveDependencyTree(groupId, artifactId, version)
        collectTransitiveArtifacts(tree, result, mutableSetOf())
        return result
    }

    /**
     * Sammelt rekursiv alle Kind-Abhängigkeiten eines Knotens.
     *
     * @param node Der aktuelle Knoten im Abhängigkeitsgraphen.
     * @param result Die Ziel-Map zum Sammeln von `groupId:artifactId` -> Version.
     * @param visited Die Menge bereits besuchter Knoten zur Zyklenerkennung.
     */
    private fun collectTransitiveArtifacts(
        node: TemporaryDependencyNode,
        result: MutableMap<String, String>,
        visited: MutableSet<String>
    ) {
        for (child in node.dependencies) {
            val key = "${child.coordinate.groupId}:${child.coordinate.artifactId}"
            if (!result.containsKey(key)) {
                result[key] = child.coordinate.version
            }
            val visitedKey = artifactKey(child.coordinate.groupId, child.coordinate.artifactId, child.coordinate.version)
            if (visited.add(visitedKey)) {
                collectTransitiveArtifacts(child, result, visited)
            }
        }
    }

    /**
     * Ermittelt das gesamte effektive `<dependencyManagement>` eines POMs inklusive aller übergeordneten Parent-POMs und BOMs.
     *
     * @param groupId Die Group-ID.
     * @param artifactId Die Artefakt-ID.
     * @param version Die Version.
     * @return Eine Map von `groupId:artifactId` zu verwalteter Version.
     */
    fun resolveEffectiveDependencyManagement(
        groupId: String,
        artifactId: String,
        version: String
    ): Map<String, String> {
        val model = resolveEffectivePom(groupId, artifactId, version) ?: return emptyMap()
        return model.dependencyManagement
    }
}
