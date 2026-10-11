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
        val newVersionCol = table.convertColumnIndexToView(NEW_VERSION_COLUMN)
        val renderer = table.getCellRenderer(0, newVersionCol)
        val rendered = renderer.getTableCellRendererComponent(
            table, table.getValueAt(0, newVersionCol), false, false, 0, newVersionCol
        )
        assertEquals(current, combo(rendered).selectedItem)
        assertTrue(table.editCellAt(0, newVersionCol))
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
        val newVersionCol = table.convertColumnIndexToView(NEW_VERSION_COLUMN)
        assertTrue(table.editCellAt(0, newVersionCol))
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
            val table = requireNotNull(findTable(window.getContent()))
            val currentVersionCol = table.convertColumnIndexToView(CURRENT_VERSION_COLUMN)
            assertEquals(current, table.getValueAt(0, currentVersionCol))
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

    /** Eine Versionsauswahl für eine Abhängigkeit ohne Property ändert nicht die Auswahl anderer Abhängigkeiten. */
    fun testVersionSelectionWithoutPropertyDoesNotAffectOtherDependencies() {
        val window = MavenUpWindowFactory().MyToolWindow(project)
        val rows = listOf(
            RefreshRow("com.example", "lib1", "", "dependency", "1.0.0"),
            RefreshRow("com.example", "lib2", "", "dependency", "1.0.0")
        )
        val candidates = mapOf(
            "com.example:lib1" to listOf("1.1.0", "1.0.0"),
            "com.example:lib2" to listOf("2.0.0", "1.0.0")
        )
        val state = AutomaticVersionSearchState(
            RefreshSnapshot(rows, emptyMap()),
            VersionSearchResult(candidates, candidates, emptyMap()),
            null
        )
        window.applyAutomaticVersionSearchState(state)
        val table = requireNotNull(findTable(window.getContent()))
        val newVersionCol = table.convertColumnIndexToView(NEW_VERSION_COLUMN)

        // Lib1 auf 1.1.0 setzen
        assertTrue(table.editCellAt(0, newVersionCol))
        combo(table.editorComponent).selectedItem = "1.1.0"
        assertTrue(table.cellEditor.stopCellEditing())

        assertEquals("1.1.0", window.selectedVersions["com.example:lib1"])
        assertNull(window.selectedVersions["com.example:lib2"])

        val updates = window.collectSelectedUpdates()
        assertEquals(1, updates.size)
        assertEquals("com.example", updates.first().groupId)
        assertEquals("lib1", updates.first().artifactId)
        assertEquals("1.1.0", updates.first().newVersion)
    }

    /** Mehrere Zeilen desselben Artefakts in verschiedenen Modulen können unabhängig aktualisiert werden. */
    fun testMultiModuleRowsCanBeSelectedIndependently() {
        val file1 = myFixture.configureByText("pom.xml", "<project/>").virtualFile
        val file2 = myFixture.configureByText("child_pom.xml", "<project/>").virtualFile

        val window = MavenUpWindowFactory().MyToolWindow(project)
        val rows = listOf(
            RefreshRow("org.apache.commons", "commons-lang3", "", "managed dependency", "3.12.0", pomFile = file1),
            RefreshRow("org.apache.commons", "commons-lang3", "", "managed dependency", "3.12.0", pomFile = file2),
            RefreshRow("org.apache.commons", "commons-lang3", "", "dependency", "3.12.0", pomFile = file2)
        )
        val candidates = mapOf(
            "org.apache.commons:commons-lang3" to listOf("3.14.0", "3.13.0", "3.12.0")
        )
        val state = AutomaticVersionSearchState(
            RefreshSnapshot(rows, emptyMap()),
            VersionSearchResult(candidates, candidates, emptyMap()),
            null
        )
        window.applyAutomaticVersionSearchState(state)
        val table = requireNotNull(findTable(window.getContent()))

        val newVersionCol = table.convertColumnIndexToView(NEW_VERSION_COLUMN)

        // Zeile 0 (Parent managed) auf 3.14.0 setzen
        assertTrue(table.editCellAt(0, newVersionCol))
        combo(table.editorComponent).selectedItem = "3.14.0"
        assertTrue(table.cellEditor.stopCellEditing())

        // Renderer von Zeile 1 und 2 prüfen
        val r1 = table.getCellRenderer(1, newVersionCol)
            .getTableCellRendererComponent(table, table.getValueAt(1, newVersionCol), false, false, 1, newVersionCol)
        val r2 = table.getCellRenderer(2, newVersionCol)
            .getTableCellRendererComponent(table, table.getValueAt(2, newVersionCol), false, false, 2, newVersionCol)

        assertEquals("3.12.0", combo(r1).selectedItem)
        assertEquals("3.12.0", combo(r2).selectedItem)

        val updates = window.collectSelectedUpdates()
        assertEquals(1, updates.size)
        assertEquals(file1, updates.first().pomFile)
        assertEquals("3.14.0", updates.first().newVersion)
    }

    /** Toolbar-Funktionen Highest Major/Minor und Reset setzen und verwerfen Versionen auf Zeilenebene. */
    fun testToolbarSelectHighestMajorAndMinorSetsSelectionsAndResetClearsAll() {
        val file1 = myFixture.configureByText("pom.xml", "<project/>").virtualFile
        val file2 = myFixture.configureByText("child_pom.xml", "<project/>").virtualFile

        val window = MavenUpWindowFactory().MyToolWindow(project)
        val rows = listOf(
            RefreshRow("org.apache.commons", "commons-lang3", "", "managed dependency", "3.12.0", pomFile = file1),
            RefreshRow("org.apache.commons", "commons-lang3", "", "dependency", "3.12.0", pomFile = file2)
        )
        val candidates = mapOf(
            "org.apache.commons:commons-lang3" to listOf("4.0.0", "3.14.0", "3.13.0", "3.12.0")
        )
        val state = AutomaticVersionSearchState(
            RefreshSnapshot(rows, emptyMap()),
            VersionSearchResult(candidates, candidates, emptyMap()),
            null
        )
        window.applyAutomaticVersionSearchState(state)
        requireNotNull(findTable(window.getContent()))

        // 1. Highest Minor (gleiche Major-Linie)
        window.selectHighestMinorVersionForAll(visibleOnly = false)
        assertTrue(window.hasSelectedUpdates())
        val minorUpdates = window.collectSelectedUpdates()
        assertEquals(2, minorUpdates.size)
        assertTrue(minorUpdates.all { it.newVersion == "3.14.0" })

        // 2. Highest Major
        window.selectHighestMajorVersionForAll(visibleOnly = false)
        val majorUpdates = window.collectSelectedUpdates()
        assertEquals(2, majorUpdates.size)
        assertTrue(majorUpdates.all { it.newVersion == "4.0.0" })

        // 3. Reset All
        window.resetAllVersionsToCurrent()
        assertFalse(window.hasSelectedUpdates())
        assertTrue(window.collectSelectedUpdates().isEmpty())
    }

    /** Nur Zeilen mit tatsächlich geerbter Version werden als Inherited gerendert. */
    fun testInheritedVersionRenderingOnlyMarksInheritedRowInMultiModule() {
        val file1 = myFixture.configureByText("pom.xml", "<project/>").virtualFile
        val file2 = myFixture.configureByText("child_pom.xml", "<project/>").virtualFile

        val window = MavenUpWindowFactory().MyToolWindow(project)
        val rows = listOf(
            RefreshRow("org.apache.commons", "commons-lang3", "", "managed dependency", "3.12.0", pomFile = file1, versionInherited = false),
            RefreshRow("org.apache.commons", "commons-lang3", "", "managed dependency", "3.12.0", pomFile = file2, versionInherited = false),
            RefreshRow("org.apache.commons", "commons-lang3", "", "dependency", "3.12.0", pomFile = file2, versionInherited = true)
        )
        val state = AutomaticVersionSearchState(
            RefreshSnapshot(rows, emptyMap()),
            VersionSearchResult(emptyMap(), emptyMap(), emptyMap()),
            null
        )
        window.applyAutomaticVersionSearchState(state)
        val table = requireNotNull(findTable(window.getContent()))

        val currentVersionCol = table.convertColumnIndexToView(CURRENT_VERSION_COLUMN)
        val renderer = table.getCellRenderer(0, currentVersionCol)

        val c0 = renderer.getTableCellRendererComponent(table, "3.12.0", false, false, 0, currentVersionCol) as javax.swing.JLabel
        assertEquals("3.12.0", c0.text)

        val c1 = renderer.getTableCellRendererComponent(table, "3.12.0", false, false, 1, currentVersionCol) as javax.swing.JLabel
        assertEquals("3.12.0", c1.text)

        val c2 = renderer.getTableCellRendererComponent(table, "3.12.0", false, false, 2, currentVersionCol) as javax.swing.JLabel
        assertTrue("Child direct dependency must be marked as inherited", c2.text.contains("inherited") || c2.text.contains("Inherited"))
    }
}
