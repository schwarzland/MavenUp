package de.schwarzland.mavenup.ui

import de.schwarzland.mavenup.service.MavenRepositoryBrowser
import de.schwarzland.mavenup.service.MavenUpSettings
import de.schwarzland.mavenup.service.DEFAULT_VULNERABILITY_COMMENT_MAX_IDS
import de.schwarzland.mavenup.service.DEFAULT_VULNERABILITY_COMMENT_PREFIX
import de.schwarzland.mavenup.service.MAVEN_UP_SETTINGS_TOPIC
import de.schwarzland.mavenup.service.OssIndexCredentialService
import de.schwarzland.mavenup.service.OssIndexCredentialStore
import de.schwarzland.mavenup.service.ToolWindowBadgeMode
import de.schwarzland.mavenup.service.VersionAutoSelectionMode
import de.schwarzland.mavenup.service.VulnerabilityCommentMode
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
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.panel
import java.util.concurrent.CompletableFuture
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPasswordField
import javax.swing.JSpinner
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
 * **Privacy**, **Pom.xml Changes** und **Vulnerability Check** gegliedert.
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
    private var toolWindowBadgeModeComboBox: ComboBox<ToolWindowBadgeMode>? = null
    private var syncMavenAfterUpdateCheckBox: JBCheckBox? = null
    private var stopAfterCentralSuccessCheckBox: JBCheckBox? = null
    private var autoSearchVersionsCheckBox: JBCheckBox? = null
    private var versionAutoSelectionModeComboBox: ComboBox<VersionAutoSelectionMode>? = null
    private var offerAllVersionsCheckBox: JBCheckBox? = null
    private var confirmVersionResetCheckBox: JBCheckBox? = null
    private var hideUnstableVersionsCheckBox: JBCheckBox? = null
    private var hiddenVersionQualifiersLabel: JLabel? = null
    private var hiddenVersionQualifiersField: JTextField? = null
    private var checkTransitiveDependenciesCheckBox: JBCheckBox? = null
    private var vulnerabilityCommentModeComboBox: ComboBox<VulnerabilityCommentMode>? = null
    private var vulnerabilityCommentPrefixLabel: JLabel? = null
    private var vulnerabilityCommentPrefixField: JTextField? = null
    private var vulnerabilityCommentMaxIdsLabel: JLabel? = null
    private var vulnerabilityCommentMaxIdsSpinner: JSpinner? = null
    private var ossIndexEnabledCheckBox: JBCheckBox? = null
    private var ossIndexTokenLabel: JLabel? = null
    private var ossIndexTokenField: JPasswordField? = null
    private var privateGroupIdsField: JTextField? = null
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
     *
     * Die fünf Einstellungsgruppen werden in eigene Erweiterungsfunktionen ausgelagert, damit diese
     * Methode flach bleibt und jede Gruppe für sich lesbar ist.
     */
    override fun createComponent(): JComponent {
        val settings = MavenUpSettings.getInstance()
        return panel {
            appearanceGroup(settings)
            versionsGroup(settings)
            privacyGroup(settings)
            vulnerabilityGroup(settings)
            pomChangesGroup(settings)
            installControlListeners(settings)
        }
    }

    /**
     * Erzeugt einen Listen-Renderer, der den Anzeigetext eines Enum-Werts über [textOf] bestimmt
     * und für `null` einen leeren Text liefert.
     *
     * @param textOf Liefert den Anzeigetext für einen Wert.
     * @return Der wiederverwendbare Renderer für Auswahlfelder.
     */
    private fun <T> listCellRenderer(textOf: (T) -> String): SimpleListCellRenderer<T> =
        object : SimpleListCellRenderer<T>() {
            override fun customize(
                list: JList<out T>,
                value: T?,
                index: Int,
                selected: Boolean,
                hasFocus: Boolean
            ) {
                text = value?.let(textOf).orEmpty()
            }
        }

    /**
     * Baut die Gruppe **Appearance and Behavior** mit Repository-Browser, Toolbar-Beschriftung,
     * Klickverhalten und der Anzeigeoption des Tool-Window-Badges auf.
     *
     * @param settings Die Einstellungen, aus denen die Startwerte gelesen werden.
     */
    private fun Panel.appearanceGroup(settings: MavenUpSettings) {
        group(MyMessageBundle.message("settings.group.appearance")) {
            row {
                label(MyMessageBundle.message("settings.repositoryBrowser"))
                repositoryBrowserComboBox = comboBox(MavenRepositoryBrowser.entries)
                    .applyToComponent {
                        selectedItem = settings.state.repositoryBrowser
                        toolTipText = MyMessageBundle.message("settings.repositoryBrowser.tooltip")
                        renderer = listCellRenderer<MavenRepositoryBrowser> { it.displayName }
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
                label(MyMessageBundle.message("settings.toolWindowBadgeMode"))
                toolWindowBadgeModeComboBox = comboBox(ToolWindowBadgeMode.entries)
                    .applyToComponent {
                        selectedItem = settings.state.toolWindowBadgeMode
                        toolTipText = MyMessageBundle.message("settings.toolWindowBadgeMode.tooltip")
                        renderer = listCellRenderer<ToolWindowBadgeMode> {
                            MyMessageBundle.message(it.messageKey)
                        }
                    }
                    .component
            }
        }
    }

    /**
     * Baut die Gruppe **Versions and Updates** mit automatischer Versionssuche, Repository-Strategie,
     * Versionsfiltern und der Vorauswahl-Strategie auf.
     *
     * @param settings Die Einstellungen, aus denen die Startwerte gelesen werden.
     */
    private fun Panel.versionsGroup(settings: MavenUpSettings) {
        group(MyMessageBundle.message("settings.group.versions")) {
            row {
                autoSearchVersionsCheckBox = checkBox(MyMessageBundle.message("settings.autoSearchVersions"))
                    .applyToComponent {
                        isSelected = settings.state.autoSearchVersions
                        toolTipText = MyMessageBundle.message("settings.autoSearchVersions.tooltip")
                    }
                    .component
            }
            row {
                stopAfterCentralSuccessCheckBox = checkBox(MyMessageBundle.message("settings.stopAfterCentralSuccess"))
                    .applyToComponent {
                        isSelected = settings.state.stopAfterCentralSuccess
                        toolTipText = MyMessageBundle.message("settings.stopAfterCentralSuccess.tooltip")
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
                    hiddenVersionQualifiersLabel =
                        label(MyMessageBundle.message("settings.hiddenVersionQualifiers")).component
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
                label(MyMessageBundle.message("settings.versionAutoSelectionMode"))
                versionAutoSelectionModeComboBox = comboBox(VersionAutoSelectionMode.entries)
                    .applyToComponent {
                        selectedItem = settings.state.versionAutoSelectionMode
                        toolTipText = MyMessageBundle.message("settings.versionAutoSelectionMode.tooltip")
                        renderer = listCellRenderer<VersionAutoSelectionMode> {
                            MyMessageBundle.message(it.messageKey)
                        }
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
    }

    /**
     * Baut die Gruppe **Privacy** mit dem Filter für private/unternehmensinterne GroupId-Präfixe auf,
     * die von Abfragen an Maven Central ausgeschlossen werden.
     *
     * @param settings Die Einstellungen, aus denen die Startwerte gelesen werden.
     */
    private fun Panel.privacyGroup(settings: MavenUpSettings) {
        group(MyMessageBundle.message("settings.group.privacy")) {
            row {
                label(MyMessageBundle.message("settings.privateGroupIds"))
                privateGroupIdsField = textField()
                    .align(Align.FILL)
                    .resizableColumn()
                    .applyToComponent {
                        text = settings.state.privateGroupIds
                        columns = 20
                        toolTipText = MyMessageBundle.message("settings.privateGroupIds.tooltip")
                    }
                    .component
            }
        }
    }

    /**
     * Baut die Gruppe **Vulnerability Check** mit der Prüfung transitiver Abhängigkeiten und den
     * OSS-Index-Zugangsdaten auf.
     *
     * @param settings Die Einstellungen, aus denen die Startwerte gelesen werden.
     */
    private fun Panel.vulnerabilityGroup(settings: MavenUpSettings) {
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
    }

    /**
     * Baut die Gruppe **Pom.xml Changes** mit Maven-Sync und den Optionen des erklärenden
     * XML-Kommentars auf.
     *
     * @param settings Die Einstellungen, aus denen die Startwerte gelesen werden.
     */
    // "Pom.xml Changes" enthaelt den Dateinamen pom.xml und ist daher bewusst nicht in Title Case.
    @Suppress("DialogTitleCapitalization")
    private fun Panel.pomChangesGroup(settings: MavenUpSettings) {
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
                label(MyMessageBundle.message("settings.vulnerabilityCommentMode"))
                vulnerabilityCommentModeComboBox = comboBox(VulnerabilityCommentMode.entries)
                    .applyToComponent {
                        selectedItem = settings.state.vulnerabilityCommentMode
                        toolTipText = MyMessageBundle.message("settings.vulnerabilityCommentMode.tooltip")
                        renderer = listCellRenderer<VulnerabilityCommentMode> {
                            MyMessageBundle.message(it.messageKey)
                        }
                    }
                    .component
            }
            indent {
                row {
                    vulnerabilityCommentPrefixLabel =
                        label(MyMessageBundle.message("settings.vulnerabilityCommentPrefix")).component
                    vulnerabilityCommentPrefixField = textField()
                        .align(Align.FILL)
                        .resizableColumn()
                        .applyToComponent {
                            text = settings.state.vulnerabilityCommentPrefix
                            columns = 20
                            toolTipText = MyMessageBundle.message("settings.vulnerabilityCommentPrefix.tooltip")
                        }
                        .component
                }
                row {
                    vulnerabilityCommentMaxIdsLabel =
                        label(MyMessageBundle.message("settings.vulnerabilityCommentMaxIds")).component
                    vulnerabilityCommentMaxIdsSpinner = spinner(0..99)
                        .applyToComponent {
                            value = settings.state.vulnerabilityCommentMaxIds
                            toolTipText = MyMessageBundle.message("settings.vulnerabilityCommentMaxIds.tooltip")
                        }
                        .component
                }
            }
        }
    }

    /**
     * Setzt den initialen Aktivierungszustand der abhängigen Bedienelemente und verdrahtet die
     * Listener, die ihn bei einer Auswahländerung nachführen.
     *
     * @param settings Die Einstellungen, aus denen der Startzustand gelesen wird.
     */
    private fun installControlListeners(settings: MavenUpSettings) {
        updateHiddenQualifierControlsEnabled(settings.state.hideUnstableVersions)
        hideUnstableVersionsCheckBox?.addActionListener {
            updateHiddenQualifierControlsEnabled(hideUnstableVersionsCheckBox?.isSelected == true)
        }
        updateOssIndexControlsEnabled(settings.state.ossIndexEnabled)
        ossIndexEnabledCheckBox?.addActionListener {
            updateOssIndexControlsEnabled(ossIndexEnabledCheckBox?.isSelected == true)
        }
        updateVulnerabilityCommentControlsEnabled(settings.state.vulnerabilityCommentMode)
        vulnerabilityCommentModeComboBox?.addActionListener {
            updateVulnerabilityCommentControlsEnabled(
                vulnerabilityCommentModeComboBox?.selectedItem as? VulnerabilityCommentMode
            )
        }
    }

    /**
     * Prüft, ob der Benutzer Änderungen an den Einstellungen vorgenommen hat, die noch nicht gespeichert wurden.
     */
    override fun isModified(): Boolean {
        val state = MavenUpSettings.getInstance().state
        val comparisons: List<Pair<Any?, Any?>> = listOf(
            jumpOnSingleClickCheckBox?.isSelected to state.jumpOnSingleClick,
            repositoryBrowserComboBox?.selectedItem to state.repositoryBrowser,
            toolbarShowTextCheckBox?.isSelected to state.toolbarShowText,
            toolWindowBadgeModeComboBox?.selectedItem to state.toolWindowBadgeMode,
            syncMavenAfterUpdateCheckBox?.isSelected to state.syncMavenAfterUpdate,
            stopAfterCentralSuccessCheckBox?.isSelected to state.stopAfterCentralSuccess,
            autoSearchVersionsCheckBox?.isSelected to state.autoSearchVersions,
            versionAutoSelectionModeComboBox?.selectedItem to state.versionAutoSelectionMode,
            offerAllVersionsCheckBox?.isSelected to state.offerAllVersions,
            confirmVersionResetCheckBox?.isSelected to state.confirmVersionReset,
            hideUnstableVersionsCheckBox?.isSelected to state.hideUnstableVersions,
            hiddenVersionQualifiersField?.text to state.hiddenVersionQualifiers,
            checkTransitiveDependenciesCheckBox?.isSelected to state.checkTransitiveDependencies,
            vulnerabilityCommentModeComboBox?.selectedItem to state.vulnerabilityCommentMode,
            vulnerabilityCommentPrefixField?.text to state.vulnerabilityCommentPrefix,
            vulnerabilityCommentMaxIdsSpinner?.value to state.vulnerabilityCommentMaxIds,
            ossIndexEnabledCheckBox?.isSelected to state.ossIndexEnabled,
            privateGroupIdsField?.text to state.privateGroupIds
        )
        return comparisons.any { (uiValue, storedValue) -> uiValue != storedValue } || isTokenModified()
    }

    /**
     * Prüft, ob das eingegebene OSS-Index-Token vom gespeicherten Token abweicht.
     * Liefert nur dann `true`, wenn die Zugangsdaten bereits geladen wurden.
     * @return `true`, wenn das Token geändert wurde.
     */
    private fun isTokenModified(): Boolean = credentialsLoaded && currentToken() != storedToken

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
        settings.state.toolWindowBadgeMode = toolWindowBadgeModeComboBox?.selectedItem as? ToolWindowBadgeMode
            ?: ToolWindowBadgeMode.VULNERABILITIES_AND_UPDATES
        settings.state.syncMavenAfterUpdate = syncMavenAfterUpdateCheckBox?.isSelected ?: true
        settings.state.stopAfterCentralSuccess = stopAfterCentralSuccessCheckBox?.isSelected ?: true
        settings.state.autoSearchVersions = autoSearchVersionsCheckBox?.isSelected ?: true
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
        settings.state.vulnerabilityCommentMode =
            vulnerabilityCommentModeComboBox?.selectedItem as? VulnerabilityCommentMode
                ?: VulnerabilityCommentMode.ADVISORY_IDS
        settings.state.addVulnerabilityFixComment =
            settings.state.vulnerabilityCommentMode != VulnerabilityCommentMode.NONE
        settings.state.vulnerabilityCommentPrefix = vulnerabilityCommentPrefixField?.text?.trim()
            ?: DEFAULT_VULNERABILITY_COMMENT_PREFIX
        settings.state.vulnerabilityCommentMaxIds =
            (vulnerabilityCommentMaxIdsSpinner?.value as? Int)?.coerceAtLeast(0)
                ?: DEFAULT_VULNERABILITY_COMMENT_MAX_IDS
        settings.state.ossIndexEnabled = ossIndexEnabled
        settings.state.privateGroupIds = privateGroupIdsField?.text?.trim().orEmpty()
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
        toolWindowBadgeModeComboBox?.selectedItem = settings.state.toolWindowBadgeMode
        syncMavenAfterUpdateCheckBox?.isSelected = settings.state.syncMavenAfterUpdate
        stopAfterCentralSuccessCheckBox?.isSelected = settings.state.stopAfterCentralSuccess
        autoSearchVersionsCheckBox?.isSelected = settings.state.autoSearchVersions
        versionAutoSelectionModeComboBox?.selectedItem = settings.state.versionAutoSelectionMode
        offerAllVersionsCheckBox?.isSelected = settings.state.offerAllVersions
        confirmVersionResetCheckBox?.isSelected = settings.state.confirmVersionReset
        hideUnstableVersionsCheckBox?.isSelected = settings.state.hideUnstableVersions
        hiddenVersionQualifiersField?.text = settings.state.hiddenVersionQualifiers
        checkTransitiveDependenciesCheckBox?.isSelected = settings.state.checkTransitiveDependencies
        vulnerabilityCommentModeComboBox?.selectedItem = settings.state.vulnerabilityCommentMode
        vulnerabilityCommentPrefixField?.text = settings.state.vulnerabilityCommentPrefix
        vulnerabilityCommentMaxIdsSpinner?.value = settings.state.vulnerabilityCommentMaxIds
        ossIndexEnabledCheckBox?.isSelected = settings.state.ossIndexEnabled
        privateGroupIdsField?.text = settings.state.privateGroupIds
        credentialsLoaded = false
        storedToken = ""
        ossIndexTokenField?.text = ""
        updateHiddenQualifierControlsEnabled(settings.state.hideUnstableVersions)
        updateOssIndexControlsEnabled(settings.state.ossIndexEnabled)
        updateVulnerabilityCommentControlsEnabled(settings.state.vulnerabilityCommentMode)
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
        toolWindowBadgeModeComboBox = null
        syncMavenAfterUpdateCheckBox = null
        stopAfterCentralSuccessCheckBox = null
        autoSearchVersionsCheckBox = null
        versionAutoSelectionModeComboBox = null
        offerAllVersionsCheckBox = null
        confirmVersionResetCheckBox = null
        hideUnstableVersionsCheckBox = null
        hiddenVersionQualifiersLabel = null
        hiddenVersionQualifiersField = null
        checkTransitiveDependenciesCheckBox = null
        vulnerabilityCommentModeComboBox = null
        vulnerabilityCommentPrefixLabel = null
        vulnerabilityCommentPrefixField = null
        vulnerabilityCommentMaxIdsLabel = null
        vulnerabilityCommentMaxIdsSpinner = null
        ossIndexEnabledCheckBox = null
        ossIndexTokenLabel = null
        ossIndexTokenField = null
        privateGroupIdsField = null
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
     * Aktiviert oder deaktiviert die Eingabefelder für den Schwachstellen-Kommentar abhängig vom Kommentarmodus.
     *
     * Der Präfixtext ist nur bearbeitbar, wenn überhaupt ein Kommentar geschrieben wird; die Höchstzahl der
     * Kennungen nur, wenn der Modus Kennungen auflistet.
     *
     * @param mode Der aktuell in der Combobox ausgewählte Kommentarmodus.
     */
    private fun updateVulnerabilityCommentControlsEnabled(mode: VulnerabilityCommentMode?) {
        val commentEnabled = mode != null && mode != VulnerabilityCommentMode.NONE
        val idsEnabled = commentEnabled && mode != VulnerabilityCommentMode.TEXT_ONLY
        vulnerabilityCommentPrefixLabel?.isEnabled = commentEnabled
        vulnerabilityCommentPrefixField?.isEnabled = commentEnabled
        vulnerabilityCommentMaxIdsLabel?.isEnabled = idsEnabled
        vulnerabilityCommentMaxIdsSpinner?.isEnabled = idsEnabled
    }

    /**
     * Hilfsmethode zum Auslesen des aktuellen Passworts/Tokens aus dem Passwortfeld.
     */
    private fun currentToken(): String = ossIndexTokenField?.password?.concatToString().orEmpty()

    private companion object {
        val LOG = Logger.getInstance(MavenUpConfigurable::class.java)
    }
}
