package de.schwarzland.mavenup.service

import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Unit-Tests für [MavenUpMavenImportListener].
 *
 * Prüft, dass der deklarativ registrierte Import-Listener konstruierbar ist und sein
 * [MavenUpMavenImportListener.importFinished]-Callback robust ohne Exceptions ausgeführt wird.
 */
class MavenUpMavenImportListenerTest : BasePlatformTestCase() {

    /**
     * Test: Der Listener sollte mit einem Projekt konstruierbar sein.
     */
    fun testListenerCanBeConstructed() {
        val listener = MavenUpMavenImportListener(project)
        assertNotNull("Listener sollte konstruierbar sein", listener)
    }

    /**
     * Test: importFinished() sollte mit leeren Eingaben keine Exception werfen.
     */
    fun testImportFinishedWithEmptyInputsDoesNotThrow() {
        val listener = MavenUpMavenImportListener(project)
        listener.importFinished(emptyList(), emptyList())
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertFalse("Projekt sollte nach importFinished nicht disposed sein", project.isDisposed)
    }

    /**
     * Test: importFinished() sollte mehrfach aufgerufen werden können.
     */
    fun testImportFinishedCanBeCalledMultipleTimes() {
        val listener = MavenUpMavenImportListener(project)
        repeat(3) { listener.importFinished(emptyList(), emptyList()) }
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertFalse("Projekt sollte nach mehrfachem importFinished nicht disposed sein", project.isDisposed)
    }
}
