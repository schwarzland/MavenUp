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
import de.schwarzland.mavenup.model.isFixedIn
import de.schwarzland.mavenup.service.MavenUpSettings
import org.apache.maven.artifact.versioning.ComparableVersion
import java.awt.BorderLayout
import java.awt.Component
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.RowFilter
import javax.swing.SortOrder
import javax.swing.table.DefaultTableModel
import javax.swing.table.TableCellRenderer
import javax.swing.table.TableRowSorter

/** Spaltenindex der GroupId in der Tabelle der transitiven Sicherheitslücken. */
internal const val TRANSITIVE_GROUP_ID_COLUMN = 0

/** Spaltenindex der ArtifactId in der Tabelle der transitiven Sicherheitslücken. */
internal const val TRANSITIVE_ARTIFACT_ID_COLUMN = 1

/** Spaltenindex der Sicherheitslücken-Zelle in der Tabelle der transitiven Sicherheitslücken. */
internal const val TRANSITIVE_VULNERABILITIES_COLUMN = 3

/** Spaltenindex der aktuellen Version in der Tabelle der transitiven Sicherheitslücken. */
internal const val TRANSITIVE_VERSION_COLUMN = 4

/** Spaltenindex der auszuwählenden neuen Version in der Tabelle der transitiven Sicherheitslücken. */
internal const val TRANSITIVE_NEW_VERSION_COLUMN = 5

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
 * Betrachtet alle in den Warnungen genannten Fixed-Versionen, die echt größer als die aktuell
 * aufgelöste Version sind, und wählt daraus die niedrigste Version, in der **alle** Warnungen der
 * Koordinate behoben sind (siehe [isFixedIn]). Dadurch wird weder eine Version empfohlen, die eine
 * weitere Warnung offen lässt, noch eine Version, die laut den betroffenen Versionsbereichen selbst
 * noch verwundbar ist (z. B. bei unvollständigen Fixes mit aufeinanderfolgenden Bereichen).
 * Lässt sich keine vollständig behebende Version bestimmen, wird die Version mit den meisten
 * behobenen Warnungen empfohlen; bei Gleichstand die niedrigste davon, damit kein unnötiger Sprung
 * in eine höhere Major-Linie vorgeschlagen wird. Versionen werden über [ComparableVersion] verglichen.
 *
 * @param advisories Die Sicherheitswarnungen der Koordinate.
 * @param currentVersion Die aktuell aufgelöste Version der transitiven Abhängigkeit.
 * @return Die empfohlene Fix-Version oder ein leerer String, wenn keine höhere Fix-Version bekannt ist.
 */
internal fun recommendedFixVersion(advisories: List<VulnerabilityAdvisory>, currentVersion: String): String {
    val current = currentVersion.takeIf { it.isNotEmpty() }?.let { ComparableVersion(it) }
    val candidates = advisories.asSequence()
        .flatMap { it.fixedVersions.asSequence() }
        .filter { it.isNotEmpty() }
        .distinct()
        .map { it to ComparableVersion(it) }
        .filter { (_, parsed) -> current == null || parsed > current }
        .sortedBy { it.second }
        .toList()
    if (candidates.isEmpty()) return ""
    val fullyFixing = candidates.firstOrNull { (_, parsed) -> advisories.all { it.isFixedIn(parsed) } }
    if (fullyFixing != null) return fullyFixing.first
    return candidates.maxByOrNull { (_, parsed) -> advisories.count { it.isFixedIn(parsed) } }?.first.orEmpty()
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
 * Zeigt pro transitiver Koordinate GroupId, ArtifactId, Typ, die Anzahl der Sicherheitslücken
 * (inklusive höchsten Schweregrades und farblicher Hervorhebung), die aufgelöste aktuelle Version, die
 * empfohlene Fix-Version sowie – wie in der Haupttabelle – eine editierbare Spalte zur Auswahl einer
 * neuen Version an. Ein Klick auf die Sicherheitslücken-Zelle öffnet den Detaildialog der jeweiligen
 * Komponente. Eine in der New-Version-Spalte gewählte, von der aktuellen abweichende Version wird als
 * anstehendes Update in `dependencyManagement` gepinnt (siehe [collectPendingUpdates]).
 *
 * Oberhalb der Tabelle liegt eine Filterzeile, die Optik und Verhalten der Filterzeile des Hauptfensters
 * übernimmt: Textfilter über GroupId und ArtifactId, Filter nach verfügbaren Updates und nach anstehenden
 * Änderungen sowie ein Button zum Zurücksetzen aller Filter. Eine Filterung nach Typ und Sicherheitslücken
 * entfällt, da die Ansicht ausschließlich verwundbare transitive Abhängigkeiten zeigt.
 *
 * @param project Das zugehörige Projekt, für das der Detaildialog geöffnet wird.
 * @param onSelectionChanged Callback, der bei jeder Änderung der Versionsauswahl aufgerufen wird
 * (z. B. um die Aktionsleiste zu aktualisieren).
 */
@Suppress("TooManyFunctions")
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

    /** Zuordnung von `groupId:artifactId` zur empfohlenen Fix-Version (für die Dropdown-Markierung). */
    private var recommendedByKey: Map<String, String> = emptyMap()

    /** Zuordnung von `groupId:artifactId` zu den IDs der Sicherheitswarnungen der Koordinate. */
    private var advisoryIdsByKey: Map<String, List<String>> = emptyMap()

    /**
     * Schlüssel (`groupId:artifactId`) der Koordinaten, die ausschließlich transitiv aufgelöst und
     * nicht in der `pom.xml` deklariert sind. Kennzeichnet die daraus erzeugten Updates als transitiv.
     */
    private var transitiveOnlyKeys: Set<String> = emptySet()

    /** Tabellenmodell der Ansicht; nur die New-Version-Spalte ist editierbar, befüllt über [update]. */
    private val tableModel = object : DefaultTableModel() {
        override fun isCellEditable(row: Int, column: Int): Boolean =
            column == TRANSITIVE_NEW_VERSION_COLUMN &&
                (getValueAt(row, TRANSITIVE_NEW_VERSION_COLUMN) as? List<*>).orEmpty().isNotEmpty()
    }.apply {
        addColumn(MyMessageBundle.message("toolwindow.MyToolWindow.table.header.groupId"))
        addColumn(MyMessageBundle.message("toolwindow.MyToolWindow.table.header.artifactId"))
        addColumn(MyMessageBundle.message("toolwindow.MyToolWindow.table.header.type"))
        addColumn(MyMessageBundle.message("toolwindow.MyToolWindow.table.header.vulnerabilities"))
        addColumn(MyMessageBundle.message("toolwindow.MyToolWindow.table.header.currentVersion"))
        addColumn(MyMessageBundle.message("toolwindow.MyToolWindow.table.header.newVersion"))
    }

    /** Die Tabelle der transitiven, verwundbaren Abhängigkeiten. */
    internal val table: JBTable = JBTable(tableModel)

    /** Filterzeile oberhalb der Tabelle; steuert Textfilter, Updates- und Änderungs-Filter. */
    internal val filterPanel = TransitiveVulnerabilitiesFilterPanel(
        updatesAvailable = { isBulkVersionSelectionEnabled() },
        changesAvailable = { hasPendingUpdates() },
        onFilterChanged = { applyRowFilter() }
    )

    /**
     * Row-Sorter der Tabelle, der sowohl das Filtern der Zeilen als auch das spaltenweise Sortieren
     * über die Kopfzeile übernimmt; wird im Init-Block zugewiesen.
     */
    private val rowSorter: TableRowSorter<DefaultTableModel>

    init {
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        table.tableHeader.reorderingAllowed = false
        applyRecommendedRowHeight(table)
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
        rowSorter = sorter
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

        add(filterPanel, BorderLayout.NORTH)
        add(JBScrollPane(table), BorderLayout.CENTER)
        updateEmptyText(withoutFindings = true)
        applyRowFilter()
    }

    /**
     * Wendet die aktuellen Filter (Suchtext, verfügbare Updates, anstehende Änderungen) auf die
     * Tabelle an.
     *
     * Der Textfilter wird gegen GroupId und ArtifactId geprüft; Typ- und Vulnerabilities-Filter
     * entfallen in dieser Ansicht und bleiben daher inaktiv.
     */
    internal fun applyRowFilter() {
        val criteria = filterPanel.criteria()

        rowSorter.rowFilter = object : RowFilter<DefaultTableModel, Int>() {
            override fun include(entry: Entry<out DefaultTableModel, out Int>): Boolean {
                val groupId = entry.getValue(TRANSITIVE_GROUP_ID_COLUMN)?.toString().orEmpty()
                val artifactId = entry.getValue(TRANSITIVE_ARTIFACT_ID_COLUMN)?.toString().orEmpty()
                val currentVersion = entry.getValue(TRANSITIVE_VERSION_COLUMN)?.toString().orEmpty()

                val key = "$groupId:$artifactId"
                val effectiveVersion = selectedVersions[key] ?: currentVersion
                val hasChange = effectiveVersion != currentVersion && effectiveVersion.isNotEmpty()
                val newestVersion = availableVersions[key]?.firstOrNull().orEmpty()

                return rowMatchesFilter(
                    FilterRow(
                        groupId = groupId,
                        artifactId = artifactId,
                        property = "",
                        type = "",
                        hasChange = hasChange,
                        hasUpdate = hasNewerVersion(currentVersion, newestVersion)
                    ),
                    criteria
                )
            }
        }
        filterPanel.refreshResetAction()
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
     * Setzt den gemeinsamen Dropdown-Renderer der ComboBox (siehe [applyVersionDropdownRenderer]).
     *
     * @param box Die zu konfigurierende ComboBox.
     * @param currentVersion Die aktuell aufgelöste Version der Koordinate.
     * @param recommendedVersion Die empfohlene Fix-Version (oder leer, wenn keine vorliegt).
     */
    private fun applyDropdownRenderer(box: ComboBox<String>, currentVersion: String, recommendedVersion: String) {
        applyVersionDropdownRenderer(box, currentVersion, recommendedVersion)
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
        filterPanel.updateAvailability()
    }

    /**
     * Sammelt die anstehenden Updates der transitiven Ansicht.
     *
     * Für jede Koordinate mit einer von der aktuellen abweichenden Auswahl wird ein Update vom Typ
     * „managed dependency" erzeugt, sodass die Version beim Anwenden im `dependencyManagement` gepinnt
     * (bzw. neu angelegt) wird. Koordinaten, die bislang ausschließlich transitiv aufgelöst werden,
     * werden zusätzlich als transitiv markiert (siehe [DependencyUpdate.transitive]).
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
                newVersion,
                advisoryIdsByKey[key].orEmpty(),
                transitive = key in transitiveOnlyKeys
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
        finishSelectionReset()
    }

    /**
     * Verwirft die Auswahlen der aktuell sichtbaren (nicht ausgefilterten) Koordinaten und
     * aktualisiert die Ansicht.
     *
     * Durch einen aktiven Filter ausgeblendete Auswahlen bleiben erhalten.
     */
    internal fun resetVisibleSelections() {
        if (selectedVersions.isEmpty()) return
        val visibleKeys = currentVersionsByKey(visibleOnly = true).keys
        if (!selectedVersions.keys.removeAll(visibleKeys)) return
        finishSelectionReset()
    }

    /**
     * Aktualisiert Tabellenmodell, Änderungs-Filter und Zeilenfilter nach dem Verwerfen von Auswahlen.
     */
    private fun finishSelectionReset() {
        tableModel.fireTableDataChanged()
        onSelectionChanged()
        filterPanel.updateAvailability()
        applyRowFilter()
    }

    /**
     * Prüft, ob für mindestens eine transitive Koordinate auswählbare Versionen vorliegen.
     *
     * @return `true`, wenn wenigstens eine dargestellte Koordinate abrufbare Versionen besitzt.
     */
    internal fun isBulkVersionSelectionEnabled(): Boolean =
        currentVersionsByKey().keys.any { availableVersions[it]?.isNotEmpty() == true }

    /**
     * Prüft, ob für mindestens eine transitive Koordinate eine empfohlene Fix-Version vorliegt.
     *
     * @return `true`, wenn wenigstens eine dargestellte Koordinate eine empfohlene Fix-Version besitzt.
     */
    internal fun hasRecommendedVersions(): Boolean =
        currentVersionsByKey().keys.any { recommendedByKey[it]?.isNotEmpty() == true }

    /**
     * Prüft, ob für die per [key] identifizierte Koordinate auswählbare Versionen vorliegen.
     *
     * @param key Der Schlüssel (`groupId:artifactId`) der Koordinate.
     * @return `true`, wenn abrufbare Versionen vorliegen.
     */
    internal fun hasSelectableVersionsForDependency(key: String): Boolean =
        availableVersions[key]?.isNotEmpty() == true

    /**
     * Prüft, ob für die per [key] identifizierte Koordinate eine empfohlene Fix-Version vorliegt.
     *
     * @param key Der Schlüssel (`groupId:artifactId`) der Koordinate.
     * @return `true`, wenn eine empfohlene Fix-Version bekannt ist.
     */
    internal fun hasRecommendedVersionForDependency(key: String): Boolean =
        recommendedByKey[key]?.isNotEmpty() == true

    /**
     * Prüft, ob für die per [key] identifizierte Koordinate eine abweichende Version ausgewählt wurde.
     *
     * @param key Der Schlüssel (`groupId:artifactId`) der Koordinate.
     * @return `true`, wenn für die Koordinate eine Auswahl vorliegt, die zurückgesetzt werden kann.
     */
    internal fun isVersionResetEnabledForDependency(key: String): Boolean =
        selectedVersions.containsKey(key)

    /**
     * Wählt für die aktuell sichtbaren transitiven Koordinaten die höchste verfügbare Version
     * (über alle Major-Linien hinweg) aus.
     */
    internal fun selectHighestMajorVersionForAll() {
        applyBulkSelection { _, versions, _ -> versions.firstOrNull().orEmpty() }
    }

    /**
     * Wählt für die aktuell sichtbaren transitiven Koordinaten die höchste Version innerhalb derselben
     * Major-Linie wie die aktuell aufgelöste Version aus.
     */
    internal fun selectHighestMinorVersionForAll() {
        applyBulkSelection { current, versions, _ -> latestVersionWithinSameMajor(current, versions) ?: current }
    }

    /**
     * Wählt für die aktuell sichtbaren transitiven Koordinaten die empfohlene Fix-Version aus.
     *
     * Koordinaten ohne empfohlene Fix-Version bleiben unverändert.
     */
    internal fun selectRecommendedVersionForAll() {
        applyBulkSelection { current, _, recommended -> recommended.ifEmpty { current } }
    }

    /**
     * Wählt für die per [key] identifizierte Koordinate die höchste verfügbare Version aus.
     *
     * @param key Der Schlüssel (`groupId:artifactId`) der Koordinate.
     */
    internal fun selectHighestMajorVersionForDependency(key: String) {
        applySingleSelection(key) { _, versions, _ -> versions.firstOrNull().orEmpty() }
    }

    /**
     * Wählt für die per [key] identifizierte Koordinate die höchste Version derselben Major-Linie aus.
     *
     * @param key Der Schlüssel (`groupId:artifactId`) der Koordinate.
     */
    internal fun selectHighestMinorVersionForDependency(key: String) {
        applySingleSelection(key) { current, versions, _ -> latestVersionWithinSameMajor(current, versions) ?: current }
    }

    /**
     * Wählt für die per [key] identifizierte Koordinate die empfohlene Fix-Version aus.
     *
     * @param key Der Schlüssel (`groupId:artifactId`) der Koordinate.
     */
    internal fun selectRecommendedVersionForDependency(key: String) {
        applySingleSelection(key) { current, _, recommended -> recommended.ifEmpty { current } }
    }

    /**
     * Setzt die per [key] identifizierte Koordinate auf ihre aktuell aufgelöste Version zurück und
     * verwirft damit eine zuvor getroffene Auswahl.
     *
     * @param key Der Schlüssel (`groupId:artifactId`) der Koordinate.
     */
    internal fun resetVersionForDependency(key: String) {
        if (selectedVersions.remove(key) == null) return
        finishSelectionChange()
    }

    /**
     * Wendet eine Auswahlstrategie auf die aktuell sichtbaren Koordinaten an und aktualisiert die Ansicht.
     *
     * Durch einen aktiven Filter ausgeblendete Zeilen bleiben – wie in der Haupttabelle – unverändert.
     *
     * @param chooser Funktion, die aus aktueller Version, verfügbaren Versionen und empfohlener
     * Fix-Version die Zielversion ermittelt.
     */
    private fun applyBulkSelection(chooser: (String, List<String>, String) -> String) {
        var changed = false
        for ((key, currentVersion) in currentVersionsByKey(visibleOnly = true)) {
            if (applySelectionForKey(key, currentVersion, chooser)) changed = true
        }
        if (changed) finishSelectionChange()
    }

    /**
     * Wendet eine Auswahlstrategie auf eine einzelne Koordinate an und aktualisiert die Ansicht.
     *
     * Ist die Koordinate nicht (mehr) dargestellt, geschieht nichts.
     *
     * @param key Der Schlüssel (`groupId:artifactId`) der Koordinate.
     * @param chooser Funktion, die aus aktueller Version, verfügbaren Versionen und empfohlener
     * Fix-Version die Zielversion ermittelt.
     */
    private fun applySingleSelection(key: String, chooser: (String, List<String>, String) -> String) {
        val currentVersion = currentVersionsByKey()[key] ?: return
        if (applySelectionForKey(key, currentVersion, chooser)) finishSelectionChange()
    }

    /**
     * Ermittelt die Zielversion einer Koordinate über [chooser] und übernimmt sie in [selectedVersions].
     *
     * Entspricht die ermittelte Version der aktuellen Version (oder ist sie leer), wird eine bestehende
     * Auswahl entfernt. Die Methode aktualisiert die Ansicht nicht selbst.
     *
     * @param key Der Schlüssel (`groupId:artifactId`) der Koordinate.
     * @param currentVersion Die aktuell aufgelöste Version der Koordinate.
     * @param chooser Funktion, die aus aktueller Version, verfügbaren Versionen und empfohlener
     * Fix-Version die Zielversion ermittelt.
     * @return `true`, wenn sich die Auswahl der Koordinate geändert hat.
     */
    private fun applySelectionForKey(
        key: String,
        currentVersion: String,
        chooser: (String, List<String>, String) -> String
    ): Boolean {
        val versions = availableVersions[key].orEmpty()
        val recommended = recommendedByKey[key].orEmpty()
        val chosen = chooser(currentVersion, versions, recommended)
        val previous = selectedVersions[key]
        return if (chosen.isNotEmpty() && chosen != currentVersion) {
            if (previous == chosen) false else { selectedVersions[key] = chosen; true }
        } else {
            if (previous == null) false else { selectedVersions.remove(key); true }
        }
    }

    /**
     * Bricht eine laufende Zellbearbeitung ab, benachrichtigt das Tabellenmodell über die geänderten
     * Auswahlen und meldet die Änderung über [onSelectionChanged].
     */
    private fun finishSelectionChange() {
        if (table.isEditing) table.cellEditor?.cancelCellEditing()
        tableModel.fireTableDataChanged()
        applyRecommendedRowHeight(table)
        onSelectionChanged()
        filterPanel.updateAvailability()
        applyRowFilter()
    }

    /**
     * Liefert die aktuell aufgelöste Version je Koordinatenschlüssel aus dem Tabellenmodell.
     *
     * @param visibleOnly Wenn `true`, werden nur aktuell sichtbare (nicht ausgefilterte) Zeilen
     *   berücksichtigt; ansonsten alle Zeilen des Modells.
     * @return Zuordnung von `groupId:artifactId` zur aktuell aufgelösten Version.
     */
    private fun currentVersionsByKey(visibleOnly: Boolean = false): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val modelRows = if (visibleOnly) {
            (0 until table.rowCount).map { table.convertRowIndexToModel(it) }
        } else {
            (0 until tableModel.rowCount).toList()
        }
        for (row in modelRows) {
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
     * Prüft, ob der aktive Zeilenfilter aktuell Einträge ausblendet.
     *
     * @return `true`, wenn weniger Zeilen sichtbar sind als das Tabellenmodell enthält.
     */
    internal fun isRowFilterHidingEntries(): Boolean = table.rowCount < tableModel.rowCount

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
                applyDropdownRenderer(box, currentVersion, recommendedByKey[currentKey].orEmpty())
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
     * Angelehnt an das Kontextmenü der Haupttabelle bietet es **Filter by "…"** (übernimmt den Wert der
     * angeklickten GroupId- oder ArtifactId-Zelle als Textfilter), **Open on `<Browser>`** (öffnet die
     * angeklickte Koordinate im konfigurierten Maven-Repository-Browser), die Versionsauswahl-Aktionen
     * **Set Highest Major Version**, **Set Highest Minor Version** und **Set Recommended Version**, ein
     * **Reset to Current Version** (nur aktiv, wenn eine abweichende Version gewählt wurde) sowie **Show
     * Vulnerability Details** (öffnet den Detaildialog; nur aktiv, wenn Sicherheitswarnungen vorliegen).
     * Das Menü wird über IntelliJs `ActionSystem` erzeugt, damit Theme, Abstände und Auswahlfarben der IDE
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
        val filterValue = when (table.columnAtPoint(e.point)) {
            TRANSITIVE_GROUP_ID_COLUMN -> tableModel.getValueAt(modelRow, TRANSITIVE_GROUP_ID_COLUMN) as? String ?: ""
            TRANSITIVE_ARTIFACT_ID_COLUMN ->
                tableModel.getValueAt(modelRow, TRANSITIVE_ARTIFACT_ID_COLUMN) as? String ?: ""
            else -> ""
        }
        if (filterValue.isNotBlank()) {
            addAction(
                MyMessageBundle.message("toolwindow.MyToolWindow.contextMenu.filterBy", filterValue)
            ) {
                filterPanel.filterBy(filterValue)
            }
            group.addSeparator()
        }
        addAction(
            MyMessageBundle.message(TOOLWINDOW_MY_TOOL_WINDOW_CONTEXT_MENU_OPEN_IN_MVN_REPOSITORY, browser.displayName)
        ) {
            openInRepository(viewRow)
        }
        val key = "${tableModel.getValueAt(modelRow, TRANSITIVE_GROUP_ID_COLUMN)}:" +
            "${tableModel.getValueAt(modelRow, TRANSITIVE_ARTIFACT_ID_COLUMN)}"
        group.addSeparator()
        addAction(
            MyMessageBundle.message("toolwindow.MyToolWindow.contextMenu.selectHighestMajor"),
            hasSelectableVersionsForDependency(key)
        ) {
            selectHighestMajorVersionForDependency(key)
        }
        addAction(
            MyMessageBundle.message("toolwindow.MyToolWindow.contextMenu.selectHighestMinor"),
            hasSelectableVersionsForDependency(key)
        ) {
            selectHighestMinorVersionForDependency(key)
        }
        addAction(
            MyMessageBundle.message("toolwindow.MyToolWindow.contextMenu.selectRecommended"),
            hasRecommendedVersionForDependency(key)
        ) {
            selectRecommendedVersionForDependency(key)
        }
        addAction(
            MyMessageBundle.message("toolwindow.MyToolWindow.contextMenu.resetToCurrent"),
            isVersionResetEnabledForDependency(key)
        ) {
            resetVersionForDependency(key)
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
        val transitiveTypeLabel = MyMessageBundle.message("toolwindow.TransitiveVulnerabilities.type.transitive")
        val rows = collectTransitiveVulnerabilityRows(
            advisoriesByCoordinate,
            transitiveCoordinates,
            knownTypes,
            transitiveTypeLabel
        )
        transitiveOnlyKeys = rows
            .filter { it.type == transitiveTypeLabel }
            .map { "${it.groupId}:${it.artifactId}" }
            .toSet()
        recommendedByKey = rows
            .filter { it.recommendedVersion.isNotEmpty() }
            .associate { "${it.groupId}:${it.artifactId}" to it.recommendedVersion }
        advisoryIdsByKey = rows.associate { row ->
            "${row.groupId}:${row.artifactId}" to row.cell.allAdvisories.map { it.id }.distinct()
        }
        selectedVersions.keys.retainAll(rows.map { "${it.groupId}:${it.artifactId}" }.toSet())
        tableModel.setRowCount(0)
        rows.forEach { row ->
            val key = "${row.groupId}:${row.artifactId}"
            tableModel.addRow(
                arrayOf(
                    row.groupId,
                    row.artifactId,
                    row.type,
                    row.cell,
                    row.version,
                    availableVersions[key].orEmpty()
                )
            )
        }
        trimColumnWidthsToContent(table)
        applyRecommendedRowHeight(table)
        updateEmptyText(rows.isEmpty())
        filterPanel.updateAvailability()
        applyRowFilter()
    }

    /**
     * Setzt den Empty-State-Text der Tabelle passend zur Ursache der leeren Ansicht.
     *
     * Der Tab bleibt gemäß den JetBrains-UI-Guidelines stets auswählbar; statt ihn zu deaktivieren,
     * erklärt dieser Text im Tab-Inhalt, warum keine Einträge vorliegen.
     *
     * @param withoutFindings `true`, wenn überhaupt keine transitiven Funde vorliegen, `false`, wenn
     * lediglich der aktive Filter alle Zeilen ausblendet.
     */
    private fun updateEmptyText(withoutFindings: Boolean) {
        table.emptyText.text = if (withoutFindings) {
            MyMessageBundle.message("toolwindow.TransitiveVulnerabilities.emptyText.noScan")
        } else {
            MyMessageBundle.message("toolwindow.TransitiveVulnerabilities.emptyText.noMatches")
        }
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
        val key = listOf(TRANSITIVE_GROUP_ID_COLUMN, TRANSITIVE_ARTIFACT_ID_COLUMN)
            .joinToString(":") { tableModel.getValueAt(modelRow, it).toString() }
        // Die Ansicht listet ausschließlich transitiv eingebundene Komponenten; ist die Koordinate
        // zusätzlich in einer pom.xml deklariert, wird das in der Origin-Spalte kenntlich gemacht.
        val origin = if (key in transitiveOnlyKeys) {
            VulnerabilityOrigin.TRANSITIVE
        } else {
            VulnerabilityOrigin.TRANSITIVE_DECLARED
        }
        VulnerabilityDetailDialog(
            project,
            cell.detailFindings(),
            "$coordinate - ${MyMessageBundle.message(VULNERABILITY_DETAILS_TITLE)}",
            mapOf(coordinate to origin)
        ).show()
    }
}
