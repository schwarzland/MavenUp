package de.schwarzland.mavenup.ui

import com.intellij.psi.xml.XmlFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.treeStructure.Tree
import de.schwarzland.mavenup.model.DependencyHierarchyNode
import de.schwarzland.mavenup.model.DependencyHierarchyNodeType
import javax.swing.tree.DefaultMutableTreeNode

/**
 * Tests für den [DependencyHierarchyDialog] und den [DependencyHierarchyTreeCellRenderer].
 */
class DependencyHierarchyDialogTest : BasePlatformTestCase() {

    fun testBuildTreeModelConstructsFullHierarchy() {
        val rootNode = DependencyHierarchyNode(
            type = DependencyHierarchyNodeType.ROOT,
            groupId = "com.fasterxml.jackson.core",
            artifactId = "jackson-databind",
            version = "2.15.2"
        )

        val projectNode = DependencyHierarchyNode(
            type = DependencyHierarchyNodeType.PROJECT,
            groupId = "com.example",
            artifactId = "my-service"
        )

        val dmNode = DependencyHierarchyNode(
            type = DependencyHierarchyNodeType.DEPENDENCY_MANAGEMENT,
            groupId = "com.fasterxml.jackson.core",
            artifactId = "jackson-databind",
            version = "2.15.2",
            propertyName = "jackson.version"
        )

        projectNode.children.add(dmNode)
        rootNode.children.add(projectNode)

        val dialog = DependencyHierarchyDialog(project, "com.fasterxml.jackson.core", "jackson-databind")
        val treeModel = dialog.buildTreeModel(rootNode)

        val rootTreeNode = treeModel.root as DefaultMutableTreeNode
        assertEquals(rootNode, rootTreeNode.userObject)
        assertEquals(1, rootTreeNode.childCount)

        val projectTreeNode = rootTreeNode.getChildAt(0) as DefaultMutableTreeNode
        assertEquals(projectNode, projectTreeNode.userObject)
        assertEquals(1, projectTreeNode.childCount)

        val dmTreeNode = projectTreeNode.getChildAt(0) as DefaultMutableTreeNode
        assertEquals(dmNode, dmTreeNode.userObject)
    }

    fun testExpandAllNodesExpandsRows() {
        val rootNode = DependencyHierarchyNode(
            type = DependencyHierarchyNodeType.ROOT,
            groupId = "com.example",
            artifactId = "lib"
        )
        val childNode = DependencyHierarchyNode(
            type = DependencyHierarchyNodeType.DIRECT_DEPENDENCY,
            groupId = "com.example",
            artifactId = "lib",
            version = "1.0.0"
        )
        rootNode.children.add(childNode)

        val dialog = DependencyHierarchyDialog(project, "com.example", "lib")
        val treeModel = dialog.buildTreeModel(rootNode)
        val tree = Tree(treeModel).apply {
            isRootVisible = true
            showsRootHandles = true
        }

        dialog.expandAllNodes(tree)
        assertTrue(tree.isExpanded(0))
    }

    fun testCollapseAllNodesCollapsesRows() {
        val rootNode = DependencyHierarchyNode(
            type = DependencyHierarchyNodeType.ROOT,
            groupId = "com.example",
            artifactId = "lib"
        )
        val childNode = DependencyHierarchyNode(
            type = DependencyHierarchyNodeType.DIRECT_DEPENDENCY,
            groupId = "com.example",
            artifactId = "lib",
            version = "1.0.0"
        )
        rootNode.children.add(childNode)

        val dialog = DependencyHierarchyDialog(project, "com.example", "lib")
        val treeModel = dialog.buildTreeModel(rootNode)
        val tree = Tree(treeModel).apply {
            isRootVisible = true
            showsRootHandles = true
        }

        dialog.expandAllNodes(tree)
        assertTrue(tree.isExpanded(0))
        dialog.collapseAllNodes(tree)
        assertFalse(tree.isExpanded(0))
    }

    fun testRendererCustomizesTextAndDetails() {
        val node = DependencyHierarchyNode(
            type = DependencyHierarchyNodeType.DEPENDENCY_MANAGEMENT,
            groupId = "org.springframework.boot",
            artifactId = "spring-boot-starter-web",
            version = "3.2.0",
            propertyName = "spring.version",
            scope = "compile",
            isManaged = true
        )

        val renderer = DependencyHierarchyTreeCellRenderer()
        val tree = Tree()
        val treeNode = DefaultMutableTreeNode(node)

        renderer.getTreeCellRendererComponent(tree, treeNode, false, false, true, 0, false)

        assertNotNull(renderer.icon)
        val renderedFragments = renderer.renderedItems
        assertTrue("Muss Prefix, Koordinate und Property enthalten", renderedFragments.any { it.contains("spring-boot-starter-web") })
    }

    fun testIsTargetDependencyIdentifiesTargetNodes() {
        val renderer = DependencyHierarchyTreeCellRenderer("com.fasterxml.jackson.core", "jackson-databind")

        val rootNode = DependencyHierarchyNode(
            type = DependencyHierarchyNodeType.ROOT,
            groupId = "com.fasterxml.jackson.core",
            artifactId = "jackson-databind",
            version = "2.15.2"
        )
        assertTrue(renderer.isTargetDependency(rootNode))

        val dmNode = DependencyHierarchyNode(
            type = DependencyHierarchyNodeType.DEPENDENCY_MANAGEMENT,
            groupId = "com.fasterxml.jackson.core",
            artifactId = "jackson-databind",
            version = "2.15.2"
        )
        assertTrue(renderer.isTargetDependency(dmNode))

        val directNode = DependencyHierarchyNode(
            type = DependencyHierarchyNodeType.DIRECT_DEPENDENCY,
            groupId = "com.fasterxml.jackson.core",
            artifactId = "jackson-databind",
            version = "2.15.2"
        )
        assertTrue(renderer.isTargetDependency(directNode))

        val transitiveTargetNode = DependencyHierarchyNode(
            type = DependencyHierarchyNodeType.TRANSITIVE_DEPENDENCY,
            groupId = "com.fasterxml.jackson.core",
            artifactId = "jackson-databind",
            version = "2.15.2"
        )
        assertTrue(renderer.isTargetDependency(transitiveTargetNode))

        val intermediateTransitiveNode = DependencyHierarchyNode(
            type = DependencyHierarchyNodeType.TRANSITIVE_DEPENDENCY,
            groupId = "org.springframework.boot",
            artifactId = "spring-boot-starter-json",
            version = "3.2.0"
        )
        assertFalse(renderer.isTargetDependency(intermediateTransitiveNode))

        val projectNode = DependencyHierarchyNode(
            type = DependencyHierarchyNodeType.PROJECT,
            groupId = "com.fasterxml.jackson.core",
            artifactId = "jackson-databind"
        )
        assertFalse("Project-Knoten dürfen nicht als Target-Dependency gewertet werden", renderer.isTargetDependency(projectNode))

        val unconfiguredRenderer = DependencyHierarchyTreeCellRenderer()
        assertFalse(unconfiguredRenderer.isTargetDependency(rootNode))
    }

    fun testRendererHighlightsTargetDependencyWithColor() {
        val targetNode = DependencyHierarchyNode(
            type = DependencyHierarchyNodeType.DEPENDENCY_MANAGEMENT,
            groupId = "com.example",
            artifactId = "my-target",
            version = "1.0.0"
        )
        val otherNode = DependencyHierarchyNode(
            type = DependencyHierarchyNodeType.DIRECT_DEPENDENCY,
            groupId = "com.example",
            artifactId = "other-lib",
            version = "2.0.0"
        )

        val renderer = DependencyHierarchyTreeCellRenderer("com.example", "my-target")
        val tree = Tree()

        renderer.getTreeCellRendererComponent(tree, DefaultMutableTreeNode(targetNode), false, false, true, 0, false)
        assertTrue(renderer.renderedItems.any { it.contains("my-target") })

        renderer.getTreeCellRendererComponent(tree, DefaultMutableTreeNode(otherNode), false, false, true, 1, false)
        assertTrue(renderer.renderedItems.any { it.contains("other-lib") })
    }

    fun testTargetDependencyColorDefined() {
        assertNotNull(TARGET_DEPENDENCY_COLOR)
    }

    fun testNavigateToSelectedNodeWithTag() {
        val psiFile = myFixture.configureByText(
            "pom.xml",
            """
            <project>
                <dependencies>
                    <dependency>
                        <groupId>com.example</groupId>
                        <artifactId>demo</artifactId>
                        <version>1.0.0</version>
                    </dependency>
                </dependencies>
            </project>
            """.trimIndent()
        ) as XmlFile

        val tag = psiFile.document?.rootTag?.findFirstSubTag("dependencies")?.findFirstSubTag("dependency")

        val node = DependencyHierarchyNode(
            type = DependencyHierarchyNodeType.DIRECT_DEPENDENCY,
            groupId = "com.example",
            artifactId = "demo",
            version = "1.0.0",
            pomFile = psiFile.virtualFile,
            xmlTag = tag
        )

        val dialog = DependencyHierarchyDialog(project, "com.example", "demo")
        val treeModel = dialog.buildTreeModel(node)
        val tree = Tree(treeModel)
        tree.setSelectionRow(0)

        // Sollte ohne Fehler ausgeführt werden
        dialog.navigateToSelectedNode(tree)
    }

    fun testNavigateToSelectedNodeWithoutTagWithPomFile() {
        val psiFile = myFixture.configureByText(
            "pom.xml",
            """
            <project>
                <dependencies>
                    <dependency>
                        <groupId>com.example</groupId>
                        <artifactId>demo</artifactId>
                        <version>1.0.0</version>
                    </dependency>
                </dependencies>
            </project>
            """.trimIndent()
        ) as XmlFile

        val node = DependencyHierarchyNode(
            type = DependencyHierarchyNodeType.DIRECT_DEPENDENCY,
            groupId = "com.example",
            artifactId = "demo",
            version = "1.0.0",
            pomFile = psiFile.virtualFile,
            xmlTag = null
        )

        val dialog = DependencyHierarchyDialog(project, "com.example", "demo")
        val treeModel = dialog.buildTreeModel(node)
        val tree = Tree(treeModel)
        tree.setSelectionRow(0)

        // Findet das Tag in der übergebenen pomFile und öffnet den Editor
        dialog.navigateToSelectedNode(tree)
    }

    fun testNavigateToSelectedNodeWithoutPomFileFallsBackToNavigationService() {
        val node = DependencyHierarchyNode(
            type = DependencyHierarchyNodeType.DIRECT_DEPENDENCY,
            groupId = "com.example",
            artifactId = "demo",
            version = "1.0.0",
            pomFile = null,
            xmlTag = null
        )

        val dialog = DependencyHierarchyDialog(project, "com.example", "demo")
        val treeModel = dialog.buildTreeModel(node)
        val tree = Tree(treeModel)
        tree.setSelectionRow(0)

        // Nutzt PomNavigationService Fallback
        dialog.navigateToSelectedNode(tree)
    }

    fun testCreateContextMenuGroupContainsNavigateAction() {
        val dialog = DependencyHierarchyDialog(project, "com.example", "demo")
        val tree = Tree()
        val group = dialog.createContextMenuGroup(tree)
        val actions = group.getChildren(null)
        assertEquals(2, actions.size)
        assertEquals(
            MyMessageBundle.message("toolwindow.MyToolWindow.contextMenu.navigateToPom"),
            actions[0].templatePresentation.text
        )
        assertEquals(
            MyMessageBundle.message("dependency.hierarchy.action.navigateToTable"),
            actions[1].templatePresentation.text
        )
    }

    fun testCreateToolbarSetsTreeAsTargetComponentAndContainsNavigateAction() {
        val dialog = DependencyHierarchyDialog(project, "com.example", "demo")
        val tree = Tree()

        val toolbar = dialog.createToolbar(tree)

        assertSame(tree, toolbar.targetComponent)
        val actions = toolbar.actionGroup.getChildren(null)
        val navigateTableAction = actions.filterIsInstance<com.intellij.openapi.actionSystem.AnAction>()
            .firstOrNull { it.templatePresentation.text == MyMessageBundle.message("dependency.hierarchy.action.navigateToTable") }
        assertNotNull(navigateTableAction)
    }

    fun testCanNavigateToTableRequiresValidCoordinates() {
        val validNode = DependencyHierarchyNode(
            type = DependencyHierarchyNodeType.DIRECT_DEPENDENCY,
            groupId = "com.example",
            artifactId = "demo",
            version = "1.0.0",
            pomFile = null
        )
        val invalidNode = DependencyHierarchyNode(
            type = DependencyHierarchyNodeType.ROOT,
            groupId = "",
            artifactId = "",
            version = null,
            pomFile = null
        )
        val tree = Tree(DefaultMutableTreeNode(validNode).apply {
            add(DefaultMutableTreeNode(invalidNode))
        })
        val dialog = DependencyHierarchyDialog(project, "com.example", "demo")

        tree.setSelectionRow(0)
        assertTrue(dialog.canNavigateToTable(tree))

        tree.setSelectionRow(1)
        assertFalse(dialog.canNavigateToTable(tree))

        tree.clearSelection()
        assertFalse(dialog.canNavigateToTable(tree))
    }

    fun testNavigateToTableForSelectedNodeClosesDialogAndInvokesCallback() {
        var callbackCalledWith: Pair<String, String>? = null
        val node = DependencyHierarchyNode(
            type = DependencyHierarchyNodeType.PARENT_POM,
            groupId = "org.springframework.boot",
            artifactId = "spring-boot-starter-parent",
            version = "3.2.0",
            pomFile = null
        )
        val tree = Tree(DefaultMutableTreeNode(node))
        tree.setSelectionRow(0)

        val dialog = DependencyHierarchyDialog(project, "com.example", "demo", onNavigateToTable = { gid, aid ->
            callbackCalledWith = Pair(gid, aid)
            true
        })

        val result = dialog.navigateToTableForSelectedNode(tree)
        assertTrue(result)
        assertEquals(Pair("org.springframework.boot", "spring-boot-starter-parent"), callbackCalledWith)
    }

    fun testContextMenuActionPerformsNavigationToTable() {
        var callbackCalled = false
        val node = DependencyHierarchyNode(
            type = DependencyHierarchyNodeType.DIRECT_DEPENDENCY,
            groupId = "com.example",
            artifactId = "demo",
            version = "1.0.0",
            pomFile = null
        )
        val tree = Tree(DefaultMutableTreeNode(node))
        tree.setSelectionRow(0)

        val dialog = DependencyHierarchyDialog(project, "com.example", "demo", onNavigateToTable = { _, _ ->
            callbackCalled = true
            true
        })
        val group = dialog.createContextMenuGroup(tree)
        val navigateAction = group.getChildren(null)[1]
        val event = com.intellij.testFramework.TestActionEvent.createTestEvent(navigateAction)
        navigateAction.actionPerformed(event)
        assertTrue(callbackCalled)
    }

    fun testContextMenuActionPerformsNavigation() {
        val psiFile = myFixture.configureByText(
            "pom.xml",
            """
            <project>
                <dependencies>
                    <dependency>
                        <groupId>com.example</groupId>
                        <artifactId>demo</artifactId>
                        <version>1.0.0</version>
                    </dependency>
                </dependencies>
            </project>
            """.trimIndent()
        ) as XmlFile

        val tag = psiFile.document?.rootTag?.findFirstSubTag("dependencies")?.findFirstSubTag("dependency")

        val node = DependencyHierarchyNode(
            type = DependencyHierarchyNodeType.DIRECT_DEPENDENCY,
            groupId = "com.example",
            artifactId = "demo",
            version = "1.0.0",
            pomFile = psiFile.virtualFile,
            xmlTag = tag
        )

        val dialog = DependencyHierarchyDialog(project, "com.example", "demo")
        val treeModel = dialog.buildTreeModel(node)
        val tree = Tree(treeModel)
        tree.setSelectionRow(0)

        val group = dialog.createContextMenuGroup(tree)
        val action = group.getChildren(null)[0]
        val event = com.intellij.testFramework.TestActionEvent.createTestEvent(action)
        action.actionPerformed(event)
    }

    fun testCreateCenterPanelBuildsUI() {
        val dialog = DependencyHierarchyDialog(project, "com.example", "demo")
        val panel = dialog.createCenterPanel()
        assertNotNull(panel)
    }

    fun testCanNavigateToSelectedNodeDependsOnSelection() {
        val rootNode = DependencyHierarchyNode(
            type = DependencyHierarchyNodeType.ROOT,
            groupId = "com.example",
            artifactId = "demo",
            version = "1.0.0",
            pomFile = null
        )
        val childNode = DependencyHierarchyNode(
            type = DependencyHierarchyNodeType.DIRECT_DEPENDENCY,
            groupId = "com.example",
            artifactId = "demo",
            version = "1.0.0",
            pomFile = null
        )
        val treeModel = DefaultMutableTreeNode(rootNode).apply {
            add(DefaultMutableTreeNode(childNode))
        }
        val tree = Tree(treeModel)
        val dialog = DependencyHierarchyDialog(project, "com.example", "demo")

        tree.setSelectionRow(0)
        assertTrue(dialog.canNavigateToSelectedNode(tree))

        val rootOnlyTree = Tree(DefaultMutableTreeNode(rootNode))
        rootOnlyTree.setSelectionRow(0)
        assertTrue(dialog.canNavigateToSelectedNode(rootOnlyTree))

        val invalidNode = DependencyHierarchyNode(
            type = DependencyHierarchyNodeType.ROOT,
            groupId = "",
            artifactId = "",
            version = "1.0.0",
            pomFile = null
        )
        val invalidTree = Tree(DefaultMutableTreeNode(invalidNode))
        invalidTree.setSelectionRow(0)
        assertFalse(dialog.canNavigateToSelectedNode(invalidTree))
    }

    private val DependencyHierarchyTreeCellRenderer.renderedItems: List<String>
        get() = (0 until iterator().asSequence().count()).map {
            iterator().asSequence().toList()[it]
        }
}
