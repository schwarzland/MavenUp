package de.schwarzland.mavenup.service

import com.sun.net.httpserver.HttpServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

class OssIndexApiServiceTest {
    private val service = OssIndexApiService()

    @Test
    fun testBuildPackageUrlUsesMavenPurlFormat() {
        assertEquals(
            "pkg:maven/com.example/demo-core@1.2.3",
            service.buildPackageUrl("com.example", "demo-core", "1.2.3")
        )
    }

    @Test
    fun testParseReportsMapsMetadataAndEmptyResults() {
        val responseBody = """
            [
              {
                "coordinates":"pkg:maven/com.example/vulnerable@1.0",
                "vulnerabilities":[{
                  "id":"sonatype-2026-1",
                  "cve":"CVE-2026-1234",
                  "cvssScore":9.8,
                  "title":"Critical issue",
                  "reference":"https://example.test/sonatype-2026-1"
                }]
              }
            ]
        """.trimIndent()
        val coordinates = mapOf(
            "pkg:maven/com.example/vulnerable@1.0" to "com.example:vulnerable:1.0",
            "pkg:maven/com.example/clean@1.0" to "com.example:clean:1.0"
        )

        val result = service.parseReports(responseBody, coordinates)

        val advisory = result.getValue("com.example:vulnerable:1.0").single()
        assertEquals("sonatype-2026-1", advisory.id)
        assertTrue(advisory.aliases.contains("CVE-2026-1234"))
        assertEquals("CRITICAL", advisory.severity.name)
        assertEquals(9.8, advisory.cvssScore)
        assertTrue(result.getValue("com.example:clean:1.0").isEmpty())
    }

    @Test
    fun testFetchChunkSendsPurlsAndBasicAuthentication() {
        val responseBody = """
            [{"coordinates":"pkg:maven/com.example/demo@1.0","vulnerabilities":[]}]
        """.trimIndent()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var receivedBody = ""
        var authorization = ""
        server.createContext("/api/v3/component-report") { exchange ->
            receivedBody = exchange.requestBody.bufferedReader().use { it.readText() }
            authorization = exchange.requestHeaders.getFirst("Authorization").orEmpty()
            val bytes = responseBody.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()

        try {
            val result = service.fetchVulnerabilityAdvisoriesForChunk(
                listOf(Triple("com.example", "demo", "1.0")),
                "secret",
                "http://127.0.0.1:${server.address.port}/api/v3/component-report"
            )

            assertTrue(receivedBody.contains("pkg:maven/com.example/demo@1.0"))
            assertEquals(
                "Basic " + java.util.Base64.getEncoder().encodeToString(
                    "$OSS_INDEX_AUTH_USERNAME:secret".toByteArray()
                ),
                authorization
            )
            assertTrue(result.getValue("com.example:demo:1.0").isEmpty())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun testFetchChunkSkipsRequestWithoutToken() {
        val requestCount = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/v3/component-report") { exchange ->
            requestCount.incrementAndGet()
            exchange.sendResponseHeaders(500, -1)
            exchange.close()
        }
        server.start()

        try {
            val reportUrl = "http://127.0.0.1:${server.address.port}/api/v3/component-report"
            val dependencies = listOf(Triple("com.example", "demo", "1.0"))

            assertTrue(
                service.fetchVulnerabilityAdvisoriesForChunk(
                    dependencies,
                    "",
                    reportUrl
                ).isEmpty()
            )
            assertTrue(
                service.fetchVulnerabilityAdvisoriesForChunk(
                    dependencies,
                    null,
                    reportUrl
                ).isEmpty()
            )
            assertEquals(0, requestCount.get())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun testFetchChunkThrowsAuthenticationExceptionOnUnauthorized() {
        assertAuthenticationExceptionForStatus(401)
    }

    @Test
    fun testFetchChunkThrowsAuthenticationExceptionOnForbidden() {
        assertAuthenticationExceptionForStatus(403)
    }

    @Test
    fun testFetchChunkThrowsGenericIoExceptionOnServerError() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/v3/component-report") { exchange ->
            val bytes = "boom".toByteArray()
            exchange.sendResponseHeaders(500, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()

        try {
            val reportUrl = "http://127.0.0.1:${server.address.port}/api/v3/component-report"
            val error = org.junit.Assert.assertThrows(java.io.IOException::class.java) {
                service.fetchVulnerabilityAdvisoriesForChunk(
                    listOf(Triple("com.example", "demo", "1.0")),
                    "secret",
                    reportUrl
                )
            }
            assertTrue(error !is OssIndexAuthenticationException)
            assertTrue(error.message.orEmpty().contains("HTTP 500"))
        } finally {
            server.stop(0)
        }
    }

    private fun assertAuthenticationExceptionForStatus(statusCode: Int) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/v3/component-report") { exchange ->
            val bytes = "denied".toByteArray()
            exchange.sendResponseHeaders(statusCode, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()

        try {
            val reportUrl = "http://127.0.0.1:${server.address.port}/api/v3/component-report"
            val error = org.junit.Assert.assertThrows(OssIndexAuthenticationException::class.java) {
                service.fetchVulnerabilityAdvisoriesForChunk(
                    listOf(Triple("com.example", "demo", "1.0")),
                    "secret",
                    reportUrl
                )
            }
            assertTrue(error.message.orEmpty().contains("HTTP $statusCode"))
        } finally {
            server.stop(0)
        }
    }
}
