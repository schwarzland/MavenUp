package de.schwarzland.mavenup.ui

import de.schwarzland.mavenup.service.MavenRepositoryBrowser
import de.schwarzland.mavenup.service.MavenUpSettings
import de.schwarzland.mavenup.service.MAVEN_UP_SETTINGS_TOPIC
import de.schwarzland.mavenup.service.OssIndexCredentialService
import de.schwarzland.mavenup.service.OssIndexCredentialStore
import de.schwarzland.mavenup.service.VersionAutoSelectionMode
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import java.util.concurrent.CompletableFuture
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPasswordField
import javax.swing.JTextField

internal const val OSS_INDEX_ACCOUNT_URL = "https://ossindex.sonatype.org"

/**
 * Diese Klasse stellt die Konfigurationsoberfläche (Settings/Preferences) für das MavenUp-Plugin bereit.
 *
 * Sie implementiert das [Configurable]-Interface von IntelliJ und ermöglicht es dem Benutzer:
 * - UI-Verhalten anzupassen (z. B. Sprung bei Einzelklick).
 * - Filter für instabile Versionen zu konfigurieren.
 * - Das Schreibverhalten beim Anwenden von Updates auf die `pom.xml` zu steuern (z. B. Maven-Sync,
 *   erklärender Kommentar bei Schwachstellen-Fixes).
 * - Sicherheitsprüfungen (OSS Index) zu aktivieren und Zugangsdaten zu verwalten.
 *
 * Die Einstellungen sind in der UI in die Gruppen **Appearance**, **Versions and Updates**,
 * **Pom.xml Changes** und **Vulnerability Check** gegliedert.
 *
 * Die Klasse wird benötigt, um die Plugin-Einstellungen in den IDE-Einstellungen anzuzeigen
 * und Änderungen an [MavenUpSettings] sowie [OssIndexCredentialStore] zu persistieren.
 */
class MavenUpConfigurable internal constructor(
    private val project: Project,
    private val credentialService: OssIndexCredentialStore
) : Configurable {
    constructor(project: Project) : this(project, OssIndexCredentialService())

    private var jumpOnSingleClickCheckBox: JBCheckBox? = null
    private var repositoryBrowserComboBox: ComboBox<MavenRepositoryBrowser>? = null
    private var toolbarShowTextCheckBox: JBCheckBox? = null
    private var syncMavenAfterUpdateCheckBox: JBCheckBox? = null
    private var stopAfterCentralSuccessCheckBox: JBCheckBox? = null
    private var versionAutoSelectionModeComboBox: ComboBox<VersionAutoSelectionMode>? = null
    private var offerAllVersionsCheckBox: JBCheckBox? = null
    private var confirmVersionResetCheckBox: JBCheckBox? = null
    private var hideUnstableVersionsCheckBox: JBCheckBox? = null
    private var hiddenVersionQualifiersLabel: JLabel? = null
    private var hiddenVersionQualifiersField: JTextField? = null
    private var checkTransitiveDependenciesCheckBox: JBCheckBox? = null
    private var addVulnerabilityFixCommentCheckBox: JBCheckBox? = null
    private var ossIndexEnabledCheckBox: JBCheckBox? = null
    private var ossIndexTokenLabel: JLabel? = null
    private var ossIndexTokenField: JPasswordField? = null
    private var storedToken = ""
    private var credentialsLoaded = false
    private var credentialLoadGeneration = 0
    private var credentialLoadFuture: CompletableFuture<*>? = null

    /**
     * Liefert den Anzeigenamen für den Eintrag in den IDE-Einstellungen.
     */
    override fun getDisplayName(): String = "MavenUp"

    /**
     * Erstellt die Benutzeroberfläche für die Einstellungen unter Verwendung des IntelliJ UI DSL.
     */
    override fun createComponent(): JComponent {
        val settings = MavenUpSettings.getInstance()
        return panel {
            group(MyMessageBundle.message("settings.group.appearance")) {
                row {
                    label(MyMessageBundle.message("settings.repositoryBrowser"))
                    repositoryBrowserComboBox = comboBox(MavenRepositoryBrowser.entries)
                        .applyToComponent {
                            selectedItem = settings.state.repositoryBrowser
                            toolTipText = MyMessageBundle.message("settings.repositoryBrowser.tooltip")
                            renderer = object : SimpleListCellRenderer<MavenRepositoryBrowser>() {
                                override fun customize(
                                    list: javax.swing.JList<out MavenRepositoryBrowser>,
                                    value: MavenRepositoryBrowser?,
                                    index: Int,
                                    selected: Boolean,
                                    hasFocus: Boolean
                                ) {
                                    text = value?.displayName ?: ""
                                }
                            }
                        }
                        .component
                }
                row {
                    toolbarShowTextCheckBox = checkBox(MyMessageBundle.message("settings.toolbarShowText"))
                        .applyToComponent {
                            isSelected = settings.state.toolbarShowText
                            toolTipText = MyMessageBundle.message("settings.toolbarShowText.tooltip")
                        }
                        .component
                }
                row {
                    jumpOnSingleClickCheckBox = checkBox(MyMessageBundle.message("settings.jumpOnSingleClick"))
                        .applyToComponent {
                            isSelected = settings.state.jumpOnSingleClick
                            toolTipText = MyMessageBundle.message("settings.jumpOnSingleClick.tooltip")
                        }
                        .component
                }
                row {
                    confirmVersionResetCheckBox = checkBox(MyMessageBundle.message("settings.confirmVersionReset"))
                        .applyToComponent {
                            isSelected = settings.state.confirmVersionReset
                            toolTipText = MyMessageBundle.message("settings.confirmVersionReset.tooltip")
                        }
                        .component
                }
            }
            group(MyMessageBundle.message("settings.group.versions")) {
                row {
                    label(MyMessageBundle.message("settings.versionAutoSelectionMode"))
                    versionAutoSelectionModeComboBox = comboBox(VersionAutoSelectionMode.entries)
                        .applyToComponent {
                            selectedItem = settings.state.versionAutoSelectionMode
                            toolTipText = MyMessageBundle.message("settings.versionAutoSelectionMode.tooltip")
                            renderer = object : SimpleListCellRenderer<VersionAutoSelectionMode>() {
                                override fun customize(
                                    list: javax.swing.JList<out VersionAutoSelectionMode>,
                                    value: VersionAutoSelectionMode?,
                                    index: Int,
                                    selected: Boolean,
                                    hasFocus: Boolean
                                ) {
                                    text = if (value == null) "" else MyMessageBundle.message(value.messageKey)
                                }
                            }
                        }
                        .component
                }
                row {
                    offerAllVersionsCheckBox = checkBox(MyMessageBundle.message("settings.offerAllVersions"))
                        .applyToComponent {
                            isSelected = settings.state.offerAllVersions
                            toolTipText = MyMessageBundle.message("settings.offerAllVersions.tooltip")
                        }
                        .component
                }
                row {
                    hideUnstableVersionsCheckBox = checkBox(MyMessageBundle.message("settings.hideUnstableVersions"))
                            .applyToComponent {
                                isSelected = settings.state.hideUnstableVersions
                                toolTipText = MyMessageBundle.message("settings.hideUnstableVersions.tooltip")
                            }
                            .component
                }
                indent {
                    row {
                        hiddenVersionQualifiersLabel = label(MyMessageBundle.message("settings.hiddenVersionQualifiers")).component
                        hiddenVersionQualifiersField = textField()
                            .align(Align.FILL)
                            .resizableColumn()
                            .applyToComponent {
                                text = settings.state.hiddenVersionQualifiers
                                columns = 20
                            }
                            .component
                    }
                }
                row {
                    stopAfterCentralSuccessCheckBox = checkBox(MyMessageBundle.message("settings.stopAfterCentralSuccess"))
                        .applyToComponent {
                            isSelected = settings.state.stopAfterCentralSuccess
                            toolTipText = MyMessageBundle.message("settings.stopAfterCentralSuccess.tooltip")
                        }
                        .component
                }
            }
            group(MyMessageBundle.message("settings.group.pomChanges")) {
                row {
                    syncMavenAfterUpdateCheckBox = checkBox(MyMessageBundle.message("settings.syncMavenAfterUpdate"))
                        .applyToComponent {
                            isSelected = settings.state.syncMavenAfterUpdate
                            toolTipText = MyMessageBundle.message("settings.syncMavenAfterUpdate.tooltip")
                        }
                        .component
                }
                row {
                    addVulnerabilityFixCommentCheckBox =
                        checkBox(MyMessageBundle.message("settings.addVulnerabilityFixComment"))
                            .applyToComponent {
                                isSelected = settings.state.addVulnerabilityFixComment
                                toolTipText = MyMessageBundle.message("settings.addVulnerabilityFixComment.tooltip")
                            }
                            .component
                }
            }
            group(MyMessageBundle.message("settings.group.vulnerability")) {
                row {
                    checkTransitiveDependenciesCheckBox =
                        checkBox(MyMessageBundle.message("settings.checkTransitiveDependencies"))
                            .applyToComponent {
                                isSelected = settings.state.checkTransitiveDependencies
                                toolTipText = MyMessageBundle.message("settings.checkTransitiveDependencies.tooltip")
                            }
                            .component
                }
                row {
                    ossIndexEnabledCheckBox = checkBox(MyMessageBundle.message("settings.ossIndex.enabled"))
                        .applyToComponent {
                            isSelected = settings.state.ossIndexEnabled
                            toolTipText = MyMessageBundle.message("settings.ossIndex.enabled.tooltip")
                        }
                        .component
                }
                indent {
                    row {
                        ossIndexTokenLabel = label(MyMessageBundle.message("settings.ossIndex.token")).component
                        ossIndexTokenField = cell(JPasswordField())
                            .align(Align.FILL)
                            .resizableColumn()
                            .applyToComponent {
                                columns = 20
                            }
                            .component
                    }
                    row {
                        comment(MyMessageBundle.message("settings.ossIndex.hint"))
                    }
                    row {
                        browserLink(
                            MyMessageBundle.message("settings.ossIndex.accountLink"),
                            OSS_INDEX_ACCOUNT_URL
                        )
                    }
                }
            }
            updateHiddenQualifierControlsEnabled(settings.state.hideUnstableVersions)
            hideUnstableVersionsCheckBox?.addActionListener {
                updateHiddenQualifierControlsEnabled(hideUnstableVersionsCheckBox?.isSelected == true)
            }
            updateOssIndexControlsEnabled(settings.state.ossIndexEnabled)
            ossIndexEnabledCheckBox?.addActionListener {
                updateOssIndexControlsEnabled(ossIndexEnabledCheckBox?.isSelected == true)
            }
        }
    }

    /**
     * Prüft, ob der Benutzer Änderungen an den Einstellungen vorgenommen hat, die noch nicht gespeichert wurden.
     */
    override fun isModified(): Boolean {
        val settings = MavenUpSettings.getInstance()
        return jumpOnSingleClickCheckBox?.isSelected != settings.state.jumpOnSingleClick ||
                repositoryBrowserComboBox?.selectedItem != settings.state.repositoryBrowser ||
                toolbarShowTextCheckBox?.isSelected != settings.state.toolbarShowText ||
                syncMavenAfterUpdateCheckBox?.isSelected != settings.state.syncMavenAfterUpdate ||
                stopAfterCentralSuccessCheckBox?.isSelected != settings.state.stopAfterCentralSuccess ||
                versionAutoSelectionModeComboBox?.selectedItem != settings.state.versionAutoSelectionMode ||
                offerAllVersionsCheckBox?.isSelected != settings.state.offerAllVersions ||
                confirmVersionResetCheckBox?.isSelected != settings.state.confirmVersionReset ||
                hideUnstableVersionsCheckBox?.isSelected != settings.state.hideUnstableVersions ||
                hiddenVersionQualifiersField?.text != settings.state.hiddenVersionQualifiers ||
                checkTransitiveDependenciesCheckBox?.isSelected != settings.state.checkTransitiveDependencies ||
                addVulnerabilityFixCommentCheckBox?.isSelected != settings.state.addVulnerabilityFixComment ||
                ossIndexEnabledCheckBox?.isSelected != settings.state.ossIndexEnabled ||
                credentialsLoaded && currentToken() != storedToken
    }

    /**
     * Speichert die vom Benutzer vorgenommenen Änderungen in [MavenUpSettings] und [OssIndexCredentialStore].
     * @throws ConfigurationException wenn erforderliche Zugangsdaten fehlen.
     */
    override fun apply() {
        val settings = MavenUpSettings.getInstance()
        val ossIndexEnabled = ossIndexEnabledCheckBox?.isSelected ?: false
        val ossIndexToken = currentToken()
        if (ossIndexEnabled && credentialsLoaded && ossIndexToken.isBlank()) {
            throw ConfigurationException(MyMessageBundle.message("settings.ossIndex.credentialsRequired"))
        }

        settings.state.jumpOnSingleClick = jumpOnSingleClickCheckBox?.isSelected ?: false
        settings.state.repositoryBrowser = repositoryBrowserComboBox?.selectedItem as? MavenRepositoryBrowser
            ?: MavenRepositoryBrowser.MVN_REPOSITORY
        settings.state.toolbarShowText = toolbarShowTextCheckBox?.isSelected ?: false
        settings.state.syncMavenAfterUpdate = syncMavenAfterUpdateCheckBox?.isSelected ?: true
        settings.state.stopAfterCentralSuccess = stopAfterCentralSuccessCheckBox?.isSelected ?: true
        settings.state.versionAutoSelectionMode =
            versionAutoSelectionModeComboBox?.selectedItem as? VersionAutoSelectionMode
                ?: VersionAutoSelectionMode.DISABLED
        settings.state.selectLatestVersion = settings.state.versionAutoSelectionMode != VersionAutoSelectionMode.DISABLED
        settings.state.selectLatestMinorVersion = settings.state.versionAutoSelectionMode == VersionAutoSelectionMode.LATEST_MINOR
        settings.state.offerAllVersions = offerAllVersionsCheckBox?.isSelected ?: false
        settings.state.confirmVersionReset = confirmVersionResetCheckBox?.isSelected ?: true
        settings.state.hideUnstableVersions = hideUnstableVersionsCheckBox?.isSelected ?: false
        settings.state.hiddenVersionQualifiers = hiddenVersionQualifiersField?.text?.trim().orEmpty()
        settings.state.checkTransitiveDependencies = checkTransitiveDependenciesCheckBox?.isSelected ?: true
        settings.state.addVulnerabilityFixComment = addVulnerabilityFixCommentCheckBox?.isSelected ?: true
        settings.state.ossIndexEnabled = ossIndexEnabled
        if (credentialsLoaded) {
            storedToken = ossIndexToken
            credentialService.store(storedToken)
        }
        project.messageBus.syncPublisher(MAVEN_UP_SETTINGS_TOPIC).run()
    }

    /**
     * Setzt die UI-Komponenten auf den zuletzt gespeicherten Stand zurück.
     * Lädt dabei auch die Zugangsdaten asynchron aus dem Passwort-Safe.
     */
    override fun reset() {
        val settings = MavenUpSettings.getInstance()
        jumpOnSingleClickCheckBox?.isSelected = settings.state.jumpOnSingleClick
        repositoryBrowserComboBox?.selectedItem = settings.state.repositoryBrowser
        toolbarShowTextCheckBox?.isSelected = settings.state.toolbarShowText
        syncMavenAfterUpdateCheckBox?.isSelected = settings.state.syncMavenAfterUpdate
        stopAfterCentralSuccessCheckBox?.isSelected = settings.state.stopAfterCentralSuccess
        versionAutoSelectionModeComboBox?.selectedItem = settings.state.versionAutoSelectionMode
        offerAllVersionsCheckBox?.isSelected = settings.state.offerAllVersions
        confirmVersionResetCheckBox?.isSelected = settings.state.confirmVersionReset
        hideUnstableVersionsCheckBox?.isSelected = settings.state.hideUnstableVersions
        hiddenVersionQualifiersField?.text = settings.state.hiddenVersionQualifiers
        checkTransitiveDependenciesCheckBox?.isSelected = settings.state.checkTransitiveDependencies
        addVulnerabilityFixCommentCheckBox?.isSelected = settings.state.addVulnerabilityFixComment
        ossIndexEnabledCheckBox?.isSelected = settings.state.ossIndexEnabled
        credentialsLoaded = false
        storedToken = ""
        ossIndexTokenField?.text = ""
        updateHiddenQualifierControlsEnabled(settings.state.hideUnstableVersions)
        updateOssIndexControlsEnabled(settings.state.ossIndexEnabled)
        loadCredentials()
    }

    /**
     * Gibt Ressourcen frei, wenn die Einstellungsseite geschlossen wird.
     */
    override fun disposeUIResources() {
        credentialLoadGeneration++
        credentialLoadFuture?.cancel(true)
        credentialLoadFuture = null
        jumpOnSingleClickCheckBox = null
        repositoryBrowserComboBox = null
        toolbarShowTextCheckBox = null
        syncMavenAfterUpdateCheckBox = null
        stopAfterCentralSuccessCheckBox = null
        versionAutoSelectionModeComboBox = null
        offerAllVersionsCheckBox = null
        confirmVersionResetCheckBox = null
        hideUnstableVersionsCheckBox = null
        hiddenVersionQualifiersLabel = null
        hiddenVersionQualifiersField = null
        checkTransitiveDependenciesCheckBox = null
        addVulnerabilityFixCommentCheckBox = null
        ossIndexEnabledCheckBox = null
        ossIndexTokenLabel = null
        ossIndexTokenField = null
    }

    /**
     * Lädt die OSS-Index-Zugangsdaten asynchron, um die UI nicht zu blockieren.
     */
    private fun loadCredentials() {
        val generation = ++credentialLoadGeneration
        credentialLoadFuture?.cancel(true)
        credentialLoadFuture = CompletableFuture.supplyAsync(
            { credentialService.retrieve() },
            AppExecutorUtil.getAppExecutorService()
        ).whenComplete { credentials, error ->
            ApplicationManager.getApplication().invokeLater(
                {
                    if (generation != credentialLoadGeneration) {
                        return@invokeLater
                    }
                    credentialLoadFuture = null
                    if (error != null) {
                        LOG.warn("Unable to load OSS Index credentials from Password Safe", error)
                        credentialsLoaded = true
                        updateOssIndexControlsEnabled(ossIndexEnabledCheckBox?.isSelected == true)
                        return@invokeLater
                    }
                    storedToken = credentials?.getPasswordAsString().orEmpty()
                    credentialsLoaded = true
                    ossIndexTokenField?.text = storedToken
                    updateOssIndexControlsEnabled(ossIndexEnabledCheckBox?.isSelected == true)
                },
                ModalityState.any(),
                project.disposed
            )
        }
    }

    /**
     * Aktiviert oder deaktiviert die Eingabefelder für Versions-Qualifizierer basierend auf dem Checkbox-Status.
     */
    private fun updateHiddenQualifierControlsEnabled(enabled: Boolean) {
        hiddenVersionQualifiersLabel?.isEnabled = enabled
        hiddenVersionQualifiersField?.isEnabled = enabled
    }

    /**
     * Aktiviert oder deaktiviert die Eingabefelder für den OSS Index und aktualisiert die Beschriftungen (Pflichtfelder).
     */
    private fun updateOssIndexControlsEnabled(enabled: Boolean) {
        ossIndexTokenLabel?.text = MyMessageBundle.message(
            if (enabled) "settings.ossIndex.tokenRequired" else "settings.ossIndex.token"
        )
        ossIndexEnabledCheckBox?.isEnabled = credentialsLoaded
        ossIndexTokenLabel?.isEnabled = enabled && credentialsLoaded
        ossIndexTokenField?.isEnabled = enabled && credentialsLoaded
    }

    /**
     * Hilfsmethode zum Auslesen des aktuellen Passworts/Tokens aus dem Passwortfeld.
     */
    private fun currentToken(): String = ossIndexTokenField?.password?.concatToString().orEmpty()

    private companion object {
        val LOG = Logger.getInstance(MavenUpConfigurable::class.java)
    }
}
