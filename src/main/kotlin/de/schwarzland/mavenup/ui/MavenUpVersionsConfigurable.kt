package de.schwarzland.mavenup.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.COLUMNS_MEDIUM
import com.intellij.ui.dsl.builder.Cell
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.selected
import de.schwarzland.mavenup.service.VersionAutoSelectionMode

/**
 * Einstellungsseite **Versions and Updates** unterhalb der MavenUp-Wurzelseite.
 *
 * Die Seite bündelt alles, was die Suche nach Versionen und deren Auswahl betrifft, und ist gemäß den
 * IntelliJ-UI-Guidelines in die Abschnitte *Version Lookup*, *Version Selection* und *Privacy* gegliedert.
 *
 * @param project Das Projekt, dessen Message-Bus nach dem Speichern benachrichtigt wird.
 */
class MavenUpVersionsConfigurable(project: Project) :
    MavenUpSettingsPage(project, MyMessageBundle.message("settings.page.versions")) {

    /** Schalter für die automatische Versionssuche nach dem Laden der Projektdaten. */
    internal var autoSearchVersionsCheckBox: JBCheckBox? = null
        private set

    /** Schalter, der weitere Repository-Abfragen nach erfolgreicher Central-Abfrage unterbindet. */
    internal var stopAfterCentralSuccessCheckBox: JBCheckBox? = null
        private set

    /** Eingabefeld für private GroupId-Präfixe. */
    internal var privateGroupIdsField: JBTextField? = null
        private set

    /** Schalter, der auch ältere Versionen zur Auswahl anbietet. */
    internal var offerAllVersionsCheckBox: JBCheckBox? = null
        private set

    /** Schalter zum Ausblenden instabiler Versionen. */
    internal var hideUnstableVersionsCheckBox: JBCheckBox? = null
        private set

    /** Eingabefeld für die als instabil geltenden Versions-Qualifizierer. */
    internal var hiddenVersionQualifiersField: JBTextField? = null
        private set

    /** Auswahlfeld für die Vorauswahl-Strategie nach einem Update-Check. */
    internal var versionAutoSelectionModeComboBox: ComboBox<VersionAutoSelectionMode>? = null
        private set

    /** Schalter für die Rückfrage vor dem Zurücksetzen aller Versionsauswahlen. */
    internal var confirmVersionResetCheckBox: JBCheckBox? = null
        private set

    /**
     * Baut die Oberfläche der Seite auf und bindet alle Bedienelemente an den Einstellungszustand.
     *
     * Das Eingabefeld für die Versions-Qualifizierer ist nur aktiv, solange instabile Versionen
     * ausgeblendet werden.
     *
     * @return Das Panel mit den Einstellungen zu Versionssuche und Versionsauswahl.
     */
    override fun createPanel(): DialogPanel = panel {
        group(MyMessageBundle.message("settings.group.versionLookup")) {
            row {
                autoSearchVersionsCheckBox = checkBox(MyMessageBundle.message("settings.autoSearchVersions"))
                    .bindSelected({ state.autoSearchVersions }, { state.autoSearchVersions = it })
                    .component
            }.rowComment(MyMessageBundle.message("settings.autoSearchVersions.comment"))
            row {
                stopAfterCentralSuccessCheckBox =
                    checkBox(MyMessageBundle.message("settings.stopAfterCentralSuccess"))
                        .bindSelected({ state.stopAfterCentralSuccess }, { state.stopAfterCentralSuccess = it })
                        .component
            }.rowComment(MyMessageBundle.message("settings.stopAfterCentralSuccess.comment"))
        }
        group(MyMessageBundle.message("settings.group.privacy")) {
            row(MyMessageBundle.message("settings.privateGroupIds")) {
                privateGroupIdsField = textField()
                    .align(Align.FILL)
                    .resizableColumn()
                    .columns(COLUMNS_MEDIUM)
                    .bindText({ state.privateGroupIds }, { state.privateGroupIds = it.trim() })
                    .component
            }.rowComment(MyMessageBundle.message("settings.privateGroupIds.comment"))
        }
        group(MyMessageBundle.message("settings.group.versionSelection")) {
            row {
                offerAllVersionsCheckBox = checkBox(MyMessageBundle.message("settings.offerAllVersions"))
                    .bindSelected({ state.offerAllVersions }, { state.offerAllVersions = it })
                    .component
            }.rowComment(MyMessageBundle.message("settings.offerAllVersions.comment"))
            lateinit var hideUnstableVersionsCell: Cell<JBCheckBox>
            row {
                hideUnstableVersionsCell = checkBox(MyMessageBundle.message("settings.hideUnstableVersions"))
                    .bindSelected({ state.hideUnstableVersions }, { state.hideUnstableVersions = it })
                hideUnstableVersionsCheckBox = hideUnstableVersionsCell.component
            }.rowComment(MyMessageBundle.message("settings.hideUnstableVersions.comment"))
            indent {
                row(MyMessageBundle.message("settings.hiddenVersionQualifiers")) {
                    hiddenVersionQualifiersField = textField()
                        .align(Align.FILL)
                        .resizableColumn()
                        .columns(COLUMNS_MEDIUM)
                        .bindText({ state.hiddenVersionQualifiers }, { state.hiddenVersionQualifiers = it.trim() })
                        .component
                }.enabledIf(hideUnstableVersionsCell.selected)
            }
            row(MyMessageBundle.message("settings.versionAutoSelectionMode")) {
                versionAutoSelectionModeComboBox = comboBox(
                    VersionAutoSelectionMode.entries,
                    settingsListCellRenderer { mode: VersionAutoSelectionMode ->
                        MyMessageBundle.message(mode.messageKey)
                    }
                )
                    .bindItem({ state.versionAutoSelectionMode }, { applyAutoSelectionMode(it) })
                    .component
            }.rowComment(MyMessageBundle.message("settings.versionAutoSelectionMode.comment"))
            row {
                confirmVersionResetCheckBox = checkBox(MyMessageBundle.message("settings.confirmVersionReset"))
                    .bindSelected({ state.confirmVersionReset }, { state.confirmVersionReset = it })
                    .component
            }.rowComment(MyMessageBundle.message("settings.confirmVersionReset.comment"))
        }
    }

    /**
     * Übernimmt die gewählte Vorauswahl-Strategie und schreibt die Legacy-Flags konsistent fort,
     * damit ältere Plugin-Versionen den Zustand weiterhin lesen können.
     *
     * @param mode Die gewählte Strategie; `null` wird als [VersionAutoSelectionMode.DISABLED] behandelt.
     */
    private fun applyAutoSelectionMode(mode: VersionAutoSelectionMode?) {
        val selectedMode = mode ?: VersionAutoSelectionMode.DISABLED
        state.versionAutoSelectionMode = selectedMode
        state.selectLatestVersion = selectedMode != VersionAutoSelectionMode.DISABLED
        state.selectLatestMinorVersion = selectedMode == VersionAutoSelectionMode.LATEST_MINOR
    }

    /**
     * Gibt die Referenzen auf die Bedienelemente frei, wenn die Einstellungsseite geschlossen wird.
     */
    override fun disposeUIResources() {
        autoSearchVersionsCheckBox = null
        stopAfterCentralSuccessCheckBox = null
        privateGroupIdsField = null
        offerAllVersionsCheckBox = null
        hideUnstableVersionsCheckBox = null
        hiddenVersionQualifiersField = null
        versionAutoSelectionModeComboBox = null
        confirmVersionResetCheckBox = null
        super.disposeUIResources()
    }
}
