package de.schwarzland.mavenup.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.schwarzland.mavenup.model.RedundancyReason
import de.schwarzland.mavenup.model.RedundantManagedDependencyRecommendation
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Prüft den [RedundantManagedDependencyCheckRunner].
 */
class RedundantManagedDependencyCheckRunnerTest : BasePlatformTestCase() {

    /**
     * Prüft Scope-Weitergabe, Ergebnis-Callback und Abschluss-Callback der Hintergrundaufgabe.
     */
    fun testStartPassesScopeAndDeliversResult() {
        val rec = RedundantManagedDependencyRecommendation(
            groupId = "org.example",
            artifactId = "unused-lib",
            currentVersion = "1.0.0",
            reason = RedundancyReason.UNUSED,
            reasonDetail = "Unused in project"
        )
        val expectedResult = listOf(rec)
        val receivedCoordinate = AtomicReference<String?>()
        val receivedResult = AtomicReference<List<RedundantManagedDependencyRecommendation>?>()
        val completed = CountDownLatch(1)

        val runner = RedundantManagedDependencyCheckRunner(project) { coordinate, _ ->
            receivedCoordinate.set(coordinate)
            expectedResult
        }
        val scope = RedundantManagedDependencyScope("org.example:unused-lib", "scope description")

        runner.start(
            scope = scope,
            onSuccess = { receivedResult.set(it) },
            onFinished = { completed.countDown() }
        )

        assertTrue("Analysis should finish", completed.await(10, TimeUnit.SECONDS))
        assertEquals("org.example:unused-lib", receivedCoordinate.get())
        assertEquals(expectedResult, receivedResult.get())
    }
}
