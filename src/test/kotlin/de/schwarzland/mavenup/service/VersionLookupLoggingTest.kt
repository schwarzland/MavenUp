package de.schwarzland.mavenup.service

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.openapi.diagnostic.awaitLogQueueProcessed
import com.intellij.openapi.util.io.FileUtil
import com.sun.net.httpserver.HttpServer
import org.apache.maven.artifact.versioning.ComparableVersion
import org.jetbrains.idea.maven.project.MavenProjectsManager
import java.io.File
import java.net.InetSocketAddress
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

/** Prüft die DEBUG-Ausgabe von Cache-Entscheidungen, Repository-Anfragen und Redundanzanalysen. */
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

    /** Auch ohne Maven-Projekte bleibt eine globale oder zeilenbezogene Redundanzanalyse nachvollziehbar. */
    fun testEmptyRedundantManagedAnalysisLogsScopeAndCompletion() {
        val service = RedundantManagedDependencyService(project)
        val messages = captureDebugLogs(RedundantManagedDependencyService::class.java) {
            assertEmpty(service.findRedundantManagedDependencies())
            assertEmpty(service.findRedundantManagedDependencies(managedCoordinate = "g:managed"))
        }
        assertTrue(messages.contains("Redundant managed dependencies analysis started: projects=0, managed=all"))
        assertTrue(messages.contains("Redundant managed dependencies analysis started: projects=0, managed=g:managed"))
        assertEquals(
            2,
            messages.count { it.startsWith("Redundant managed dependencies analysis completed: results=0") }
        )
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
