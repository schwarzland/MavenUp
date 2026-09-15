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
        val tree = Tree(treeModel)

        dialog.expandAllNodes(tree)
        assertTrue(tree.isExpanded(0))
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

    private val DependencyHierarchyTreeCellRenderer.renderedItems: List<String>
        get() = (0 until iterator().asSequence().count()).map {
            iterator().asSequence().toList()[it]
        }
}
