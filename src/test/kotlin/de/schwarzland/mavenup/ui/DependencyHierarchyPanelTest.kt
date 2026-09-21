package de.schwarzland.mavenup.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.Separator
import com.intellij.psi.xml.XmlFile
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.treeStructure.Tree
import de.schwarzland.mavenup.model.DependencyHierarchyNode
import de.schwarzland.mavenup.model.DependencyHierarchyNodeType
import de.schwarzland.mavenup.model.VulnerabilityAdvisory
import de.schwarzland.mavenup.model.VulnerabilitySeverity
import javax.swing.tree.DefaultMutableTreeNode

/**
 * Tests für das [DependencyHierarchyPanel] und den [DependencyHierarchyTreeCellRenderer].
 */
class DependencyHierarchyPanelTest : BasePlatformTestCase() {

    fun testMinimumSizeDoesNotConstrainHostingSplitter() {
        val panel = DependencyHierarchyPanel(project)

        assertEquals(0, panel.minimumSize.width)
        assertEquals(0, panel.minimumSize.height)
    }

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

        val panel = DependencyHierarchyPanel(project)
        val treeModel = panel.buildTreeModel(rootNode)

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

        val panel = DependencyHierarchyPanel(project)
        val treeModel = panel.buildTreeModel(rootNode)
        val tree = Tree(treeModel).apply {
            isRootVisible = true
            showsRootHandles = true
        }

        panel.expandAllNodes(tree)
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

        val panel = DependencyHierarchyPanel(project)
        val treeModel = panel.buildTreeModel(rootNode)
        val tree = Tree(treeModel).apply {
            isRootVisible = true
            showsRootHandles = true
        }

        panel.expandAllNodes(tree)
        assertTrue(tree.isExpanded(0))
        panel.collapseAllNodes(tree)
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

        val otherNode = DependencyHierarchyNode(
            type = DependencyHierarchyNodeType.DIRECT_DEPENDENCY,
            groupId = "org.slf4j",
            artifactId = "slf4j-api",
            version = "2.0.7"
        )
        assertFalse(renderer.isTargetDependency(otherNode))

        val projectNode = DependencyHierarchyNode(
            type = DependencyHierarchyNodeType.PROJECT,
            groupId = "com.fasterxml.jackson.core",
            artifactId = "jackson-databind"
        )
        assertFalse(renderer.isTargetDependency(projectNode))
    }

    fun testRendererRendersAllNodeTypes() {
        val types = listOf(
            DependencyHierarchyNodeType.ROOT,
            DependencyHierarchyNodeType.PROJECT,
            DependencyHierarchyNodeType.PARENT_POM,
            DependencyHierarchyNodeType.BOM_IMPORT,
            DependencyHierarchyNodeType.DEPENDENCY_MANAGEMENT,
            DependencyHierarchyNodeType.PLUGIN_MANAGEMENT,
            DependencyHierarchyNodeType.DIRECT_DEPENDENCY,
            DependencyHierarchyNodeType.DIRECT_PLUGIN,
            DependencyHierarchyNodeType.TRANSITIVE_DEPENDENCY
        )
        val compactPrefixes = mapOf(
            DependencyHierarchyNodeType.DIRECT_DEPENDENCY to "[Direct]",
            DependencyHierarchyNodeType.DIRECT_PLUGIN to "[Direct]",
            DependencyHierarchyNodeType.TRANSITIVE_DEPENDENCY to "[Transitive]"
        )

        val renderer = DependencyHierarchyTreeCellRenderer("org.example", "target-lib")
        val tree = Tree()

        for ((index, type) in types.withIndex()) {
            val node = DependencyHierarchyNode(
                type = type,
                groupId = "org.example",
                artifactId = "artifact-$index",
                version = "1.0.0",
                propertyName = if (type == DependencyHierarchyNodeType.DEPENDENCY_MANAGEMENT) "lib.version" else null,
                scope = if (type == DependencyHierarchyNodeType.TRANSITIVE_DEPENDENCY) "test" else "compile",
                isManaged = true
            )
            val treeNode = DefaultMutableTreeNode(node)
            renderer.getTreeCellRendererComponent(tree, treeNode, false, false, true, index, false)
            assertNotNull("Icon für $type darf nicht null sein", renderer.icon)
            val rendered = renderer.renderedItems
            assertTrue("Text für $type muss Koordinate enthalten", rendered.any { it.contains("artifact-$index") })
            compactPrefixes[type]?.let { prefix ->
                assertTrue(
                    "Text für $type muss das kompakte Präfix $prefix enthalten",
                    rendered.any { it.contains(prefix) }
                )
            }
        }
    }

    fun testRendererHandlesUserObjectNonHierarchyNode() {
        val renderer = DependencyHierarchyTreeCellRenderer()
        val tree = Tree()
        val treeNode = DefaultMutableTreeNode("Ein String Knoten")

        renderer.getTreeCellRendererComponent(tree, treeNode, false, false, true, 0, false)
        assertTrue(renderer.renderedItems.contains("Ein String Knoten"))
    }

    fun testRendererHighlightsTargetDependency() {
        val renderer = DependencyHierarchyTreeCellRenderer("my.group", "my-target")
        val tree = Tree()

        val targetNode = DependencyHierarchyNode(
            type = DependencyHierarchyNodeType.DIRECT_DEPENDENCY,
            groupId = "my.group",
            artifactId = "my-target",
            version = "1.0.0"
        )
        val otherNode = DependencyHierarchyNode(
            type = DependencyHierarchyNodeType.DIRECT_DEPENDENCY,
            groupId = "my.group",
            artifactId = "other-lib",
            version = "1.0.0"
        )

        renderer.getTreeCellRendererComponent(tree, DefaultMutableTreeNode(targetNode), false, false, true, 0, false)
        assertTrue(renderer.renderedItems.any { it.contains("my-target") })

        renderer.getTreeCellRendererComponent(tree, DefaultMutableTreeNode(otherNode), false, false, true, 1, false)
        assertTrue(renderer.renderedItems.any { it.contains("other-lib") })
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

        val panel = DependencyHierarchyPanel(project)
        val treeModel = panel.buildTreeModel(node)
        val tree = Tree(treeModel)
        tree.setSelectionRow(0)

        // Sollte ohne Fehler ausgeführt werden
        panel.navigateToSelectedNode(tree)
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

        val panel = DependencyHierarchyPanel(project)
        val treeModel = panel.buildTreeModel(node)
        val tree = Tree(treeModel)
        tree.setSelectionRow(0)

        // Sollte ohne Fehler ausgeführt werden
        panel.navigateToSelectedNode(tree)
    }

    fun testNavigateToSelectedNodeWithoutSelectionDoesNothing() {
        val panel = DependencyHierarchyPanel(project)
        val tree = Tree()
        tree.clearSelection()

        panel.navigateToSelectedNode(tree)
    }

    fun testCanNavigateToSelectedNode() {
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
            pomFile = psiFile.virtualFile
        )
        val projectNode = DependencyHierarchyNode(
            type = DependencyHierarchyNodeType.PROJECT,
            groupId = "com.example",
            artifactId = "demo-project",
            pomFile = psiFile.virtualFile
        )

        val panel = DependencyHierarchyPanel(project)

        val validTree = Tree(DefaultMutableTreeNode(node))
        validTree.setSelectionRow(0)
        assertTrue(panel.canNavigateToSelectedNode(validTree))

        val projectTree = Tree(DefaultMutableTreeNode(projectNode))
        projectTree.setSelectionRow(0)
        assertFalse(panel.canNavigateToSelectedNode(projectTree))

        val emptyTree = Tree()
        emptyTree.clearSelection()
        assertFalse(panel.canNavigateToSelectedNode(emptyTree))
    }

    fun testCanNavigateToTable() {
        val inTableNode = DependencyHierarchyNode(
            type = DependencyHierarchyNodeType.DIRECT_DEPENDENCY,
            groupId = "com.example",
            artifactId = "in-table",
            version = "1.0.0"
        )
        val notInTableNode = DependencyHierarchyNode(
            type = DependencyHierarchyNodeType.DIRECT_DEPENDENCY,
            groupId = "com.example",
            artifactId = "not-in-table",
            version = "1.0.0"
        )
        val projectNode = DependencyHierarchyNode(
            type = DependencyHierarchyNodeType.PROJECT,
            groupId = "com.example",
            artifactId = "my-module"
        )

        val panel = DependencyHierarchyPanel(
            project = project,
            isDependencyInTable = { g, a -> g == "com.example" && a == "in-table" }
        )

        val treeInTable = Tree(DefaultMutableTreeNode(inTableNode)).apply { setSelectionRow(0) }
        assertTrue(panel.canNavigateToTable(treeInTable))

        val treeNotInTable = Tree(DefaultMutableTreeNode(notInTableNode)).apply { setSelectionRow(0) }
        assertFalse(panel.canNavigateToTable(treeNotInTable))

        val treeProject = Tree(DefaultMutableTreeNode(projectNode)).apply { setSelectionRow(0) }
        assertFalse(panel.canNavigateToTable(treeProject))

        val emptyTree = Tree().apply { clearSelection() }
        assertFalse(panel.canNavigateToTable(emptyTree))
    }

    fun testNavigateToTableForSelectedNodeExecutesCallback() {
        var navigatedGroupId = ""
        var navigatedArtifactId = ""

        val node = DependencyHierarchyNode(
            type = DependencyHierarchyNodeType.DIRECT_DEPENDENCY,
            groupId = "org.slf4j",
            artifactId = "slf4j-api",
            version = "2.0.7"
        )

        val panel = DependencyHierarchyPanel(
            project = project,
            onNavigateToTable = { g, a ->
                navigatedGroupId = g
                navigatedArtifactId = a
                true
            }
        )

        val tree = Tree(DefaultMutableTreeNode(node)).apply { setSelectionRow(0) }
        val result = panel.navigateToTableForSelectedNode(tree)

        assertTrue(result)
        assertEquals("org.slf4j", navigatedGroupId)
        assertEquals("slf4j-api", navigatedArtifactId)
    }

    fun testTableNavigationActionLabelUsesTargetTable() {
        val dependenciesNode = DependencyHierarchyNode(
            type = DependencyHierarchyNodeType.DIRECT_DEPENDENCY,
            groupId = "com.example",
            artifactId = "direct"
        )
        val transitiveNode = DependencyHierarchyNode(
            type = DependencyHierarchyNodeType.TRANSITIVE_DEPENDENCY,
            groupId = "com.example",
            artifactId = "transitive"
        )
        val panel = DependencyHierarchyPanel(
            project = project,
            tableNavigationLabelProvider = { groupId, artifactId ->
                if (groupId == "com.example" && artifactId == "transitive") {
                    MyMessageBundle.message("dependency.hierarchy.action.navigateToTransitiveCves")
                } else {
                    MyMessageBundle.message("dependency.hierarchy.action.navigateToDependencies")
                }
            }
        )

        val dependenciesTree = Tree(DefaultMutableTreeNode(dependenciesNode)).apply { setSelectionRow(0) }
        val transitiveTree = Tree(DefaultMutableTreeNode(transitiveNode)).apply { setSelectionRow(0) }

        assertEquals(
            MyMessageBundle.message("dependency.hierarchy.action.navigateToDependencies"),
            panel.tableNavigationActionLabel(dependenciesTree)
        )
        assertEquals(
            MyMessageBundle.message("dependency.hierarchy.action.navigateToTransitiveCves"),
            panel.tableNavigationActionLabel(transitiveTree)
        )

        val transitiveLabel = MyMessageBundle.message("dependency.hierarchy.action.navigateToTransitiveCves")
        val dependenciesLabel = MyMessageBundle.message("dependency.hierarchy.action.navigateToDependencies")
        val toolbarAction = panel.createToolbar(transitiveTree).actionGroup
            .getChildren(null)
            .filterIsInstance<com.intellij.openapi.actionSystem.AnAction>()
            .first { it.templatePresentation.text == transitiveLabel }
        assertEquals(transitiveLabel, toolbarAction.templatePresentation.text)

        val contextAction = panel.createContextMenuGroup(dependenciesTree).getChildren(null)
            .filterIsInstance<com.intellij.openapi.actionSystem.AnAction>()
            .first { it.templatePresentation.text == dependenciesLabel }
        assertEquals(dependenciesLabel, contextAction.templatePresentation.text)
    }

    fun testToolbarAndContextMenuActions() {
        val rootNode = DependencyHierarchyNode(
            type = DependencyHierarchyNodeType.ROOT,
            groupId = "com.example",
            artifactId = "lib"
        )
        var closed = false
        val panel = DependencyHierarchyPanel(project, onClose = { closed = true })
        val tree = Tree(DefaultMutableTreeNode(rootNode))

        val toolbar = panel.createToolbar(tree)
        assertNotNull(toolbar)

        val contextGroup = panel.createContextMenuGroup(tree)
        assertNotNull(contextGroup)
        assertEquals(2, contextGroup.childrenCount)

        // Close action in toolbar
        val actions = panel.createToolbar(tree).actionGroup.getChildren(null)
        val closeAction = actions.filterIsInstance<com.intellij.openapi.actionSystem.AnAction>()
            .lastOrNull { it !is Separator }
        assertNotNull(closeAction)
        val event = TestActionEvent.createTestEvent()
        closeAction?.actionPerformed(event)
        assertTrue(closed)
    }

    fun testShowHierarchyUpdatesPanel() {
        myFixture.configureByText(
            "pom.xml",
            """
            <project>
                <groupId>com.example</groupId>
                <artifactId>demo</artifactId>
                <version>1.0.0</version>
                <dependencyManagement>
                    <dependencies>
                        <dependency>
                            <groupId>org.slf4j</groupId>
                            <artifactId>slf4j-api</artifactId>
                            <version>2.0.7</version>
                        </dependency>
                    </dependencies>
                </dependencyManagement>
            </project>
            """.trimIndent()
        )

        val panel = DependencyHierarchyPanel(project)
        panel.showHierarchy("org.slf4j", "slf4j-api", false)

        assertEquals("org.slf4j", panel.currentGroupId)
        assertEquals("slf4j-api", panel.currentArtifactId)
        assertFalse(panel.currentIsPlugin)
        assertNotNull(panel.tree)
        assertNotNull(panel.toolbar)
    }

    fun testShowHierarchyWithEmptyRoot() {
        val panel = DependencyHierarchyPanel(project)
        panel.showHierarchy("nonexistent.group", "nonexistent-artifact", false)

        assertEquals("nonexistent.group", panel.currentGroupId)
        assertEquals("nonexistent-artifact", panel.currentArtifactId)
        assertNotNull(panel.tree)
        assertNotNull(panel.toolbar)
    }

    fun testIsNodeInPomResolvesCorrectly() {
        val psiFile = myFixture.configureByText(
            "pom.xml",
            """
            <project>
                <parent>
                    <groupId>org.springframework.boot</groupId>
                    <artifactId>spring-boot-starter-parent</artifactId>
                    <version>3.2.0</version>
                </parent>
                <groupId>com.example</groupId>
                <artifactId>demo-app</artifactId>
                <version>1.0.0</version>
                <dependencyManagement>
                    <dependencies>
                        <dependency>
                            <groupId>org.slf4j</groupId>
                            <artifactId>slf4j-api</artifactId>
                            <version>2.0.7</version>
                        </dependency>
                    </dependencies>
                </dependencyManagement>
                <dependencies>
                    <dependency>
                        <groupId>com.example</groupId>
                        <artifactId>direct-lib</artifactId>
                        <version>1.0.0</version>
                    </dependency>
                </dependencies>
            </project>
            """.trimIndent()
        ) as XmlFile

        val panel = DependencyHierarchyPanel(project)

        val directNode = DependencyHierarchyNode(
            type = DependencyHierarchyNodeType.DIRECT_DEPENDENCY,
            groupId = "com.example",
            artifactId = "direct-lib",
            version = "1.0.0",
            pomFile = psiFile.virtualFile
        )
        assertTrue(panel.isNodeInPom(directNode))

        val parentNode = DependencyHierarchyNode(
            type = DependencyHierarchyNodeType.PARENT_POM,
            groupId = "org.springframework.boot",
            artifactId = "spring-boot-starter-parent",
            version = "3.2.0",
            pomFile = psiFile.virtualFile
        )
        assertTrue(panel.isNodeInPom(parentNode))

        val managedTransitiveNode = DependencyHierarchyNode(
            type = DependencyHierarchyNodeType.TRANSITIVE_DEPENDENCY,
            groupId = "org.slf4j",
            artifactId = "slf4j-api",
            version = "2.0.7",
            pomFile = psiFile.virtualFile
        )
        assertTrue(panel.isNodeInPom(managedTransitiveNode))

        val unmanagedTransitiveNode = DependencyHierarchyNode(
            type = DependencyHierarchyNodeType.TRANSITIVE_DEPENDENCY,
            groupId = "org.apache.commons",
            artifactId = "commons-lang3",
            version = "3.12.0",
            pomFile = psiFile.virtualFile
        )
        assertFalse(panel.isNodeInPom(unmanagedTransitiveNode))

        val notInPomNode = DependencyHierarchyNode(
            type = DependencyHierarchyNodeType.DIRECT_DEPENDENCY,
            groupId = "org.unknown",
            artifactId = "unknown-lib",
            version = "1.0.0",
            pomFile = psiFile.virtualFile
        )
        assertFalse(panel.isNodeInPom(notInPomNode))
    }

    fun testNavigateToSelectedNodeIgnoresProjectNode() {
        val psiFile = myFixture.configureByText(
            "pom.xml",
            """
            <project>
                <groupId>com.example</groupId>
                <artifactId>demo-project</artifactId>
                <version>1.0.0</version>
            </project>
            """.trimIndent()
        ) as XmlFile

        val projectNode = DependencyHierarchyNode(
            type = DependencyHierarchyNodeType.PROJECT,
            groupId = "com.example",
            artifactId = "demo-project",
            pomFile = psiFile.virtualFile
        )
        val tree = Tree(DefaultMutableTreeNode(projectNode))
        tree.setSelectionRow(0)

        val panel = DependencyHierarchyPanel(project)
        panel.navigateToSelectedNode(tree)
    }

    fun testNavigateToSelectedNodeNavigatesToManagedTransitiveDependency() {
        val psiFile = myFixture.configureByText(
            "pom.xml",
            """
            <project>
                <dependencyManagement>
                    <dependencies>
                        <dependency>
                            <groupId>org.slf4j</groupId>
                            <artifactId>slf4j-api</artifactId>
                            <version>2.0.7</version>
                        </dependency>
                    </dependencies>
                </dependencyManagement>
            </project>
            """.trimIndent()
        ) as XmlFile

        val managedTransitiveNode = DependencyHierarchyNode(
            type = DependencyHierarchyNodeType.TRANSITIVE_DEPENDENCY,
            groupId = "org.slf4j",
            artifactId = "slf4j-api",
            version = "2.0.7",
            pomFile = psiFile.virtualFile
        )
        val tree = Tree(DefaultMutableTreeNode(managedTransitiveNode))
        tree.setSelectionRow(0)

        val panel = DependencyHierarchyPanel(project)
        panel.navigateToSelectedNode(tree)
    }

    fun testShowEmptyRendersEmptyStateAndResetsCoordinates() {
        val panel = DependencyHierarchyPanel(project)
        panel.showEmpty()

        assertNull(panel.currentGroupId)
        assertNull(panel.currentArtifactId)
        assertFalse(panel.currentIsPlugin)
        assertNotNull(panel.tree)
        assertNotNull(panel.toolbar)
    }

    fun testShowEmptyWithCustomMessage() {
        val panel = DependencyHierarchyPanel(project)
        panel.showEmpty("Custom empty message")

        assertNull(panel.currentGroupId)
        assertNull(panel.currentArtifactId)
        assertFalse(panel.currentIsPlugin)
        assertNotNull(panel.tree)
    }

    fun testEscapeKeyTriggersCloseCallback() {
        var closed = false
        val panel = DependencyHierarchyPanel(project, onClose = { closed = true })
        panel.showEmpty()

        val tree = panel.tree
        assertNotNull(tree)

        val action = tree!!.getActionForKeyStroke(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0))
        assertNotNull(action)
        action.actionPerformed(java.awt.event.ActionEvent(tree, java.awt.event.ActionEvent.ACTION_PERFORMED, ""))
        assertTrue(closed)
    }

    fun testRendererHighlightsVulnerableTransitiveDependency() {
        val advisory = VulnerabilityAdvisory(
            id = "CVE-2023-9999",
            summary = "Critical vulnerability in jackson-databind",
            severity = VulnerabilitySeverity.CRITICAL,
            sources = setOf("OSV")
        )
        val advisoriesMap = mapOf("com.fasterxml.jackson.core:jackson-databind:2.15.2" to listOf(advisory))

        val renderer = DependencyHierarchyTreeCellRenderer(
            targetGroupId = "org.springframework.boot",
            targetArtifactId = "spring-boot-starter-web",
            vulnerabilityAdvisories = advisoriesMap
        )

        val vulnerableTransitiveNode = DependencyHierarchyNode(
            type = DependencyHierarchyNodeType.TRANSITIVE_DEPENDENCY,
            groupId = "com.fasterxml.jackson.core",
            artifactId = "jackson-databind",
            version = "2.15.2"
        )

        val tree = Tree()
        val treeNode = DefaultMutableTreeNode(vulnerableTransitiveNode)
        renderer.getTreeCellRendererComponent(tree, treeNode, false, false, true, 0, false)

        assertEquals(AllIcons.General.BalloonWarning, renderer.icon)
        val renderedFragments = renderer.renderedItems
        assertTrue("Muss VULNERABLE: CRITICAL enthalten", renderedFragments.any { it.contains("VULNERABLE: CRITICAL") })
        assertNotNull(renderer.toolTipText)
        assertEquals("Vulnerable transitive dependency (CRITICAL, 1 advisory/advisories).", renderer.toolTipText)
        assertFalse(renderer.toolTipText!!.contains("CVE-2023-9999"))
        assertFalse(renderer.toolTipText!!.contains("Critical vulnerability in jackson-databind"))

        val safeTransitiveNode = DependencyHierarchyNode(
            type = DependencyHierarchyNodeType.TRANSITIVE_DEPENDENCY,
            groupId = "org.slf4j",
            artifactId = "slf4j-api",
            version = "2.0.7"
        )
        val safeTreeNode = DefaultMutableTreeNode(safeTransitiveNode)
        renderer.getTreeCellRendererComponent(tree, safeTreeNode, false, false, true, 1, false)
        assertEquals(AllIcons.Nodes.Related, renderer.icon)
        val safeFragments = renderer.renderedItems
        assertFalse("Darf kein VULNERABLE enthalten", safeFragments.any { it.contains("VULNERABLE") })
    }

    fun testFindAdvisoriesWithExactAndPrefixMatch() {
        val advisory = VulnerabilityAdvisory(
            id = "CVE-2023-1111",
            summary = "Test Advisory",
            severity = VulnerabilitySeverity.HIGH,
            sources = setOf("OSV")
        )
        val advisoriesMap = mapOf("org.example:foo:1.2.3" to listOf(advisory))
        val renderer = DependencyHierarchyTreeCellRenderer(vulnerabilityAdvisories = advisoriesMap)

        val exactNode = DependencyHierarchyNode(
            type = DependencyHierarchyNodeType.TRANSITIVE_DEPENDENCY,
            groupId = "org.example",
            artifactId = "foo",
            version = "1.2.3"
        )
        assertEquals(listOf(advisory), renderer.findAdvisories(exactNode))

        val prefixNode = DependencyHierarchyNode(
            type = DependencyHierarchyNodeType.TRANSITIVE_DEPENDENCY,
            groupId = "org.example",
            artifactId = "foo",
            version = null
        )
        assertEquals(listOf(advisory), renderer.findAdvisories(prefixNode))

        val otherNode = DependencyHierarchyNode(
            type = DependencyHierarchyNodeType.TRANSITIVE_DEPENDENCY,
            groupId = "org.example",
            artifactId = "bar",
            version = "1.2.3"
        )
        assertTrue(renderer.findAdvisories(otherNode).isEmpty())
    }

    fun testPanelPassesVulnerabilityAdvisoriesToTreeRenderer() {
        val advisory = VulnerabilityAdvisory(
            id = "CVE-2023-2222",
            summary = "Vulnerability in lib",
            severity = VulnerabilitySeverity.MEDIUM,
            sources = setOf("OSS Index")
        )
        var advisoriesProviderCalled = false
        val panel = DependencyHierarchyPanel(
            project = project,
            vulnerabilityAdvisoriesProvider = {
                advisoriesProviderCalled = true
                mapOf("com.example:lib:1.0.0" to listOf(advisory))
            }
        )

        panel.showHierarchy("com.example", "lib", false)
        assertTrue(advisoriesProviderCalled)
        assertNotNull(panel.tree)
    }

    private val DependencyHierarchyTreeCellRenderer.renderedItems: List<String>
        get() = (0 until iterator().asSequence().count()).map {
            iterator().asSequence().toList()[it]
        }
}
