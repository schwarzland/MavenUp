package de.schwarzland.mavenup.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBPanel
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.BorderFactory
import javax.swing.DefaultComboBoxModel
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.event.DocumentEvent

/**
 * Filterzeile der Ansicht der transitiven, verwundbaren Abhängigkeiten.
 *
 * Optik und Verhalten entsprechen der Filterzeile der Haupttabelle: links die Auswahlfelder für
 * verfügbare Updates und anstehende Änderungen, in der Mitte das Suchfeld und rechts ein Button, der
 * alle Filter zurücksetzt. Eine Filterung nach Typ und Sicherheitslücken entfällt, da die Ansicht
 * ausschließlich verwundbare transitive Abhängigkeiten zeigt.
 *
 * @param updatesAvailable Liefert `true`, wenn der Updates-Filter bedienbar sein soll.
 * @param changesAvailable Liefert `true`, wenn der Änderungs-Filter bedienbar sein soll.
 * @param onFilterChanged Callback, der bei jeder Änderung eines Filterkriteriums aufgerufen wird.
 */
internal class TransitiveVulnerabilitiesFilterPanel(
    private val updatesAvailable: () -> Boolean,
    private val changesAvailable: () -> Boolean,
    private val onFilterChanged: () -> Unit
) : JBPanel<JBPanel<*>>(BorderLayout()) {

    /** Eingabefeld für den Textfilter über GroupId und ArtifactId. */
    internal val searchTextField = SearchTextField()

    /** Auswahlfeld für den Filter nach verfügbaren Updates (Alle, Ja, Nein). */
    internal val updatesFilterComboBox = ComboBox(TriStateFilter.entries.toTypedArray())

    /** Auswahlfeld für den Filter nach anstehenden Änderungen (Alle, Ja, Nein). */
    internal val changesFilterComboBox = ComboBox(TriStateFilter.entries.toTypedArray())

    /** Aktionsleiste am Ende der Filterzeile zum Zurücksetzen aller Filter. */
    private var resetToolbar: ActionToolbar? = null

    init {
        border = BorderFactory.createEmptyBorder(2, 4, 2, 4)
        add(buildControlsPanel(), BorderLayout.WEST)

        searchTextField.textEditor.emptyText.text =
            MyMessageBundle.message("toolwindow.TransitiveVulnerabilities.filter.search.placeholder")
        searchTextField.toolTipText =
            MyMessageBundle.message("toolwindow.TransitiveVulnerabilities.filter.search.tooltip")
        searchTextField.textEditor.toolTipText =
            MyMessageBundle.message("toolwindow.TransitiveVulnerabilities.filter.search.tooltip")
        searchTextField.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = onFilterChanged()
        })
        add(searchTextField, BorderLayout.CENTER)

        add(buildResetToolbar(), BorderLayout.EAST)
    }

    /**
     * Erstellt den linken Bereich der Filterzeile mit Updates- und Änderungs-Combobox.
     *
     * @return Die Komponente mit den beschrifteten Auswahlfeldern.
     */
    private fun buildControlsPanel(): JComponent {
        val panel = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 4, 0))

        panel.add(JLabel(MyMessageBundle.message("toolwindow.MyToolWindow.filter.updates.label")))
        updatesFilterComboBox.model = DefaultComboBoxModel(TriStateFilter.entries.toTypedArray())
        updatesFilterComboBox.selectedItem = TriStateFilter.ALL
        updatesFilterComboBox.renderer = triStateFilterRenderer(UPDATES_FILTER_LABELS)
        updatesFilterComboBox.toolTipText =
            MyMessageBundle.message("toolwindow.TransitiveVulnerabilities.filter.updates.tooltip")
        updatesFilterComboBox.isEnabled = updatesAvailable()
        updatesFilterComboBox.addActionListener { onFilterChanged() }
        panel.add(updatesFilterComboBox)

        panel.add(JLabel(MyMessageBundle.message("toolwindow.MyToolWindow.filter.changes.label")))
        changesFilterComboBox.model = DefaultComboBoxModel(TriStateFilter.entries.toTypedArray())
        changesFilterComboBox.selectedItem = TriStateFilter.ALL
        changesFilterComboBox.renderer = triStateFilterRenderer(CHANGES_FILTER_LABELS)
        changesFilterComboBox.toolTipText = MyMessageBundle.message("toolwindow.MyToolWindow.filter.changes.tooltip")
        changesFilterComboBox.isEnabled = changesAvailable()
        changesFilterComboBox.addActionListener { onFilterChanged() }
        panel.add(changesFilterComboBox)

        return panel
    }

    /**
     * Erstellt eine schmale Aktionsleiste mit einem einzelnen Icon-Button, der alle Filter der
     * Filterzeile zurücksetzt.
     *
     * Der Button ist nur aktiv, solange mindestens ein Filter aktiv ist (siehe [isResetFiltersEnabled]).
     *
     * @return Die Toolbar-Komponente mit der Reset-Aktion.
     */
    private fun buildResetToolbar(): JComponent {
        val resetTitle = MyMessageBundle.message("toolwindow.MyToolWindow.filter.reset.button")
        val resetAction = object : AnAction(resetTitle, null, AllIcons.General.Reset) {
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = isResetFiltersEnabled()
            }

            override fun actionPerformed(e: AnActionEvent) = resetAllFilters()
        }
        val group = DefaultActionGroup().apply { add(resetAction) }
        val toolbar = ActionManager.getInstance()
            .createActionToolbar("MavenUpTransitiveFilterReset", group, true)
        toolbar.targetComponent = searchTextField
        resetToolbar = toolbar
        return toolbar.component
    }

    /**
     * Liefert die aktuell eingestellten Filterkriterien.
     *
     * Typ- und Vulnerabilities-Filter bleiben bewusst inaktiv, da die Ansicht ausschließlich
     * verwundbare transitive Abhängigkeiten zeigt.
     *
     * @return Die Kriterien für [rowMatchesFilter].
     */
    internal fun criteria(): FilterCriteria = FilterCriteria(
        searchText = searchTextField.text,
        typeFilter = "",
        changesFilter = changesFilterComboBox.selectedItem as? TriStateFilter ?: TriStateFilter.ALL,
        updatesFilter = updatesFilterComboBox.selectedItem as? TriStateFilter ?: TriStateFilter.ALL
    )

    /**
     * Prüft, ob aktuell mindestens ein Filter der Filterzeile aktiv ist.
     *
     * @return `true`, wenn Suchtext, Updates- oder Änderungs-Filter von ihrem Standardwert abweichen.
     */
    internal fun isResetFiltersEnabled(): Boolean {
        val searchActive = searchTextField.text.isNotEmpty()
        val updatesActive =
            (updatesFilterComboBox.selectedItem as? TriStateFilter ?: TriStateFilter.ALL) != TriStateFilter.ALL
        val changesActive =
            (changesFilterComboBox.selectedItem as? TriStateFilter ?: TriStateFilter.ALL) != TriStateFilter.ALL
        return searchActive || updatesActive || changesActive
    }

    /**
     * Setzt alle Filter der Filterzeile auf ihren Standardwert zurück und meldet die Änderung.
     */
    internal fun resetAllFilters() {
        searchTextField.text = ""
        updatesFilterComboBox.selectedItem = TriStateFilter.ALL
        changesFilterComboBox.selectedItem = TriStateFilter.ALL
        onFilterChanged()
    }

    /**
     * Übernimmt den übergebenen Wert als alleinigen Textfilter der Filterzeile.
     *
     * Ein eventuell bereits vorhandener Suchtext wird vollständig durch [value] ersetzt.
     *
     * @param value Der zu setzende Filtertext (typischerweise eine GroupId oder ArtifactId aus dem
     *              Kontextmenü).
     */
    internal fun filterBy(value: String) {
        searchTextField.text = value
        onFilterChanged()
    }

    /**
     * Aktualisiert den Aktivierungszustand der Filter-Comboboxen.
     *
     * Ist ein Filter nicht verfügbar, wird seine Auswahl auf [TriStateFilter.ALL] zurückgesetzt, damit
     * keine unsichtbare Filterung aktiv bleibt.
     */
    internal fun updateAvailability() {
        val updatesEnabled = updatesAvailable()
        updatesFilterComboBox.isEnabled = updatesEnabled
        if (!updatesEnabled && updatesFilterComboBox.selectedItem != TriStateFilter.ALL) {
            updatesFilterComboBox.selectedItem = TriStateFilter.ALL
        }
        val changesEnabled = changesAvailable()
        changesFilterComboBox.isEnabled = changesEnabled
        if (!changesEnabled && changesFilterComboBox.selectedItem != TriStateFilter.ALL) {
            changesFilterComboBox.selectedItem = TriStateFilter.ALL
        }
    }

    /**
     * Fordert den Reset-Button auf, seinen Aktivierungszustand neu zu berechnen.
     */
    internal fun refreshResetAction() {
        resetToolbar?.updateActionsAsync()
    }
}
