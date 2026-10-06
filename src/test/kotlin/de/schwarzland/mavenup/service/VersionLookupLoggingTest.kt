package de.schwarzland.mavenup.service

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.openapi.diagnostic.awaitLogQueueProcessed
import com.intellij.openapi.util.io.FileUtil
import com.sun.net.httpserver.HttpServer
import org.apache.maven.artifact.versioning.ComparableVersion
import org.jetbrains.idea.maven.project.MavenProject
import org.jetbrains.idea.maven.project.MavenProjectsManager
import java.io.File
import java.net.InetSocketAddress
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

/** Prüft die DEBUG-Ausgabe von Cache-Entscheidungen, Repository-Anfragen und Bereinigungsanalysen. */
class VersionLookupLoggingTest : BasePlatformTestCase() {

    /** Jeder Cache-Zustand wird mit Artefakt protokolliert; nur Treffer vermeiden die Live-Abfrage. */
    fun testCacheLogsHitsMissesExpiryAndDisabledCaching() {
        val cache = VersionMetadataCache()
        var requests = 0
        val fetch = { requests++; listOf("1.0") }
        val messages = captureDebugLogs(VersionMetadataCache::class.java) {
            cache.getOrFetch("g", "a", 1, 0L, fetch = fetch)
            cache.getOrFetch("g", "a", 1, 1_000L, fetch = fetch)
            cache.getOrFetch("g", "a", 1, 60_001L, fetch = fetch)
            cache.getOrFetch("g", "a", 0, 60_002L, fetch = fetch)
        }
        assertEquals(3, requests)
        assertEquals(4, messages.size)
        assertTrue(messages[0].contains("Version cache miss for g:a"))
        assertTrue(messages[1].contains("Version cache hit for g:a"))
        assertTrue(messages[2].contains("Version cache expired for g:a"))
        assertTrue(messages[3].contains("Version cache disabled for g:a"))
    }

    /** HTTP-Versuche werden vor der Antwort protokolliert, auch bei fehlenden Metadaten und Serverfehlern. */
    fun testRepositoryLogsEachHttpAttemptButNotCacheHits() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var requests = 0
        var status = 200
        server.createContext("/") { exchange ->
            requests++
            val body = "<metadata><versioning><versions><version>1.0</version></versions></versioning></metadata>"
                .toByteArray()
            exchange.sendResponseHeaders(status, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
            exchange.close()
        }
        server.start()
        try {
            val cache = VersionMetadataCache()
            val service = DependencyApiService(project)
            val repository = "local" to "http://127.0.0.1:${server.address.port}"
            val messages = captureDebugLogs(DependencyApiService::class.java) {
                for (response in listOf(200, 404, 503)) {
                    status = response
                    cache.clear()
                    repeat(2) {
                        cache.getOrFetch("g", "a", 60) {
                            service.fetchVersionsFromRepository(
                                repository, "g", "a", ComparableVersion(""), emptyMap()
                            ).versions
                        }
                    }
                }
            }
            assertEquals(5, requests)
            assertEquals(5, messages.count {
                it == "Querying version metadata for g:a from 127.0.0.1 via HTTP GET"
            })
        } finally {
            server.stop(0)
        }
    }

    /** POM-Abfragen protokollieren jeden Status, aber keine URL-Zugangsdaten oder Header. */
    fun testPomRequestsLogHttpStatusesWithoutCredentials() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var requests = 0
        var status = 200
        server.createContext("/") { exchange ->
            requests++
            val body = "<project/>".toByteArray()
            exchange.sendResponseHeaders(status, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
            exchange.close()
        }
        server.start()
        try {
            val resolver = TemporaryDependencyTreeResolver()
            val messages = captureDebugLogs(TemporaryDependencyTreeResolver::class.java) {
                for (response in listOf(200, 404, 503)) {
                    status = response
                    val content = resolver.tryFetchPomFromRepository(
                        "local", "http://url-user:url-password@127.0.0.1:${server.address.port}",
                        "g/a/1/a-1.pom", mapOf("local" to ("auth-user" to "auth-password"))
                    )
                    if (response == 200) assertEquals("<project/>", content) else assertNull(content)
                }
            }
            assertEquals(3, requests)
            assertEquals(3, messages.count { it == "Querying POM g/a/1/a-1.pom from 127.0.0.1 via HTTP GET" })
            for (response in listOf(200, 404, 503)) {
                assertTrue(messages.contains("POM response for g/a/1/a-1.pom from 127.0.0.1: HTTP $response"))
            }
            assertFalse(messages.any {
                it.contains("url-user") || it.contains("url-password") ||
                    it.contains("auth-user") || it.contains("auth-password") || it.contains("Authorization")
            })
        } finally {
            server.stop(0)
        }
    }

    /** Fehlertexte und ungültige URLs dürfen keine Zugangsdaten ins Debug-Log schreiben. */
    fun testPomRequestFailureLogsSafeExceptionClass() {
        val messages = captureDebugLogs(TemporaryDependencyTreeResolver::class.java) {
            assertNull(
                TemporaryDependencyTreeResolver().tryFetchPomFromRepository(
                    null, "http://secret-user:secret-password@invalid host", "g/a/1/a-1.pom", emptyMap()
                )
            )
        }
        assertTrue(messages.contains("Failed to fetch POM g/a/1/a-1.pom from <invalid>: URISyntaxException"))
        assertFalse(messages.any { it.contains("secret") || it.contains("invalid host") })
    }

    /** Lokale POMs und Wiederholungen sind ohne HTTP-Abfrage eindeutig im Log erkennbar. */
    fun testPomLocalRepositoryAndMemoryCacheLogging() {
        val localRepository = FileUtil.createTempDirectory("mavenup-local-pom", "repository", true)
        val localPom = File(localRepository, "g${File.separator}a${File.separator}1${File.separator}a-1.pom")
        assertTrue(localPom.parentFile.mkdirs())
        localPom.writeText("<project/>")
        val settings = MavenProjectsManager.getInstance(project).generalSettings
        val oldRepository = settings.localRepository
        settings.setLocalRepository(localRepository.path)
        try {
            val resolver = TemporaryDependencyTreeResolver(project)
            val messages = captureDebugLogs(TemporaryDependencyTreeResolver::class.java) {
                assertEquals("<project/>", resolver.fetchPomXml("g", "a", "1"))
                assertEquals("<project/>", resolver.fetchPomXml("g", "a", "1"))
            }
            assertTrue(messages.contains("POM cache miss for g:a:1"))
            assertTrue(messages.contains("Local Maven POM hit for g:a:1"))
            assertTrue(messages.contains("POM cache hit for g:a:1"))
            assertFalse(messages.any { it.contains("HTTP GET") })
        } finally {
            settings.setLocalRepository(oldRepository)
            FileUtil.delete(localRepository)
        }
    }

    /** Injizierte POM-Quellen unterscheiden erfolgreiche, fehlende und wiederverwendete Inhalte. */
    fun testPomProviderAndCacheLogging() {
        var requests = 0
        val resolver = TemporaryDependencyTreeResolver { _, artifactId, _ ->
            requests++
            if (artifactId == "found") "<project/>" else null
        }
        val messages = captureDebugLogs(TemporaryDependencyTreeResolver::class.java) {
            assertNotNull(resolver.fetchPomXml("g", "found", "1"))
            assertNotNull(resolver.fetchPomXml("g", "found", "1"))
            assertNull(resolver.fetchPomXml("g", "missing", "1"))
        }
        assertEquals(2, requests)
        assertTrue(messages.contains("POM provider lookup for g:found:1: found=true"))
        assertTrue(messages.contains("POM cache hit for g:found:1"))
        assertTrue(messages.contains("POM provider lookup for g:missing:1: found=false"))
    }

    /** Auch ohne Maven-Projekte bleibt eine globale oder zeilenbezogene Analyse nachvollziehbar. */
    fun testEmptyCleanupAnalysisLogsScopeAndCompletion() {
        val service = ManagedDependencyRecommendationService(project)
        val messages = captureDebugLogs(ManagedDependencyRecommendationService::class.java) {
            assertEmpty(service.findRecommendations())
            assertEmpty(service.findRecommendations(managedCoordinate = "g:managed"))
            assertEmpty(service.findRecommendations(triggerCoordinate = "g:parent"))
        }
        assertTrue(messages.contains("Cleanup analysis started: projects=0, managed=all, trigger=all"))
        assertTrue(messages.contains("Cleanup analysis started: projects=0, managed=g:managed, trigger=all"))
        assertTrue(messages.contains("Cleanup analysis started: projects=0, managed=all, trigger=g:parent"))
        assertEquals(3, messages.count { it == "Cleanup analysis completed: recommendations=0" })
    }

    /** Kandidatenbewertungen erklären fehlende, zu alte und kompatible bereitgestellte Versionen. */
    fun testCleanupCandidateLogsRejectionAndAcceptance() {
        val resolver = TemporaryDependencyTreeResolver { _, _, version ->
            val managedDependency = if (version == "2") "" else """
                <dependencyManagement><dependencies><dependency>
                    <groupId>g</groupId><artifactId>managed</artifactId>
                    <version>${if (version == "3") "0.5" else "1"}</version>
                </dependency></dependencies></dependencyManagement>
            """.trimIndent()
            "<project><groupId>g</groupId><artifactId>parent</artifactId><version>$version</version>" +
                "$managedDependency</project>"
        }
        val mavenProject = MavenProject(myFixture.configureByText("pom.xml", "<project/>").virtualFile)
        val managed = ManagedDependencyRecommendationService.ManagedDependencyDeclaration("g", "managed", "1", mavenProject)
        val trigger = ManagedDependencyRecommendationService.TriggerCandidate(
            "g", "parent", "1", "parent", listOf("2", "3", "4"), mavenProject
        )
        val service = ManagedDependencyRecommendationService(project, resolver)
        val messages = captureDebugLogs(ManagedDependencyRecommendationService::class.java) {
            assertNull(service.evaluateTriggerRecommendation(managed, ComparableVersion("1"), trigger, "2", emptyList()))
            assertNull(service.evaluateTriggerRecommendation(managed, ComparableVersion("1"), trigger, "3", emptyList()))
            assertNotNull(service.evaluateTriggerRecommendation(managed, ComparableVersion("1"), trigger, "4", emptyList()))
        }
        assertTrue(messages.contains("Checking cleanup for g:managed:1 with parent g:parent:1 -> 2"))
        assertTrue(messages.contains("Cleanup parent candidate g:parent:2 does not provide g:managed"))
        assertTrue(messages.contains("Cleanup rejected for g:managed: provided 0.5 is older than 1"))
        assertTrue(messages.contains(
            "Cleanup candidate result for g:managed via g:parent:4: provided=1, all consumers satisfied=true"
        ))
    }

    /** Zeichnet DEBUG-Nachrichten des IntelliJ-JUL-Loggers auf und stellt dessen Konfiguration wieder her. */
    private fun captureDebugLogs(category: Class<*>, action: () -> Unit): List<String> {
        awaitLogQueueProcessed()
        val messages = mutableListOf<String>()
        val logger = Logger.getLogger("#${category.name}")
        val oldLevel = logger.level
        val handler = object : Handler() {
            /** Übernimmt ausschließlich DEBUG-Nachrichten. */
            override fun publish(record: LogRecord) {
                if (record.level == Level.FINE) messages.add(record.message)
            }

            /** Der Speicherpuffer benötigt kein Flush. */
            override fun flush() = Unit

            /** Der Handler hält keine externen Ressourcen. */
            override fun close() = Unit
        }
        handler.level = Level.ALL
        logger.addHandler(handler)
        logger.level = Level.ALL
        try {
            action()
        } finally {
            awaitLogQueueProcessed()
            logger.removeHandler(handler)
            logger.level = oldLevel
            handler.close()
        }
        return messages
    }
}
