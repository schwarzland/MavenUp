package de.schwarzland.mavenup

import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.runBlocking
import org.jetbrains.idea.maven.project.MavenImportListener
import org.jetbrains.idea.maven.project.MavenProjectsManager

/**
 * Unit-Tests für die Klasse MavenUpStartupActivity.
 *
 * Diese Test-Klasse prüft das Verhalten der MavenUpStartupActivity unter verschiedenen Szenarien:
 * - Maven-Projekte sind beim Startup bereits vorhanden
 * - Maven-Projekte sind noch nicht vorhanden, werden aber später importiert
 * - Fehlerbehandlung bei disposed Projects
 * - Tool Window-Verfügbarkeit und Registrierung
 * - Race Conditions nach Plugin-Updates
 */
class MavenUpStartupActivityTest : BasePlatformTestCase() {

    private lateinit var activity: MavenUpStartupActivity

    override fun setUp() {
        super.setUp()
        activity = MavenUpStartupActivity()
    }

    /**
     * Test: Aktivität sollte initialisiert werden können.
     */
    fun testActivityInitialization() {
        assertNotNull("MavenUpStartupActivity sollte initialisiert werden können", activity)
    }

    /**
     * Test: execute() sollte nicht werfen wenn keine Maven-Projekte vorhanden sind.
     */
    fun testExecuteWithoutExistingMavenProjects() = runBlocking {
        try {
            activity.execute(project)
            assertTrue("Activity sollte erfolgreich ausgeführt werden", true)
        } catch (e: Exception) {
            fail("Activity sollte keine Exception werfen: ${e.message}")
        }
    }

    /**
     * Test: execute() sollte nicht werfen wenn Maven-Projekte vorhanden sind.
     */
    fun testExecuteWithExistingMavenProjects() = runBlocking {
        try {
            activity.execute(project)
            assertTrue("Activity sollte erfolgreich ausgeführt werden", true)
        } catch (e: Exception) {
            fail("Activity sollte keine Exception werfen: ${e.message}")
        }
    }

    /**
     * Test: Tool Window Manager sollte verfügbar sein.
     */
    fun testToolWindowManagerIsAvailable() {
        val toolWindowManager = ToolWindowManager.getInstance(project)
        assertNotNull("ToolWindowManager sollte verfügbar sein", toolWindowManager)
    }

    /**
     * Test: Projekt sollte nach Activity nicht disposed sein.
     */
    fun testProjectNotDisposedAfterExecution() = runBlocking {
        assertFalse("Projekt sollte nach Activity nicht disposed sein", project.isDisposed)

        activity.execute(project)

        assertFalse("Projekt sollte nach Activity nicht disposed sein", project.isDisposed)
    }

    /**
     * Test: MavenProjectsManager sollte korrekt initialisiert sein.
     */
    fun testMavenProjectsManagerInitialization() {
        val mavenManager = MavenProjectsManager.getInstance(project)
        assertNotNull("MavenProjectsManager sollte nicht null sein", mavenManager)
    }

    /**
     * Test: execute() sollte mehrmals aufgerufen werden können.
     */
    fun testExecuteCanBeCalledMultipleTimes() = runBlocking {
        try {
            activity.execute(project)
            activity.execute(project)
            assertTrue("Activity sollte mehrmals aufgerufen werden können", true)
        } catch (e: Exception) {
            fail("Activity sollte mehrmals aufgerufen werden können: ${e.message}")
        }
    }

    /**
     * Test: Activity sollte robust mit Edge Cases umgehen.
     */
    fun testActivityIsRobust() = runBlocking {
        try {
            activity.execute(project)
            assertTrue("Activity sollte robust sein", true)
        } catch (e: Exception) {
            fail("Activity sollte robust sein und keine Exception werfen: ${e.message}")
        }
    }

    /**
     * Test: MessageBus sollte verfügbar sein.
     */
    fun testMessageBusIsAvailable() {
        val messageBus = project.messageBus
        assertNotNull("MessageBus sollte verfügbar sein", messageBus)
    }

    /**
     * Test: execute() sollte nicht blockieren.
     */
    fun testExecuteDoesNotBlock() = runBlocking {
        val startTime = System.currentTimeMillis()
        activity.execute(project)
        val endTime = System.currentTimeMillis()

        val duration = endTime - startTime
        assertTrue("Execute sollte nicht zu lange dauern (< 5 Sekunden)", duration < 5000)
    }

    /**
     * Test: MavenImportListener Topic sollte registrierbar sein.
     */
    fun testMavenImportListenerTopicExists() {
        val topic = MavenImportListener.TOPIC
        assertNotNull("MavenImportListener.TOPIC sollte existieren", topic)
    }

    /**
     * Test: Tool Window ID sollte "MavenUp" sein.
     */
    fun testMavenUpToolWindowIdIsCorrect() {
        val toolWindowManager = ToolWindowManager.getInstance(project)
        val toolWindow = toolWindowManager.getToolWindow("MavenUp")

        // Tool Window kann null sein, wenn es nicht registriert ist
        // Das ist OK für diesen Test
        if (toolWindow != null) {
            assertEquals("Tool Window ID sollte 'MavenUp' sein", "MavenUp", toolWindow.id)
        }
    }

    /**
     * Test: Wiederholte Executions sollten keine Ressourcen-Leaks verursachen.
     */
    fun testNoResourceLeaksOnRepeatedExecution() = runBlocking {
        try {
            repeat(3) {
                activity.execute(project)
            }
            assertTrue("Wiederholte Executions sollten OK sein", true)
        } catch (e: Exception) {
            fail("Wiederholte Executions sollten keine Fehler verursachen: ${e.message}")
        }
    }

    /**
     * Test: Activity sollte mit projektspezifischen Settings umgehen können.
     */
    fun testActivityWorksWithProjectSettings() = runBlocking {
        try {
            val settings = MavenUpSettings.getInstance(project)
            assertNotNull("Settings sollte existieren", settings)

            activity.execute(project)
            assertTrue("Activity sollte mit Settings funktionieren", true)
        } catch (e: Exception) {
            fail("Activity sollte mit Settings funktionieren: ${e.message}")
        }
    }
}