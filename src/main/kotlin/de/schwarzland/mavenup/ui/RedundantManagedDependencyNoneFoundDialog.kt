package de.schwarzland.mavenup.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import javax.swing.Action
import javax.swing.JComponent

/**
 * Informationsdialog, der angezeigt wird, wenn bei der Prüfung auf redundante verwaltete
 * Abhängigkeiten keine redundanten Einträge im aktuellen Prüfumfang gefunden wurden.
 *
 * Der Dialog bietet ausreichend Platz, um den Ergebnisstatus, den gewählten Prüfumfang
 * sowie die Hinweise zu Abdeckungsgrenzen übersichtlich und ohne Scrollbalken darzustellen.
 *
 * @param project Das aktuelle IntelliJ-Projekt.
 * @property scopeDescription Die Beschreibung des durchgeführten Prüfumfangs.
 */
class RedundantManagedDependencyNoneFoundDialog(
    project: Project,
    private val scopeDescription: String = MyMessageBundle.message("redundant.managed.dependency.scope.global")
) : DialogWrapper(project) {

    init {
        title = MyMessageBundle.message("redundant.managed.dependency.dialog.title")
        setOKButtonText(MyMessageBundle.message("button.close"))
        isResizable = true
        init()
    }

    /**
     * Zeigt ausschließlich die Schließen-Schaltfläche an, da der Dialog rein informativ ist.
     */
    public override fun createActions(): Array<Action> = arrayOf(okAction)

    /**
     * Erstellt den Dialoginhalt mit Ergebnisnachricht, Prüfumfang und Abdeckungshinweisen.
     */
    public override fun createCenterPanel(): JComponent {
        return panel {
            row {
                text(MyMessageBundle.message("toolwindow.MyToolWindow.checkRedundantManaged.noneFound"))
            }
            row {
                comment(scopeDescription)
            }
            row {
                comment(MyMessageBundle.message("managed.dependency.removal.coverage.limitations"))
            }
        }.also {
            it.preferredSize = JBUI.size(680, 180)
        }
    }
}
