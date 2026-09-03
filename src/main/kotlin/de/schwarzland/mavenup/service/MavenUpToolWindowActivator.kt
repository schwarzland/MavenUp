package de.schwarzland.mavenup.service

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import org.jetbrains.idea.maven.project.MavenProjectsManager

/**
 * Zentrale Hilfskomponente, die das MavenUp Tool Window für Maven-Projekte sichtbar macht.
 *
 * Die Aktivierung ist an mehreren Stellen erforderlich (beim Projektstart über
 * [MavenUpStartupActivity] und nach jedem abgeschlossenen Maven-Import über
 * [MavenUpMavenImportListener]). Diese Logik wird hier gebündelt, um Duplikate zu vermeiden
 * und ein einheitliches, idempotentes Verhalten sicherzustellen.
 */
internal object MavenUpToolWindowActivator {

    private val LOG = Logger.getInstance(MavenUpToolWindowActivator::class.java)

    /**
     * Macht das MavenUp Tool Window für Maven-Projekte sichtbar.
     *
     * Die Ausführung erfolgt auf dem EDT und ist idempotent: Ist das Tool Window bereits
     * verfügbar oder liegen keine Maven-Projekte vor, wird nichts geändert. Die Methode enthält
     * umfangreiche Fehlerbehandlung, damit einzelne Fehler den Projektstart nicht beeinträchtigen.
     *
     * @param project Das Projekt, für das das Tool Window verfügbar gemacht werden soll.
     */
    fun makeToolWindowAvailable(project: Project) {
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
                val tw = toolWindowManager.getToolWindow(MAVEN_UP_TOOL_WINDOW_ID)

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
