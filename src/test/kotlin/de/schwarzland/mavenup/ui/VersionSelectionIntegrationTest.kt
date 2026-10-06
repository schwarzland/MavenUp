package de.schwarzland.mavenup.ui

import com.intellij.openapi.ui.ComboBox
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.table.JBTable
import de.schwarzland.mavenup.model.VulnerabilityAdvisory
import de.schwarzland.mavenup.service.AutomaticVersionSearchState
import de.schwarzland.mavenup.service.MavenUpSettings
import de.schwarzland.mavenup.service.VersionAutoSelectionMode
import de.schwarzland.mavenup.service.VersionSearchResult
import java.awt.Component
import java.awt.Container
import javax.swing.JPanel

/** Prueft konsistente Anzeige, Editor-Verhalten und Update-Zustand beider Versionstabellen. */
class VersionSelectionIntegrationTest : BasePlatformTestCase() {
    private val key = "com.example:lib"
    private val current = "5.0.0-SNAPSHOT"
    private val versions = listOf("5.1.0-SNAPSHOT", "5.0.0")

    /** Erzeugt einen automatischen Suchzustand mit optionaler Zielauswahl und gemeinsamer Property. */
    private fun state(selected: String? = null): AutomaticVersionSearchState {
        val rows = listOf(
            RefreshRow("com.example", "lib", "shared.version", "dependency", current),
            RefreshRow("com.example", "other", "shared.version", "dependency", current)
        )
        val candidates = rows.associate { it.key to versions }
        return AutomaticVersionSearchState(
            RefreshSnapshot(rows, rows.associate { it.key to it.propertyName }),
            VersionSearchResult(candidates, candidates, selected?.let { mapOf(key to it) }.orEmpty()),
            null
        )
    }

    /** Findet die Haupttabelle im Tool-Window ohne Zugriff auf private Implementierungsdetails. */
    private fun findTable(component: Component): JBTable? {
        if (component is JBTable) return component
        return (component as? Container)?.components?.firstNotNullOfOrNull(::findTable)
    }

    /** Liest die ComboBox eines Versionspanels. */
    private fun combo(component: Component): ComboBox<*> =
        (component as JPanel).getComponent(1) as ComboBox<*>

    /** Eine abwesende aktuelle Version erzeugt weder beim Rendern noch beim Editorabschluss ein Update. */
    fun testMainEditorKeepsMissingCurrentVersionWithoutSynchronizingProperty() {
        val window = MavenUpWindowFactory().MyToolWindow(project)
        window.applyAutomaticVersionSearchState(state())
        val table = requireNotNull(findTable(window.getContent()))
        val renderer = table.getCellRenderer(0, NEW_VERSION_COLUMN)
        val rendered = renderer.getTableCellRendererComponent(
            table, table.getValueAt(0, NEW_VERSION_COLUMN), false, false, 0, NEW_VERSION_COLUMN
        )
        assertEquals(current, combo(rendered).selectedItem)
        assertTrue(table.editCellAt(0, NEW_VERSION_COLUMN))
        assertEquals(current, combo(table.editorComponent).selectedItem)
        assertTrue(table.cellEditor.stopCellEditing())
        assertTrue(window.selectedVersions.isEmpty())
        assertFalse(window.hasSelectedUpdates())
        assertTrue(window.collectSelectedUpdates().isEmpty())
    }

    /** Nur eine explizite Auswahl synchronisiert gemeinsame Properties und erzeugt Updates. */
    fun testMainEditorSynchronizesExplicitSelectionAndResetClearsIt() {
        val window = MavenUpWindowFactory().MyToolWindow(project)
        window.applyAutomaticVersionSearchState(state())
        val table = requireNotNull(findTable(window.getContent()))
        assertTrue(table.editCellAt(0, NEW_VERSION_COLUMN))
        combo(table.editorComponent).selectedItem = versions.first()
        assertTrue(table.cellEditor.stopCellEditing())
        assertEquals(versions.first(), window.selectedVersions[key])
        assertEquals(versions.first(), window.selectedVersions["com.example:other"])
        assertEquals(2, window.collectSelectedUpdates().size)
        window.resetVersionForDependency(key, "dependency")
        assertTrue(window.selectedVersions.isEmpty())
        assertFalse(window.hasSelectedUpdates())
    }

    /** Alte Zeitstempel-Auswahlen werden am Eingang verworfen statt nur optisch versteckt. */
    fun testAutomaticStateDropsUnavailableTargetSelection() {
        val window = MavenUpWindowFactory().MyToolWindow(project)
        window.applyAutomaticVersionSearchState(state("5.0.0-20261005.135311-3"))
        assertTrue(window.selectedVersions.isEmpty())
        assertFalse(window.hasSelectedUpdates())
        assertTrue(window.collectSelectedUpdates().isEmpty())
    }

    /** Wird eine gewaehlte SNAPSHOT-Version ausgeblendet, bleibt auch ohne Kandidaten kein Update uebrig. */
    fun testVisibilitySettingsDropUnavailableTargetsWithoutChangingCurrentVersion() {
        val settings = MavenUpSettings.getInstance()
        val originalHide = settings.state.hideUnstableVersions
        val originalQualifiers = settings.state.hiddenVersionQualifiers
        val originalOfferAll = settings.state.offerAllVersions
        try {
            val window = MavenUpWindowFactory().MyToolWindow(project)
            val candidates = mapOf(key to listOf("5.1.0-SNAPSHOT"))
            val snapshot = RefreshSnapshot(
                listOf(RefreshRow("com.example", "lib", "", "dependency", current)),
                emptyMap()
            )
            window.applyAutomaticVersionSearchState(
                AutomaticVersionSearchState(
                    snapshot, VersionSearchResult(candidates, candidates, mapOf(key to "5.1.0-SNAPSHOT")), null
                )
            )
            assertTrue(window.hasSelectedUpdates())
            settings.state.hideUnstableVersions = true
            settings.state.hiddenVersionQualifiers = "snapshot"
            settings.state.offerAllVersions = true
            window.applyVersionVisibilitySettings()
            assertTrue(window.selectedVersions.isEmpty())
            assertFalse(window.hasSelectedUpdates())
            assertEquals(current, requireNotNull(findTable(window.getContent())).getValueAt(0, CURRENT_VERSION_COLUMN))
        } finally {
            settings.state.hideUnstableVersions = originalHide
            settings.state.hiddenVersionQualifiers = originalQualifiers
            settings.state.offerAllVersions = originalOfferAll
        }
    }

    /** Das Ausschalten der Vorauswahl verwirft Ziele, auch wenn die aktuelle Version ausgeblendet ist. */
    fun testDisablingAutoSelectionRemovesPendingUpdate() {
        val settings = MavenUpSettings.getInstance()
        val original = settings.state.versionAutoSelectionMode
        try {
            val window = MavenUpWindowFactory().MyToolWindow(project)
            window.applyAutomaticVersionSearchState(state())
            for (mode in listOf(VersionAutoSelectionMode.LATEST, VersionAutoSelectionMode.LATEST_MINOR)) {
                settings.state.versionAutoSelectionMode = mode
                window.applySelectLatestVersionSetting()
                assertEquals(versions.first(), window.selectedVersions[key])
                settings.state.versionAutoSelectionMode = VersionAutoSelectionMode.DISABLED
                window.applySelectLatestVersionSetting()
                assertTrue(window.selectedVersions.isEmpty())
                assertFalse(window.hasSelectedUpdates())
            }
        } finally {
            settings.state.versionAutoSelectionMode = original
        }
    }

    /** Auch die transitive Ansicht behaelt fehlende aktuelle Versionen ohne implizites Pin-Update. */
    fun testTransitiveEditorKeepsMissingCurrentVersionUntilExplicitSelection() {
        val view = TransitiveVulnerabilitiesView(project)
        val coordinate = "$key:$current"
        view.update(
            mapOf(coordinate to listOf(VulnerabilityAdvisory(id = "TEST-1", sources = setOf("TEST")))),
            setOf(coordinate),
            availableVersions = mapOf(key to versions)
        )
        val table = view.table
        val renderer = table.getCellRenderer(0, TRANSITIVE_NEW_VERSION_COLUMN)
        val rendered = renderer.getTableCellRendererComponent(
            table, table.getValueAt(0, TRANSITIVE_NEW_VERSION_COLUMN), false, false, 0, TRANSITIVE_NEW_VERSION_COLUMN
        )
        assertEquals(current, combo(rendered).selectedItem)
        assertTrue(table.editCellAt(0, TRANSITIVE_NEW_VERSION_COLUMN))
        assertEquals(current, combo(table.editorComponent).selectedItem)
        assertTrue(table.cellEditor.stopCellEditing())
        assertTrue(view.selectedVersions.isEmpty())
        assertTrue(view.collectPendingUpdates().isEmpty())
        assertTrue(table.editCellAt(0, TRANSITIVE_NEW_VERSION_COLUMN))
        combo(table.editorComponent).selectedItem = versions.first()
        assertTrue(table.cellEditor.stopCellEditing())
        assertEquals(versions.first(), view.collectPendingUpdates().single().newVersion)
        view.update(
            mapOf(coordinate to listOf(VulnerabilityAdvisory(id = "TEST-1", sources = setOf("TEST")))),
            setOf(coordinate),
            availableVersions = mapOf(key to emptyList())
        )
        assertTrue(view.selectedVersions.isEmpty())
        assertTrue(view.collectPendingUpdates().isEmpty())
    }
}
