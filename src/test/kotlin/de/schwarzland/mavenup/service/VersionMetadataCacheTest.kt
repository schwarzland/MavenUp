package de.schwarzland.mavenup.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionMetadataCacheTest {

    @Test
    fun testGetOrFetchCallsFetchOnFirstAccess() {
        val cache = VersionMetadataCache()
        var fetchCount = 0

        val versions = cache.getOrFetch("com.example", "artifact", ttlMinutes = 60) {
            fetchCount++
            listOf("1.0.0", "1.1.0")
        }

        assertEquals(listOf("1.0.0", "1.1.0"), versions)
        assertEquals(1, fetchCount)
    }

    @Test
    fun testGetOrFetchReturnsCachedResultWithinTtl() {
        val cache = VersionMetadataCache()
        var fetchCount = 0
        val fetch: () -> List<String> = {
            fetchCount++
            listOf("1.0.0")
        }

        cache.getOrFetch("com.example", "artifact", ttlMinutes = 60, nowMillis = 0L, fetch = fetch)
        val second = cache.getOrFetch("com.example", "artifact", ttlMinutes = 60, nowMillis = 1_000L, fetch = fetch)

        assertEquals(listOf("1.0.0"), second)
        assertEquals(1, fetchCount)
    }

    @Test
    fun testGetOrFetchRefetchesAfterTtlExpiry() {
        val cache = VersionMetadataCache()
        var fetchCount = 0
        val fetch: () -> List<String> = {
            fetchCount++
            listOf("1.0.0")
        }

        cache.getOrFetch("com.example", "artifact", ttlMinutes = 1, nowMillis = 0L, fetch = fetch)
        cache.getOrFetch("com.example", "artifact", ttlMinutes = 1, nowMillis = 120_000L, fetch = fetch)

        assertEquals(2, fetchCount)
    }

    @Test
    fun testGetOrFetchDoesNotCacheWhenTtlIsZeroOrLess() {
        val cache = VersionMetadataCache()
        var fetchCount = 0
        val fetch: () -> List<String> = {
            fetchCount++
            listOf("1.0.0")
        }

        cache.getOrFetch("com.example", "artifact", ttlMinutes = 0, fetch = fetch)
        cache.getOrFetch("com.example", "artifact", ttlMinutes = 0, fetch = fetch)

        assertEquals(2, fetchCount)
        assertEquals(0, cache.size())
    }

    @Test
    fun testGetOrFetchDoesNotCacheEmptyResults() {
        val cache = VersionMetadataCache()
        var fetchCount = 0

        cache.getOrFetch("com.example", "artifact", ttlMinutes = 60) {
            fetchCount++
            emptyList()
        }
        cache.getOrFetch("com.example", "artifact", ttlMinutes = 60) {
            fetchCount++
            emptyList()
        }

        assertEquals(2, fetchCount)
        assertEquals(0, cache.size())
    }

    @Test
    fun testGetOrFetchUsesSeparateEntriesPerArtifact() {
        val cache = VersionMetadataCache()

        cache.getOrFetch("com.example", "artifact-a", ttlMinutes = 60) { listOf("1.0.0") }
        cache.getOrFetch("com.example", "artifact-b", ttlMinutes = 60) { listOf("2.0.0") }

        assertEquals(2, cache.size())
    }

    @Test
    fun testInvalidateRemovesSingleEntry() {
        val cache = VersionMetadataCache()
        cache.getOrFetch("com.example", "artifact", ttlMinutes = 60) { listOf("1.0.0") }

        cache.invalidate("com.example", "artifact")

        assertEquals(0, cache.size())
    }

    @Test
    fun testClearRemovesAllEntries() {
        val cache = VersionMetadataCache()
        cache.getOrFetch("com.example", "artifact-a", ttlMinutes = 60) { listOf("1.0.0") }
        cache.getOrFetch("com.example", "artifact-b", ttlMinutes = 60) { listOf("2.0.0") }

        cache.clear()

        assertEquals(0, cache.size())
    }

    @Test
    fun testSizeReflectsCurrentEntryCount() {
        val cache = VersionMetadataCache()
        assertTrue(cache.size() == 0)

        cache.getOrFetch("com.example", "artifact", ttlMinutes = 60) { listOf("1.0.0") }

        assertFalse(cache.size() == 0)
    }

    @Test
    fun testSnapshotReturnsEmptyListWhenCacheIsEmpty() {
        val cache = VersionMetadataCache()

        assertTrue(cache.snapshot().isEmpty())
    }

    @Test
    fun testSnapshotReflectsStoredArtifactsCountsAndTimestamps() {
        val cache = VersionMetadataCache()
        cache.getOrFetch("com.example", "artifact-a", ttlMinutes = 60, nowMillis = 111L) { listOf("1.0.0", "1.1.0") }
        cache.getOrFetch("com.example", "artifact-b", ttlMinutes = 60, nowMillis = 222L) { listOf("2.0.0") }

        val snapshot = cache.snapshot().associateBy { it.artifactId }

        assertEquals("com.example", snapshot.getValue("artifact-a").groupId)
        assertEquals(2, snapshot.getValue("artifact-a").versionCount)
        assertEquals(111L, snapshot.getValue("artifact-a").timestampMillis)
        assertEquals("com.example", snapshot.getValue("artifact-b").groupId)
        assertEquals(1, snapshot.getValue("artifact-b").versionCount)
        assertEquals(222L, snapshot.getValue("artifact-b").timestampMillis)
    }

    @Test
    fun testSnapshotIsUnaffectedByLaterCacheChanges() {
        val cache = VersionMetadataCache()
        cache.getOrFetch("com.example", "artifact-a", ttlMinutes = 60) { listOf("1.0.0") }

        val snapshot = cache.snapshot()
        cache.getOrFetch("com.example", "artifact-b", ttlMinutes = 60) { listOf("2.0.0") }

        assertEquals(1, snapshot.size)
    }
}
