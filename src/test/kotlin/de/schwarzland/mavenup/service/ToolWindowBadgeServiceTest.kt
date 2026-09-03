package de.schwarzland.mavenup.service

import de.schwarzland.mavenup.model.MavenUpBadgeState
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Unit-Tests für [ToolWindowBadgeService].
 *
 * Prüft die Zuordnung von Badge-Zustand zu Icon sowie die Robustheit von [ToolWindowBadgeService.update]
 * und [ToolWindowBadgeService.reset], wenn das Tool-Window im Testprojekt nicht registriert ist.
 */
class ToolWindowBadgeServiceTest : BasePlatformTestCase() {

    /**
     * Test: Ohne Handlungsbedarf wird das unveränderte Basis-Icon geliefert.
     */
    fun testNoneStateUsesBaseIcon() {
        val service = ToolWindowBadgeService.getInstance(project)
        val base = service.badgeIcon(MavenUpBadgeState.NONE)

        assertNotNull("Basis-Icon sollte geladen werden können", base)
        assertSame("NONE sollte stets dasselbe Basis-Icon liefern", base, service.badgeIcon(MavenUpBadgeState.NONE))
    }

    /**
     * Test: Jeder Badge-Zustand liefert ein nutzbares, stabil zwischengespeichertes Icon.
     *
     * Ob tatsächlich ein farbiger Punkt aufgemalt wird, entscheidet der `IconManager` der Plattform;
     * im Headless-Testmodus liefert dieser das unveränderte Basis-Icon zurück. Getestet wird daher
     * die Abbildung selbst und nicht das Rendering der IDE.
     */
    fun testEveryBadgeStateProducesStableIcon() {
        val service = ToolWindowBadgeService.getInstance(project)

        MavenUpBadgeState.entries.forEach { state ->
            val icon = service.badgeIcon(state)
            assertNotNull("Badge-Icon sollte für $state erzeugt werden", icon)
            assertSame(
                "Wiederholte Abfrage sollte für $state dasselbe Icon liefern",
                icon,
                service.badgeIcon(state)
            )
        }
    }

    /**
     * Test: Das Badge-Icon behält die Größe des Basis-Icons bei, damit der Stripe-Button nicht springt.
     */    fun testBadgeIconKeepsBaseIconSize() {
        val service = ToolWindowBadgeService.getInstance(project)
        val base = service.badgeIcon(MavenUpBadgeState.NONE)
        val badged = service.badgeIcon(MavenUpBadgeState.SEVERE_VULNERABILITIES)

        assertEquals("Breite sollte unverändert bleiben", base.iconWidth, badged.iconWidth)
        assertEquals("Höhe sollte unverändert bleiben", base.iconHeight, badged.iconHeight)
    }

    /**
     * Test: Ohne registriertes Tool-Window dürfen update() und reset() keine Exception werfen.
     */
    fun testUpdateAndResetDoNotThrowWithoutToolWindow() {
        val service = ToolWindowBadgeService.getInstance(project)

        MavenUpBadgeState.entries.forEach { service.update(it) }
        service.reset()
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        assertFalse("Projekt sollte nach den Badge-Updates nicht disposed sein", project.isDisposed)
    }
}
