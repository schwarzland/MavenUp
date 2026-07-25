package de.schwarzland.mavenup

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.wm.ToolWindowManager
import org.jetbrains.idea.maven.project.MavenImportListener
import org.jetbrains.idea.maven.project.MavenProjectsManager
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds

/**
 * Startup-Aktivität, die das MavenUp Tool Window beim Öffnen eines Projekts verfügbar macht.
 *
 * Diese Klasse implementiert [ProjectActivity] und wird beim Laden eines IntelliJ-Projektes ausgeführt.
 * Sie prüft, ob Maven-Projekte vorhanden sind, und macht das MavenUp Tool Window entsprechend sichtbar:
 * - Falls Maven-Projekte bereits importiert sind, wird das Tool Window sofort verfügbar gemacht
 * - Falls noch kein Import stattgefunden hat, wird auf den [MavenImportListener] gehört und das Tool Window
 *   wird verfügbar gemacht, sobald der Maven-Import abgeschlossen ist
 *
 * Diese Implementierung enthält auch einen Mechanismus zur Vermeidung von Race Conditions nach Plugin-Updates,
 * indem sie auf die vollständige Maven-Indizierung wartet.
 *
 * @see ProjectActivity
 * @see MavenProjectsManager
 * @see MavenImportListener
 */

private val LOG = Logger.getInstance(MavenUpStartupActivity::class.java)
private const val MAVEN_INDEXING_TIMEOUT_MS = 30000L // 30 Sekunden Timeout

class MavenUpStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        try {
            // Warte kurz, damit Maven-ProjectManager initialisiert sein kann
            // Dies hilft bei Race Conditions nach Plugin-Updates
            awaitMavenProjectManagerReady(project)

            // Pruefe ob Maven-Projekte bereits vorhanden sind
            val mavenManager = MavenProjectsManager.getInstance(project)

            if (mavenManager.hasProjects()) {
                LOG.debug("Maven-Projekte gefunden. Tool Window wird verfügbar gemacht.")
                makeToolWindowAvailable(project)
                return
            }

            LOG.debug("Keine Maven-Projekte in StartupActivity gefunden. Registriere Import-Listener...")
        } catch (e: Exception) {
            LOG.warn("Fehler beim Initialisieren der Maven-Projektverwaltung: ${e.message}", e)
        }

        // Registriere einen Listener für zukünftige Maven-Importe
        registerMavenImportListener(project)
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

    /**
     * Registriert einen Listener für Maven-Importe.
     * Dies ist wichtig, wenn beim Startup noch keine Projekte vorhanden sind.
     */
    private fun registerMavenImportListener(project: Project) {
        val connection = project.messageBus.connect()
        connection.subscribe(
            MavenImportListener.TOPIC,
            object : MavenImportListener {
                override fun importFinished(
                    importedProjects: Collection<org.jetbrains.idea.maven.project.MavenProject>,
                    newModules: List<com.intellij.openapi.module.Module>
                ) {
                    LOG.debug(
                        "Maven-Import abgeschlossen (${importedProjects.size} Projekte, " +
                                "${newModules.size} Module). Tool Window wird verfügbar gemacht."
                    )
                    makeToolWindowAvailable(project)
                    // Listener kann nach erster Verwendung auch wieder abmelden
                    connection.disconnect()
                }
            }
        )
    }

    /**
     * Macht das MavenUp Tool Window für Maven-Projekte sichtbar.
     * Diese Methode wird auf dem EDT ausgeführt und enthält umfangreiche Fehlerbehandlung.
     */
    private fun makeToolWindowAvailable(project: Project) {
        ApplicationManager.getApplication().invokeLater {
            try {
                if (project.isDisposed) {
                    LOG.debug("Projekt ist disposed. Tool Window wird nicht verfügbar gemacht.")
                    return@invokeLater
                }

                val mavenManager = MavenProjectsManager.getInstance(project)
                if (!mavenManager.hasProjects()) {
                    LOG.debug("Keine Maven-Projekte vorhanden. Tool Window bleibt nicht verfügbar.")
                    return@invokeLater
                }

                val toolWindowManager = ToolWindowManager.getInstance(project)
                val tw = toolWindowManager.getToolWindow("MavenUp")

                if (tw == null) {
                    LOG.warn("MavenUp Tool Window konnte nicht gefunden werden (Tool Window nicht registriert?)")
                    return@invokeLater
                }

                if (!tw.isAvailable) {
                    tw.setAvailable(true)
                    LOG.info("MavenUp Tool Window ist nun verfügbar für ${mavenManager.projects.size} Maven-Projekt(e).")
                } else {
                    LOG.debug("MavenUp Tool Window war bereits verfügbar.")
                }
            } catch (e: Exception) {
                LOG.error("Fehler beim Verfügbarmachen des Tool Windows: ${e.message}", e)
            }
        }
    }
}