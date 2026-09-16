package de.schwarzland.mavenup.service

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import org.jetbrains.idea.maven.project.MavenProjectsManager
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds

/**
 * Startup-Aktivität, die beim Öffnen eines Maven-Projekts das Tool Window aktiviert und die
 * automatische Versionssuche auslöst.
 *
 * Diese Klasse implementiert [ProjectActivity] und wird beim Laden eines IntelliJ-Projektes ausgeführt.
 * Sind bereits Maven-Projekte importiert, wird das Tool Window sofort verfügbar gemacht. Andernfalls
 * übernimmt der deklarativ registrierte [MavenUpMavenImportListener] Aktivierung und
 * Versionssuche, sobald der nächste Maven-Import abgeschlossen ist.
 *
 * Die Aktivierung erfolgt über [MavenUpToolWindowActivator], damit Startup-Aktivität und Import-Listener
 * dieselbe, idempotente Logik verwenden.
 *
 * Diese Implementierung enthält außerdem einen Mechanismus zur Vermeidung von Race Conditions nach
 * Plugin-Updates, indem sie auf die Initialisierung des Maven-Projektmanagers wartet.
 *
 * @see ProjectActivity
 * @see MavenProjectsManager
 * @see MavenUpMavenImportListener
 */

private val LOG = Logger.getInstance(MavenUpStartupActivity::class.java)
private const val MAVEN_INDEXING_TIMEOUT_MS = 30000L // 30 Sekunden Timeout

class MavenUpStartupActivity : ProjectActivity {
    /**
     * Wird beim Start eines IntelliJ-Projekts aufgerufen. Prüft, ob Maven-Projekte vorhanden sind,
     * und aktiviert das MavenUp Tool Window sowie die automatische Versionssuche direkt. Sind noch
     * keine Projekte vorhanden, wird beides dem deklarativen [MavenUpMavenImportListener] überlassen.
     */
    override suspend fun execute(project: Project) {
        try {
            // Warte kurz, damit Maven-ProjectManager initialisiert sein kann
            // Dies hilft bei Race Conditions nach Plugin-Updates
            awaitMavenProjectManagerReady(project)

            // Pruefe ob Maven-Projekte bereits vorhanden sind
            val mavenManager = MavenProjectsManager.getInstance(project)

            if (mavenManager.hasProjects()) {
                LOG.debug("Maven-Projekte gefunden. Tool Window wird verfügbar gemacht.")
                MavenUpToolWindowActivator.makeToolWindowAvailable(project)
                AutomaticVersionSearchCoordinator.getInstance(project).requestAutomaticSearch()
                return
            }

            LOG.debug(
                "Keine Maven-Projekte in StartupActivity gefunden. " +
                        "Der deklarative MavenImportListener übernimmt die Aktivierung."
            )
        } catch (e: Exception) {
            LOG.warn("Fehler beim Initialisieren der Maven-Projektverwaltung: ${e.message}", e)
        }
    }

    /**
     * Wartet kurz darauf, dass der Maven-ProjectManager vollständig initialisiert ist.
     * Dies ist besonders wichtig nach Plugin-Updates, wo alte und neue Plugin-Instanzen
     * kurzzeitig konkurrieren könnten.
     */
    private suspend fun awaitMavenProjectManagerReady(project: Project) {
        val result = withTimeoutOrNull(MAVEN_INDEXING_TIMEOUT_MS.milliseconds) {
            val delayMs = 100L
            var attempts = 0
            val maxAttempts = 30

            while (attempts < maxAttempts && !project.isDisposed) {
                try {
                    val mavenManager = MavenProjectsManager.getInstance(project)
                    // Check if Maven projects exist or if it's actually initialized
                    if (mavenManager.hasProjects() || mavenManager.isMavenizedProject()) {
                        LOG.debug("Maven-ProjectManager ist bereit (Versuch: ${attempts + 1}/$maxAttempts)")
                        return@withTimeoutOrNull
                    }
                } catch (e: Exception) {
                    LOG.debug("Maven-ProjectManager noch nicht bereit (Versuch: ${attempts + 1}/$maxAttempts): ${e.message}")
                }

                attempts++
                kotlinx.coroutines.delay(delayMs.milliseconds)
            }
        }

        if (result == null) {
            LOG.warn("Timeout beim Warten auf Maven-ProjectManager ($MAVEN_INDEXING_TIMEOUT_MS ms)")
        }
    }
}