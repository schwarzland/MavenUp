package de.schwarzland.mavenup.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class VersionMetadataCacheTest {

    @Rule
    @JvmField
    val temporaryFolder = TemporaryFolder()

    @Test
    fun testGetOrFetchCallsFetchOnFirstAccess() {
        val cache = VersionMetadataCache(storagePath = null)
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
        val cache = VersionMetadataCache(storagePath = null)
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
    fun testGetOrFetchReportsValidCacheHit() {
        val cache = VersionMetadataCache(storagePath = null)
        var cacheHitCount = 0
        cache.getOrFetch("com.example", "artifact", ttlMinutes = 60, nowMillis = 0L) { listOf("1.0.0") }

        cache.getOrFetch(
            "com.example",
            "artifact",
            ttlMinutes = 60,
            nowMillis = 1_000L,
            onCacheHit = { cacheHitCount++ }
        ) {
            error("A valid cache hit must not fetch versions")
        }

        assertEquals(1, cacheHitCount)
    }

    @Test
    fun testGetOrFetchRefetchesAfterTtlExpiry() {
        val cache = VersionMetadataCache(storagePath = null)
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
        val cache = VersionMetadataCache(storagePath = null)
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
        val cache = VersionMetadataCache(storagePath = null)
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
        val cache = VersionMetadataCache(storagePath = null)

        cache.getOrFetch("com.example", "artifact-a", ttlMinutes = 60) { listOf("1.0.0") }
        cache.getOrFetch("com.example", "artifact-b", ttlMinutes = 60) { listOf("2.0.0") }

        assertEquals(2, cache.size())
    }

    @Test
    fun testInvalidateRemovesSingleEntry() {
        val cache = VersionMetadataCache(storagePath = null)
        cache.getOrFetch("com.example", "artifact", ttlMinutes = 60) { listOf("1.0.0") }

        cache.invalidate("com.example", "artifact")

        assertEquals(0, cache.size())
    }

    @Test
    fun testClearRemovesAllEntries() {
        val cache = VersionMetadataCache(storagePath = null)
        cache.getOrFetch("com.example", "artifact-a", ttlMinutes = 60) { listOf("1.0.0") }
        cache.getOrFetch("com.example", "artifact-b", ttlMinutes = 60) { listOf("2.0.0") }

        cache.clear()

        assertEquals(0, cache.size())
    }

    @Test
    fun testSizeReflectsCurrentEntryCount() {
        val cache = VersionMetadataCache(storagePath = null)
        assertTrue(cache.size() == 0)

        cache.getOrFetch("com.example", "artifact", ttlMinutes = 60) { listOf("1.0.0") }

        assertFalse(cache.size() == 0)
    }

    @Test
    fun testSnapshotReturnsEmptyListWhenCacheIsEmpty() {
        val cache = VersionMetadataCache(storagePath = null)

        assertTrue(cache.snapshot().isEmpty())
    }

    @Test
    fun testSnapshotReflectsStoredArtifactsCountsAndTimestamps() {
        val cache = VersionMetadataCache(storagePath = null)
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
        val cache = VersionMetadataCache(storagePath = null)
        cache.getOrFetch("com.example", "artifact-a", ttlMinutes = 60) { listOf("1.0.0") }

        val snapshot = cache.snapshot()
        cache.getOrFetch("com.example", "artifact-b", ttlMinutes = 60) { listOf("2.0.0") }

        assertEquals(1, snapshot.size)
    }

    @Test
    fun testPersistsToDiskAndLoadsAcrossInstances() {
        val storagePath = temporaryFolder.newFile("version-cache.json").toPath()
        val cache1 = VersionMetadataCache(storagePath = storagePath)

        cache1.getOrFetch("com.example", "artifact-a", ttlMinutes = 60, nowMillis = 1000L) {
            listOf("1.0.0", "1.1.0")
        }

        assertTrue(Files.exists(storagePath))

        // Simuliere IDE-Neustart durch neue Instanz mit demselben Speicherpfad
        val cache2 = VersionMetadataCache(storagePath = storagePath)
        assertEquals(1, cache2.size())

        var fetchCalled = false
        val versions = cache2.getOrFetch("com.example", "artifact-a", ttlMinutes = 60, nowMillis = 2000L) {
            fetchCalled = true
            listOf("2.0.0")
        }

        assertFalse(fetchCalled)
        assertEquals(listOf("1.0.0", "1.1.0"), versions)

        val snapshot = cache2.snapshot().first()
        assertEquals("com.example", snapshot.groupId)
        assertEquals("artifact-a", snapshot.artifactId)
        assertEquals(2, snapshot.versionCount)
        assertEquals(1000L, snapshot.timestampMillis)
    }

    @Test
    fun testDiskPersistenceInvalidateAndClear() {
        val storagePath = temporaryFolder.newFile("version-cache-inv.json").toPath()
        val cache1 = VersionMetadataCache(storagePath = storagePath)

        cache1.getOrFetch("com.example", "artifact-a", ttlMinutes = 60, nowMillis = 1000L) { listOf("1.0.0") }
        cache1.getOrFetch("com.example", "artifact-b", ttlMinutes = 60, nowMillis = 1000L) { listOf("2.0.0") }
        assertEquals(2, cache1.size())

        cache1.invalidate("com.example", "artifact-a")

        val cache2 = VersionMetadataCache(storagePath = storagePath)
        assertEquals(1, cache2.size())
        assertEquals("artifact-b", cache2.snapshot().first().artifactId)

        cache2.clear()

        val cache3 = VersionMetadataCache(storagePath = storagePath)
        assertEquals(0, cache3.size())
    }

    @Test
    fun testDiskCorruptedFileHandledGracefully() {
        val storagePath = temporaryFolder.newFile("version-cache-corrupt.json").toPath()
        Files.writeString(storagePath, "{ corrupted json syntax")

        val cache = VersionMetadataCache(storagePath = storagePath)
        assertEquals(0, cache.size())

        // Cache muss weiterhin voll funktionsfähig sein
        val versions = cache.getOrFetch("com.example", "artifact-a", ttlMinutes = 60) {
            listOf("1.0.0")
        }
        assertEquals(listOf("1.0.0"), versions)
        assertEquals(1, cache.size())

        // Und den Zustand persistent überschreiben
        val reloadedCache = VersionMetadataCache(storagePath = storagePath)
        assertEquals(1, reloadedCache.size())
    }

    @Test
    fun testExpiredEntryLoadedFromDiskIsRefetched() {
        val storagePath = temporaryFolder.newFile("version-cache-exp.json").toPath()
        val cache1 = VersionMetadataCache(storagePath = storagePath)

        cache1.getOrFetch("com.example", "artifact-a", ttlMinutes = 1, nowMillis = 1000L) { listOf("1.0.0") }

        // Nach Ablauf der TTL (z. B. nach 2 Minuten)
        val cache2 = VersionMetadataCache(storagePath = storagePath)
        var fetchCount = 0
        val versions = cache2.getOrFetch("com.example", "artifact-a", ttlMinutes = 1, nowMillis = 130_000L) {
            fetchCount++
            listOf("2.0.0")
        }

        assertEquals(1, fetchCount)
        assertEquals(listOf("2.0.0"), versions)
    }

    @Test
    fun testConcurrentGetOrFetchCoalescesToSingleFetch() {
        val cache = VersionMetadataCache(storagePath = null)
        val threadCount = 8
        val executor = Executors.newFixedThreadPool(threadCount)
        val readyLatch = CountDownLatch(threadCount)
        val startLatch = CountDownLatch(1)
        val fetchCount = AtomicInteger(0)

        try {
            val futures = (1..threadCount).map {
                executor.submit<List<String>> {
                    readyLatch.countDown()
                    startLatch.await(5, TimeUnit.SECONDS)
                    cache.getOrFetch("com.example", "artifact", ttlMinutes = 60) {
                        fetchCount.incrementAndGet()
                        Thread.sleep(50)
                        listOf("1.0.0", "1.1.0")
                    }
                }
            }

            assertTrue(readyLatch.await(5, TimeUnit.SECONDS))
            startLatch.countDown()

            val results = futures.map { it.get(5, TimeUnit.SECONDS) }
            assertEquals(1, fetchCount.get())
            results.forEach { result ->
                assertEquals(listOf("1.0.0", "1.1.0"), result)
            }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun testConcurrentGetOrFetchHandlesExceptionGracefullyAndAllowsRetry() {
        val cache = VersionMetadataCache(storagePath = null)
        val threadCount = 4
        val executor = Executors.newFixedThreadPool(threadCount)
        val readyLatch = CountDownLatch(threadCount)
        val startLatch = CountDownLatch(1)
        val fetchCount = AtomicInteger(0)

        try {
            val futures = (1..threadCount).map {
                executor.submit<List<String>> {
                    readyLatch.countDown()
                    startLatch.await(5, TimeUnit.SECONDS)
                    cache.getOrFetch("com.example", "artifact", ttlMinutes = 60) {
                        fetchCount.incrementAndGet()
                        Thread.sleep(30)
                        throw IllegalStateException("Simulated network failure")
                    }
                }
            }

            assertTrue(readyLatch.await(5, TimeUnit.SECONDS))
            startLatch.countDown()

            futures.forEach { future ->
                assertThrows(java.util.concurrent.ExecutionException::class.java) {
                    future.get(5, TimeUnit.SECONDS)
                }
            }
            assertEquals(1, fetchCount.get())

            // Neuer Versuch nach fehlgeschlagenem Request darf nicht blockiert sein
            val retryVersions = cache.getOrFetch("com.example", "artifact", ttlMinutes = 60) {
                fetchCount.incrementAndGet()
                listOf("2.0.0")
            }
            assertEquals(listOf("2.0.0"), retryVersions)
            assertEquals(2, fetchCount.get())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun testConcurrentGetOrFetchForDifferentKeysRunsInParallel() {
        val cache = VersionMetadataCache(storagePath = null)
        val executor = Executors.newFixedThreadPool(2)
        val fetchCount = AtomicInteger(0)

        try {
            val future1 = executor.submit<List<String>> {
                cache.getOrFetch("com.example", "artifact-1", ttlMinutes = 60) {
                    fetchCount.incrementAndGet()
                    listOf("1.0.0")
                }
            }
            val future2 = executor.submit<List<String>> {
                cache.getOrFetch("com.example", "artifact-2", ttlMinutes = 60) {
                    fetchCount.incrementAndGet()
                    listOf("2.0.0")
                }
            }

            assertEquals(listOf("1.0.0"), future1.get(5, TimeUnit.SECONDS))
            assertEquals(listOf("2.0.0"), future2.get(5, TimeUnit.SECONDS))
            assertEquals(2, fetchCount.get())
        } finally {
            executor.shutdownNow()
        }
    }
}
