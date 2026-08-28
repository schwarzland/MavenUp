package de.schwarzland.mavenup.ui

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.AbstractTableCellEditor
import de.schwarzland.mavenup.model.DependencyUpdate
import de.schwarzland.mavenup.model.VulnerabilityAdvisory
import de.schwarzland.mavenup.service.MavenUpSettings
import org.apache.maven.artifact.versioning.ComparableVersion
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.RowSorter.SortKey
import javax.swing.SortOrder
import javax.swing.table.DefaultTableModel
import javax.swing.table.TableCellRenderer
import javax.swing.table.TableRowSorter

/** Spaltenindex der GroupId in der Tabelle der transitiven Sicherheitslücken. */
internal const val TRANSITIVE_GROUP_ID_COLUMN = 0

/** Spaltenindex der ArtifactId in der Tabelle der transitiven Sicherheitslücken. */
internal const val TRANSITIVE_ARTIFACT_ID_COLUMN = 1

/** Spaltenindex des Typs in der Tabelle der transitiven Sicherheitslücken. */
internal const val TRANSITIVE_TYPE_COLUMN = 2

/** Spaltenindex der Version in der Tabelle der transitiven Sicherheitslücken. */
internal const val TRANSITIVE_VERSION_COLUMN = 3

/** Spaltenindex der Sicherheitslücken-Zelle in der Tabelle der transitiven Sicherheitslücken. */
internal const val TRANSITIVE_VULNERABILITIES_COLUMN = 4

/** Spaltenindex der empfohlenen Fix-Version in der Tabelle der transitiven Sicherheitslücken. */
internal const val TRANSITIVE_RECOMMENDED_VERSION_COLUMN = 5

/** Spaltenindex der auszuwählenden neuen Version in der Tabelle der transitiven Sicherheitslücken. */
internal const val TRANSITIVE_NEW_VERSION_COLUMN = 6

/**
 * Eine Zeile der Ansicht der transitiven, verwundbaren Abhängigkeiten.
 *
 * @property groupId GroupId der transitiven Abhängigkeit.
 * @property artifactId ArtifactId der transitiven Abhängigkeit.
 * @property type Anzeigetyp der Koordinate (z. B. `managed dependency`, wenn sie bereits im
 * `dependencyManagement` der `pom.xml` deklariert ist, sonst der transitive Standardtyp).
 * @property version Aufgelöste Version der transitiven Abhängigkeit.
 * @property cell Die zusammengefassten Sicherheitswarnungen dieser Koordinate.
 * @property recommendedVersion Die empfohlene Fix-Version (niedrigste behebende Version größer als
 * [version]) oder ein leerer String, wenn keine geeignete Fix-Version bekannt ist.
 */
internal data class TransitiveVulnerabilityRow(
    val groupId: String,
    val artifactId: String,
    val type: String,
    val version: String,
    val cell: VulnerabilityCell,
    val recommendedVersion: String
)

/**
 * Ermittelt die empfohlene Fix-Version für eine transitive Koordinate.
 *
 * Betrachtet alle in den Warnungen genannten Fixed-Versionen und wählt die niedrigste Version, die
 * echt größer als die aktuell aufgelöste Version ist. So wird die geringste nötige Anhebung empfohlen,
 * die alle bekannten Fixes berücksichtigt. Versionen werden über [ComparableVersion] verglichen.
 *
 * @param advisories Die Sicherheitswarnungen der Koordinate.
 * @param currentVersion Die aktuell aufgelöste Version der transitiven Abhängigkeit.
 * @return Die empfohlene Fix-Version oder ein leerer String, wenn keine höhere Fix-Version bekannt ist.
 */
internal fun recommendedFixVersion(advisories: List<VulnerabilityAdvisory>, currentVersion: String): String {
    val current = currentVersion.takeIf { it.isNotEmpty() }?.let { ComparableVersion(it) }
    return advisories.asSequence()
        .flatMap { it.fixedVersions.asSequence() }
        .filter { it.isNotEmpty() }
        .distinct()
        .filter { current == null || ComparableVersion(it) > current }
        .minWithOrNull(compareBy { ComparableVersion(it) })
        .orEmpty()
}

/**
 * Ermittelt die anzuzeigenden Zeilen der transitiven Sicherheitslücken-Ansicht.
 *
 * Berücksichtigt ausschließlich transitive Koordinaten, für die mindestens eine Sicherheitswarnung
 * vorliegt. Die Koordinate wird in GroupId, ArtifactId und Version zerlegt; Koordinaten ohne die drei
 * erforderlichen Bestandteile werden übersprungen. Der Typ einer Koordinate wird über ihre GroupId/ArtifactId
 * in [knownTypes] nachgeschlagen: Ist die Koordinate bereits als Abhängigkeit oder im `dependencyManagement`
 * der `pom.xml` deklariert, wird der dortige Typ übernommen; andernfalls gilt [transitiveTypeLabel].
 * Die Zeilen werden absteigend nach höchstem
 * Schweregrad, dann nach Anzahl der Warnungen und schließlich alphabetisch nach GroupId/ArtifactId
 * sortiert, damit die kritischsten Funde zuerst erscheinen.
 *
 * @param advisoriesByCoordinate Zuordnung von Koordinate (`groupId:artifactId:version`) zu ihren Warnungen.
 * @param transitiveCoordinates Menge aller transitiven Koordinaten aus dem letzten Scan.
 * @param knownTypes Zuordnung von `groupId:artifactId` zum in der Haupttabelle angezeigten Typ.
 * @param transitiveTypeLabel Anzeigetyp für rein transitive Koordinaten, die nicht in der `pom.xml` deklariert sind.
 * @return Die sortierte Liste der darzustellenden Zeilen.
 */
internal fun collectTransitiveVulnerabilityRows(
    advisoriesByCoordinate: Map<String, List<VulnerabilityAdvisory>>,
    transitiveCoordinates: Set<String>,
    knownTypes: Map<String, String> = emptyMap(),
    transitiveTypeLabel: String = "transitive"
): List<TransitiveVulnerabilityRow> =
    transitiveCoordinates.asSequence()
        .mapNotNull { coordinate ->
            val advisories = advisoriesByCoordinate[coordinate]?.takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            val parts = coordinate.split(":")
            if (parts.size < 3) return@mapNotNull null
            val version = parts.drop(2).joinToString(":")
            TransitiveVulnerabilityRow(
                groupId = parts[0],
                artifactId = parts[1],
                type = knownTypes["${parts[0]}:${parts[1]}"] ?: transitiveTypeLabel,
                version = version,
                cell = VulnerabilityCell(mapOf(coordinate to advisories), emptySet()),
                recommendedVersion = recommendedFixVersion(advisories, version)
            )
        }
        .sortedWith(
            compareByDescending<TransitiveVulnerabilityRow> { worstSeverity(it.cell.allAdvisories).rank }
                .thenByDescending { it.cell.allAdvisories.size }
                .thenBy { it.groupId }
                .thenBy { it.artifactId }
        )
        .toList()

/**
 * Alternative Ansicht des MavenUp-Tool-Windows, die ausschließlich die transitiven, verwundbaren
 * Abhängigkeiten auflistet.
 *
 * Zeigt pro transitiver Koordinate GroupId, ArtifactId, Typ, aufgelöste Version, die Anzahl der
 * Sicherheitslücken (inklusive höchstem Schweregrad und farblicher Hervorhebung), die empfohlene
 * Fix-Version sowie – wie in der Haupttabelle – eine editierbare Spalte zur Auswahl einer neuen
 * Version an. Ein Klick auf die Sicherheitslücken-Zelle öffnet den Detaildialog der jeweiligen
 * Komponente. Eine in der New-Version-Spalte gewählte, von der aktuellen abweichende Version wird als
 * anstehendes Update in `dependencyManagement` gepinnt (siehe [collectPendingUpdates]).
 *
 * @param project Das zugehörige Projekt, für das der Detaildialog geöffnet wird.
 * @param onSelectionChanged Callback, der bei jeder Änderung der Versionsauswahl aufgerufen wird
 * (z. B. um die Aktionsleiste zu aktualisieren).
 */
internal class TransitiveVulnerabilitiesView(
    private val project: Project,
    private val onSelectionChanged: () -> Unit = {}
) : JBPanel<JBPanel<*>>(BorderLayout()) {

    /**
     * Zuordnung von `groupId:artifactId` zur in der New-Version-Spalte gewählten Zielversion.
     * Enthält nur bewusst gewählte Werte; eine fehlende Zuordnung bedeutet „aktuelle Version beibehalten".
     */
    internal val selectedVersions = mutableMapOf<String, String>()

    /** Zuordnung von `groupId:artifactId` zu den verfügbaren Versionen der letzten Versionssuche. */
    private var availableVersions: Map<String, List<String>> = emptyMap()

    /**
     * Optional erzwungene Zeilenhöhe. Wird gesetzt, damit die Ansicht exakt die Zeilenhöhe der
     * Haupttabelle übernimmt (JBTable würde sonst aus dem höheren ComboBox-Panel eine größere Höhe
     * ableiten). Bei jedem [update] erneut angewendet.
     */
    internal var enforcedRowHeight: Int? = null
        set(value) {
            field = value
            value?.let { table.rowHeight = it }
        }

    /** Tabellenmodell der Ansicht; nur die New-Version-Spalte ist editierbar, befüllt über [update]. */
    private val tableModel = object : DefaultTableModel() {
        override fun isCellEditable(row: Int, column: Int): Boolean =
            column == TRANSITIVE_NEW_VERSION_COLUMN &&
                (getValueAt(row, TRANSITIVE_NEW_VERSION_COLUMN) as? List<*>).orEmpty().isNotEmpty()
    }.apply {
        addColumn(MyMessageBundle.message("toolwindow.MyToolWindow.table.header.groupId"))
        addColumn(MyMessageBundle.message("toolwindow.MyToolWindow.table.header.artifactId"))
        addColumn(MyMessageBundle.message("toolwindow.MyToolWindow.table.header.type"))
        addColumn(MyMessageBundle.message("toolwindow.TransitiveVulnerabilities.table.header.version"))
        addColumn(MyMessageBundle.message("toolwindow.TransitiveVulnerabilities.table.header.vulnerabilities"))
        addColumn(MyMessageBundle.message("toolwindow.TransitiveVulnerabilities.table.header.recommendedVersion"))
        addColumn(MyMessageBundle.message("toolwindow.MyToolWindow.table.header.newVersion"))
    }

    /** Die Tabelle der transitiven, verwundbaren Abhängigkeiten. */
    internal val table: JBTable = JBTable(tableModel)

    init {
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        table.tableHeader.reorderingAllowed = false
        table.columnModel.getColumn(TRANSITIVE_VULNERABILITIES_COLUMN).cellRenderer = vulnerabilityCellRenderer()

        val textComparator = Comparator<Any?> { a, b ->
            String.CASE_INSENSITIVE_ORDER.compare(a?.toString().orEmpty(), b?.toString().orEmpty())
        }
        val sorter = object : TableRowSorter<DefaultTableModel>(tableModel) {
            /**
             * Schaltet die Sortierung einer Spalte zyklisch weiter: aufsteigend →
             * absteigend → unsortiert (ursprüngliche, nach Schweregrad vorsortierte Reihenfolge).
             *
             * @param column Modellindex der angeklickten Spalte.
             */
            override fun toggleSortOrder(column: Int) {
                if (!isSortable(column)) return
                val current = sortKeys.firstOrNull { it.column == column }?.sortOrder
                val next = when (current) {
                    SortOrder.ASCENDING -> SortOrder.DESCENDING
                    SortOrder.DESCENDING -> SortOrder.UNSORTED
                    else -> SortOrder.ASCENDING
                }
                sortKeys = if (next == SortOrder.UNSORTED) {
                    emptyList()
                } else {
                    listOf(SortKey(column, next))
                }
            }
        }
        for (column in 0 until tableModel.columnCount) {
            when (column) {
                TRANSITIVE_VERSION_COLUMN, TRANSITIVE_NEW_VERSION_COLUMN -> sorter.setSortable(column, false)
                TRANSITIVE_VULNERABILITIES_COLUMN -> {
                    sorter.setSortable(column, true)
                    sorter.setComparator(column, vulnerabilityCellComparator)
                }
                else -> {
                    sorter.setSortable(column, true)
                    sorter.setComparator(column, textComparator)
                }
            }
        }
        table.rowSorter = sorter
        installSortableHeaderRenderer(table)
        installNewVersionColumn()

        table.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (e.isPopupTrigger) showContextMenu(e)
            }

            override fun mouseReleased(e: MouseEvent) {
                if (e.isPopupTrigger) showContextMenu(e)
            }

            override fun mouseClicked(e: MouseEvent) {
                val row = table.rowAtPoint(e.point)
                val column = table.columnAtPoint(e.point)
                if (row >= 0 && column == TRANSITIVE_VULNERABILITIES_COLUMN && e.clickCount == 1) {
                    openVulnerabilityDetails(row)
                }
            }
        })

        add(JBScrollPane(table), BorderLayout.CENTER)
    }

    /**
     * Installiert Renderer und Editor der New-Version-Spalte analog zur Haupttabelle.
     *
     * Der Renderer zeigt Status-Glyph, farbcodierte Version und Tooltip; der Editor bietet eine
     * `ComboBox` mit den verfügbaren Versionen und schreibt die Auswahl in [selectedVersions].
     */
    private fun installNewVersionColumn() {
        table.columnModel.getColumn(TRANSITIVE_NEW_VERSION_COLUMN).cellRenderer =
            TableCellRenderer { tbl, value, isSelected, _, row, _ ->
                val versions = versionsOf(value)
                if (versions.isEmpty()) {
                    JLabel("")
                } else {
                    val currentVersion = tbl?.getValueAt(row, TRANSITIVE_VERSION_COLUMN) as? String ?: ""
                    val effectiveVersion = selectedVersions[keyForRow(tbl, row)] ?: currentVersion
                    buildVersionPanel(versions, currentVersion, effectiveVersion) { box ->
                        if (isSelected) box.background = tbl?.selectionBackground
                    }
                }
            }
        table.columnModel.getColumn(TRANSITIVE_NEW_VERSION_COLUMN).cellEditor = NewVersionEditor()
    }

    /**
     * Extrahiert die Versionsliste aus einem Zellwert der New-Version-Spalte.
     *
     * @param value Der Zellwert (erwartet eine `List<String>`).
     * @return Die Versionsliste oder eine leere Liste.
     */
    @Suppress("UNCHECKED_CAST")
    private fun versionsOf(value: Any?): List<String> = value as? List<String> ?: emptyList()

    /**
     * Baut das Panel der New-Version-Spalte mit Status-Glyph und ComboBox.
     *
     * @param versions Die verfügbaren Versionen.
     * @param currentVersion Die aktuell aufgelöste Version der Koordinate.
     * @param effectiveVersion Die aktuell wirksame Version (Auswahl oder aktuelle Version).
     * @param configureCombo Callback zur weiteren Konfiguration der ComboBox (z. B. Editor-Listener).
     * @return Das konfigurierte Panel.
     */
    private fun buildVersionPanel(
        versions: List<String>,
        currentVersion: String,
        effectiveVersion: String,
        configureCombo: (ComboBox<String>) -> Unit
    ): JPanel {
        val newestVersion = versions.firstOrNull() ?: ""
        val upToDate = isVersionUpToDate(effectiveVersion, newestVersion)
        val hasChange = effectiveVersion != currentVersion && effectiveVersion.isNotEmpty()
        val combo = ComboBox(versions.toTypedArray())
        if (effectiveVersion.isNotEmpty()) combo.selectedItem = effectiveVersion
        if (hasChange) combo.foreground = versionStatusColor(upToDate)
        configureCombo(combo)
        return createVersionPanel(
            combo,
            versionStatusText(upToDate),
            if (hasChange) versionStatusColor(upToDate) else null,
            versionStatusTooltip(currentVersion, effectiveVersion, newestVersion),
            hasChange
        )
    }

    /**
     * Setzt den Dropdown-Renderer der ComboBox: das Anzeigefeld übernimmt Farbe/Font der Box, und
     * die aktuelle Version wird in der aufgeklappten Liste mit Marker und Fettschrift hervorgehoben.
     *
     * @param box Die zu konfigurierende ComboBox.
     * @param currentVersion Die aktuell aufgelöste Version der Koordinate.
     */
    private fun applyDropdownRenderer(box: ComboBox<String>, currentVersion: String) {
        box.setRenderer { _, itemValue, index, _, _ ->
            JLabel(itemValue ?: "").apply {
                when {
                    index == -1 -> {
                        foreground = box.foreground
                        font = box.font
                    }
                    itemValue != null && itemValue == currentVersion -> {
                        text = versionDropdownItemText(itemValue, currentVersion)
                        font = font.deriveFont(Font.BOLD)
                    }
                }
            }
        }
    }

    /**
     * Bildet den Abhängigkeitsschlüssel `groupId:artifactId` der angegebenen Sichtzeile.
     *
     * @param tbl Die Tabelle, aus der die Werte gelesen werden.
     * @param row Der Zeilenindex in der (ggf. sortierten) Sicht.
     * @return Der Schlüssel `groupId:artifactId`.
     */
    private fun keyForRow(tbl: JTable?, row: Int): String {
        val groupId = tbl?.getValueAt(row, TRANSITIVE_GROUP_ID_COLUMN) as? String ?: ""
        val artifactId = tbl?.getValueAt(row, TRANSITIVE_ARTIFACT_ID_COLUMN) as? String ?: ""
        return "$groupId:$artifactId"
    }

    /**
     * Übernimmt eine in der New-Version-Spalte gewählte Version in [selectedVersions].
     *
     * Entspricht die gewählte Version der aktuellen Version, wird die Auswahl entfernt (kein
     * anstehendes Update); andernfalls wird sie gespeichert. In beiden Fällen wird [onSelectionChanged]
     * ausgelöst.
     *
     * @param key Der Abhängigkeitsschlüssel `groupId:artifactId`.
     * @param selectedVersion Die gewählte Zielversion.
     * @param currentVersion Die aktuell aufgelöste Version der Koordinate.
     */
    private fun applySelection(key: String, selectedVersion: String, currentVersion: String) {
        if (selectedVersion == currentVersion) {
            selectedVersions.remove(key)
        } else {
            selectedVersions[key] = selectedVersion
        }
        onSelectionChanged()
    }

    /**
     * Sammelt die anstehenden Updates der transitiven Ansicht.
     *
     * Für jede Koordinate mit einer von der aktuellen abweichenden Auswahl wird ein Update vom Typ
     * „managed dependency" erzeugt, sodass die Version beim Anwenden im `dependencyManagement` gepinnt
     * (bzw. neu angelegt) wird.
     *
     * @return Die Liste der anstehenden Pin-Updates.
     */
    internal fun collectPendingUpdates(): List<DependencyUpdate> {
        val managedType = MyMessageBundle.message(TOOLWINDOW_MY_TOOL_WINDOW_TYPE_MANAGED_DEPENDENCY)
        val currentVersions = currentVersionsByKey()
        return selectedVersions.mapNotNull { (key, newVersion) ->
            val currentVersion = currentVersions[key] ?: return@mapNotNull null
            if (newVersion.isEmpty() || newVersion == currentVersion) return@mapNotNull null
            DependencyUpdate(
                key.substringBefore(":"),
                key.substringAfter(":"),
                managedType,
                currentVersion,
                newVersion
            )
        }
    }

    /**
     * Prüft, ob die transitive Ansicht anstehende Versions-Updates enthält.
     *
     * @return `true`, wenn mindestens ein Pin-Update ansteht.
     */
    internal fun hasPendingUpdates(): Boolean = collectPendingUpdates().isNotEmpty()

    /**
     * Verwirft alle in der New-Version-Spalte getroffenen Auswahlen und aktualisiert die Ansicht.
     */
    internal fun resetSelections() {
        if (selectedVersions.isEmpty()) return
        selectedVersions.clear()
        tableModel.fireTableDataChanged()
        onSelectionChanged()
    }

    /**
     * Liefert die aktuell aufgelöste Version je Koordinatenschlüssel aus dem Tabellenmodell.
     *
     * @return Zuordnung von `groupId:artifactId` zur aktuell aufgelösten Version.
     */
    private fun currentVersionsByKey(): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for (row in 0 until tableModel.rowCount) {
            val groupId = tableModel.getValueAt(row, TRANSITIVE_GROUP_ID_COLUMN) as? String
            val artifactId = tableModel.getValueAt(row, TRANSITIVE_ARTIFACT_ID_COLUMN) as? String
            if (groupId != null && artifactId != null) {
                val version = tableModel.getValueAt(row, TRANSITIVE_VERSION_COLUMN) as? String ?: ""
                result["$groupId:$artifactId"] = version
            }
        }
        return result
    }

    /**
     * Editor der New-Version-Spalte: bietet eine `ComboBox` mit verfügbaren Versionen und übernimmt
     * die Auswahl in [selectedVersions]. Ohne Property-Synchronisation, da gepinnte transitive
     * Abhängigkeiten keine `pom.xml`-Property nutzen.
     */
    private inner class NewVersionEditor : AbstractTableCellEditor() {
        private var comboBox: ComboBox<String>? = null
        private var key: String? = null

        override fun getTableCellEditorComponent(
            tbl: JTable?, value: Any?, isSelected: Boolean, row: Int, column: Int
        ): Component {
            val currentKey = keyForRow(tbl, row)
            key = currentKey
            val versions = versionsOf(value)
            val currentVersion = tbl?.getValueAt(row, TRANSITIVE_VERSION_COLUMN) as? String ?: ""
            val effectiveVersion = selectedVersions[currentKey] ?: currentVersion
            return buildVersionPanel(versions, currentVersion, effectiveVersion) { box ->
                comboBox = box
                applyDropdownRenderer(box, currentVersion)
                box.addActionListener {
                    (box.selectedItem as? String)?.let { applySelection(currentKey, it, currentVersion) }
                }
            }
        }

        override fun getCellEditorValue(): Any? = availableVersions[key]
    }

    /**
     * Zeigt das zeilenbezogene Kontextmenü der transitiven Ansicht an.
     *
     * Angelehnt an das Kontextmenü der Haupttabelle bietet es **Open on [Browser]** (öffnet die
     * angeklickte Koordinate im konfigurierten Maven-Repository-Browser) und **Show Vulnerability
     * Details** (öffnet den Detaildialog; nur aktiv, wenn Sicherheitswarnungen vorliegen). Das Menü
     * wird über IntelliJs `ActionSystem` erzeugt, damit Theme, Abstände und Auswahlfarben der IDE
     * verwendet werden.
     *
     * @param e Das auslösende Maus-Ereignis mit der Klickposition.
     */
    private fun showContextMenu(e: MouseEvent) {
        val viewRow = table.rowAtPoint(e.point)
        if (viewRow < 0) return
        if (!table.isRowSelected(viewRow)) {
            table.setRowSelectionInterval(viewRow, viewRow)
        }
        val modelRow = table.convertRowIndexToModel(viewRow)
        val cell = tableModel.getValueAt(modelRow, TRANSITIVE_VULNERABILITIES_COLUMN) as? VulnerabilityCell

        val group = DefaultActionGroup()
        fun addAction(label: String, enabled: Boolean = true, action: () -> Unit) {
            group.add(object : AnAction(label) {
                override fun getActionUpdateThread() = ActionUpdateThread.BGT
                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = enabled
                }
                override fun actionPerformed(e: AnActionEvent) = action()
            })
        }

        val browser = MavenUpSettings.getInstance().state.repositoryBrowser
        addAction(
            MyMessageBundle.message(TOOLWINDOW_MY_TOOL_WINDOW_CONTEXT_MENU_OPEN_IN_MVN_REPOSITORY, browser.displayName)
        ) {
            openInRepository(viewRow)
        }
        val hasVulnerabilities = cell != null && cell.allAdvisories.isNotEmpty()
        group.addSeparator()
        addAction(
            MyMessageBundle.message("toolwindow.MyToolWindow.contextMenu.showVulnerabilityDetails"),
            hasVulnerabilities
        ) {
            openVulnerabilityDetails(viewRow)
        }

        ActionManager.getInstance().createActionPopupMenu(
            "MavenUp.TransitiveVulnerabilitiesTable", group
        ).component.show(e.component, e.x, e.y)
    }

    /**
     * Prüft, ob aktuell eine Zeile der transitiven Tabelle selektiert ist.
     *
     * @return `true`, wenn mindestens eine Zeile selektiert ist.
     */
    internal fun hasSelectedRow(): Boolean = table.selectedRow >= 0

    /**
     * Prüft, ob die aktuell selektierte Zeile mindestens eine Sicherheitswarnung besitzt.
     *
     * @return `true`, wenn die selektierte Zeile Sicherheitswarnungen enthält.
     */
    internal fun selectedRowHasVulnerabilities(): Boolean {
        val cell = selectedCell() ?: return false
        return cell.allAdvisories.isNotEmpty()
    }

    /**
     * Öffnet die aktuell selektierte Koordinate im konfigurierten Maven-Repository-Browser.
     */
    internal fun openSelectedInRepository() {
        val viewRow = table.selectedRow
        if (viewRow < 0) return
        openInRepository(viewRow)
    }

    /**
     * Öffnet den Vulnerability-Detaildialog für die aktuell selektierte Zeile.
     */
    internal fun openSelectedVulnerabilityDetails() {
        val viewRow = table.selectedRow
        if (viewRow < 0) return
        openVulnerabilityDetails(viewRow)
    }

    /**
     * Liefert die Sicherheitslücken-Zelle der aktuell selektierten Zeile.
     *
     * @return Die [VulnerabilityCell] der selektierten Zeile oder `null`, wenn keine Zeile selektiert ist.
     */
    private fun selectedCell(): VulnerabilityCell? {
        val viewRow = table.selectedRow
        if (viewRow < 0) return null
        val modelRow = table.convertRowIndexToModel(viewRow)
        return tableModel.getValueAt(modelRow, TRANSITIVE_VULNERABILITIES_COLUMN) as? VulnerabilityCell
    }

    /**
     * Öffnet die Koordinate der angegebenen Sichtzeile im konfigurierten Maven-Repository-Browser.
     *
     * @param viewRow Der Zeilenindex in der (ggf. sortierten) Sicht.
     */
    private fun openInRepository(viewRow: Int) {
        val modelRow = table.convertRowIndexToModel(viewRow)
        val groupId = tableModel.getValueAt(modelRow, TRANSITIVE_GROUP_ID_COLUMN) as? String ?: ""
        val artifactId = tableModel.getValueAt(modelRow, TRANSITIVE_ARTIFACT_ID_COLUMN) as? String ?: ""
        val version = tableModel.getValueAt(modelRow, TRANSITIVE_VERSION_COLUMN) as? String ?: ""
        val browser = MavenUpSettings.getInstance().state.repositoryBrowser
        BrowserUtil.browse(buildMavenRepositoryUrl(groupId, artifactId, version, browser))
    }

    /**
     * Aktualisiert die Ansicht mit den transitiven, verwundbaren Abhängigkeiten des letzten Scans.
     *
     * Getroffene Versionsauswahlen bleiben für weiterhin vorhandene Koordinaten erhalten; Auswahlen
     * für nicht mehr vorhandene Koordinaten werden verworfen.
     *
     * @param advisoriesByCoordinate Zuordnung aller Koordinaten zu ihren Warnungen.
     * @param transitiveCoordinates Menge aller transitiven Koordinaten.
     * @param knownTypes Zuordnung von `groupId:artifactId` zum in der Haupttabelle angezeigten Typ.
     * @param availableVersions Zuordnung von `groupId:artifactId` zu den verfügbaren Versionen der
     * letzten Versionssuche (befüllt die New-Version-Spalte).
     */
    fun update(
        advisoriesByCoordinate: Map<String, List<VulnerabilityAdvisory>>,
        transitiveCoordinates: Set<String>,
        knownTypes: Map<String, String> = emptyMap(),
        availableVersions: Map<String, List<String>> = emptyMap()
    ) {
        this.availableVersions = availableVersions
        val rows = collectTransitiveVulnerabilityRows(
            advisoriesByCoordinate,
            transitiveCoordinates,
            knownTypes,
            MyMessageBundle.message("toolwindow.TransitiveVulnerabilities.type.transitive")
        )
        selectedVersions.keys.retainAll(rows.map { "${it.groupId}:${it.artifactId}" }.toSet())
        tableModel.setRowCount(0)
        rows.forEach { row ->
            val key = "${row.groupId}:${row.artifactId}"
            tableModel.addRow(
                arrayOf(
                    row.groupId,
                    row.artifactId,
                    row.type,
                    row.version,
                    row.cell,
                    row.recommendedVersion,
                    availableVersions[key].orEmpty()
                )
            )
        }
        trimColumnWidthsToContent(table)
        enforcedRowHeight?.let { table.rowHeight = it }
    }

    /**
     * Öffnet den Detaildialog für die Sicherheitslücken der angeklickten Sichtzeile.
     *
     * @param viewRow Der Zeilenindex in der (ggf. sortierten) Sicht.
     */
    private fun openVulnerabilityDetails(viewRow: Int) {
        val modelRow = table.convertRowIndexToModel(viewRow)
        val cell = tableModel.getValueAt(modelRow, TRANSITIVE_VULNERABILITIES_COLUMN) as? VulnerabilityCell ?: return
        if (cell.allAdvisories.isEmpty()) return
        val coordinate = listOf(TRANSITIVE_GROUP_ID_COLUMN, TRANSITIVE_ARTIFACT_ID_COLUMN, TRANSITIVE_VERSION_COLUMN)
            .joinToString(":") { tableModel.getValueAt(modelRow, it).toString() }
        VulnerabilityDetailDialog(
            project,
            cell.detailFindings(),
            "$coordinate - ${MyMessageBundle.message(VULNERABILITY_DETAILS_TITLE)}"
        ).show()
    }
}
