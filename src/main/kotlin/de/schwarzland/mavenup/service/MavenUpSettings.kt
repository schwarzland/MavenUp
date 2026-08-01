package de.schwarzland.mavenup.service

import com.intellij.openapi.components.*
import com.intellij.openapi.project.Project

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
 * Diese Klasse verwaltet die persistenten Einstellungen für das MavenUp-Plugin auf Projektebene.
 *
 * Sie nutzt das IntelliJ-Framework zur Speicherung des Zustands in der Datei `mavenup_settings.xml`.
 * Die Einstellungen umfassen UI-Verhalten, Filterregeln für Versionen und Konfigurationen für den OSS Index.
 *
 * Die Klasse wird benötigt, um Benutzereinstellungen projektübergreifend zu speichern und bereitzustellen.
 */
@Service(Service.Level.PROJECT)
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
     * @property ossIndexUsername Der Benutzername (E-Mail) für die Authentifizierung beim OSS Index.
     * @property checkTransitiveDependencies Bestimmt, ob auch transitive Abhängigkeiten auf Updates geprüft werden sollen.
     * @property repositoryBrowser Der Maven-Repository-Browser, der für Links auf Abhängigkeits-Versionsseiten verwendet wird.
     */
    data class State(
        var jumpOnSingleClick: Boolean = false,
        var selectLatestVersion: Boolean = true,
        var hideUnstableVersions: Boolean = false,
        var hiddenVersionQualifiers: String = "rc,beta,alpha,ea,milestone,preview,cr,nightly,snapshot",
        var ossIndexEnabled: Boolean = false,
        var ossIndexUsername: String = "",
        var checkTransitiveDependencies: Boolean = true,
        var repositoryBrowser: MavenRepositoryBrowser = MavenRepositoryBrowser.MVN_REPOSITORY
    )

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
         * Liefert die Instanz dieses Services für das angegebene Projekt.
         */
        fun getInstance(project: Project): MavenUpSettings = project.getService(MavenUpSettings::class.java)
    }
}
