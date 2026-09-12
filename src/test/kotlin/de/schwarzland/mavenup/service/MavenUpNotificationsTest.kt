package de.schwarzland.mavenup.service

import com.intellij.notification.Notification
import com.intellij.notification.Notifications
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Unit-Tests für [MavenUpNotifications].
 *
 * Fängt die über [Notifications.TOPIC] versendeten Benachrichtigungen mit einem projektgebundenen
 * Message-Bus-Listener ab, um Inhalt und Unterdrückung ohne Fund zu prüfen.
 */
class MavenUpNotificationsTest : BasePlatformTestCase() {

    private fun collectNotifications(action: () -> Unit): List<Notification> {
        val received = mutableListOf<Notification>()
        val connection = project.messageBus.connect(testRootDisposable)
        connection.subscribe(Notifications.TOPIC, object : Notifications {
            override fun notify(notification: Notification) {
                received.add(notification)
            }
        })
        action()
        return received
    }

    /**
     * Test: Werden Versionen für mindestens eine Abhängigkeit gefunden, wird eine Benachrichtigung
     * mit der erwarteten Anzahl an Versionen und Abhängigkeiten versendet.
     */
    fun testNotifyVersionsFoundSendsNotificationWithCounts() {
        val notifications = collectNotifications {
            MavenUpNotifications.notifyVersionsFound(project, versionCount = 5, dependencyCount = 3)
        }

        assertSize(1, notifications)
        assertTrue(notifications[0].content.contains("5"))
        assertTrue(notifications[0].content.contains("3"))
    }

    /**
     * Test: Ohne Abhängigkeit mit gefundener Version wird keine Benachrichtigung versendet.
     */
    fun testNotifyVersionsFoundSuppressedWhenNoDependencyHasVersions() {
        val notifications = collectNotifications {
            MavenUpNotifications.notifyVersionsFound(project, versionCount = 0, dependencyCount = 0)
        }

        assertEmpty(notifications)
    }

    /**
     * Test: Werden direkte und indirekte Schwachstellen gefunden, wird eine Benachrichtigung mit
     * beiden Zählwerten und der Gesamtzahl versendet.
     */
    fun testNotifyVulnerabilitiesFoundSendsNotificationWithCounts() {
        val notifications = collectNotifications {
            MavenUpNotifications.notifyVulnerabilitiesFound(project, directCount = 2, indirectCount = 4)
        }

        assertSize(1, notifications)
        assertTrue(notifications[0].content.contains("2"))
        assertTrue(notifications[0].content.contains("4"))
        assertTrue(notifications[0].content.contains("6"))
    }

    /**
     * Test: Ohne jede Schwachstelle wird keine Benachrichtigung versendet.
     */
    fun testNotifyVulnerabilitiesFoundSuppressedWhenNoneFound() {
        val notifications = collectNotifications {
            MavenUpNotifications.notifyVulnerabilitiesFound(project, directCount = 0, indirectCount = 0)
        }

        assertEmpty(notifications)
    }
}
