package de.schwarzland.mavenup.ui

import com.intellij.ide.DataManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.options.ex.Settings
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel
import de.schwarzland.mavenup.service.MavenRepositoryBrowser
import de.schwarzland.mavenup.service.ToolWindowBadgeMode
import java.awt.Component

/**
 * Beschreibt eine Unterseite der MavenUp-Einstellungen, die von der Wurzelseite aus verlinkt wird.
 *
 * @property titleKey Schlüssel des im Einstellungsbaum und im Link angezeigten Seitentitels.
 * @property descriptionKey Schlüssel der Kurzbeschreibung, die neben dem Link steht.
 * @property configurableClass Die Klasse der verlinkten Einstellungsseite.
 */
private data class SubPageLink(
    val titleKey: String,
    val descriptionKey: String,
    val configurableClass: Class<out Configurable>
)

/**
 * Wurzelseite der MavenUp-Einstellungen unter `Settings > Tools > MavenUp`.
 *
 * Die Seite enthält ausschließlich die Einstellungen zu Darstellung und Verhalten
 * (Repository-Browser, Beschriftung der Aktionsleiste, Klickverhalten, Tool-Window-Badge).
 * Alle weiteren Themen liegen in den Unterseiten [MavenUpVersionsConfigurable],
 * [MavenUpVulnerabilityConfigurable] und [MavenUpPomChangesConfigurable], die in `plugin.xml`
 * über `parentId` unterhalb dieser Seite eingehängt sind. Am Seitenende führen Quick-Links direkt
 * zu diesen Unterseiten.
 *
 * @property project Das Projekt, dessen Message-Bus nach dem Speichern benachrichtigt wird und in
 *   dem eine Unterseite geöffnet wird, wenn kein Einstellungsdialog verfügbar ist.
 */
class MavenUpConfigurable(private val project: Project) : MavenUpSettingsPage(project, DISPLAY_NAME) {

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

    /** Die Quick-Links zu den Unterseiten in der Reihenfolge des Einstellungsbaums. */
    internal val subPageLinks: List<ActionLink>
        get() = subPageLinkComponents.toList()

    private val subPageLinkComponents = mutableListOf<ActionLink>()

    /**
     * Baut die Oberfläche der Wurzelseite auf und bindet alle Bedienelemente an den Einstellungszustand.
     *
     * @return Das Panel mit den Einstellungen zu Darstellung und Verhalten sowie den Quick-Links.
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
        subPageLinkGroup()
    }

    /**
     * Baut die Gruppe mit den Quick-Links zu den Unterseiten auf.
     *
     * Jeder Link trägt den Titel der Zielseite und darunter eine Kurzbeschreibung ihres Inhalts,
     * sodass die Aufteilung der Einstellungen direkt auf der Wurzelseite erkennbar ist.
     */
    private fun Panel.subPageLinkGroup() {
        subPageLinkComponents.clear()
        group(MyMessageBundle.message("settings.group.moreSettings")) {
            for (subPage in SUB_PAGES) {
                row {
                    val link = link(MyMessageBundle.message(subPage.titleKey)) { event ->
                        navigateToSubPage(event.source as? Component, subPage.configurableClass)
                    }.component
                    subPageLinkComponents += link
                }.rowComment(MyMessageBundle.message(subPage.descriptionKey))
            }
        }
    }

    /**
     * Wechselt zur angegebenen Unterseite.
     *
     * Innerhalb eines geöffneten Einstellungsdialogs wird über [Settings] direkt der passende Baumknoten
     * ausgewählt; steht kein Dialog zur Verfügung, wird der Einstellungsdialog für die Seite geöffnet.
     *
     * @param source Die Komponente, über die der Datenkontext des Einstellungsdialogs ermittelt wird.
     * @param configurableClass Die Klasse der Zielseite.
     */
    private fun navigateToSubPage(source: Component?, configurableClass: Class<out Configurable>) {
        val settings = source?.let { Settings.KEY.getData(DataManager.getInstance().getDataContext(it)) }
        val configurable = settings?.find(configurableClass)
        if (settings != null && configurable != null) {
            settings.select(configurable)
        } else {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, configurableClass)
        }
    }

    /**
     * Gibt die Referenzen auf die Bedienelemente frei, wenn die Einstellungsseite geschlossen wird.
     */
    override fun disposeUIResources() {
        repositoryBrowserComboBox = null
        toolbarShowTextCheckBox = null
        jumpOnSingleClickCheckBox = null
        toolWindowBadgeModeComboBox = null
        subPageLinkComponents.clear()
        super.disposeUIResources()
    }

    private companion object {
        /** Anzeigename der Wurzelseite im Einstellungsbaum. */
        const val DISPLAY_NAME = "MavenUp"

        /** Die verlinkten Unterseiten in der Reihenfolge des Einstellungsbaums. */
        val SUB_PAGES = listOf(
            SubPageLink(
                "settings.page.versions",
                "settings.page.versions.description",
                MavenUpVersionsConfigurable::class.java
            ),
            SubPageLink(
                "settings.page.vulnerability",
                "settings.page.vulnerability.description",
                MavenUpVulnerabilityConfigurable::class.java
            ),
            SubPageLink(
                "settings.page.pomChanges",
                "settings.page.pomChanges.description",
                MavenUpPomChangesConfigurable::class.java
            )
        )
    }
}
