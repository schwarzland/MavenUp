package de.schwarzland.mavenup.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.schwarzland.mavenup.service.ManagedDependencyRecommendationResult
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Prüft, dass der Cleanup-Runner Filter und Analyseergebnis zwischen Hintergrundaufgabe und UI weitergibt.
 */
class ManagedDependencyCleanupCheckRunnerTest : BasePlatformTestCase() {

    /**
     * Prüft Scope-Weitergabe, Ergebnis-Callback und Abschluss-Callback der Hintergrundaufgabe.
     */
    fun testStartPassesScopeAndDeliversResult() {
        val result = ManagedDependencyRecommendationResult(emptyList(), setOf("Versions: g:library"))
        val receivedScope = AtomicReference<Pair<String?, String?>?>()
        val receivedResult = AtomicReference<ManagedDependencyRecommendationResult?>()
        val completed = CountDownLatch(1)
        val runner = ManagedDependencyCleanupCheckRunner(project, emptyMap()) { _, managed, trigger, _ ->
            receivedScope.set(managed to trigger)
            result
        }
        val scope = ManagedDependencyCleanupScope("g:managed", null, "scope")

        runner.start(
            scope = scope,
            onSuccess = { receivedResult.set(it) },
            onFinished = { completed.countDown() }
        )

        assertTrue("Cleanup analysis should finish", completed.await(10, TimeUnit.SECONDS))
        assertEquals("g:managed" to null, receivedScope.get())
        assertSame(result, receivedResult.get())
    }
}
