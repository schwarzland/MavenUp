package de.schwarzland

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class MavenUpWindowFactoryTest : BasePlatformTestCase() {

    fun testShouldBeAvailableOnlyWhenMavenProjectsExist() {
        val factory = MavenUpWindowFactory()
        
        // Standardmäßig sollten keine Maven-Projekte in einem leeren Testprojekt vorhanden sein
        assertFalse("ToolWindow sollte ohne Maven-Projekte nicht verfügbar sein", factory.shouldBeAvailable(project))
    }

    fun testToolWindowContentCreation() {
        val factory = MavenUpWindowFactory()
        val toolWindow = com.intellij.openapi.wm.ToolWindowManager.getInstance(project).registerToolWindow("TestWindow", true, com.intellij.openapi.wm.ToolWindowAnchor.BOTTOM)
        
        try {
            factory.createToolWindowContent(project, toolWindow)
            assertTrue("Content sollte zum ToolWindow hinzugefügt worden sein", toolWindow.contentManager.contentCount > 0)
        } finally {
            com.intellij.openapi.wm.ToolWindowManager.getInstance(project).unregisterToolWindow("TestWindow")
        }
    }
}
