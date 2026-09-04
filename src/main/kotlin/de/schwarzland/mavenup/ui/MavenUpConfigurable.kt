package de.schwarzland.mavenup.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel
import de.schwarzland.mavenup.service.MavenRepositoryBrowser
import de.schwarzland.mavenup.service.ToolWindowBadgeMode

/**
 * Wurzelseite der MavenUp-Einstellungen unter `Settings > Tools > MavenUp`.
 *
 * Die Seite enthält ausschließlich die Einstellungen zu Darstellung und Verhalten
 * (Repository-Browser, Beschriftung der Aktionsleiste, Klickverhalten, Tool-Window-Badge).
 * Alle weiteren Themen liegen in den Unterseiten [MavenUpVersionsConfigurable],
 * [MavenUpVulnerabilityConfigurable] und [MavenUpPomChangesConfigurable], die in `plugin.xml`
 * über `parentId` unterhalb dieser Seite eingehängt sind.
 *
 * @param project Das Projekt, dessen Message-Bus nach dem Speichern benachrichtigt wird.
 */
class MavenUpConfigurable(project: Project) : MavenUpSettingsPage(project, DISPLAY_NAME) {

    /** Auswahlfeld für den externen Maven-Repository-Browser. */
    internal var repositoryBrowserComboBox: ComboBox<MavenRepositoryBrowser>? = null
        private set

    /** Schalter für Textbeschriftungen in den Aktionsleisten. */
    internal var toolbarShowTextCheckBox: JBCheckBox? = null
        private set

    /** Schalter für den Sprung zur `pom.xml` bei einfachem Klick. */
    internal var jumpOnSingleClickCheckBox: JBCheckBox? = null
        private set

    /** Auswahlfeld für den Badge auf dem Tool-Window-Icon. */
    internal var toolWindowBadgeModeComboBox: ComboBox<ToolWindowBadgeMode>? = null
        private set

    /**
     * Baut die Oberfläche der Wurzelseite auf und bindet alle Bedienelemente an den Einstellungszustand.
     *
     * @return Das Panel mit den Einstellungen zu Darstellung und Verhalten.
     */
    override fun createPanel(): DialogPanel = panel {
        row(MyMessageBundle.message("settings.repositoryBrowser")) {
            repositoryBrowserComboBox = comboBox(
                MavenRepositoryBrowser.entries,
                settingsListCellRenderer { browser: MavenRepositoryBrowser -> browser.displayName }
            )
                .bindItem(
                    { state.repositoryBrowser },
                    { state.repositoryBrowser = it ?: MavenRepositoryBrowser.MVN_REPOSITORY }
                )
                .component
        }.rowComment(MyMessageBundle.message("settings.repositoryBrowser.comment"))
        row {
            toolbarShowTextCheckBox = checkBox(MyMessageBundle.message("settings.toolbarShowText"))
                .bindSelected({ state.toolbarShowText }, { state.toolbarShowText = it })
                .component
        }.rowComment(MyMessageBundle.message("settings.toolbarShowText.comment"))
        row {
            jumpOnSingleClickCheckBox = checkBox(MyMessageBundle.message("settings.jumpOnSingleClick"))
                .bindSelected({ state.jumpOnSingleClick }, { state.jumpOnSingleClick = it })
                .component
        }.rowComment(MyMessageBundle.message("settings.jumpOnSingleClick.comment"))
        row(MyMessageBundle.message("settings.toolWindowBadgeMode")) {
            toolWindowBadgeModeComboBox = comboBox(
                ToolWindowBadgeMode.entries,
                settingsListCellRenderer { mode: ToolWindowBadgeMode -> MyMessageBundle.message(mode.messageKey) }
            )
                .bindItem(
                    { state.toolWindowBadgeMode },
                    { state.toolWindowBadgeMode = it ?: ToolWindowBadgeMode.VULNERABILITIES_AND_UPDATES }
                )
                .component
        }.rowComment(MyMessageBundle.message("settings.toolWindowBadgeMode.comment"))
    }

    /**
     * Gibt die Referenzen auf die Bedienelemente frei, wenn die Einstellungsseite geschlossen wird.
     */
    override fun disposeUIResources() {
        repositoryBrowserComboBox = null
        toolbarShowTextCheckBox = null
        jumpOnSingleClickCheckBox = null
        toolWindowBadgeModeComboBox = null
        super.disposeUIResources()
    }

    private companion object {
        /** Anzeigename der Wurzelseite im Einstellungsbaum. */
        const val DISPLAY_NAME = "MavenUp"
    }
}
