package de.schwarzland.mavenup.ui

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.Project
import com.intellij.ui.SimpleListCellRenderer
import de.schwarzland.mavenup.service.MAVEN_UP_SETTINGS_TOPIC
import de.schwarzland.mavenup.service.MavenUpSettings
import de.schwarzland.mavenup.service.VersionMetadataCache
import de.schwarzland.mavenup.service.VulnerabilityResultCache
import javax.swing.JList

/**
 * Gemeinsame Basis aller MavenUp-Einstellungsseiten.
 *
 * Die Seiten sind als Baum in den IDE-Einstellungen registriert: Die Wurzelseite [MavenUpConfigurable]
 * enthält nur Darstellung und Verhalten, die fachlichen Themen liegen in den Unterseiten
 * [MavenUpVersionsConfigurable], [MavenUpVulnerabilityConfigurable] und [MavenUpPomChangesConfigurable].
 * Diese Aufteilung folgt den IntelliJ-Platform-UI-Guidelines, nach denen eine Einstellungsseite ohne
 * Scrollen lesbar bleiben soll.
 *
 * Die Klasse bündelt das, was alle Seiten gemeinsam haben: den Zugriff auf den Einstellungszustand,
 * die Benachrichtigung offener UI-Komponenten über den Message-Bus nach dem Speichern sowie die
 * Erweiterungspunkte [beforeApply] und [afterApply] für seitenspezifische Sonderbehandlung.
 *
 * @property project Das Projekt, dessen Message-Bus nach dem Speichern benachrichtigt wird.
 * @param displayName Der im Einstellungsbaum angezeigte Name der Seite.
 */
abstract class MavenUpSettingsPage internal constructor(
    protected val project: Project,
    displayName: String
) : BoundConfigurable(displayName) {

    /**
     * Liefert den aktuellen Einstellungszustand.
     *
     * Der Zustand wird bewusst bei jedem Zugriff neu ermittelt, damit die Bindungen der Seite auch dann
     * korrekt arbeiten, wenn der Zustand zwischenzeitlich neu geladen wurde.
     */
    protected val state: MavenUpSettings.State
        get() = MavenUpSettings.getInstance().state

    /**
     * Übernimmt die Eingaben der Seite in die Einstellungen und benachrichtigt anschließend
     * alle Zuhörer über den Message-Bus.
     *
     * Leert den Versionscache nur, wenn sich die konfigurierten privaten GroupId-Präfixe oder die
     * Central-first-Strategie ändern, da diese Einstellungen beeinflussen, welche Repositories
     * Versionsdaten liefern. Leert den Vulnerability-Cache nur, wenn sich die Datenquellen-Konfiguration
     * (Sonatype OSS Index aktiviert/deaktiviert) ändert.
     */
    override fun apply() {
        beforeApply()
        val previousPrivateGroupIds = state.privateGroupIds
        val previousStopAfterCentralSuccess = state.stopAfterCentralSuccess
        val previousOssIndexEnabled = state.ossIndexEnabled
        super.apply()
        afterApply()
        if (state.privateGroupIds != previousPrivateGroupIds ||
            state.stopAfterCentralSuccess != previousStopAfterCentralSuccess
        ) {
            VersionMetadataCache.getInstance().clear()
        }
        if (state.ossIndexEnabled != previousOssIndexEnabled) {
            VulnerabilityResultCache.getInstance().clear()
        }
        project.messageBus.syncPublisher(MAVEN_UP_SETTINGS_TOPIC).run()
    }

    /**
     * Wird vor dem Übernehmen der gebundenen Eingaben aufgerufen.
     *
     * Unterklassen können hier Pflichtangaben prüfen und den Speichervorgang durch eine
     * [com.intellij.openapi.options.ConfigurationException] abbrechen, bevor Werte geschrieben werden.
     */
    protected open fun beforeApply() = Unit

    /**
     * Wird nach dem Übernehmen der gebundenen Eingaben und vor der Benachrichtigung aufgerufen.
     *
     * Unterklassen können hier Werte speichern, die nicht über die UI-DSL gebunden sind.
     */
    protected open fun afterApply() = Unit
}

/**
 * Erzeugt einen Listen-Renderer für Auswahlfelder, der den Anzeigetext eines Werts über [textOf]
 * bestimmt und für `null` einen leeren Text liefert.
 *
 * @param textOf Liefert den Anzeigetext für einen Wert.
 * @return Der wiederverwendbare Renderer für Auswahlfelder.
 */
internal fun <T : Any> settingsListCellRenderer(textOf: (T) -> String): SimpleListCellRenderer<T?> =
    object : SimpleListCellRenderer<T?>() {
        override fun customize(
            list: JList<out T?>,
            value: T?,
            index: Int,
            selected: Boolean,
            hasFocus: Boolean
        ) {
            text = value?.let(textOf).orEmpty()
        }
    }
