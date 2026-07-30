package de.schwarzland.mavenup.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogSummaryTest {
    @Test
    fun testShortListsRemainComplete() {
        assertEquals("one, two", summarizeForDebugLog(listOf("one", "two")))
    }

    @Test
    fun testLongListsAreLimitedAndReportOmittedItems() {
        val values = (1..15).map { "item-$it" }

        val summary = summarizeForDebugLog(values)

        assertTrue(summary.contains("item-10"))
        assertFalse(summary.contains("item-11"))
        assertTrue(summary.endsWith("... (+5 more)"))
    }
}
