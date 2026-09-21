package de.schwarzland.mavenup.ui

import de.schwarzland.mavenup.model.DependencyHierarchyNode
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

/**
 * Erzeugt das Swing-Baummodell aus der fachlichen Abhängigkeitshierarchie.
 */
internal class DependencyHierarchyTreeModelBuilder {

    /**
     * Baut das Swing-Baummodell aus dem Hierarchieknoten-Datenmodell auf.
     *
     * @param rootData Der Wurzelknoten der Hierarchie.
     * @return Das initialisierte Baummodell.
     */
    internal fun build(rootData: DependencyHierarchyNode): DefaultTreeModel {
        val rootTreeNode = DefaultMutableTreeNode(rootData)
        populate(rootTreeNode, rootData)
        return DefaultTreeModel(rootTreeNode)
    }

    /**
     * Befüllt die Kindknoten rekursiv im Swing-Baum.
     *
     * @param parentTreeNode Der übergeordnete Swing-Baumknoten.
     * @param parentData Das zugehörige Datenmodell.
     */
    private fun populate(parentTreeNode: DefaultMutableTreeNode, parentData: DependencyHierarchyNode) {
        for (childData in parentData.children) {
            val childTreeNode = DefaultMutableTreeNode(childData)
            parentTreeNode.add(childTreeNode)
            populate(childTreeNode, childData)
        }
    }
}
