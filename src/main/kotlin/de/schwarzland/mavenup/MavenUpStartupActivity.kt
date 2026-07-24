package de.schwarzland.mavenup

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.wm.ToolWindowManager
import org.jetbrains.idea.maven.project.MavenProjectsManager

class MavenUpStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        val mavenManager = MavenProjectsManager.getInstance(project)
        if (mavenManager.hasProjects()) {
            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                ToolWindowManager.getInstance(project).getToolWindow("MavenUp")?.setAvailable(true)
            }
        } else {
            // Auf den Maven-Import warten
            project.messageBus.connect().subscribe(
                org.jetbrains.idea.maven.project.MavenImportListener.TOPIC,
                object : org.jetbrains.idea.maven.project.MavenImportListener {
                    override fun importFinished(
                        importedProjects: Collection<org.jetbrains.idea.maven.project.MavenProject>,
                        newModules: List<com.intellij.openapi.module.Module>
                    ) {
                        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                            ToolWindowManager.getInstance(project).getToolWindow("MavenUp")?.setAvailable(true)
                        }
                    }
                }
            )
        }
    }
}