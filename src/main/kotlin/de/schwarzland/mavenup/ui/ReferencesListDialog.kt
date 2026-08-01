package de.schwarzland.mavenup.ui

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel

/**
 * Ein Dialog, der alle Referenz-Links einer Sicherheitswarnung als klickbare Liste anzeigt.
 *
 * Ein Klick auf einen Listeneintrag öffnet den entsprechenden Link im Standard-Webbrowser.
 *
 * @param project Das aktuelle IntelliJ-Projekt.
 * @param references Die sortierte Liste der Referenz-URLs.
 * @param advisoryId Die Advisory-ID, die im Dialogtitel angezeigt wird.
 */
class ReferencesListDialog(
    project: Project,
    private val references: List<String>,
    advisoryId: String
) : DialogWrapper(project) {

    init {
        title = MyMessageBundle.message("vulnerability.references.title", advisoryId)
        init()
    }

    /**
     * Erstellt den Inhaltsbereich mit einer klickbaren Link-Liste.
     */
    override fun createCenterPanel(): JComponent {
        val listModel = DefaultListModel<String>().apply {
            references.forEach { addElement(it) }
        }

        val list = JBList(listModel).apply {
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            cellRenderer = ListCellRenderer<String> { _, value, _, isSelected, _ ->
                JLabel("<html><a href=''>$value</a></html>").apply {
                    if (isSelected) {
                        isOpaque = true
                    }
                }
            }
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(event: MouseEvent) {
                    val index = locationToIndex(event.point)
                    if (index >= 0) {
                        val url = listModel.getElementAt(index)
                        if (url.isNotEmpty()) BrowserUtil.browse(url)
                    }
                }
            })
        }

        return JBPanel<JBPanel<*>>(BorderLayout()).apply {
            preferredSize = Dimension(700, 300)
            add(JBScrollPane(list), BorderLayout.CENTER)
        }
    }

    /** Zeigt nur den OK-Button (kein Cancel erforderlich). */
    override fun createActions() = arrayOf(okAction)
}
