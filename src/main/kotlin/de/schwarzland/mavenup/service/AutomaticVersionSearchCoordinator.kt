package de.schwarzland.mavenup.service

import de.schwarzland.mavenup.model.ApiError
import de.schwarzland.mavenup.ui.MyMessageBundle
import de.schwarzland.mavenup.ui.RefreshSnapshot
import de.schwarzland.mavenup.ui.TOOLWINDOW_MY_TOOL_WINDOW_TYPE_MANAGED_DEPENDENCY
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Ergebnis einer automatisch ausgelösten Aktualisierung der Maven-Projektdaten.
 *
 * @property snapshot Die nach einem Maven-Import erfassten Abhängigkeiten.
 * @property versionSearchResult Die online ermittelten Versionen oder `null`, wenn die automatische
 *   Versionssuche in den Einstellungen deaktiviert ist.
 * @property repositoryError Der erste Fehler der Versionssuche oder `null`.
 */
internal data class AutomaticVersionSearchState(
    val snapshot: RefreshSnapshot,
    val versionSearchResult: VersionSearchResult?,
    val repositoryError: ApiError?
)

/**
 * Listener für abgeschlossene automatische Aktualisierungen der Versionsdaten.
 */
internal fun interface AutomaticVersionSearchListener {
    /**
     * Empfängt den aktuellen Zustand nach einem abgeschlossenen Maven-Import oder Projektstart.
     *
     * @param state Der veröffentlichte Aktualisierungszustand.
     */
    fun automaticVersionSearchCompleted(state: AutomaticVersionSearchState)
}

/**
 * Projektweiter Topic-Kanal für automatische Aktualisierungen der Versionsdaten.
 */
@JvmField
internal val AUTOMATIC_VERSION_SEARCH_TOPIC: Topic<AutomaticVersionSearchListener> =
    Topic.create("MavenUp automatic version search completed", AutomaticVersionSearchListener::class.java)

/**
 * Prüft, ob ein Ergebnis der automatischen Versionssuche noch veröffentlicht werden darf.
 *
 * @param isCancelled `true`, wenn die Hintergrundaufgabe abgebrochen wurde.
 * @param isCurrentGeneration `true`, wenn kein neuerer Maven-Import eingetroffen ist.
 * @param isProjectDisposed `true`, wenn das Projekt bereits geschlossen wurde.
 * @return `true`, wenn die UI das Ergebnis noch sicher übernehmen darf.
 */
internal fun shouldPublishAutomaticVersionSearchResult(
    isCancelled: Boolean,
    isCurrentGeneration: Boolean,
    isProjectDisposed: Boolean
): Boolean = !isCancelled && isCurrentGeneration && !isProjectDisposed

/**
 * Prüft, ob ein Versionssuchergebnis mindestens ein verfügbares Update enthält.
 *
 * @param snapshot Schnappschuss mit den aktuell verwendeten Versionen.
 * @param result Ergebnis der Versionssuche.
 * @return `true`, wenn die höchste angebotene Version einer Koordinate von ihrer aktuellen Version abweicht.
 */
internal fun hasAvailableVersionUpdates(
    snapshot: RefreshSnapshot,
    result: VersionSearchResult
): Boolean {
    val currentVersions = snapshot.rows.associate { it.key to it.currentVersion }
    return result.availableVersions.any { (key, versions) ->
        versions.firstOrNull()?.let { newestVersion ->
            newestVersion.isNotEmpty() && newestVersion != currentVersions[key].orEmpty()
        } == true
    }
}

/**
 * Führt die von Maven-Importen ausgelöste Versionssuche unabhängig vom Tool Window aus.
 *
 * Die Komponente erfasst nach Projektstart und jedem abgeschlossenen Maven-Import zunächst einen
 * konsistenten PSI-Schnappschuss. Ist die automatische Suche aktiviert, werden daraufhin die
 * Repository-Abfragen im Hintergrund durchgeführt. Mehrere dicht aufeinanderfolgende Trigger
 * werden über eine Generation entwertet, sodass ausschließlich das jüngste Ergebnis veröffentlicht
 * und von geöffneten Tool Windows dargestellt wird.
 *
 * @property project Das Maven-Projekt, dessen Versionen geprüft werden.
 */
@Service(Service.Level.PROJECT)
internal class AutomaticVersionSearchCoordinator(private val project: Project) {

    private val refreshSnapshotCollector = RefreshSnapshotCollector(project)
    private val repositoryError = AtomicReference<ApiError?>()
    private val generation = AtomicInteger()
    private val activeIndicator = AtomicReference<ProgressIndicator?>()

    @Volatile
    private var latestState: AutomaticVersionSearchState? = null

    /**
     * Startet eine automatische Aktualisierung nach Projektstart oder Maven-Import.
     *
     * Bereits laufende, ältere Aufgaben dürfen noch ihren aktuellen Repository-Aufruf beenden,
     * ihre Ergebnisse werden wegen der Generation aber nicht mehr veröffentlicht.
     */
    fun requestAutomaticSearch() {
        if (project.isDisposed) return
        val requestGeneration = generation.incrementAndGet()
        activeIndicator.get()?.cancel()
        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project,
            MyMessageBundle.message("toolwindow.MyToolWindow.checkUpdates.progress"),
            true
        ) {
            /**
             * Erfasst den Maven-Schnappschuss und führt optional die Online-Suche aus.
             *
             * @param indicator Fortschrittsindikator der Hintergrundaufgabe.
             */
            override fun run(indicator: ProgressIndicator) {
                if (requestGeneration != generation.get()) return
                activeIndicator.getAndSet(indicator)?.cancel()
                try {
                    val state = collectState(indicator) ?: return
                    if (!shouldPublishAutomaticVersionSearchResult(
                            indicator.isCanceled,
                            requestGeneration == generation.get(),
                            project.isDisposed
                        )
                    ) {
                        return
                    }

                    latestState = state
                    updateToolWindowBadge(state)
                    ApplicationManager.getApplication().invokeLater {
                        if (shouldPublishAutomaticVersionSearchResult(
                                indicator.isCanceled,
                                requestGeneration == generation.get(),
                                project.isDisposed
                            )
                        ) {
                            project.messageBus.syncPublisher(AUTOMATIC_VERSION_SEARCH_TOPIC)
                                .automaticVersionSearchCompleted(state)
                        }
                    }
                } finally {
                    activeIndicator.compareAndSet(indicator, null)
                }
            }
        })
    }

    /**
     * Liefert den zuletzt erfolgreich erfassten automatischen Aktualisierungszustand.
     *
     * @return Der Zustand oder `null`, solange noch keine automatische Aktualisierung abgeschlossen ist.
     */
    fun latestState(): AutomaticVersionSearchState? = latestState

    /**
     * Erfasst die aktuellen Maven-Daten in einer Read Action und führt abhängig von der Einstellung
     * die Versionssuche aus.
     *
     * @param indicator Fortschrittsindikator der Hintergrundaufgabe.
     * @return Den Aktualisierungszustand oder `null`, wenn der Vorgang abgebrochen wurde.
     */
    private fun collectState(indicator: ProgressIndicator): AutomaticVersionSearchState? {
        if (indicator.isCanceled) return null
        val snapshot = ApplicationManager.getApplication().runReadAction<RefreshSnapshot> {
            refreshSnapshotCollector.collectRefreshSnapshot(
                MyMessageBundle.message(TOOLWINDOW_MY_TOOL_WINDOW_TYPE_MANAGED_DEPENDENCY)
            )
        }
        if (indicator.isCanceled) return null
        if (!MavenUpSettings.getInstance().state.autoSearchVersions) {
            return AutomaticVersionSearchState(snapshot, null, null)
        }

        repositoryError.set(null)
        val dependencyApiService = DependencyApiService(project)
        val dependencyVersionService = DependencyVersionService(
            project,
            fetchAllVersions = { groupId, artifactId ->
                dependencyApiService.fetchAllVersions(groupId, artifactId) { error ->
                    repositoryError.compareAndSet(null, error)
                }
            }
        )
        val currentVersions = snapshot.rows.associate { it.key to it.currentVersion }
        val result = dependencyVersionService.searchVersions(
            currentVersions,
            snapshot.dependencyProperties,
            indicator
        )
        if (indicator.isCanceled) return null
        LOG.info("Finished automatic version search for ${result.availableVersions.size} Maven coordinates.")
        return AutomaticVersionSearchState(snapshot, result, repositoryError.get())
    }

    /**
     * Aktualisiert das Tool-Window-Badge nach einer automatischen Versionssuche.
     *
     * Sicherheitslücken werden durch automatische Versionssuchen nicht ermittelt; daher wird für
     * den Badge-Zustand nur die Verfügbarkeit neuer Versionen berücksichtigt.
     *
     * @param state Der abgeschlossene automatische Aktualisierungszustand.
     */
    private fun updateToolWindowBadge(state: AutomaticVersionSearchState) {
        val result = state.versionSearchResult
        val hasUpdates = result != null && hasAvailableVersionUpdates(state.snapshot, result)
        ToolWindowBadgeService.getInstance(project).update(
            determineBadgeState(
                worstSeverity = null,
                hasUpdates = hasUpdates,
                mode = MavenUpSettings.getInstance().state.toolWindowBadgeMode
            )
        )
    }

    companion object {
        private val LOG = Logger.getInstance(AutomaticVersionSearchCoordinator::class.java)

        /**
         * Liefert den projektgebundenen Koordinator.
         *
         * @param project Das Projekt, für das der Koordinator benötigt wird.
         * @return Die Service-Instanz des Projekts.
         */
        fun getInstance(project: Project): AutomaticVersionSearchCoordinator =
            project.getService(AutomaticVersionSearchCoordinator::class.java)
    }
}
