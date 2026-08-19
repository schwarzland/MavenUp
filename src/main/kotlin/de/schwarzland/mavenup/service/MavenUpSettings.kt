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
     * @property selectLatestVersion Bestimmt, ob standardmäßig die neueste verfügbare Version ausgewählt werden soll.
     * @property hideUnstableVersions Gibt an, ob instabile Versionen in der Auswahl ausgeblendet werden sollen.
     * @property hiddenVersionQualifiers Kommagetrennte Liste von Qualifizierern (z.B. "beta", "alpha"), die als instabil gelten.
     * @property ossIndexEnabled Gibt an, ob die Prüfung auf Sicherheitslücken via Sonatype OSS Index aktiviert ist.
     * @property checkTransitiveDependencies Bestimmt, ob auch transitive Abhängigkeiten auf Updates geprüft werden sollen.
     * @property repositoryBrowser Der Maven-Repository-Browser, der für Links auf Abhängigkeits-Versionsseiten verwendet wird.
     * @property toolbarShowText Bestimmt, ob die Aktionsleisten Text-Buttons statt reiner Icon-Buttons anzeigen.
     * @property syncMavenAfterUpdate Bestimmt, ob nach dem Schreiben der `pom.xml` automatisch der Maven-Sync der IDE ausgelöst wird.
     * @property stopAfterCentralSuccess Bestimmt, ob nach einer erfolgreichen Abfrage von Maven Central keine weiteren privaten Repositories abgefragt werden.
     * @property selectLatestMinorVersion Bestimmt, ob bei aktivierter Auto-Auswahl bevorzugt die höchste Version innerhalb derselben Major-Version gewählt wird.
     */
    data class State(
        var jumpOnSingleClick: Boolean = false,
        var selectLatestVersion: Boolean = false,
        var hideUnstableVersions: Boolean = false,
        var hiddenVersionQualifiers: String = "rc,beta,alpha,ea,milestone,preview,cr,nightly,snapshot",
        var ossIndexEnabled: Boolean = false,
        var checkTransitiveDependencies: Boolean = true,
        var repositoryBrowser: MavenRepositoryBrowser = MavenRepositoryBrowser.MVN_REPOSITORY,
        var toolbarShowText: Boolean = true,
        var syncMavenAfterUpdate: Boolean = true,
        var stopAfterCentralSuccess: Boolean = true,
        var selectLatestMinorVersion: Boolean = false
    ) {
        /**
         * Sekundärer Konstruktor zur Binärkompatibilität mit bereits kompiliertem Code,
         * der den früheren Zustand ohne `stopAfterCentralSuccess` und `selectLatestMinorVersion` erwartet.
         */
        @Suppress("LongParameterList")
        constructor(
            jumpOnSingleClick: Boolean,
            selectLatestVersion: Boolean,
            hideUnstableVersions: Boolean,
            hiddenVersionQualifiers: String,
            ossIndexEnabled: Boolean,
            checkTransitiveDependencies: Boolean,
            repositoryBrowser: MavenRepositoryBrowser,
            toolbarShowText: Boolean,
            syncMavenAfterUpdate: Boolean
        ) : this(
            jumpOnSingleClick = jumpOnSingleClick,
            selectLatestVersion = selectLatestVersion,
            hideUnstableVersions = hideUnstableVersions,
            hiddenVersionQualifiers = hiddenVersionQualifiers,
            ossIndexEnabled = ossIndexEnabled,
            checkTransitiveDependencies = checkTransitiveDependencies,
            repositoryBrowser = repositoryBrowser,
            toolbarShowText = toolbarShowText,
            syncMavenAfterUpdate = syncMavenAfterUpdate,
            stopAfterCentralSuccess = true,
            selectLatestMinorVersion = false
        )
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
        myState = state
    }

    companion object {
        /**
         * Liefert die anwendungsweite Instanz dieses Services.
         */
        fun getInstance(): MavenUpSettings =
            ApplicationManager.getApplication().getService(MavenUpSettings::class.java)
    }
}
