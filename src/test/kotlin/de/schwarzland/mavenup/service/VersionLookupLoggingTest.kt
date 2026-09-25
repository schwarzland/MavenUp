package de.schwarzland.mavenup.service

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.openapi.diagnostic.awaitLogQueueProcessed
import com.sun.net.httpserver.HttpServer
import org.apache.maven.artifact.versioning.ComparableVersion
import java.net.InetSocketAddress
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

/** Prüft die tatsächliche DEBUG-Ausgabe von Cache-Entscheidungen und Repository-Anfragen. */
class VersionLookupLoggingTest : BasePlatformTestCase() {

    /** Jeder Cache-Zustand wird mit Artefakt protokolliert; nur Treffer vermeiden die Live-Abfrage. */
    fun testCacheLogsHitsMissesExpiryAndDisabledCaching() {
        val cache = VersionMetadataCache()
        var requests = 0
        val fetch = { requests++; listOf("1.0") }
        val messages = captureDebugLogs(VersionMetadataCache::class.java) {
            cache.getOrFetch("g", "a", 1, 0L, fetch)
            cache.getOrFetch("g", "a", 1, 1_000L, fetch)
            cache.getOrFetch("g", "a", 1, 60_001L, fetch)
            cache.getOrFetch("g", "a", 0, 60_002L, fetch)
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
