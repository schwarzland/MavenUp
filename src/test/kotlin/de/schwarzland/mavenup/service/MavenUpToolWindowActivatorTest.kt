package de.schwarzland.mavenup.service

import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Unit-Tests für [MavenUpToolWindowActivator].
 *
 * Prüft, dass das Verfügbarmachen des Tool-Windows robust ist, keine Exceptions wirft,
 * idempotent mehrfach aufgerufen werden kann und das Projekt dabei nicht beschädigt.
 */
class MavenUpToolWindowActivatorTest : BasePlatformTestCase() {

    /**
     * Test: makeToolWindowAvailable() sollte ohne Maven-Projekte keine Exception werfen.
     */
    fun testMakeToolWindowAvailableDoesNotThrow() {
        MavenUpToolWindowActivator.makeToolWindowAvailable(project)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertFalse("Projekt sollte nach Aktivierung nicht disposed sein", project.isDisposed)
    }

    /**
     * Test: makeToolWindowAvailable() sollte idempotent mehrfach aufrufbar sein.
     */
    fun testMakeToolWindowAvailableIsIdempotent() {
        repeat(3) { MavenUpToolWindowActivator.makeToolWindowAvailable(project) }
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertFalse("Projekt sollte nach mehrfacher Aktivierung nicht disposed sein", project.isDisposed)
    }
}
