package de.schwarzland.mavenup.ui

import de.schwarzland.mavenup.model.DependencyHierarchyNode
import de.schwarzland.mavenup.model.DependencyHierarchyNodeType
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreePath

/**
 * Bestimmt den initialen Aufklappzustand eines Abhängigkeitshierarchiebaums.
 *
 * Pfade zu Einträgen der Haupttabelle oder zu transitiven CVE-Funden bleiben sichtbar.
 * Ausschließlich aus nicht referenzierten transitiven Abhängigkeiten bestehende Teilbäume
 * werden eingeklappt, können aber jederzeit manuell aufgeklappt werden.
 *
 * @property isDependencyInTable Prüft, ob eine Koordinate in der Haupttabelle oder in den transitiven CVEs erscheint.
 */
internal class DependencyHierarchyExpansionPolicy(
    private val isDependencyInTable: (groupId: String, artifactId: String) -> Boolean
) {

    /**
     * Klappt den Baum zunächst vollständig ein und öffnet anschließend nur Pfade mit relevanten Knoten.
     *
     * @param tree Der zu konfigurierende Hierarchiebaum.
     */
    internal fun applyInitialExpansion(tree: JTree) {
        val root = tree.model.root as? DefaultMutableTreeNode ?: return
        val rootPath = TreePath(root.path)
        collapseSubtree(tree, root, rootPath)
        expandRelevantBranches(tree, root, rootPath)
    }

    /**
     * Klappt einen Teilbaum von den Blättern bis zur Wurzel ein.
     *
     * @param tree Der zu konfigurierende Hierarchiebaum.
     * @param treeNode Der aktuelle Baumknoten.
     * @param path Der Pfad zum aktuellen Baumknoten.
     */
    private fun collapseSubtree(tree: JTree, treeNode: DefaultMutableTreeNode, path: TreePath) {
        treeNode.children().asSequence().filterIsInstance<DefaultMutableTreeNode>().forEach { child ->
            collapseSubtree(tree, child, path.pathByAddingChild(child))
        }
        tree.collapsePath(path)
    }

    /**
     * Öffnet alle Teilpfade, die mindestens einen relevanten Knoten erreichen.
     *
     * @param tree Der zu konfigurierende Hierarchiebaum.
     * @param treeNode Der aktuelle Baumknoten.
     * @param path Der Pfad zum aktuellen Baumknoten.
     * @return `true`, wenn der Teilbaum mindestens einen relevanten Knoten enthält.
     */
    private fun expandRelevantBranches(tree: JTree, treeNode: DefaultMutableTreeNode, path: TreePath): Boolean {
        var childrenContainRelevantNode = false
        treeNode.children().asSequence().filterIsInstance<DefaultMutableTreeNode>().forEach { child ->
            if (expandRelevantBranches(tree, child, path.pathByAddingChild(child))) {
                childrenContainRelevantNode = true
            }
        }

        if (childrenContainRelevantNode) {
            tree.expandPath(path)
        }

        return isRelevant(treeNode) || childrenContainRelevantNode
    }

    /**
     * Prüft, ob ein Knoten selbst in einer Tabelle erscheint oder als nicht-transitiver Kontext sichtbar bleibt.
     *
     * @param treeNode Der zu prüfende Baumknoten.
     * @return `true`, wenn der Knoten für die initiale Ansicht relevant ist.
     */
    private fun isRelevant(treeNode: DefaultMutableTreeNode): Boolean {
        val node = treeNode.userObject as? DependencyHierarchyNode ?: return false
        return node.type != DependencyHierarchyNodeType.TRANSITIVE_DEPENDENCY ||
            isDependencyInTable(node.groupId, node.artifactId)
    }
}
