package de.schwarzland.mavenup.service

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import org.jetbrains.idea.maven.project.MavenImportListener
import org.jetbrains.idea.maven.project.MavenProject

/**
 * Projektgebundener Listener, der das MavenUp Tool Window nach jedem abgeschlossenen
 * Maven-Import verfügbar macht.
 *
 * Der Listener wird deklarativ über `<projectListeners>` in der `plugin.xml` registriert.
 * Dadurch wird er beim Entladen des Plugins automatisch wieder abgemeldet – im Gegensatz zu
 * einer programmatischen Registrierung auf dem Projekt-Message-Bus, die eine Referenz auf den
 * Plugin-Klassenlader halten und so ein dynamisches Update ohne IDE-Neustart verhindern würde.
 *
 * @property project Das Projekt, für das der Listener registriert ist.
 * @see MavenUpToolWindowActivator
 */
class MavenUpMavenImportListener(private val project: Project) : MavenImportListener {

    private companion object {
        private val LOG = Logger.getInstance(MavenUpMavenImportListener::class.java)
    }

    /**
     * Wird nach Abschluss eines Maven-Imports aufgerufen und macht das Tool Window verfügbar.
     *
     * @param importedProjects Die im Zuge des Imports verarbeiteten Maven-Projekte.
     * @param newModules Die dabei neu erzeugten IntelliJ-Module.
     */
    override fun importFinished(
        importedProjects: Collection<MavenProject>,
        newModules: List<Module>
    ) {
        LOG.debug(
            "Maven-Import abgeschlossen (${importedProjects.size} Projekte, " +
                    "${newModules.size} Module). Tool Window wird verfügbar gemacht."
        )
        MavenUpToolWindowActivator.makeToolWindowAvailable(project)
    }
}
