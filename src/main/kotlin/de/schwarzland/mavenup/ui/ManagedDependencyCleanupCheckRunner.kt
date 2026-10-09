package de.schwarzland.mavenup.ui

import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import de.schwarzland.mavenup.service.ManagedDependencyRecommendationResult
import de.schwarzland.mavenup.service.ManagedDependencyRecommendationService

/**
 * Führt eine Bereinigungsanalyse abbrechbar im Hintergrund aus.
 *
 * @param project Das IntelliJ-Projekt.
 * @param availableVersions Bereits ermittelte Versionen je Maven-Koordinate.
 * @param recommendationProvider Analysefunktion; wird für Tests injiziert.
 */
internal class ManagedDependencyCleanupCheckRunner(
    private val project: Project,
    private val availableVersions: Map<String, List<String>>,
    private val recommendationProvider: (
        Map<String, List<String>>,
        String?,
        String?,
        ProgressIndicator?
    ) -> ManagedDependencyRecommendationResult = { versions, managed, trigger, indicator ->
        ManagedDependencyRecommendationService(project)
            .findRecommendationsWithStatus(versions, managed, trigger, indicator)
    }
) {
    /**
     * Startet die Analyse und liefert Ergebnis sowie Abschlussstatus an die UI zurück.
     *
     * @param scope Der sichtbare und maschinenlesbare Analyseumfang.
     * @param onSuccess Callback für ein vollständig zurückgekehrtes Analyseergebnis.
     * @param onFinished Callback zum Freigeben des UI-Aktionszustands, auch nach Abbruch.
     */
    internal fun start(
        scope: ManagedDependencyCleanupScope,
        onSuccess: (ManagedDependencyRecommendationResult) -> Unit,
        onFinished: () -> Unit
    ) {
        ProgressManager.getInstance().run(
            object : Task.Backgroundable(
                project,
                MyMessageBundle.message("toolwindow.MyToolWindow.checkManagedRemoval.progress"),
                true
            ) {
                private lateinit var result: ManagedDependencyRecommendationResult

                /** Führt die injizierte Bereinigungsanalyse im Hintergrund aus. */
                override fun run(indicator: ProgressIndicator) {
                    indicator.isIndeterminate = true
                    result = recommendationProvider(
                        availableVersions,
                        scope.managedCoordinate,
                        scope.triggerCoordinate,
                        indicator
                    )
                }

                /** Liefert ein erfolgreich abgeschlossenes Analyseergebnis an die UI. */
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
