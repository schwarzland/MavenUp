package de.schwarzland.mavenup.ui

import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import de.schwarzland.mavenup.model.RedundantManagedDependencyRecommendation
import de.schwarzland.mavenup.service.RedundantManagedDependencyService

/**
 * Führt die Prüfung auf redundante verwaltete Abhängigkeiten abbrechbar im Hintergrund aus.
 *
 * @param project Das IntelliJ-Projekt.
 * @param recommendationProvider Analysefunktion; wird für Tests injiziert.
 */
internal class RedundantManagedDependencyCheckRunner(
    private val project: Project,
    private val recommendationProvider: (
        String?,
        ProgressIndicator?
    ) -> List<RedundantManagedDependencyRecommendation> = { managed, indicator ->
        RedundantManagedDependencyService(project).findRedundantManagedDependencies(managed, indicator)
    }
) {
    /**
     * Startet die Analyse und liefert das Ergebnis sowie den Abschlussstatus an die UI zurück.
     *
     * @param scope Der sichtbare Prüfumfang.
     * @param onSuccess Callback für ein vollständig zurückgekehrtes Analyseergebnis.
     * @param onFinished Callback zum Freigeben des UI-Aktionszustands, auch nach Abbruch.
     */
    internal fun start(
        scope: RedundantManagedDependencyScope,
        onSuccess: (List<RedundantManagedDependencyRecommendation>) -> Unit,
        onFinished: () -> Unit
    ) {
        ProgressManager.getInstance().run(
            object : Task.Backgroundable(
                project,
                MyMessageBundle.message("toolwindow.MyToolWindow.checkRedundantManaged.progress"),
                true
            ) {
                private lateinit var result: List<RedundantManagedDependencyRecommendation>

                /** Führt die injizierte Analyse im Hintergrund aus. */
                override fun run(indicator: ProgressIndicator) {
                    indicator.isIndeterminate = true
                    result = recommendationProvider(scope.managedCoordinate, indicator)
                }

                /** Liefert das Analyseergebnis an die UI. */
                override fun onSuccess() {
                    onSuccess(result)
                }

                /** Gibt den UI-Aktionszustand nach Erfolg oder Abbruch wieder frei. */
                override fun onFinished() {
                    onFinished()
                }
            }
        )
    }
}
