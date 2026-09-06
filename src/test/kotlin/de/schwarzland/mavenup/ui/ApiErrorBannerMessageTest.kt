package de.schwarzland.mavenup.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Testet die Aufbereitung der Fehlermeldungen für das HTML-basierte API-Fehlerbanner
 * (siehe [formatApiErrorBannerMessage]).
 */
class ApiErrorBannerMessageTest {

    @Test
    fun `no message yields no banner text`() {
        assertNull(formatApiErrorBannerMessage(emptyList()))
    }

    @Test
    fun `blank messages are discarded`() {
        assertNull(formatApiErrorBannerMessage(listOf("", "   ")))
    }

    @Test
    fun `a single message is used unchanged`() {
        assertEquals("OSV.dev is unreachable.", formatApiErrorBannerMessage(listOf("OSV.dev is unreachable.")))
    }

    @Test
    fun `several messages are separated by a html line break`() {
        val message = formatApiErrorBannerMessage(listOf("OSV.dev failed.", "OSS Index failed."))

        assertEquals("OSV.dev failed.<br>OSS Index failed.", message)
    }

    @Test
    fun `duplicate messages appear only once`() {
        val message = formatApiErrorBannerMessage(listOf("Network error.", " Network error. "))

        assertEquals("Network error.", message)
    }

    @Test
    fun `html special characters are escaped`() {
        val message = formatApiErrorBannerMessage(listOf("Host <unknown> failed & timed out"))

        assertEquals("Host &lt;unknown&gt; failed &amp; timed out", message)
    }
}
