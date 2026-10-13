package de.schwarzland.mavenup.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.table.JBTable
import com.intellij.ui.treeStructure.Tree
import de.schwarzland.mavenup.model.DependencyHierarchyNode
import de.schwarzland.mavenup.model.DependencyHierarchyNodeType
import de.schwarzland.mavenup.model.VulnerabilityAdvisory
import de.schwarzland.mavenup.service.AutomaticVersionSearchState
import java.awt.Component
import java.awt.Container
import javax.swing.SortOrder
import javax.swing.RowSorter
import javax.swing.tree.DefaultMutableTreeNode

/** Prueft die modul- und typspezifische Tabellennavigation aus beiden Hierarchiepanels. */
class HierarchyTableNavigationIntegrationTest : BasePlatformTestCase() {
    /** Erzeugt einen Tool-Window-Zustand ohne Netzwerkabfragen. */
    private fun window(rows: List<RefreshRow>): MavenUpWindowFactory.MyToolWindow =
        MavenUpWindowFactory().MyToolWindow(project).apply {
            applyAutomaticVersionSearchState(
                AutomaticVersionSearchState(RefreshSnapshot(rows, emptyMap()), null, null)
            )
        }

    /** Findet eine eingebettete Komponente ohne Zugriff auf private Felder. */
    private fun <T : Component> find(component: Component, type: Class<T>): T? =
        if (type.isInstance(component)) type.cast(component)
        else (component as? Container)?.components?.firstNotNullOfOrNull { find(it, type) }

    /** Baut eine Baumselektion fuer den angeklickten Hierarchieknoten. */
    private fun tree(node: DependencyHierarchyNode): Tree =
        Tree(DefaultMutableTreeNode(node)).apply { setSelectionRow(0) }

    /** Das Beispiel mit spring-web in zwei Modulen waehlt aus beiden Panels das Modul A. */
    fun testSelectInDependenciesUsesModuleAndTypeFromBothPanelsWithSortingAndFilters() {
        val moduleA = myFixture.addFileToProject("module-a/pom.xml", "<project/>").virtualFile
        val moduleB = myFixture.addFileToProject("module-b/pom.xml", "<project/>").virtualFile
        val managedType = MyMessageBundle.message("toolwindow.MyToolWindow.type.managedDependency")
        val rows = listOf(
            RefreshRow("org.springframework", "spring-web", "", "dependency", "6.0.0",
                pomFile = moduleB, moduleName = "module-b"),
            RefreshRow("org.springframework", "spring-web", "", managedType, "6.0.0",
                pomFile = moduleA, moduleName = "module-a"),
            RefreshRow("org.springframework", "spring-web", "", "dependency", "6.0.0",
                pomFile = moduleA, moduleName = "module-a")
        )
        val window = window(rows)
        val table = requireNotNull(find(window.getContent(), JBTable::class.java))
        window.showDependencyHierarchy("org.springframework", "spring-web")
        val mainPanel = requireNotNull(find(window.getContent(), DependencyHierarchyPanel::class.java))
        val node = DependencyHierarchyNode(
            DependencyHierarchyNodeType.DIRECT_DEPENDENCY, "org.springframework", "spring-web",
            pomFile = moduleA
        )

        for (panel in listOf(mainPanel, window.transitiveVulnerabilitiesView.dependencyHierarchyPanel)) {
            table.rowSorter.sortKeys = listOf(RowSorter.SortKey(MODULE_COLUMN, SortOrder.DESCENDING))
            table.setRowSelectionInterval(table.convertRowIndexToView(0), table.convertRowIndexToView(0))
            window.searchTextField.text = "module-b"
            window.applyRowFilter()
            val selectedTree = tree(node)
            assertTrue(panel.canNavigateToTable(selectedTree))
            assertEquals(MyMessageBundle.message("dependency.hierarchy.action.navigateToDependencies"),
                panel.tableNavigationActionLabel(selectedTree))
            assertTrue(panel.navigateToTableForSelectedNode(selectedTree))
            assertEquals("", window.searchTextField.text)
            assertEquals(2, table.convertRowIndexToModel(table.selectedRow))
            assertEquals("module-a", table.model.getValueAt(2, MODULE_COLUMN))
            assertEquals("dependency", table.model.getValueAt(2, TYPE_COLUMN))
        }
    }

    /** Alle deklarierten Knotentypen unterscheiden gleichnamige Tabellenzeilen in derselben POM. */
    fun testDeclarationTypesSelectTheirOwnRows() {
        val pom = myFixture.addFileToProject("pom.xml", "<project/>").virtualFile
        val managedType = MyMessageBundle.message("toolwindow.MyToolWindow.type.managedDependency")
        val types = listOf("dependency", managedType, "plugin", "managed plugin", "parent")
        val window = window(types.map {
            RefreshRow("com.example", "shared", "", it, "1.0", pomFile = pom)
        })
        val table = requireNotNull(find(window.getContent(), JBTable::class.java))
        val expectedRows = mapOf(
            DependencyHierarchyNodeType.DIRECT_DEPENDENCY to 0,
            DependencyHierarchyNodeType.DEPENDENCY_MANAGEMENT to 1,
            DependencyHierarchyNodeType.BOM_IMPORT to 1,
            DependencyHierarchyNodeType.DIRECT_PLUGIN to 2,
            DependencyHierarchyNodeType.PLUGIN_MANAGEMENT to 3,
            DependencyHierarchyNodeType.PARENT_POM to 4
        )
        for ((type, index) in expectedRows) {
            val node = DependencyHierarchyNode(type, "com.example", "shared", pomFile = pom)
            assertTrue(window.navigateToDependencyInTable(node.groupId, node.artifactId, node))
            assertEquals(index, table.convertRowIndexToModel(table.selectedRow))
        }
    }

    /** Fehlende Quell-POM oder falscher Typ selektieren keine andere Deklaration als Ersatz. */
    fun testMissingDeclarationDoesNotSelectAnotherModuleOrType() {
        val moduleA = myFixture.addFileToProject("module-a/pom.xml", "<project/>").virtualFile
        val moduleB = myFixture.addFileToProject("module-b/pom.xml", "<project/>").virtualFile
        val window = window(listOf(
            RefreshRow("org.springframework", "spring-web", "", "dependency", "6.0",
                pomFile = moduleB)
        ))
        val table = requireNotNull(find(window.getContent(), JBTable::class.java))
        val panel = window.transitiveVulnerabilitiesView.dependencyHierarchyPanel
        val missing = DependencyHierarchyNode(
            DependencyHierarchyNodeType.DIRECT_DEPENDENCY, "org.springframework", "spring-web",
            pomFile = moduleA
        )
        assertFalse(panel.canNavigateToTable(tree(missing)))
        assertFalse(panel.navigateToTableForSelectedNode(tree(missing)))
        assertFalse(panel.navigateToTableForSelectedNode(tree(missing.copy(
            type = DependencyHierarchyNodeType.DIRECT_PLUGIN, pomFile = moduleB
        ))))
        assertEquals(-1, table.selectedRow)
    }

    /** Transitive Funde ohne passende Deklaration werden in der aggregierten CVE-Tabelle selektiert. */
    fun testSelectInTransitiveCvesClearsFiltersAndSelectsAggregatedCoordinate() {
        val window = window(emptyList())
        val view = window.transitiveVulnerabilitiesView
        val coordinate = "org.springframework:spring-web:6.0"
        view.update(
            mapOf(coordinate to listOf(VulnerabilityAdvisory(id = "CVE-TEST", sources = setOf("TEST")))),
            setOf(coordinate), scanPerformed = true
        )
        view.filterPanel.filterBy("not-present")
        assertEquals(0, view.table.rowCount)
        val panel = view.dependencyHierarchyPanel
        val node = DependencyHierarchyNode(
            DependencyHierarchyNodeType.TRANSITIVE_DEPENDENCY, "org.springframework", "spring-web"
        )
        assertTrue(panel.canNavigateToTable(tree(node)))
        assertEquals(MyMessageBundle.message("dependency.hierarchy.action.navigateToTransitiveCves"),
            panel.tableNavigationActionLabel(tree(node)))
        assertTrue(panel.navigateToTableForSelectedNode(tree(node)))
        assertEquals(1, view.table.rowCount)
        assertEquals("spring-web", view.table.getValueAt(view.table.selectedRow, TRANSITIVE_ARTIFACT_ID_COLUMN))
        assertFalse(panel.navigateToTableForSelectedNode(tree(node.copy(artifactId = "unknown"))))
        assertFalse(panel.navigateToTableForSelectedNode(tree(node.copy(groupId = ""))))
    }

    /** Abstrakte oder transitive Knoten ohne Deklarationskontext behalten den Koordinaten-Fallback. */
    fun testNodesWithoutDeclarationContextKeepCoordinateNavigation() {
        val window = window(listOf(RefreshRow("com.example", "lib", "", "dependency", "1.0")))
        val panel = window.transitiveVulnerabilitiesView.dependencyHierarchyPanel
        for (type in listOf(DependencyHierarchyNodeType.ROOT, DependencyHierarchyNodeType.TRANSITIVE_DEPENDENCY)) {
            val node = DependencyHierarchyNode(type, "com.example", "lib")
            assertEquals(MyMessageBundle.message("dependency.hierarchy.action.navigateToDependencies"),
                panel.tableNavigationActionLabel(tree(node)))
            assertTrue(panel.navigateToTableForSelectedNode(tree(node)))
        }
        assertFalse(window.navigateToDependencyInTable("unknown", "lib"))
    }

    /** Eine Deklaration aus einem anderen Modul verdeckt das aggregierte CVE-Ziel nicht. */
    fun testMissingModuleDeclarationFallsBackToScannedCoordinateFromBothPanels() {
        val moduleA = myFixture.addFileToProject("module-a/pom.xml", "<project/>").virtualFile
        val moduleB = myFixture.addFileToProject("module-b/pom.xml", "<project/>").virtualFile
        val window = window(listOf(
            RefreshRow("org.springframework", "spring-web", "", "dependency", "6.0",
                pomFile = moduleB)
        ))
        val table = requireNotNull(find(window.getContent(), JBTable::class.java))
        window.showDependencyHierarchy("org.springframework", "spring-web")
        val mainPanel = requireNotNull(find(window.getContent(), DependencyHierarchyPanel::class.java))
        val view = window.transitiveVulnerabilitiesView
        val coordinate = "org.springframework:spring-web:6.0"
        view.update(
            mapOf(coordinate to listOf(VulnerabilityAdvisory(id = "CVE-TEST", sources = setOf("TEST")))),
            setOf(coordinate), scanPerformed = true
        )
        val selectedTree = tree(DependencyHierarchyNode(
            DependencyHierarchyNodeType.DIRECT_DEPENDENCY, "org.springframework", "spring-web",
            pomFile = moduleA
        ))
        for (panel in listOf(mainPanel, view.dependencyHierarchyPanel)) {
            view.filterPanel.filterBy("hidden")
            assertTrue(panel.canNavigateToTable(selectedTree))
            assertEquals(MyMessageBundle.message("dependency.hierarchy.action.navigateToTransitiveCves"),
                panel.tableNavigationActionLabel(selectedTree))
            assertTrue(panel.navigateToTableForSelectedNode(selectedTree))
            assertEquals(0, view.table.selectedRow)
            assertEquals(-1, table.selectedRow)
        }
    }
}
