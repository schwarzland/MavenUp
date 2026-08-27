package de.schwarzland.mavenup.service

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import de.schwarzland.mavenup.model.VulnerabilityAdvisory
import de.schwarzland.mavenup.model.VulnerabilitySeverity
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64

private const val OSS_INDEX_REPORT_URL = "https://ossindex.sonatype.org/api/v3/component-report"
private const val OSS_INDEX_BATCH_SIZE = 128
private const val OSS_INDEX_SOURCE = "OSS Index"
private val OSS_INDEX_LOG = Logger.getInstance(OssIndexApiService::class.java)

/**
 * Fester Platzhalter-Benutzername für die HTTP-Basic-Authentifizierung beim OSS Index.
 *
 * Der Sonatype OSS Index authentifiziert Anfragen ausschließlich über das API-Token; der
 * Benutzername-Teil des Auth-Headers wird serverseitig nicht ausgewertet. Es genügt daher
 * ein beliebiger, nicht-leerer Platzhalter, sodass keine E-Mail-Adresse konfiguriert werden muss.
 */
internal const val OSS_INDEX_AUTH_USERNAME = "MavenUp"

/**
 * Wird geworfen, wenn der Sonatype OSS Index die Anfrage wegen eines ungültigen oder
 * abgelaufenen API-Tokens ablehnt (HTTP 401 Unauthorized oder HTTP 403 Forbidden).
 *
 * Diese spezielle Ausnahme ermöglicht es der Benutzeroberfläche, eine qualifizierte
 * Fehlermeldung anzuzeigen, die den Benutzer gezielt auf ein fehlerhaftes Token hinweist,
 * anstatt eine generische technische HTTP-Fehlermeldung auszugeben.
 *
 * @param message Die technische Beschreibung des fehlgeschlagenen HTTP-Aufrufs.
 */
class OssIndexAuthenticationException(message: String) : IOException(message)

/**
 * Dieser Service ist für die Abfrage von Sicherheitsanfälligkeiten (Vulnerabilities)
 * über den Sonatype OSS Index zuständig.
 *
 * Die Hauptaufgaben dieser Klasse umfassen:
 * - Erstellung von Package-URLs (PURL) für Maven-Artefakte.
 * - Senden von Batch-Anfragen an die OSS Index API.
 * - Authentifizierung mittels API-Token (mit festem Platzhalter-Benutzernamen).
 * - Parsen der API-Antworten in das interne Modell `VulnerabilityAdvisory`.
 *
 * Dieser Service wird benötigt, um den Benutzer über bekannte Sicherheitslücken in
 * den verwendeten Abhängigkeiten zu informieren.
 */
class OssIndexApiService {
    /**
     * Erstellt eine Package-URL (PURL) gemäß dem Maven-Schema.
     * Beispiel: `pkg:maven/org.apache.logging.log4j/log4j-core@2.14.1`
     */
    fun buildPackageUrl(groupId: String, artifactId: String, version: String): String =
        "pkg:maven/${encode(groupId)}/${encode(artifactId)}@${encode(version)}"

    /**
     * Parst das JSON-Array der API-Antwort und ordnet die gefundenen Schwachstellen
     * den ursprünglichen Abhängigkeits-Schlüsseln (groupId:artifactId:version) zu.
     */
    fun parseReports(
        responseBody: String,
        coordinateToKey: Map<String, String>
    ): Map<String, List<VulnerabilityAdvisory>> {
        val reports = JsonParser.parseString(responseBody).asJsonArray
        val results = coordinateToKey.values.associateWith { emptyList<VulnerabilityAdvisory>() }.toMutableMap()

        reports.forEach { reportElement ->
            val report = reportElement.asJsonObject
            val coordinate = report.get("coordinates")?.asString.orEmpty()
            val key = coordinateToKey[coordinate] ?: return@forEach
            results[key] = report.getAsJsonArray("vulnerabilities")
                ?.mapNotNull { parseVulnerability(it.asJsonObject) }
                .orEmpty()
        }
        return results
    }

    /**
     * Führt eine einzelne Batch-Anfrage für eine Liste von Abhängigkeiten durch.
     * Erfordert ein API-Token; der Benutzername des Auth-Headers ist ein fester Platzhalter.
     */
    fun fetchVulnerabilityAdvisoriesForChunk(
        chunk: List<Triple<String, String, String>>,
        token: String? = null,
        reportUrl: String = OSS_INDEX_REPORT_URL
    ): Map<String, List<VulnerabilityAdvisory>> {
        if (chunk.isEmpty()) return emptyMap()
        if (token.isNullOrBlank()) {
            OSS_INDEX_LOG.warn("Skipping OSS Index request because the API token is missing.")
            return emptyMap()
        }

        val coordinateToKey = chunk.associate { (groupId, artifactId, version) ->
            buildPackageUrl(groupId, artifactId, version) to "$groupId:$artifactId:$version"
        }
        val requestBody = JsonObject().apply {
            add("coordinates", JsonArray().apply { coordinateToKey.keys.forEach(::add) })
        }
        val requestJson = Gson().toJson(requestBody)
        val connection = (URI(reportUrl).toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10000
            readTimeout = 20000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            val value = Base64.getEncoder()
                .encodeToString("$OSS_INDEX_AUTH_USERNAME:$token".toByteArray(StandardCharsets.UTF_8))
            setRequestProperty("Authorization", "Basic $value")
        }

        connection.outputStream.use { it.write(requestJson.toByteArray(StandardCharsets.UTF_8)) }
        val responseCode = connection.responseCode
        if (responseCode != HttpURLConnection.HTTP_OK) {
            val errorBody = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (responseCode == HttpURLConnection.HTTP_UNAUTHORIZED ||
                responseCode == HttpURLConnection.HTTP_FORBIDDEN
            ) {
                throw OssIndexAuthenticationException(
                    "OSS Index rejected the API token with HTTP $responseCode " +
                        "${connection.responseMessage}. Body: $errorBody"
                )
            }
            throw IOException(
                "OSS Index request failed with HTTP $responseCode ${connection.responseMessage}. Body: $errorBody"
            )
        }

        val responseBody = connection.inputStream.bufferedReader().use { it.readText() }
        val results = parseReports(responseBody, coordinateToKey)
        OSS_INDEX_LOG.info(
            "OSS Index checked ${chunk.size} components and returned " +
                "${results.values.count { it.isNotEmpty() }} vulnerable components."
        )
        return results
    }

    /**
     * Die Hauptmethode zum Abrufen von Schwachstellen für eine Liste von Abhängigkeiten.
     * Teilt die Liste in Chunks auf, um API-Limits einzuhalten, und aktualisiert den Fortschrittsindikator.
     */
    fun fetchVulnerabilityAdvisories(
        dependencies: List<Triple<String, String, String>>,
        token: String? = null,
        indicator: ProgressIndicator? = null
    ): Map<String, List<VulnerabilityAdvisory>> {
        if (dependencies.isEmpty()) return emptyMap()
        if (token.isNullOrBlank()) {
            OSS_INDEX_LOG.warn("Skipping OSS Index request because the API token is missing.")
            return emptyMap()
        }

        val results = mutableMapOf<String, List<VulnerabilityAdvisory>>()
        val chunks = dependencies.chunked(OSS_INDEX_BATCH_SIZE)
        chunks.forEachIndexed { index, chunk ->
            if (indicator?.isCanceled == true) return results
            indicator?.text2 = "OSS Index ${index + 1}/${chunks.size}"
            results.putAll(fetchVulnerabilityAdvisoriesForChunk(chunk, token))
        }
        return results
    }

    /**
     * Transformiert ein einzelnes Vulnerability-Objekt aus der JSON-Antwort
     * in ein `VulnerabilityAdvisory`-Modellobjekt.
     */
    private fun parseVulnerability(vulnerability: JsonObject): VulnerabilityAdvisory? {
        val id = vulnerability.get("id")?.asString?.trim().orEmpty()
        if (id.isEmpty()) return null

        val cvssScore = vulnerability.get("cvssScore")?.takeUnless { it.isJsonNull }?.asDouble
        val cvssVector = vulnerability.get("cvssVector")?.takeUnless { it.isJsonNull }?.asString
            ?.trim()?.takeIf(String::isNotEmpty)
        val aliases = buildSet {
            vulnerability.get("cve")?.takeUnless { it.isJsonNull }?.asString
                ?.split(',', ' ')
                ?.map(String::trim)
                ?.filter(String::isNotEmpty)
                ?.let(::addAll)
        } - id
        val reference = vulnerability.get("reference")?.takeUnless { it.isJsonNull }?.asString.orEmpty()
        return VulnerabilityAdvisory(
            id = id,
            aliases = aliases,
            summary = vulnerability.get("title")?.asString
                ?: vulnerability.get("description")?.asString
                ?: "",
            severity = VulnerabilitySeverity.fromScore(cvssScore),
            cvssScore = cvssScore,
            cvssVector = cvssVector,
            references = setOfNotNull(reference.takeIf(String::isNotEmpty)),
            sources = setOf(OSS_INDEX_SOURCE)
        )
    }

    /**
     * Hilfsmethode zur URL-Codierung von PURL-Komponenten gemäß den Anforderungen des OSS Index.
     */
    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")
}
