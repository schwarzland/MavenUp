package de.schwarzland.mavenup.service

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import de.schwarzland.mavenup.ui.MyMessageBundle

/**
 * Zeigt Benachrichtigungen des Plugins über die registrierte Notification-Gruppe **MavenUp** an.
 *
 * Kapselt den Zugriff auf [NotificationGroupManager], damit die Benachrichtigungslogik unabhängig
 * von der jeweils auslösenden UI-Komponente getestet und wiederverwendet werden kann.
 */
internal object MavenUpNotifications {

    private const val NOTIFICATION_GROUP_ID = "MavenUp"

    /**
     * Meldet das Ergebnis einer Versionssuche: die Anzahl gefundener Versionen für die
     * betroffenen Abhängigkeiten.
     *
     * @param project Das Projekt, in dem die Benachrichtigung angezeigt wird.
     * @param versionCount Gesamtzahl der über alle betroffenen Abhängigkeiten gefundenen Versionen.
     * @param dependencyCount Anzahl der Abhängigkeiten, für die mindestens eine Version gefunden wurde.
     */
    fun notifyVersionsFound(project: Project, versionCount: Int, dependencyCount: Int) {
        if (dependencyCount <= 0) return
        notify(
            project,
            MyMessageBundle.message("notification.checkUpdates.result", versionCount, dependencyCount)
        )
    }

    /**
     * Meldet das Ergebnis eines Vulnerability-Scans: Anzahl direkter und indirekter (transitiver)
     * Sicherheitswarnungen sowie die Gesamtzahl der betroffenen Abhängigkeiten.
     *
     * @param project Das Projekt, in dem die Benachrichtigung angezeigt wird.
     * @param directCount Anzahl direkt deklarierter Abhängigkeiten mit mindestens einer Warnung.
     * @param indirectCount Anzahl transitiver Abhängigkeiten mit mindestens einer Warnung.
     */
    fun notifyVulnerabilitiesFound(project: Project, directCount: Int, indirectCount: Int) {
        val dependencyCount = directCount + indirectCount
        if (dependencyCount <= 0) return
        notify(
            project,
            MyMessageBundle.message(
                "notification.checkVulnerabilities.result",
                directCount,
                indirectCount,
                dependencyCount
            )
        )
    }

    private fun notify(project: Project, content: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP_ID)
            .createNotification(content, NotificationType.INFORMATION)
            .notify(project)
    }
}
