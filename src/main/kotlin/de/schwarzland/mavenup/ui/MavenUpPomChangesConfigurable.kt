package de.schwarzland.mavenup.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.JBIntSpinner
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.COLUMNS_MEDIUM
import com.intellij.ui.dsl.builder.bindIntValue
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.layout.selectedValueMatches
import de.schwarzland.mavenup.service.VulnerabilityCommentMode

/** Obergrenze für die Anzahl der im erklärenden XML-Kommentar aufgelisteten Kennungen. */
private const val VULNERABILITY_COMMENT_MAX_IDS_LIMIT = 99

/**
 * Einstellungsseite **Pom.xml Changes** unterhalb der MavenUp-Wurzelseite.
 *
 * Die Seite steuert, was beim Schreiben der `pom.xml` passiert: den anschließenden Maven-Sync und
 * den erklärenden XML-Kommentar, der beim Pinnen einer Abhängigkeit zur Behebung einer Sicherheitslücke
 * eingefügt wird.
 *
 * @param project Das Projekt, dessen Message-Bus nach dem Speichern benachrichtigt wird.
 */
// "Pom.xml Changes" enthaelt den Dateinamen pom.xml und ist daher bewusst nicht in Title Case.
@Suppress("DialogTitleCapitalization")
class MavenUpPomChangesConfigurable(project: Project) :
    MavenUpSettingsPage(project, MyMessageBundle.message("settings.page.pomChanges")) {

    /** Schalter für den automatischen Maven-Sync nach dem Schreiben der `pom.xml`. */
    internal var syncMavenAfterUpdateCheckBox: JBCheckBox? = null
        private set

    /** Auswahlfeld für den Umfang des erklärenden XML-Kommentars. */
    internal var vulnerabilityCommentModeComboBox: ComboBox<VulnerabilityCommentMode>? = null
        private set

    /** Eingabefeld für den Text vor den aufgelisteten Kennungen. */
    internal var vulnerabilityCommentPrefixField: JBTextField? = null
        private set

    /** Eingabefeld für die Höchstzahl der aufgelisteten Kennungen. */
    internal var vulnerabilityCommentMaxIdsSpinner: JBIntSpinner? = null
        private set

    /**
     * Baut die Oberfläche der Seite auf und bindet alle Bedienelemente an den Einstellungszustand.
     *
     * Der Kommentartext ist nur bearbeitbar, wenn überhaupt ein Kommentar geschrieben wird; die
     * Höchstzahl der Kennungen nur, wenn der gewählte Modus Kennungen auflistet.
     *
     * @return Das Panel mit den Einstellungen zum Schreiben der `pom.xml`.
     */
    override fun createPanel(): DialogPanel = panel {
        row {
            syncMavenAfterUpdateCheckBox = checkBox(MyMessageBundle.message("settings.syncMavenAfterUpdate"))
                .bindSelected({ state.syncMavenAfterUpdate }, { state.syncMavenAfterUpdate = it })
                .component
        }.rowComment(MyMessageBundle.message("settings.syncMavenAfterUpdate.comment"))
        lateinit var commentModeComboBox: ComboBox<VulnerabilityCommentMode>
        row(MyMessageBundle.message("settings.vulnerabilityCommentMode")) {
            commentModeComboBox = comboBox(
                VulnerabilityCommentMode.entries,
                settingsListCellRenderer { mode: VulnerabilityCommentMode ->
                    MyMessageBundle.message(mode.messageKey)
                }
            )
                .bindItem({ state.vulnerabilityCommentMode }, { applyCommentMode(it) })
                .component
            vulnerabilityCommentModeComboBox = commentModeComboBox
        }.rowComment(MyMessageBundle.message("settings.vulnerabilityCommentMode.comment"))
        indent {
            row(MyMessageBundle.message("settings.vulnerabilityCommentPrefix")) {
                vulnerabilityCommentPrefixField = textField()
                    .align(Align.FILL)
                    .resizableColumn()
                    .columns(COLUMNS_MEDIUM)
                    .bindText(
                        { state.vulnerabilityCommentPrefix },
                        { state.vulnerabilityCommentPrefix = it.trim() }
                    )
                    .component
            }.enabledIf(commentModeComboBox.selectedValueMatches { it != null && it != VulnerabilityCommentMode.NONE })
            row(MyMessageBundle.message("settings.vulnerabilityCommentMaxIds")) {
                vulnerabilityCommentMaxIdsSpinner = spinner(0..VULNERABILITY_COMMENT_MAX_IDS_LIMIT)
                    .bindIntValue(
                        { state.vulnerabilityCommentMaxIds },
                        { state.vulnerabilityCommentMaxIds = it.coerceAtLeast(0) }
                    )
                    .component
            }.enabledIf(
                commentModeComboBox.selectedValueMatches {
                    it != null && it != VulnerabilityCommentMode.NONE && it != VulnerabilityCommentMode.TEXT_ONLY
                }
            ).rowComment(MyMessageBundle.message("settings.vulnerabilityCommentMaxIds.comment"))
        }
    }

    /**
     * Übernimmt den gewählten Kommentarmodus und schreibt das Legacy-Flag konsistent fort,
     * damit ältere Plugin-Versionen den Zustand weiterhin lesen können.
     *
     * @param mode Der gewählte Modus; `null` wird als [VulnerabilityCommentMode.ADVISORY_IDS] behandelt.
     */
    private fun applyCommentMode(mode: VulnerabilityCommentMode?) {
        val selectedMode = mode ?: VulnerabilityCommentMode.ADVISORY_IDS
        state.vulnerabilityCommentMode = selectedMode
        state.addVulnerabilityFixComment = selectedMode != VulnerabilityCommentMode.NONE
    }

    /**
     * Gibt die Referenzen auf die Bedienelemente frei, wenn die Einstellungsseite geschlossen wird.
     */
    override fun disposeUIResources() {
        syncMavenAfterUpdateCheckBox = null
        vulnerabilityCommentModeComboBox = null
        vulnerabilityCommentPrefixField = null
        vulnerabilityCommentMaxIdsSpinner = null
        super.disposeUIResources()
    }
}
