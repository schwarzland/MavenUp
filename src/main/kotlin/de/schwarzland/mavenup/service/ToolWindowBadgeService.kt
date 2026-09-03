package de.schwarzland.mavenup.service

import de.schwarzland.mavenup.model.MavenUpBadgeState
import de.schwarzland.mavenup.model.VulnerabilitySeverity
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.BadgeIconSupplier
import javax.swing.Icon

/** Registrierte ID des MavenUp-Tool-Windows aus der `plugin.xml`. */
const val MAVEN_UP_TOOL_WINDOW_ID = "MavenUp"

/** Ressourcenpfad des Basis-Icons des Tool-Windows. */
private const val TOOL_WINDOW_ICON_PATH = "/icons/mavenUpToolWindow.svg"

/**
 * Ermittelt aus den Ergebnissen der letzten Aktualisierung den anzuzeigenden Badge-Zustand.
 *
 * Die Funktion ist bewusst frei von IntelliJ-UI-Zustand, damit sie isoliert testbar bleibt.
 * Es gilt eine feste Rangfolge: schwerwiegende Sicherheitslücken vor leichteren Funden vor
 * verfügbaren Updates. Ein Zustand „alles in Ordnung" wird nicht signalisiert, da ein Badge
 * laut IntelliJ-UI-Guidelines ausschließlich Handlungsbedarf anzeigt.
 *
 * @param worstSeverity Der höchste Schweregrad aller bekannten Funde oder `null`, wenn keine
 * Sicherheitslücken vorliegen bzw. noch kein Scan durchgeführt wurde.
 * @param hasUpdates `true`, wenn für mindestens eine Abhängigkeit eine neuere Version vorliegt.
 * @param mode Die konfigurierte Anzeigeoption des Badges.
 * @return Der anzuzeigende [MavenUpBadgeState].
 */
internal fun determineBadgeState(
    worstSeverity: VulnerabilitySeverity?,
    hasUpdates: Boolean,
    mode: ToolWindowBadgeMode
): MavenUpBadgeState = when {
    mode == ToolWindowBadgeMode.OFF -> MavenUpBadgeState.NONE
    worstSeverity == VulnerabilitySeverity.CRITICAL ||
        worstSeverity == VulnerabilitySeverity.HIGH -> MavenUpBadgeState.SEVERE_VULNERABILITIES
    worstSeverity != null -> MavenUpBadgeState.VULNERABILITIES
    hasUpdates && mode == ToolWindowBadgeMode.VULNERABILITIES_AND_UPDATES -> MavenUpBadgeState.UPDATES
    else -> MavenUpBadgeState.NONE
}

/**
 * Projektgebundener Service, der den Badge-Punkt auf dem Stripe-Icon des MavenUp-Tool-Windows setzt.
 *
 * Die Badges werden über [BadgeIconSupplier] der IntelliJ-Plattform erzeugt. Dadurch stammen
 * Position (unten rechts) und Farbe aus dem aktiven Theme, sodass Light-, Dark- und
 * Kontrast-Themes ohne eigene SVG-Varianten korrekt bedient werden.
 *
 * @property project Das Projekt, dessen Tool-Window-Icon aktualisiert wird.
 */
@Service(Service.Level.PROJECT)
internal class ToolWindowBadgeService(private val project: Project) {

    /** Basis-Icon des Tool-Windows ohne Badge. */
    private val baseIcon: Icon = IconLoader.getIcon(TOOL_WINDOW_ICON_PATH, ToolWindowBadgeService::class.java)

    /** Erzeugt die mit einem Badge überlagerten Varianten des Basis-Icons. */
    private val badgeIcons = BadgeIconSupplier(baseIcon)

    /**
     * Liefert das zum Zustand passende Icon.
     *
     * @param state Der darzustellende Badge-Zustand.
     * @return Das Basis-Icon bei [MavenUpBadgeState.NONE], sonst das Icon mit passendem Badge.
     */
    internal fun badgeIcon(state: MavenUpBadgeState): Icon = when (state) {
        MavenUpBadgeState.NONE -> baseIcon
        MavenUpBadgeState.UPDATES -> badgeIcons.infoIcon
        MavenUpBadgeState.VULNERABILITIES -> badgeIcons.warningIcon
        MavenUpBadgeState.SEVERE_VULNERABILITIES -> badgeIcons.errorIcon
    }

    /**
     * Setzt das Stripe-Icon des Tool-Windows auf die zum Zustand passende Variante.
     *
     * Der Aufruf ist idempotent und wird auf dem EDT ausgeführt. Ist das Projekt bereits
     * geschlossen oder das Tool-Window noch nicht registriert, passiert nichts.
     *
     * @param state Der darzustellende Badge-Zustand.
     */
    fun update(state: MavenUpBadgeState) {
        runOnEdt {
            if (project.isDisposed) return@runOnEdt
            val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(MAVEN_UP_TOOL_WINDOW_ID)
            if (toolWindow == null || toolWindow.isDisposed) {
                LOG.debug("MavenUp Tool Window nicht verfügbar; Badge wird nicht gesetzt.")
                return@runOnEdt
            }
            toolWindow.setIcon(badgeIcon(state))
        }
    }

    /**
     * Entfernt einen gesetzten Badge und stellt das unveränderte Basis-Icon wieder her.
     *
     * Wird beim Verwerfen der Tool-Window-Komponente aufgerufen, damit nach einem dynamischen
     * Plugin-Update oder Projektwechsel kein veralteter Badge zurückbleibt.
     */
    fun reset() = update(MavenUpBadgeState.NONE)

    /**
     * Führt [action] auf dem Event Dispatch Thread aus.
     *
     * Läuft der Aufruf bereits auf dem EDT, wird [action] direkt ausgeführt, damit der Badge
     * synchron zum übrigen UI-Update gesetzt wird.
     *
     * @param action Die auszuführende Aktion.
     */
    private fun runOnEdt(action: () -> Unit) {
        val application = ApplicationManager.getApplication()
        if (application.isDispatchThread) action() else application.invokeLater(action)
    }

    companion object {
        private val LOG = Logger.getInstance(ToolWindowBadgeService::class.java)

        /**
         * Liefert die projektgebundene Instanz dieses Services.
         *
         * @param project Das Projekt, für das der Service benötigt wird.
         * @return Die Service-Instanz des Projekts.
         */
        fun getInstance(project: Project): ToolWindowBadgeService =
            project.getService(ToolWindowBadgeService::class.java)
    }
}
