package de.schwarzland.mavenup.service

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.*
import com.intellij.util.messages.Topic

/**
 * Message-Bus-Topic, über das Einstellungsänderungen veröffentlicht werden.
 *
 * Wird verwendet, damit offene UI-Komponenten (z. B. das Tool-Window) unmittelbar auf
 * geänderte Einstellungen reagieren können, ohne neu geöffnet werden zu müssen. Der Listener
 * wird als [Runnable] modelliert; [Runnable.run] wird nach dem Speichern der Einstellungen aufgerufen.
 */
@JvmField
val MAVEN_UP_SETTINGS_TOPIC: Topic<Runnable> =
    Topic.create("MavenUp settings changed", Runnable::class.java)

/**
 * Repräsentiert einen öffentlichen Maven-Repository-Browser, der für die Anzeige von
 * Abhängigkeitsdetails im Standard-Browser genutzt werden kann.
 *
 * Jeder Wert kennt seinen Anzeigenamen sowie die URL-Vorlage für die Versionsseite.
 */
enum class MavenRepositoryBrowser(val displayName: String) {
    MVN_REPOSITORY("MVN Repository"),
    SONATYPE_CENTRAL("Sonatype Central");

    /** Erzeugt die direkte URL zur Versionsseite der angegebenen Komponente. */
    fun urlFor(groupId: String, artifactId: String, version: String): String = when (this) {
        MVN_REPOSITORY   -> "https://mvnrepository.com/artifact/$groupId/$artifactId/$version"
        SONATYPE_CENTRAL -> "https://central.sonatype.com/artifact/$groupId/$artifactId/$version"
    }
}

/**
 * Beschreibt die Strategie zur automatischen Vorauswahl einer Zielversion nach einem Update-Check.
 *
 * @property messageKey Schlüssel für den lokalisierten Anzeigetext in den Einstellungen.
 */
enum class VersionAutoSelectionMode(val messageKey: String) {
    /** Keine automatische Auswahl; die aktuelle Version bleibt vorausgewählt. */
    DISABLED("settings.versionAutoSelectionMode.disabled"),

    /** Wählt immer die höchste bekannte Version vor. */
    LATEST("settings.versionAutoSelectionMode.latest"),

    /** Wählt bevorzugt die höchste Version innerhalb derselben Major-Linie vor. */
    LATEST_MINOR("settings.versionAutoSelectionMode.latestMinor")
}

/**
 * Beschreibt, welche Kennungen der behobenen Sicherheitswarnungen in den erklärenden XML-Kommentar
 * eines neu angelegten, gepinnten `dependencyManagement`-Eintrags geschrieben werden.
 *
 * @property messageKey Schlüssel für den lokalisierten Anzeigetext in den Einstellungen.
 */
enum class VulnerabilityCommentMode(val messageKey: String) {
    /** Es wird kein Kommentar eingefügt. */
    NONE("settings.vulnerabilityCommentMode.none"),

    /** Es wird nur der konfigurierte Präfixtext ohne Kennungen eingefügt. */
    TEXT_ONLY("settings.vulnerabilityCommentMode.textOnly"),

    /** Es werden nur die primären Advisory-IDs (z. B. `GHSA-…`) aufgelistet. */
    ADVISORY_IDS("settings.vulnerabilityCommentMode.advisoryIds"),

    /** Es werden nur die Aliase (z. B. `CVE-…`) aufgelistet; ohne Alias dient die primäre ID als Ersatz. */
    ALIASES("settings.vulnerabilityCommentMode.aliases"),

    /** Es werden alle bekannten Kennungen (primäre IDs und Aliase) aufgelistet. */
    ALL_IDS("settings.vulnerabilityCommentMode.allIds")
}

/**
 * Beschreibt, welche Zustände als Badge (kleiner farbiger Punkt) auf dem Stripe-Icon des
 * MavenUp-Tool-Windows signalisiert werden.
 *
 * @property messageKey Schlüssel für den lokalisierten Anzeigetext in den Einstellungen.
 */
enum class ToolWindowBadgeMode(val messageKey: String) {
    /** Es wird nie ein Badge angezeigt. */
    OFF("settings.toolWindowBadgeMode.off"),

    /** Es wird nur bei bekannten Sicherheitslücken ein Badge angezeigt. */
    VULNERABILITIES("settings.toolWindowBadgeMode.vulnerabilities"),

    /** Es wird zusätzlich ein Badge angezeigt, wenn neue Versionen verfügbar sind. */
    VULNERABILITIES_AND_UPDATES("settings.toolWindowBadgeMode.vulnerabilitiesAndUpdates")
}

/** Standardpräfix des erklärenden XML-Kommentars vor den aufgelisteten Kennungen. */
const val DEFAULT_VULNERABILITY_COMMENT_PREFIX = "Pinned by MavenUp to fix:"

/** Standardanzahl der höchstens aufgelisteten Kennungen; `0` bedeutet „unbegrenzt". */
const val DEFAULT_VULNERABILITY_COMMENT_MAX_IDS = 3

/**
 * Diese Klasse verwaltet die persistenten Einstellungen für das MavenUp-Plugin global auf Anwendungsebene.
 *
 * Sie nutzt das IntelliJ-Framework zur Speicherung des Zustands in der Datei `mavenup_settings.xml`.
 * Die Einstellungen umfassen UI-Verhalten, Filterregeln für Versionen und Konfigurationen für den OSS Index.
 *
 * Die Klasse wird benötigt, um Benutzereinstellungen anwendungsweit (für alle Projekte) zu speichern und bereitzustellen.
 */
@Service(Service.Level.APP)
@State(name = "MavenUpSettings", storages = [Storage("mavenup_settings.xml")])
class MavenUpSettings : PersistentStateComponent<MavenUpSettings.State> {
    /**
     * Repräsentiert den Zustand der Plugin-Einstellungen.
     *
     * @property jumpOnSingleClick Bestimmt, ob ein einfacher Klick ausreicht, um zur Abhängigkeit im Code zu springen.
     * @property versionAutoSelectionMode Bestimmt die Vorauswahl-Strategie für die Spalte "New Version" nach einem Update-Check.
     * @property hideUnstableVersions Gibt an, ob instabile Versionen in der Auswahl ausgeblendet werden sollen.
     * @property hiddenVersionQualifiers Kommagetrennte Liste von Qualifizierern (z.B. "beta", "alpha"), die als instabil gelten.
     * @property ossIndexEnabled Gibt an, ob die Prüfung auf Sicherheitslücken via Sonatype OSS Index aktiviert ist.
     * @property checkTransitiveDependencies Bestimmt, ob auch transitive Abhängigkeiten auf Updates geprüft werden sollen.
     * @property repositoryBrowser Der Maven-Repository-Browser, der für Links auf Abhängigkeits-Versionsseiten verwendet wird.
     * @property toolbarShowText Bestimmt, ob die Aktionsleisten Text-Buttons statt reiner Icon-Buttons anzeigen.
     * @property syncMavenAfterUpdate Bestimmt, ob nach dem Schreiben der `pom.xml` automatisch der Maven-Sync der IDE ausgelöst wird.
     * @property stopAfterCentralSuccess Bestimmt, ob nach einer erfolgreichen Abfrage von Maven Central keine weiteren privaten Repositories abgefragt werden.
     * @property offerAllVersions Bestimmt, ob in der Versionsauswahl alle verfügbaren Versionen (inklusive älterer) angeboten werden, statt nur Versionen `>=` der aktuellen Version.
     * @property confirmVersionReset Bestimmt, ob vor dem Zurücksetzen aller Versionsauswahlen ein Bestätigungsdialog angezeigt wird.
     * @property autoSearchVersions Bestimmt, ob nach dem automatischen Neuladen der Projektdaten (Tool-Window-Start, Maven-Import/Resync) unmittelbar online nach neuen Versionen gesucht wird.
     * @property addVulnerabilityFixComment Legacy-Flag früherer Plugin-Versionen; wird ausschließlich zur Migration auf [vulnerabilityCommentMode] gelesen und daraus fortgeschrieben.
     * @property vulnerabilityCommentMode Bestimmt, welche Kennungen der behobenen Sicherheitswarnungen beim Anlegen eines gepinnten `dependencyManagement`-Eintrags als erklärender XML-Kommentar eingefügt werden.
     * @property vulnerabilityCommentPrefix Der Text, der im erklärenden XML-Kommentar vor den Kennungen steht.
     * @property vulnerabilityCommentMaxIds Die Höchstzahl der aufgelisteten Kennungen; darüber hinausgehende Kennungen werden durch einen „and more"-Hinweis ersetzt. `0` bedeutet „unbegrenzt".
     * @property toolWindowBadgeMode Bestimmt, welche Zustände als farbiger Badge auf dem Stripe-Icon des Tool-Windows signalisiert werden.
     */
    data class State(
        var jumpOnSingleClick: Boolean = false,
        var versionAutoSelectionMode: VersionAutoSelectionMode = VersionAutoSelectionMode.DISABLED,
        var hideUnstableVersions: Boolean = false,
        var hiddenVersionQualifiers: String = "rc,beta,alpha,ea,milestone,preview,cr,nightly,snapshot",
        var ossIndexEnabled: Boolean = false,
        var checkTransitiveDependencies: Boolean = true,
        var repositoryBrowser: MavenRepositoryBrowser = MavenRepositoryBrowser.MVN_REPOSITORY,
        var toolbarShowText: Boolean = true,
        var syncMavenAfterUpdate: Boolean = true,
        var stopAfterCentralSuccess: Boolean = true,
        var selectLatestVersion: Boolean = false,
        var selectLatestMinorVersion: Boolean = false,
        var offerAllVersions: Boolean = false,
        var confirmVersionReset: Boolean = true,
        var autoSearchVersions: Boolean = true,
        var addVulnerabilityFixComment: Boolean = true,
        var vulnerabilityCommentMode: VulnerabilityCommentMode = VulnerabilityCommentMode.ADVISORY_IDS,
        var vulnerabilityCommentPrefix: String = DEFAULT_VULNERABILITY_COMMENT_PREFIX,
        var vulnerabilityCommentMaxIds: Int = DEFAULT_VULNERABILITY_COMMENT_MAX_IDS,
        var toolWindowBadgeMode: ToolWindowBadgeMode = ToolWindowBadgeMode.VULNERABILITIES_AND_UPDATES
    ) {
        /**
         * Normalisiert den geladenen Zustand und migriert Legacy-Bool-Flags auf [versionAutoSelectionMode].
         *
         * Wenn ein Zustand aus älteren Plugin-Versionen geladen wird, kann [versionAutoSelectionMode]
         * noch auf dem Standardwert stehen und die alten Flags enthalten die tatsächliche Benutzerwahl.
         * In diesem Fall wird die Strategie aus den Legacy-Feldern abgeleitet.
         */
        fun migrateLegacyAutoSelection(): State {
            if (versionAutoSelectionMode == VersionAutoSelectionMode.DISABLED &&
                (selectLatestVersion || selectLatestMinorVersion)
            ) {
                versionAutoSelectionMode = if (selectLatestMinorVersion) {
                    VersionAutoSelectionMode.LATEST_MINOR
                } else {
                    VersionAutoSelectionMode.LATEST
                }
            }
            selectLatestVersion = versionAutoSelectionMode != VersionAutoSelectionMode.DISABLED
            selectLatestMinorVersion = versionAutoSelectionMode == VersionAutoSelectionMode.LATEST_MINOR
            return this
        }

        /**
         * Migriert das Legacy-Flag [addVulnerabilityFixComment] auf [vulnerabilityCommentMode].
         *
         * Wurde in einer älteren Plugin-Version der Kommentar abgeschaltet, steht [vulnerabilityCommentMode]
         * noch auf dem Standardwert; in diesem Fall wird der Modus auf [VulnerabilityCommentMode.NONE] gesetzt.
         * Anschließend wird das Legacy-Flag konsistent aus dem Modus fortgeschrieben.
         *
         * @return Der migrierte Zustand (dieselbe Instanz).
         */
        fun migrateLegacyVulnerabilityComment(): State {
            if (vulnerabilityCommentMode == VulnerabilityCommentMode.ADVISORY_IDS && !addVulnerabilityFixComment) {
                vulnerabilityCommentMode = VulnerabilityCommentMode.NONE
            }
            addVulnerabilityFixComment = vulnerabilityCommentMode != VulnerabilityCommentMode.NONE
            return this
        }
    }

    private var myState = State()

    /**
     * Gibt den aktuellen Zustand der Einstellungen zurück. Wird vom IntelliJ-Framework aufgerufen.
     */
    override fun getState(): State = myState

    /**
     * Lädt einen gespeicherten Zustand in die Komponente. Wird vom IntelliJ-Framework aufgerufen.
     */
    override fun loadState(state: State) {
        myState = state.migrateLegacyAutoSelection().migrateLegacyVulnerabilityComment()
    }

    companion object {
        /**
         * Liefert die anwendungsweite Instanz dieses Services.
         */
        fun getInstance(): MavenUpSettings =
            ApplicationManager.getApplication().getService(MavenUpSettings::class.java)
    }
}
