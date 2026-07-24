package de.schwarzland.mavenup

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.wm.ToolWindowManager
import org.jetbrains.idea.maven.project.MavenImportListener
import org.jetbrains.idea.maven.project.MavenProjectsManager

/**
 * Startup-Aktivität, die das MavenUp Tool Window beim Öffnen eines Projekts verfügbar macht.
 *
 * Diese Klasse implementiert [ProjectActivity] und wird beim Laden eines IntelliJ-Projektes ausgeführt.
 * Sie prüft, ob Maven-Projekte vorhanden sind, und macht das MavenUp Tool Window entsprechend sichtbar:
 * - Falls Maven-Projekte bereits importiert sind, wird das Tool Window sofort verfügbar gemacht
 * - Falls noch kein Import stattgefunden hat, wird auf den [MavenImportListener] gehört und das Tool Window
 *   wird verfügbar gemacht, sobald der Maven-Import abgeschlossen ist
 *
 * @see ProjectActivity
 * @see MavenProjectsManager
 * @see MavenImportListener
 */
class MavenUpStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        // Pruefe ob Maven-Projekte bereits vorhanden sind
        val mavenManager = MavenProjectsManager.getInstance(project)
        if (mavenManager.hasProjects()) {
            makeToolWindowAvailable(project)
            return
        }

        // Registriere einen Listener für zukünftige Maven-Importe
        val connection = project.messageBus.connect()
        connection.subscribe(
            MavenImportListener.TOPIC,
            object : MavenImportListener {
                override fun importFinished(
                    importedProjects: Collection<org.jetbrains.idea.maven.project.MavenProject>,
                    newModules: List<com.intellij.openapi.module.Module>
                ) {
                    makeToolWindowAvailable(project)
                    // Listener kann nach erster Verwendung auch wieder abmelden
                    connection.disconnect()
                }
            }
        )
    }

    private fun makeToolWindowAvailable(project: Project) {
        ApplicationManager.getApplication().invokeLater {
            val tw = ToolWindowManager.getInstance(project).getToolWindow("MavenUp")
            if (tw != null && MavenProjectsManager.getInstance(project).hasProjects()) {
                tw.setAvailable(true)
            }
        }
    }
}