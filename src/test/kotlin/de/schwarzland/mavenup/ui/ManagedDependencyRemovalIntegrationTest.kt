package de.schwarzland.mavenup.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.UIUtil
import de.schwarzland.mavenup.model.ConsumerDependencyInfo
import de.schwarzland.mavenup.model.ManagedDependencyRemovalRecommendation
import de.schwarzland.mavenup.service.AutomaticVersionSearchState
import de.schwarzland.mavenup.service.VersionSearchResult
import javax.swing.RowSorter
import javax.swing.SortOrder

/**
 * Integrationstests für die Erkennung und Anwendung von Bereinigungsempfehlungen im Tool-Window.
 */
class ManagedDependencyRemovalIntegrationTest : BasePlatformTestCase() {

    /** Prüft Zielversionsauswahl und Entfernungsvormerkung ohne POM-Änderung. */
    fun testApplyRecommendationsUpdatesToolWindowState() {
        val factory = MavenUpWindowFactory()
        val toolWindow = factory.MyToolWindow(project)

        val rec = ManagedDependencyRemovalRecommendation(
            managedGroupId = "com.fasterxml.jackson.core",
            managedArtifactId = "jackson-databind",
            managedCurrentVersion = "2.14.0",
            triggerGroupId = "org.springframework.boot",
            triggerArtifactId = "spring-boot-starter-parent",
            triggerType = "parent",
            triggerCurrentVersion = "3.1.0",
            triggerTargetVersion = "3.2.0",
            transitiveVersionInTarget = "2.15.2",
            consumers = listOf(
                ConsumerDependencyInfo(
                    groupId = "org.springframework.boot",
                    artifactId = "spring-boot-starter-web",
                    resolvedVersion = "2.15.2",
                    pathDescription = "spring-boot-starter-web -> jackson-databind:2.15.2"
                )
            ),
            isSatisfiedAcrossAllConsumers = true
        )

        // Apply recommendation
        toolWindow.applyManagedDependencyRemovalRecommendations(listOf(rec))

        // Check that trigger version was selected
        val triggerKey = "org.springframework.boot:spring-boot-starter-parent"
        assertEquals("3.2.0", toolWindow.selectedVersions[triggerKey])

        // Check that managed dependency was marked for removal
        val managedKey = "com.fasterxml.jackson.core:jackson-databind"
        val managedType = MyMessageBundle.message("toolwindow.MyToolWindow.type.managedDependency")
        assertTrue(toolWindow.isManagedEntryMarkedForRemoval(managedKey, managedType))

        val removalUpdate = toolWindow.pendingManagedRemovalUpdates["$managedType|$managedKey"]
        assertNotNull(removalUpdate)
        assertTrue(removalUpdate!!.removeFromPom)
        assertEquals("2.14.0", removalUpdate.oldVersion)
        assertTrue(toolWindow.hasSelectedUpdates())
    }

    /** Prüft den optionalen Filterwechsel einschließlich vorher vorgemerkter Änderungen und Sortierung. */
    fun testApplyRecommendationsCanShowAllPendingChanges() {
        val window = filteredWindow()
        val table = UIUtil.findComponentOfType(window.getContent(), JBTable::class.java)!!
        val sortKeys = listOf(RowSorter.SortKey(ARTIFACT_ID_COLUMN, SortOrder.DESCENDING))
        table.rowSorter.sortKeys = sortKeys
        window.selectedVersions["com.example:previous"] = "2.0.0"

        window.applyManagedDependencyRemovalRecommendations(listOf(filterRecommendation()), true)

        assertEquals("", window.searchTextField.text)
        assertEquals(PendingChangesFilter.ALL_CHANGES, window.changesFilterComboBox.selectedItem)
        assertTrue(window.changesFilterComboBox.isEnabled)
        assertEquals(TriStateFilter.ALL, window.updatesFilterComboBox.selectedItem)
        assertEquals(TriStateFilter.ALL, window.versionSourceFilterComboBox.selectedItem)
        assertEquals(VulnerabilityFilter.ALL, window.vulnerabilitiesFilterComboBox.selectedItem)
        assertEquals(0, window.typeFilterComboBox.selectedIndex)
        assertEquals(sortKeys, table.rowSorter.sortKeys)
        assertEquals(3, table.rowCount)
        val visible = (0 until table.rowCount).map { table.getValueAt(it, ARTIFACT_ID_COLUMN) }.toSet()
        assertEquals(setOf("trigger", "managed", "previous"), visible)
        assertEquals("2.0.0", window.selectedVersions["com.example:previous"])
        assertTrue(window.isManagedEntryMarkedForRemoval(
            "com.example:managed", MyMessageBundle.message("toolwindow.MyToolWindow.type.managedDependency")
        ))
    }

    /** Prüft, dass ohne Option und bei leerer Empfehlungsliste alle bestehenden Filter erhalten bleiben. */
    fun testApplyRecommendationsPreservesFiltersByDefaultAndForEmptyInput() {
        for (emptyInput in listOf(false, true)) {
            val window = filteredWindow()
            window.applyManagedDependencyRemovalRecommendations(
                if (emptyInput) emptyList() else listOf(filterRecommendation()),
                showAllPendingChanges = emptyInput
            )
            assertEquals("unchanged", window.searchTextField.text)
            assertEquals(PendingChangesFilter.UNCHANGED, window.changesFilterComboBox.selectedItem)
            assertEquals(TriStateFilter.YES, window.updatesFilterComboBox.selectedItem)
            assertEquals(TriStateFilter.YES, window.versionSourceFilterComboBox.selectedItem)
            assertEquals(VulnerabilityFilter.VULNERABLE, window.vulnerabilitiesFilterComboBox.selectedItem)
            assertEquals("dependency", window.typeFilterComboBox.selectedItem)
        }
    }

    /** Erstellt eine Haupttabelle mit allen Filtern, einem Managed-Eintrag und unveränderten Vergleichszeilen. */
    private fun filteredWindow(): MavenUpWindowFactory.MyToolWindow {
        val window = MavenUpWindowFactory().MyToolWindow(project)
        val managedType = MyMessageBundle.message("toolwindow.MyToolWindow.type.managedDependency")
        val rows = listOf(
            RefreshRow("com.example", "trigger", "", "dependency", "1.0.0"),
            RefreshRow("com.example", "managed", "", managedType, "1.0.0"),
            RefreshRow("com.example", "previous", "", "dependency", "1.0.0"),
            RefreshRow("com.example", "unchanged", "", "dependency", "1.0.0")
        )
        val versions = rows.associate { it.key to listOf("2.0.0", "1.0.0") }
        window.applyAutomaticVersionSearchState(AutomaticVersionSearchState(
            RefreshSnapshot(rows, emptyMap()),
            VersionSearchResult(versions, versions, emptyMap()),
            null
        ))
        window.searchTextField.text = "unchanged"
        window.typeFilterComboBox.selectedItem = "dependency"
        window.updatesFilterComboBox.selectedItem = TriStateFilter.YES
        window.versionSourceFilterComboBox.selectedItem = TriStateFilter.YES
        window.vulnerabilitiesFilterComboBox.selectedItem = VulnerabilityFilter.VULNERABLE
        window.changesFilterComboBox.selectedItem = PendingChangesFilter.UNCHANGED
        return window
    }

    /** Erstellt eine Empfehlung zur Zielversionsanpassung und Entfernung des Test-Managed-Eintrags. */
    private fun filterRecommendation() = ManagedDependencyRemovalRecommendation(
        managedGroupId = "com.example",
        managedArtifactId = "managed",
        managedCurrentVersion = "1.0.0",
        triggerGroupId = "com.example",
        triggerArtifactId = "trigger",
        triggerType = "dependency",
        triggerCurrentVersion = "1.0.0",
        triggerTargetVersion = "2.0.0",
        transitiveVersionInTarget = "1.0.0",
        consumers = emptyList(),
        isSatisfiedAcrossAllConsumers = true
    )

    /** Prüft den Cleanup-Eintrag im Kontextmenü verwalteter Dependencies. */
    fun testContextMenuContainsCheckRemovalAction() {
        val factory = MavenUpWindowFactory()
        val toolWindow = factory.MyToolWindow(project)
        val target = DependencyContextMenuTarget(
            column = 1,
            groupId = "com.fasterxml.jackson.core",
            artifactId = "jackson-databind",
            property = "",
            type = MyMessageBundle.message("toolwindow.MyToolWindow.type.managedDependency"),
            currentVersion = "2.14.0",
            vulnerabilityCell = null
        )

        val group = toolWindow.buildContextMenuGroup(target)
        val actions = group.getChildren(null)
        val hasCheckAction = actions.any {
            it.templatePresentation.text == MyMessageBundle.message("toolwindow.MyToolWindow.checkManagedRemoval.contextMenu")
        }
        assertTrue(hasCheckAction)
    }
}
