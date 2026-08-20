package de.schwarzland.mavenup.ui

import de.schwarzland.mavenup.model.DependencyUpdate
import de.schwarzland.mavenup.model.VulnerabilityAdvisory
import de.schwarzland.mavenup.model.VulnerabilitySeverity
import de.schwarzland.mavenup.service.DependencyApiService
import de.schwarzland.mavenup.service.MavenRepositoryBrowser
import de.schwarzland.mavenup.service.MavenUpSettings
import de.schwarzland.mavenup.service.MAVEN_UP_SETTINGS_TOPIC
import de.schwarzland.mavenup.service.OssIndexApiService
import de.schwarzland.mavenup.service.OssIndexAuthenticationException
import de.schwarzland.mavenup.service.OssIndexCredentialService
import de.schwarzland.mavenup.service.VersionAutoSelectionMode
import de.schwarzland.mavenup.service.VulnerabilityApiService
import de.schwarzland.mavenup.service.VulnerabilityMerger
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.table.JBTable
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.AbstractTableCellEditor
import com.intellij.icons.AllIcons
import org.apache.maven.artifact.versioning.ComparableVersion
import org.jetbrains.idea.maven.project.MavenImportListener
import org.jetbrains.idea.maven.project.MavenProject
import org.jetbrains.idea.maven.project.MavenProjectsManager
import org.jetbrains.idea.maven.model.MavenArtifactNode
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.table.DefaultTableModel
import javax.swing.table.TableCellRenderer
import javax.swing.table.TableRowSorter


private const val PARENT_TYPE = "parent"
private const val MANAGED_PLUGIN = "managed plugin"
private const val TOOLWINDOW_MY_TOOL_WINDOW_TYPE_MANAGED_DEPENDENCY = "toolwindow.MyToolWindow.type.managedDependency"
private const val GROUP_ID_COLUMN = 0
private const val ARTIFACT_ID_COLUMN = 1
private const val PROPERTY_COLUMN = 2
private const val TYPE_COLUMN = 3
private const val CURRENT_VERSION_COLUMN = 4
private const val VULNERABILITIES_COLUMN = 5
private const val NEW_VERSION_COLUMN = 6
private val LOG = Logger.getInstance(MavenUpWindowFactory::class.java)

/** Farbe für Abhängigkeiten, die bereits auf der neuesten Version sind (Light-/Dark-Mode). */
private val VERSION_UP_TO_DATE_COLOR = com.intellij.ui.JBColor(Color(0, 128, 0), Color(80, 200, 80))

/** Farbe für Abhängigkeiten, für die ein Update verfügbar ist (Light-/Dark-Mode). */
private val VERSION_UPDATE_AVAILABLE_COLOR = com.intellij.ui.JBColor(Color(204, 120, 0), Color(255, 180, 50))

/** Glyph für Abhängigkeiten auf der neuesten Version (grüner Haken „✓"). */
private const val VERSION_UP_TO_DATE_GLYPH = "\u2713"

/** Glyph für Abhängigkeiten mit verfügbarem Update (Pfeil nach oben „↑"). */
private const val VERSION_UPDATE_AVAILABLE_GLYPH = "\u2191"

/**
 * Bestimmt, ob die angegebene Version der höchsten bekannten Version entspricht.
 *
 * @param version Die zu prüfende Version (typischerweise die ausgewählte Version).
 * @param newestVersion Die höchste bekannte Version (erstes Element der Versionsliste).
 * @return `true`, wenn die Version der neuesten entspricht und nicht leer ist.
 */
internal fun isVersionUpToDate(version: String, newestVersion: String): Boolean =
    version == newestVersion && version.isNotEmpty()

/**
 * Filteroption für dreiwertige Filterkriterien (Alle, Ja, Nein) in der Filterzeile.
 *
 * @property labelKey Der Schlüssel des lokalisierten Anzeigetextes im Message-Bundle.
 */
internal enum class TriStateFilter(val labelKey: String) {
    /** Alle Einträge anzeigen (keine Filterung nach diesem Kriterium). */
    ALL("toolwindow.MyToolWindow.filter.option.all"),

    /** Nur Einträge anzeigen, die das Kriterium erfüllen. */
    YES("toolwindow.MyToolWindow.filter.option.yes"),

    /** Nur Einträge anzeigen, die das Kriterium nicht erfüllen. */
    NO("toolwindow.MyToolWindow.filter.option.no");

    /**
     * Liefert die lokalisierte Bezeichnung der Filteroption.
     */
    val label: String
        get() = MyMessageBundle.message(labelKey)

    override fun toString(): String = label
}

/**
 * Fasst die filterrelevanten Werte einer einzelnen Tabellenzeile zusammen.
 *
 * @property groupId Die GroupId der Zeile.
 * @property artifactId Die ArtifactId der Zeile.
 * @property property Der Property-Name der Zeile.
 * @property type Der Typ der Zeile.
 * @property hasChange `true`, wenn für die Zeile eine Versionsänderung vorliegt.
 * @property hasVulnerabilities `true`, wenn für die Zeile mindestens eine Sicherheitslücke gemeldet ist.
 */
internal data class FilterRow(
    val groupId: String,
    val artifactId: String,
    val property: String,
    val type: String,
    val hasChange: Boolean = false,
    val hasVulnerabilities: Boolean = false
)

/**
 * Fasst die aktiven Filterkriterien der Haupttabelle zusammen.
 *
 * @property searchText Der eingegebene Suchtext (wird getrimmt und case-insensitiv verglichen).
 * @property typeFilter Der ausgewählte Typ oder ein leerer String für "alle Typen".
 * @property changesFilter Die ausgewählte Filteroption für Änderungen (Alle, Ja, Nein).
 * @property vulnerabilitiesFilter Die ausgewählte Filteroption für Sicherheitslücken (Alle, Ja, Nein).
 */
internal data class FilterCriteria(
    val searchText: String,
    val typeFilter: String,
    val changesFilter: TriStateFilter = TriStateFilter.ALL,
    val vulnerabilitiesFilter: TriStateFilter = TriStateFilter.ALL
)

/**
 * Prüft, ob eine Tabellenzeile den aktuellen Filterkriterien entspricht.
 *
 * Der Textfilter wird case-insensitiv gegen GroupId, ArtifactId und Property geprüft;
 * die Zeile passt, sobald einer dieser Werte den Suchtext enthält. Ein leerer Suchtext
 * lässt alle Zeilen zu. Der Typfilter passt bei leerem Wert auf jeden Typ, sonst nur bei
 * exakter Übereinstimmung des Typs. Der Änderungs- und Vulnerabilities-Filter prüfen,
 * ob Änderungen bzw. Sicherheitslücken vorliegen (`YES`), nicht vorliegen (`NO`) oder
 * der Filter inaktiv ist (`ALL`).
 *
 * @param row Die filterrelevanten Werte der zu prüfenden Zeile.
 * @param criteria Die aktuell aktiven Filterkriterien.
 * @return `true`, wenn die Zeile allen aktiven Filterkriterien entspricht.
 */
internal fun rowMatchesFilter(row: FilterRow, criteria: FilterCriteria): Boolean {
    val needle = criteria.searchText.trim().lowercase()
    val textMatches = needle.isEmpty() ||
        row.groupId.lowercase().contains(needle) ||
        row.artifactId.lowercase().contains(needle) ||
        row.property.lowercase().contains(needle)
    val typeMatches = criteria.typeFilter.isEmpty() || row.type == criteria.typeFilter
    val changesMatches = when (criteria.changesFilter) {
        TriStateFilter.ALL -> true
        TriStateFilter.YES -> row.hasChange
        TriStateFilter.NO -> !row.hasChange
    }
    val vulnerabilitiesMatches = when (criteria.vulnerabilitiesFilter) {
        TriStateFilter.ALL -> true
        TriStateFilter.YES -> row.hasVulnerabilities
        TriStateFilter.NO -> !row.hasVulnerabilities
    }
    return textMatches && typeMatches && changesMatches && vulnerabilitiesMatches
}

/**
 * Liefert das passende Status-Glyph für die Versionsanzeige.
 *
 * Anders als ein IntelliJ-Icon lässt sich ein Text-Glyph über die Vordergrundfarbe
 * des Labels einfärben und kann so dieselbe Farbe wie die Versionsnummer annehmen.
 *
 * @param upToDate `true`, wenn die ausgewählte Version die neueste ist.
 * @return Ein grüner Haken bei neuestem Stand, ein Pfeil nach oben sonst.
 */
internal fun versionStatusText(upToDate: Boolean): String =
    if (upToDate) VERSION_UP_TO_DATE_GLYPH else VERSION_UPDATE_AVAILABLE_GLYPH

/**
 * Liefert die passende Textfarbe für die Versionsanzeige.
 *
 * @param upToDate `true`, wenn die ausgewählte Version die neueste ist.
 * @return Grün bei neuestem Stand, Orange sonst.
 */
internal fun versionStatusColor(upToDate: Boolean): Color =
    if (upToDate) VERSION_UP_TO_DATE_COLOR else VERSION_UPDATE_AVAILABLE_COLOR

/**
 * Ermittelt den Anzeigetext für einen Eintrag der Versions-Dropdown-Liste.
 *
 * Entspricht [value] der aktuell verwendeten Version [currentVersion], wird der Eintrag mit
 * einem lokalisierten Marker („(current)") versehen, damit die aktuelle Version – insbesondere
 * bei aktivierter Option „alle Versionen anzeigen" – in der Liste hervorsticht. Andernfalls wird
 * der unveränderte Versionswert zurückgegeben.
 *
 * @param value Der Versionswert des Dropdown-Eintrags.
 * @param currentVersion Die aktuell verwendete Version der Abhängigkeit.
 * @return Der ggf. mit Marker versehene Anzeigetext.
 */
internal fun versionDropdownItemText(value: String, currentVersion: String): String =
    if (currentVersion.isNotEmpty() && value == currentVersion) {
        MyMessageBundle.message("toolwindow.MyToolWindow.version.currentMarker", value)
    } else {
        value
    }

/**
 * Erzeugt den lokalisierten Tooltip-Text für die Versionszelle.
 *
 * Berücksichtigt vier Zustände:
 * 1. Ausgewählt == Aktuell == Neueste → "Up to date"
 * 2. Ausgewählt == Aktuell ≠ Neueste → "Update available"
 * 3. Ausgewählt ≠ Aktuell, Ausgewählt == Neueste → "Will update (to newest)"
 * 4. Ausgewählt ≠ Aktuell, Ausgewählt ≠ Neueste → "Will update (not latest)"
 *
 * @param currentVersion Die aktuell im Projekt verwendete Version.
 * @param selectedVersion Die vom Benutzer ausgewählte Zielversion.
 * @param newestVersion Die höchste bekannte Version.
 * @return Ein beschreibender Tooltip-Text.
 */
internal fun versionStatusTooltip(currentVersion: String, selectedVersion: String, newestVersion: String): String {
    val hasChange = selectedVersion != currentVersion && selectedVersion.isNotEmpty()
    val selectedIsNewest = isVersionUpToDate(selectedVersion, newestVersion)
    return when {
        !hasChange && selectedIsNewest ->
            MyMessageBundle.message("toolwindow.MyToolWindow.version.upToDate", currentVersion)
        !hasChange ->
            MyMessageBundle.message("toolwindow.MyToolWindow.version.updateAvailable", currentVersion, newestVersion)
        selectedIsNewest ->
            MyMessageBundle.message("toolwindow.MyToolWindow.version.willUpdate", currentVersion, selectedVersion)
        else ->
            MyMessageBundle.message("toolwindow.MyToolWindow.version.willUpdateNotLatest", currentVersion, selectedVersion, newestVersion)
    }
}

/**
 * Erstellt ein JPanel mit Status-Glyph und ComboBox für die Versionsanzeige in der Tabelle.
 * Bei einer ausstehenden Änderung (ausgewählte ≠ aktuelle Version) wird die ComboBox-Schrift fett dargestellt.
 * Das Status-Glyph wird fett gerendert und, sofern [statusColor] gesetzt ist, in dieser Farbe
 * eingefärbt – so nimmt der Pfeil dieselbe Farbe wie die Versionsnummer an.
 *
 * @param combo Die ComboBox mit den verfügbaren Versionen.
 * @param statusText Das Status-Glyph (grüner Haken oder Pfeil nach oben).
 * @param statusColor Die Farbe für das Status-Glyph, oder `null` für die Standardfarbe.
 * @param tooltip Der Tooltip-Text für das Panel.
 * @param hasChange `true`, wenn die ausgewählte Version von der aktuellen abweicht.
 * @return Ein konfiguriertes JPanel mit Status-Glyph links und ComboBox in der Mitte.
 */
internal fun createVersionPanel(
    combo: JComponent,
    statusText: String,
    statusColor: Color?,
    tooltip: String,
    hasChange: Boolean = false
): JPanel =
    JPanel(BorderLayout(2, 0)).apply {
        isOpaque = false
        val statusLabel = JLabel(statusText).apply {
            border = BorderFactory.createEmptyBorder(0, 2, 0, 0)
            font = font.deriveFont(java.awt.Font.BOLD)
            if (statusColor != null) {
                foreground = statusColor
            }
        }
        add(statusLabel, BorderLayout.WEST)
        if (hasChange) {
            combo.font = combo.font.deriveFont(java.awt.Font.BOLD)
        }
        add(combo, BorderLayout.CENTER)
        toolTipText = tooltip
    }

/**
 * Erstellt die URL zur Repository-Browser-Seite für eine gegebene Abhängigkeit und Version.
 * Verwendet den konfigurierten [MavenRepositoryBrowser]; standard ist [MavenRepositoryBrowser.MVN_REPOSITORY].
 */
internal fun buildMavenRepositoryUrl(
    groupId: String,
    artifactId: String,
    version: String,
    browser: MavenRepositoryBrowser = MavenRepositoryBrowser.MVN_REPOSITORY
): String = browser.urlFor(groupId, artifactId, version)

/**
 * Repräsentiert eine einzelne Zeile in der Abhängigkeitstabelle.
 */
internal data class RefreshRow(
    val groupId: String,
    val artifactId: String,
    val propertyName: String,
    val type: String,
    val currentVersion: String
) {
    val key: String = "$groupId:$artifactId"
}

/**
 * Enthält einen Schnappschuss aller relevanten Projektdaten für die Anzeige im Tool Window.
 */
internal data class RefreshSnapshot(
    val rows: List<RefreshRow>,
    val dependencyProperties: Map<String, String>
)

/**
 * Modell für die Darstellung von Sicherheitslücken in einer Tabellenzelle.
 * Kapselt die Zuordnung von Koordinaten zu Warnungen und unterscheidet zwischen direkten und transitiven Funden.
 */
internal data class VulnerabilityCell(
    val advisoriesByCoordinate: Map<String, List<VulnerabilityAdvisory>>,
    val transitiveCoordinates: Set<String>
) {
    val allAdvisories: List<VulnerabilityAdvisory>
        get() = advisoriesByCoordinate.values.flatten()

    val transitiveAdvisoryCount: Int
        get() = advisoriesByCoordinate
            .filterKeys { it in transitiveCoordinates }
            .values
            .sumOf { it.size }

    fun detailFindings(): Map<String, List<VulnerabilityAdvisory>> =
        advisoriesByCoordinate.mapKeys { (coordinate, _) ->
            if (coordinate in transitiveCoordinates) "$coordinate (transitive)" else coordinate
        }
}

/**
 * Erstellt ein [VulnerabilityCell]-Objekt für eine spezifische Abhängigkeit.
 * Kombiniert die Warnungen für die direkte Abhängigkeit mit denen ihrer transitiven Kinder.
 */
internal fun buildVulnerabilityCell(
    directCoordinate: String,
    vulnerabilityAdvisories: Map<String, List<VulnerabilityAdvisory>>,
    transitiveCoordinates: Set<String>
): VulnerabilityCell {
    val findings = linkedMapOf<String, List<VulnerabilityAdvisory>>()
    vulnerabilityAdvisories[directCoordinate]
        ?.takeIf { it.isNotEmpty() }
        ?.let { findings[directCoordinate] = it }
    transitiveCoordinates.sorted().forEach { coordinate ->
        vulnerabilityAdvisories[coordinate]
            ?.takeIf { it.isNotEmpty() }
            ?.let { findings[coordinate] = it }
    }
    return VulnerabilityCell(findings, transitiveCoordinates)
}

/**
 * Erstellt einen kurzen Zusammenfassungstext für die Anzeige in der Tabellenzelle.
 * Zeigt die Anzahl der Warnungen, den schlimmsten Schweregrad und (falls vorhanden) transitive Funde an.
 */
internal fun vulnerabilitySummary(cell: VulnerabilityCell): String {
    val advisories = cell.allAdvisories
    if (advisories.isEmpty()) return ""
    val severity = worstSeverity(advisories)
    val attributes = buildList {
        if (cell.transitiveAdvisoryCount > 0) add("${cell.transitiveAdvisoryCount} transitive")
        if (severity != VulnerabilitySeverity.UNKNOWN) add(severity.name)
    }
    return if (attributes.isEmpty()) {
        advisories.size.toString()
    } else {
        "${advisories.size} (${attributes.joinToString()})"
    }
}

private fun worstSeverity(advisories: List<VulnerabilityAdvisory>): VulnerabilitySeverity =
    advisories.maxByOrNull { it.severity.rank }?.severity ?: VulnerabilitySeverity.UNKNOWN

private data class VulnerabilityScanTargets(
    val dependencies: Set<Triple<String, String, String>>,
    val transitiveCoordinates: Set<String>,
    val transitiveDependenciesByDirect: Map<String, Set<String>>
)

private fun artifactNodeCoordinate(node: MavenArtifactNode): Triple<String, String, String>? {
    val artifact = node.artifact
    val version = artifact.version.orEmpty()
    return if (artifact.groupId.isNotEmpty() && artifact.artifactId.isNotEmpty() && version.isNotEmpty()) {
        Triple(artifact.groupId, artifact.artifactId, version)
    } else {
        null
    }
}

private fun coordinateString(coordinate: Triple<String, String, String>): String =
    "${coordinate.first}:${coordinate.second}:${coordinate.third}"

/**
 * Prüft, ob eine Sicherheitsprüfung aktuell durchgeführt werden kann.
 */
internal fun canCheckVulnerabilities(isRefreshing: Boolean, isUpdating: Boolean): Boolean {
    return !isRefreshing && !isUpdating
}


private const val VULNERABILITY_DETAILS_TITLE = "vulnerability.details.title"

private const val TOOLWINDOW_MY_TOOL_WINDOW_CONTEXT_MENU_OPEN_IN_MVN_REPOSITORY = "toolwindow.MyToolWindow.contextMenu.openInMvnRepository"

/**
 * -----------------------------------------------------------------------------------------------
 * Factory-Klasse zur Erstellung und Initialisierung des MavenUp Tool Windows in der IntelliJ-IDE.
 *
 * Diese Klasse registriert das Tool Window und bettet das [MyToolWindow]-Panel ein, welches die
 * Hauptoberfläche für die Maven-Abhängigkeitsverwaltung bereitstellt.
 */
class MavenUpWindowFactory : ToolWindowFactory {
    /**
     * Bestimmt, ob das Tool Window für das aktuelle Projekt verfügbar sein soll.
     * Es wird nur angezeigt, wenn Maven-Projekte im Projekt konfiguriert sind.
     */
    override fun shouldBeAvailable(project: Project): Boolean {
        return MavenProjectsManager.getInstance(project).hasProjects()
    }

    /**
     * Erstellt den Inhalt des Tool Windows und fügt ihn dem ContentManager hinzu.
     */
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val myToolWindow = MyToolWindow(project)

        val content = ContentFactory
            .getInstance()
            .createContent(myToolWindow.getContent(), null, false)
        content.setDisposer(myToolWindow)

        toolWindow.contentManager.addContent(content)
    }

    /**
     * Dialog zur Bestätigung der ausgewählten Updates. Zeigt eine Tabelle mit den
     * durchzuführenden Änderungen (Gruppe, Artefakt, Version alt/neu).
     */
    class UpdateConfirmationDialog(
        project: Project,
        private val updates: List<DependencyUpdate>
    ) : DialogWrapper(project) {
        private val syncMavenCheckbox: JCheckBox = JCheckBox(
            MyMessageBundle.message("toolwindow.MyToolWindow.update.confirm.syncMaven")
        ).apply {
            isSelected = MavenUpSettings.getInstance().state.syncMavenAfterUpdate
        }

        init {
            title = MyMessageBundle.message("toolwindow.MyToolWindow.update.confirm.title")
            init()
        }

        /**
         * Gibt zurück, ob der Benutzer die Option "Sync Maven Changes" ausgewählt hat.
         */
        fun isSyncMavenSelected(): Boolean = syncMavenCheckbox.isSelected

        /**
         * Erstellt den zentralen Bereich des Dialogs mit der Update-Übersichtstabelle und der Sync-Option.
         */
        override fun createCenterPanel(): JComponent {
            val panel = JBPanel<JBPanel<*>>(BorderLayout())
            panel.preferredSize = java.awt.Dimension(600, 450)
            
            val topPanel = JBPanel<JBPanel<*>>(BorderLayout())
            topPanel.add(
                JLabel(MyMessageBundle.message("toolwindow.MyToolWindow.update.confirm.message")),
                BorderLayout.NORTH
            )
            topPanel.border = BorderFactory.createEmptyBorder(0, 0, 10, 0)
            panel.add(topPanel, BorderLayout.NORTH)

            panel.add(JBScrollPane(buildTable()), BorderLayout.CENTER)

            val bottomPanel = JBPanel<JBPanel<*>>(BorderLayout())
            bottomPanel.border = BorderFactory.createEmptyBorder(10, 0, 0, 0)
            bottomPanel.add(syncMavenCheckbox, BorderLayout.WEST)
            panel.add(bottomPanel, BorderLayout.SOUTH)

            return panel
        }

        /**
         * Erstellt die schreibgeschützte Update-Übersichtstabelle mit Einzelselektion.
         *
         * @return Die konfigurierte, nicht editierbare Tabelle mit allen anstehenden Updates.
         */
        internal fun buildTable(): JBTable {
            val tableModel = object : DefaultTableModel() {
                override fun isCellEditable(row: Int, column: Int): Boolean = false
            }.apply {
                addColumn(MyMessageBundle.message("toolwindow.MyToolWindow.table.header.groupId"))
                addColumn(MyMessageBundle.message("toolwindow.MyToolWindow.table.header.artifactId"))
                addColumn(MyMessageBundle.message("toolwindow.MyToolWindow.table.header.type"))
                addColumn(MyMessageBundle.message("toolwindow.MyToolWindow.table.header.currentVersion"))
                addColumn(MyMessageBundle.message("toolwindow.MyToolWindow.table.header.newVersion"))
            }

            updates.forEach { update ->
                tableModel.addRow(
                    arrayOf(
                        update.groupId,
                        update.artifactId,
                        update.type,
                        update.oldVersion,
                        update.newVersion
                    )
                )
            }

            val table = JBTable(tableModel)
            table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
            table.tableHeader.reorderingAllowed = false
            return table
        }

    }

    /**
     * Die eigentliche Tool-Window-Komponente, die die Tabelle der Abhängigkeiten und die Aktions-Buttons verwaltet.
     */
    internal inner class MyToolWindow(private val project: Project) : Disposable {
        private val vulnerabilityApiService = VulnerabilityApiService()
        private val ossIndexApiService = OssIndexApiService()
        private val ossIndexCredentialService = OssIndexCredentialService()
        private val dependencyApiService = DependencyApiService(project)
        private val availableVersions = mutableMapOf<String, List<String>>()
        private val selectedVersions = mutableMapOf<String, String>()
        private val dependencyToProperty = mutableMapOf<String, String>()
        private val knownDependencies = mutableMapOf<String, String>() // key to current version
        private val knownTypes = mutableMapOf<String, String>()
        private val vulnerabilityAdvisories = mutableMapOf<String, List<VulnerabilityAdvisory>>()
        private val transitiveCoordinates = mutableSetOf<String>()
        private val transitiveDependenciesByDirect = mutableMapOf<String, Set<String>>()
        private var isUpdating = false
        private var isRefreshing = false
        private var refreshGeneration = 0

        /**
         * Zuletzt bekannter Wert der Auto-Selektionsstrategie.
         *
         * Dient dazu, bei einer Einstellungsänderung nur dann die "New Version"-Auswahl
         * neu zu berechnen, wenn sich die Strategie tatsächlich geändert hat.
         */
        private var lastVersionAutoSelectionMode =
            MavenUpSettings.getInstance().state.versionAutoSelectionMode

        /** Die Tabelle der Abhängigkeiten; wird im Property-Initializer von [content] zugewiesen. */
        private var table: JBTable

        /** Die obere Aktionsleiste des Tool-Windows; wird im Init-Block initialisiert. */
        private var actionToolbar: ActionToolbar? = null

        /** Die Aktionsgruppe der oberen Aktionsleiste; wird im Init-Block befüllt. */
        private val toolbarGroup = DefaultActionGroup()

        /** Eingabefeld für den Textfilter über GroupId, ArtifactId und Property. */
        internal val searchTextField = SearchTextField()

        /** Auswahlfeld für den Typfilter der Tabelle. */
        internal val typeFilterComboBox = ComboBox<String>()

        /** Auswahlfeld für den Filter nach anstehenden Änderungen (Ja/Nein/Alle). */
        internal val changesFilterComboBox = ComboBox(TriStateFilter.entries.toTypedArray())

        /** Auswahlfeld für den Filter nach Sicherheitslücken (Ja/Nein/Alle). */
        internal val vulnerabilitiesFilterComboBox = ComboBox(TriStateFilter.entries.toTypedArray())

        /** Anzeigetext der Combobox-Option, die alle Typen zulässt. */
        private val allTypesFilterLabel =
            MyMessageBundle.message("toolwindow.MyToolWindow.filter.type.all")

        /** Container für Aktionsleiste und Filterzeile im Nordbereich. */
        private val topPanel = JBPanel<JBPanel<*>>(BorderLayout())

        /** Aktionsleiste am Ende der Filterzeile zum Zurücksetzen aller Filter. */
        private var filterResetToolbar: ActionToolbar? = null

        /**
         * Row-Sorter der Tabelle, der sowohl das Filtern der Zeilen als auch das
         * spaltenweise Sortieren über die Kopfzeile übernimmt.
         */
        private var tableRowSorter: TableRowSorter<DefaultTableModel>

        private val content = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            val tableModel = object : DefaultTableModel() {
                override fun isCellEditable(row: Int, column: Int): Boolean = column == NEW_VERSION_COLUMN
            }.apply {
                addColumn(MyMessageBundle.message("toolwindow.MyToolWindow.table.header.groupId"))
                addColumn(MyMessageBundle.message("toolwindow.MyToolWindow.table.header.artifactId"))
                addColumn(MyMessageBundle.message("toolwindow.MyToolWindow.table.header.property"))
                addColumn(MyMessageBundle.message("toolwindow.MyToolWindow.table.header.type"))
                addColumn(MyMessageBundle.message("toolwindow.MyToolWindow.table.header.currentVersion"))
                addColumn(MyMessageBundle.message("toolwindow.MyToolWindow.table.header.vulnerabilities"))
                addColumn(MyMessageBundle.message("toolwindow.MyToolWindow.table.header.newVersion"))
            }

            table = object : JBTable(tableModel) {
                override fun getToolTipText(e: MouseEvent): String? {
                    val row = rowAtPoint(e.point)
                    val column = columnAtPoint(e.point)
                    if (row < 0 || column == VULNERABILITIES_COLUMN) return super.getToolTipText(e)
                    if (column == NEW_VERSION_COLUMN) {
                        @Suppress("UNCHECKED_CAST")
                        val versions = getValueAt(row, NEW_VERSION_COLUMN) as? List<String> ?: emptyList()
                        if (versions.isEmpty()) return null
                        val groupId = getValueAt(row, GROUP_ID_COLUMN) as? String ?: ""
                        val artifactId = getValueAt(row, ARTIFACT_ID_COLUMN) as? String ?: ""
                        val currentVersion = getValueAt(row, CURRENT_VERSION_COLUMN) as? String ?: ""
                        val newestVersion = versions.firstOrNull() ?: ""
                        val effectiveVersion = selectedVersions["$groupId:$artifactId"] ?: currentVersion
                        return versionStatusTooltip(currentVersion, effectiveVersion, newestVersion)
                    }
                    val settings = MavenUpSettings.getInstance()
                    return if (settings.state.jumpOnSingleClick)
                        MyMessageBundle.message("toolwindow.MyToolWindow.table.row.tooltip.singleClick")
                    else
                        MyMessageBundle.message("toolwindow.MyToolWindow.table.row.tooltip.doubleClick")
                }
            }
            table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
            table.tableHeader.reorderingAllowed = false

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
                    if (row < 0) return
                    if (column == VULNERABILITIES_COLUMN && e.clickCount == 1) {
                        val cell = table.getValueAt(row, VULNERABILITIES_COLUMN) as? VulnerabilityCell
                        if (cell != null && cell.allAdvisories.isNotEmpty()) {
                            val coordinate = listOf(GROUP_ID_COLUMN, ARTIFACT_ID_COLUMN, CURRENT_VERSION_COLUMN)
                                .joinToString(":") { table.getValueAt(row, it).toString() }
                            VulnerabilityDetailDialog(
                                project,
                                cell.detailFindings(),
                                "$coordinate - ${MyMessageBundle.message(VULNERABILITY_DETAILS_TITLE)}"
                            ).show()
                        }
                        return
                    }

                    val settings = MavenUpSettings.getInstance()
                    val requiredClickCount = if (settings.state.jumpOnSingleClick) 1 else 2

                    if (e.clickCount == requiredClickCount) {
                        val groupId = table.getValueAt(row, GROUP_ID_COLUMN) as? String ?: ""
                        val artifactId = table.getValueAt(row, ARTIFACT_ID_COLUMN) as? String ?: ""
                        val type = table.getValueAt(row, TYPE_COLUMN) as? String ?: "dependency"

                        navigateToDependency(groupId, artifactId, type)
                    }
                }

                private fun showContextMenu(e: MouseEvent) {
                    val row = table.rowAtPoint(e.point)
                    if (row < 0) return
                    if (!table.isRowSelected(row)) {
                        table.setRowSelectionInterval(row, row)
                    }
                    val groupId = table.getValueAt(row, GROUP_ID_COLUMN) as? String ?: ""
                    val artifactId = table.getValueAt(row, ARTIFACT_ID_COLUMN) as? String ?: ""
                    val type = table.getValueAt(row, TYPE_COLUMN) as? String ?: "dependency"
                    val currentVersion = table.getValueAt(row, CURRENT_VERSION_COLUMN) as? String ?: ""
                    val vulnerabilityCell = table.getValueAt(row, VULNERABILITIES_COLUMN) as? VulnerabilityCell

                    val popup = JPopupMenu()
                    popup.add(JMenuItem(MyMessageBundle.message("toolwindow.MyToolWindow.contextMenu.navigateToPom")).apply {
                        addActionListener { navigateToDependency(groupId, artifactId, type) }
                    })
                    val browserName = MavenUpSettings.getInstance().state.repositoryBrowser.displayName
                    popup.add(JMenuItem(MyMessageBundle.message(
                        TOOLWINDOW_MY_TOOL_WINDOW_CONTEXT_MENU_OPEN_IN_MVN_REPOSITORY, browserName)).apply {
                        addActionListener { openInMavenRepository(groupId, artifactId, currentVersion) }
                    })
                    if (vulnerabilityCell != null && vulnerabilityCell.allAdvisories.isNotEmpty()) {
                        popup.addSeparator()
                        popup.add(JMenuItem(MyMessageBundle.message("toolwindow.MyToolWindow.contextMenu.showVulnerabilityDetails")).apply {
                            addActionListener {
                                val coordinate = "$groupId:$artifactId:$currentVersion"
                                VulnerabilityDetailDialog(
                                    project,
                                    vulnerabilityCell.detailFindings(),
                                    "$coordinate - ${MyMessageBundle.message(VULNERABILITY_DETAILS_TITLE)}"
                                ).show()
                            }
                        })
                    }
                    popup.show(e.component, e.x, e.y)
                }
            })

            // Force commit editor when focus lost
            table.putClientProperty("terminateEditOnFocusLost", true)

            table.selectionModel.addListSelectionListener { event ->
                if (!event.valueIsAdjusting) {
                    refreshToolbar()
                }
            }

            tableRowSorter = object : TableRowSorter<DefaultTableModel>(tableModel) {
                /**
                 * Schaltet die Sortierung einer Spalte zyklisch weiter: aufsteigend →
                 * absteigend → unsortiert (Reihenfolge wie in der pom.xml).
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
            val textComparator = Comparator<Any?> { a, b ->
                String.CASE_INSENSITIVE_ORDER.compare(a?.toString().orEmpty(), b?.toString().orEmpty())
            }
            for (columnIndex in 0 until tableModel.columnCount) {
                when (columnIndex) {
                    CURRENT_VERSION_COLUMN, VULNERABILITIES_COLUMN, NEW_VERSION_COLUMN ->
                        tableRowSorter.setSortable(columnIndex, false)
                    else -> {
                        tableRowSorter.setSortable(columnIndex, true)
                        tableRowSorter.setComparator(columnIndex, textComparator)
                    }
                }
            }
            table.rowSorter = tableRowSorter
            applyRowFilter()

            // Custom Renderer and Editor for the "New Version" column
            table.columnModel.getColumn(NEW_VERSION_COLUMN).cellRenderer =
                TableCellRenderer { table, value, isSelected, _, row, _ ->
                    val groupId = table?.getValueAt(row, GROUP_ID_COLUMN) as? String ?: ""
                    val artifactId = table?.getValueAt(row, ARTIFACT_ID_COLUMN) as? String ?: ""
                    val key = "$groupId:$artifactId"

                    @Suppress("UNCHECKED_CAST")
                    val versions = value as? List<String> ?: emptyList()
                    if (versions.isEmpty()) return@TableCellRenderer JLabel("")

                    val selectedVersion = selectedVersions[key]
                    val currentVersion = table?.getValueAt(row, CURRENT_VERSION_COLUMN) as? String ?: ""
                    val newestVersion = versions.firstOrNull() ?: ""
                    val effectiveVersion = selectedVersion ?: currentVersion
                    val upToDate = isVersionUpToDate(effectiveVersion, newestVersion)
                    val hasChange = effectiveVersion != currentVersion && effectiveVersion.isNotEmpty()

                    val combo = ComboBox(versions.toTypedArray()).apply {
                        if (effectiveVersion.isNotEmpty()) {
                            selectedItem = effectiveVersion
                        }
                        if (hasChange) {
                            foreground = versionStatusColor(upToDate)
                        }

                        if (isSelected) {
                            background = table?.selectionBackground
                        }
                    }

                    createVersionPanel(
                        combo,
                        versionStatusText(upToDate),
                        if (hasChange) versionStatusColor(upToDate) else null,
                        versionStatusTooltip(currentVersion, effectiveVersion, newestVersion),
                        hasChange
                    )
                }

            table.columnModel.getColumn(NEW_VERSION_COLUMN).cellEditor = object : AbstractTableCellEditor() {
                private var currentComboBox: ComboBox<String>? = null
                private var currentKey: String? = null
                private var editorPanel: JPanel? = null

                override fun getTableCellEditorComponent(
                    table: JTable?, value: Any?, isSelected: Boolean, row: Int, column: Int
                ): Component {
                    val groupId = table?.getValueAt(row, GROUP_ID_COLUMN) as? String ?: ""
                    val artifactId = table?.getValueAt(row, ARTIFACT_ID_COLUMN) as? String ?: ""
                    currentKey = "$groupId:$artifactId"

                    @Suppress("UNCHECKED_CAST")
                    val versions = value as? List<String> ?: emptyList()
                    val combo = ComboBox(versions.toTypedArray())

                    val currentVersion = table?.getValueAt(row, CURRENT_VERSION_COLUMN) as? String ?: ""
                    val newestVersion = versions.firstOrNull() ?: ""

                    val selectedVersion = if (currentKey != null) selectedVersions[currentKey!!] else null
                    val effectiveVersion = selectedVersion ?: currentVersion
                    if (effectiveVersion.isNotEmpty()) {
                        combo.selectedItem = effectiveVersion
                    }

                    val upToDate = isVersionUpToDate(effectiveVersion, newestVersion)
                    val hasChange = effectiveVersion != currentVersion && effectiveVersion.isNotEmpty()
                    if (hasChange) {
                        combo.foreground = versionStatusColor(upToDate)
                    }

                    // Custom-Renderer, der Farbe und Font nur im Anzeigefeld übernimmt (nicht im Dropdown)
                    // und die aktuelle Version in der Dropdown-Liste hervorhebt.
                    combo.setRenderer { _, value, index, _, _ ->
                        JLabel(value ?: "").apply {
                            if (index == -1) {
                                foreground = combo.foreground
                                font = combo.font
                            } else if (value != null && value == currentVersion) {
                                text = versionDropdownItemText(value, currentVersion)
                                font = font.deriveFont(java.awt.Font.BOLD)
                            }
                        }
                    }

                    combo.addActionListener {
                        val selected = combo.selectedItem as? String
                        val key = currentKey
                        if (key != null && selected != null) {
                            synchronizePropertyVersions(key, selected)
                        }
                        updateUpdateButtonState()
                        updateEditorVisuals(combo, editorPanel, selected, currentVersion, newestVersion)
                        applyRowFilter()
                    }

                    currentComboBox = combo
                    val panel = createVersionPanel(
                        combo,
                        versionStatusText(upToDate),
                        if (hasChange) versionStatusColor(upToDate) else null,
                        versionStatusTooltip(currentVersion, effectiveVersion, newestVersion),
                        hasChange
                    )
                    editorPanel = panel
                    return panel
                }

                override fun getCellEditorValue(): Any? {
                    val selected = currentComboBox?.selectedItem as? String
                    if (currentKey != null && selected != null) {
                        synchronizePropertyVersions(currentKey!!, selected)
                    }
                    updateUpdateButtonState()
                    val groupId = currentKey?.substringBefore(":")
                    val artifactId = currentKey?.substringAfter(":")
                    return availableVersions["$groupId:$artifactId"]
                }
            }

            table.columnModel.getColumn(VULNERABILITIES_COLUMN).cellRenderer =
                TableCellRenderer { currentTable, value, isSelected, _, _, _ ->
                    val cell = value as? VulnerabilityCell ?: VulnerabilityCell(emptyMap(), emptySet())
                    val advisories = cell.allAdvisories
                    JLabel(vulnerabilitySummary(cell)).apply {
                        isOpaque = true
                        border = BorderFactory.createEmptyBorder(0, 6, 0, 6)
                        if (advisories.isNotEmpty()) {
                            icon = AllIcons.Ide.Link
                            horizontalTextPosition = JLabel.TRAILING
                            toolTipText = MyMessageBundle.message(VULNERABILITY_DETAILS_TITLE)
                        } else {
                            toolTipText = null
                        }
                        background = if (isSelected) {
                            currentTable.selectionBackground
                        } else {
                            vulnerabilityColor(worstSeverity(advisories), currentTable.background)
                        }
                        foreground = if (isSelected) currentTable.selectionForeground else currentTable.foreground
                    }
                }

            fun refreshAction(checkUpdates: Boolean, clearData: Boolean, clearVulnerabilities: Boolean) {
                if (isUpdating) return

                val generation = ++refreshGeneration
                isRefreshing = true
                refreshToolbar()
                cancelActiveCellEditing()
                tableModel.setRowCount(0)
                if (clearData) {
                    availableVersions.clear()
                    selectedVersions.clear()
                }
                if (clearVulnerabilities) {
                    vulnerabilityAdvisories.clear()
                    transitiveCoordinates.clear()
                    transitiveDependenciesByDirect.clear()
                }
                dependencyToProperty.clear()
                knownDependencies.clear()
                knownTypes.clear()
                updateUpdateButtonState()

                val managedDependencyType =
                    MyMessageBundle.message(TOOLWINDOW_MY_TOOL_WINDOW_TYPE_MANAGED_DEPENDENCY)
                ReadAction.nonBlocking<RefreshSnapshot> {
                    collectRefreshSnapshot(managedDependencyType)
                }.expireWith(this@MyToolWindow)
                    .finishOnUiThread(ModalityState.any()) { snapshot ->
                        if (generation != refreshGeneration) {
                            return@finishOnUiThread
                        }

                        dependencyToProperty.putAll(snapshot.dependencyProperties)
                        snapshot.rows.forEach { row ->
                            knownDependencies[row.key] = row.currentVersion
                            knownTypes[row.key] = row.type
                            tableModel.addRow(
                                arrayOf(
                                    row.groupId,
                                    row.artifactId,
                                    row.propertyName,
                                    row.type,
                                    row.currentVersion,
                                    buildVulnerabilityCell(
                                        "${row.key}:${row.currentVersion}",
                                        vulnerabilityAdvisories,
                                        transitiveDependenciesByDirect["${row.key}:${row.currentVersion}"].orEmpty()
                                    ),
                                    availableVersions[row.key].orEmpty()
                                )
                            )
                        }
                        updateUpdateButtonState()
                        updateTypeFilterOptions()

                        if (checkUpdates) {
                            performUpdateCheck {
                                refreshAction(false, false, false)
                            }
                        }
                        isRefreshing = false
                        refreshToolbar()
                    }
                    .submit(AppExecutorUtil.getAppExecutorService())
            }


            val updateAction = {
                if (!isUpdating && selectedVersions.isNotEmpty()) {
                    val updates = collectSelectedUpdates()

                    if (updates.isNotEmpty()) {
                        val dialog = UpdateConfirmationDialog(project, updates)
                        if (dialog.showAndGet()) {
                            val shouldSyncMaven = dialog.isSyncMavenSelected()
                            MavenUpSettings.getInstance().state.syncMavenAfterUpdate = shouldSyncMaven
                            ProgressManager.getInstance().run(object : Task.Backgroundable(
                                project,
                                MyMessageBundle.message("toolwindow.MyToolWindow.update.progress"),
                                true
                            ) {
                                override fun run(indicator: ProgressIndicator) {
                                    val mavenManager = MavenProjectsManager.getInstance(project)
                                    val pomFiles = mavenManager.projects.map { it.file }
                                    mavenManager.projects.forEach { mavenProject ->
                                        applyUpdateToPom(mavenProject, updates)
                                    }

                                    if (shouldSyncMaven) {
                                        persistPomChanges(pomFiles)
                                        mavenManager.forceUpdateAllProjectsOrFindAllAvailablePomFiles()
                                    }

                                    ApplicationManager.getApplication().invokeLater {
                                        selectedVersions.clear()
                                        availableVersions.clear()
                                        refreshAction(false, true, true)

                                        for (row in 0 until tableModel.rowCount) {
                                            tableModel.setValueAt("", row, CURRENT_VERSION_COLUMN)
                                            tableModel.setValueAt(emptyList<String>(), row, NEW_VERSION_COLUMN)
                                        }
                                    }
                                }
                            })
                        }
                    }
                }
            }

            val checkVulnerabilitiesAction = {
                if (!isUpdating) {
                    isUpdating = true
                    refreshToolbar()

                    performVulnerabilityCheck {
                        isUpdating = false
                        refreshAction(false, false, false)
                        refreshToolbar()
                    }
                }
            }

            refreshAction(false, true, true)

            add(JBScrollPane(table), BorderLayout.CENTER)

            fun toolbarAction(
                messageKey: String,
                icon: Icon,
                isEnabled: () -> Boolean,
                onPerform: () -> Unit
            ): AnAction {
                val label = MyMessageBundle.message(messageKey)
                return object : AnAction(label, label, icon) {
                    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
                    override fun update(e: AnActionEvent) {
                        e.presentation.isEnabled = isEnabled()
                        e.presentation.putClientProperty(ActionUtil.SHOW_TEXT_IN_TOOLBAR, isToolbarTextEnabled())
                    }

                    override fun actionPerformed(e: AnActionEvent) = onPerform()
                }
            }

            val openInRepositoryAction = object : AnAction() {
                override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
                override fun update(e: AnActionEvent) {
                    val label = currentOpenInRepositoryText()
                    e.presentation.text = label
                    e.presentation.description = label
                    e.presentation.icon = AllIcons.General.Web
                    e.presentation.isEnabled = isOpenInRepositoryEnabled()
                    e.presentation.putClientProperty(ActionUtil.SHOW_TEXT_IN_TOOLBAR, isToolbarTextEnabled())
                }

                override fun actionPerformed(e: AnActionEvent) = openInMavenRepositoryForSelectedRow()
            }

            toolbarGroup.apply {
                add(toolbarAction(
                    "toolwindow.MyToolWindow.refresh.button",
                    AllIcons.Actions.Refresh,
                    { !isUpdating }
                ) { refreshAction(false, true, true) })
                add(toolbarAction(
                    "toolwindow.MyToolWindow.checkUpdates.button",
                    AllIcons.Actions.Find,
                    { !isUpdating }
                ) { refreshAction(true, true, false) })
                add(toolbarAction(
                    "toolwindow.MyToolWindow.checkVulnerabilities.button",
                    AllIcons.General.InspectionsEye,
                    { isCheckVulnerabilitiesEnabled() }
                ) { checkVulnerabilitiesAction() })
                add(toolbarAction(
                    "toolwindow.MyToolWindow.update.button",
                    AllIcons.Actions.Execute,
                    { isUpdateActionEnabled() }
                ) { updateAction() })
                addSeparator()
                add(toolbarAction(
                    "toolwindow.MyToolWindow.selectHighestMajor.button",
                    AllIcons.Actions.Play_last,
                    { isBulkVersionSelectionEnabled() }
                ) { selectHighestMajorVersionForAll() })
                add(toolbarAction(
                    "toolwindow.MyToolWindow.selectHighestMinor.button",
                    AllIcons.Actions.Play_forward,
                    { isBulkVersionSelectionEnabled() }
                ) { selectHighestMinorVersionForAll() })
                add(toolbarAction(
                    "toolwindow.MyToolWindow.resetVersions.button",
                    AllIcons.Actions.Undo,
                    { isResetVersionsEnabled() }
                ) { resetAllVersionsToCurrent() })
                addSeparator()
                add(openInRepositoryAction)
                add(toolbarAction(
                    "toolwindow.MyToolWindow.vulnerabilityDetails.button",
                    AllIcons.General.BalloonWarning,
                    { isVulnerabilityDetailsEnabled() }
                ) { openVulnerabilityDetailsForSelectedRow() })
                add(Separator.getInstance())
                add(toolbarAction(
                    "toolwindow.MyToolWindow.settings.button",
                    AllIcons.General.Settings,
                    { true }
                ) { openSettings() })
            }
            val toolbar = ActionManager.getInstance()
                .createActionToolbar("MavenUpToolWindow", toolbarGroup, true)
            toolbar.targetComponent = table
            actionToolbar = toolbar

            topPanel.add(toolbar.component, BorderLayout.NORTH)
            topPanel.add(buildFilterPanel(), BorderLayout.SOUTH)
            add(topPanel, BorderLayout.NORTH)

            project.messageBus.connect(this@MyToolWindow).subscribe(MavenImportListener.TOPIC, object : MavenImportListener {
                override fun importFinished(
                    importedProjects: Collection<MavenProject>,
                    newModules: List<com.intellij.openapi.module.Module>
                ) {
                    ApplicationManager.getApplication().invokeLater {
                        availableVersions.clear()
                        selectedVersions.clear()
                        refreshAction(false, true, true)
                    }
                }
            })

            project.messageBus.connect(this@MyToolWindow).subscribe(MAVEN_UP_SETTINGS_TOPIC, Runnable {
                ApplicationManager.getApplication().invokeLater {
                    rebuildToolbar()
                    applySelectLatestVersionSettingIfChanged()
                }
            })
        }

        override fun dispose() = Unit

        /**
         * Fordert die obere Aktionsleiste auf, den Aktivierungszustand und die Beschriftungen
         * ihrer Aktionen neu zu berechnen.
         */
        private fun refreshToolbar() {
            actionToolbar?.updateActionsAsync()
        }

        /**
         * Prüft, ob die Aktionsleisten gemäß den Einstellungen Text-Buttons statt reiner Icon-Buttons
         * anzeigen sollen.
         *
         * @return `true`, wenn Text-Buttons angezeigt werden sollen.
         */
        internal fun isToolbarTextEnabled(): Boolean =
            MavenUpSettings.getInstance().state.toolbarShowText

        /**
         * Erstellt die Filterzeile mit Typ-, Änderungs- und Vulnerabilities-Combobox sowie Textfeld unterhalb der Aktionsleiste.
         *
         * @return Die konfigurierte Filter-Komponente.
         */
        private fun buildFilterPanel(): JComponent {
            val panel = JBPanel<JBPanel<*>>(BorderLayout())
            panel.border = BorderFactory.createEmptyBorder(2, 4, 2, 4)

            val filterControlsPanel = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 4, 0))

            filterControlsPanel.add(JLabel(MyMessageBundle.message("toolwindow.MyToolWindow.filter.type.label")))
            typeFilterComboBox.model = DefaultComboBoxModel(arrayOf(allTypesFilterLabel))
            typeFilterComboBox.addActionListener { applyRowFilter() }
            filterControlsPanel.add(typeFilterComboBox)

            filterControlsPanel.add(JLabel(MyMessageBundle.message("toolwindow.MyToolWindow.filter.changes.label")))
            changesFilterComboBox.model = DefaultComboBoxModel(TriStateFilter.entries.toTypedArray())
            changesFilterComboBox.selectedItem = TriStateFilter.ALL
            changesFilterComboBox.addActionListener { applyRowFilter() }
            filterControlsPanel.add(changesFilterComboBox)

            filterControlsPanel.add(JLabel(MyMessageBundle.message("toolwindow.MyToolWindow.filter.vulnerabilities.label")))
            vulnerabilitiesFilterComboBox.model = DefaultComboBoxModel(TriStateFilter.entries.toTypedArray())
            vulnerabilitiesFilterComboBox.selectedItem = TriStateFilter.ALL
            vulnerabilitiesFilterComboBox.addActionListener { applyRowFilter() }
            filterControlsPanel.add(vulnerabilitiesFilterComboBox)

            panel.add(filterControlsPanel, BorderLayout.WEST)

            searchTextField.textEditor.emptyText.text =
                MyMessageBundle.message("toolwindow.MyToolWindow.filter.search.placeholder")
            searchTextField.addDocumentListener(object : DocumentAdapter() {
                override fun textChanged(e: DocumentEvent) = applyRowFilter()
            })
            panel.add(searchTextField, BorderLayout.CENTER)

            panel.add(buildFilterResetToolbar(), BorderLayout.EAST)

            return panel
        }

        /**
         * Erstellt eine schmale Aktionsleiste mit einem einzelnen Icon-Button, der alle Filter
         * der Filterzeile zurücksetzt.
         *
         * Der Button wird am Ende der Filterzeile platziert und ist nur aktiv, solange mindestens
         * ein Filter aktiv ist (siehe [isResetFiltersEnabled]).
         *
         * @return Die Toolbar-Komponente mit der Reset-Aktion.
         */
        private fun buildFilterResetToolbar(): JComponent {
            val resetLabel = MyMessageBundle.message("toolwindow.MyToolWindow.filter.reset.button")
            val resetAction = object : AnAction(resetLabel, resetLabel, AllIcons.Actions.Cancel) {
                override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = isResetFiltersEnabled()
                }

                override fun actionPerformed(e: AnActionEvent) = resetAllFilters()
            }
            val group = DefaultActionGroup().apply { add(resetAction) }
            val toolbar = ActionManager.getInstance()
                .createActionToolbar("MavenUpFilterReset", group, true)
            toolbar.targetComponent = searchTextField
            filterResetToolbar = toolbar
            return toolbar.component
        }

        /**
         * Prüft, ob aktuell mindestens ein Filter der Filterzeile aktiv ist.
         *
         * @return `true`, wenn Suchtext, Typ-, Änderungs- oder Vulnerabilities-Filter von ihrem
         *         Standardwert abweichen.
         */
        internal fun isResetFiltersEnabled(): Boolean {
            val searchActive = searchTextField.text.isNotEmpty()
            val typeActive = (typeFilterComboBox.selectedItem as? String ?: allTypesFilterLabel) != allTypesFilterLabel
            val changesActive = (changesFilterComboBox.selectedItem as? TriStateFilter ?: TriStateFilter.ALL) != TriStateFilter.ALL
            val vulnerabilitiesActive =
                (vulnerabilitiesFilterComboBox.selectedItem as? TriStateFilter ?: TriStateFilter.ALL) != TriStateFilter.ALL
            return searchActive || typeActive || changesActive || vulnerabilitiesActive
        }

        /**
         * Setzt alle Filter der Filterzeile auf ihren Standardwert zurück und aktualisiert die
         * Tabellenansicht.
         *
         * Zurückgesetzt werden Suchtext, Typ-, Änderungs- und Vulnerabilities-Filter.
         */
        internal fun resetAllFilters() {
            searchTextField.text = ""
            typeFilterComboBox.selectedItem = allTypesFilterLabel
            changesFilterComboBox.selectedItem = TriStateFilter.ALL
            vulnerabilitiesFilterComboBox.selectedItem = TriStateFilter.ALL
            applyRowFilter()
        }

        /**
         * Wendet die aktuellen Filter (Suchtext, Typ, Änderungen, Sicherheitslücken) auf die Tabelle an.
         *
         * Liest den Suchtext aus [searchTextField], den gewählten Typ aus [typeFilterComboBox],
         * die Filteroptionen aus [changesFilterComboBox] und [vulnerabilitiesFilterComboBox]
         * und setzt einen entsprechenden [RowFilter] auf den [tableRowSorter].
         */
        internal fun applyRowFilter() {
            val searchText = searchTextField.text
            val selectedType = typeFilterComboBox.selectedItem as? String ?: allTypesFilterLabel
            val typeFilter = if (selectedType == allTypesFilterLabel) "" else selectedType
            val changesFilter = changesFilterComboBox.selectedItem as? TriStateFilter ?: TriStateFilter.ALL
            val vulnerabilitiesFilter = vulnerabilitiesFilterComboBox.selectedItem as? TriStateFilter ?: TriStateFilter.ALL

            tableRowSorter.rowFilter = object : RowFilter<DefaultTableModel, Int>() {
                override fun include(entry: Entry<out DefaultTableModel, out Int>): Boolean {
                    val groupId = entry.getValue(GROUP_ID_COLUMN)?.toString().orEmpty()
                    val artifactId = entry.getValue(ARTIFACT_ID_COLUMN)?.toString().orEmpty()
                    val property = entry.getValue(PROPERTY_COLUMN)?.toString().orEmpty()
                    val type = entry.getValue(TYPE_COLUMN)?.toString().orEmpty()
                    val currentVersion = entry.getValue(CURRENT_VERSION_COLUMN)?.toString().orEmpty()
                    val cell = entry.getValue(VULNERABILITIES_COLUMN) as? VulnerabilityCell

                    val key = "$groupId:$artifactId"
                    val selectedVersion = selectedVersions[key]
                    val effectiveVersion = selectedVersion ?: currentVersion
                    val hasChange = effectiveVersion != currentVersion && effectiveVersion.isNotEmpty()
                    val hasVulnerabilities = cell != null && cell.allAdvisories.isNotEmpty()

                    return rowMatchesFilter(
                        FilterRow(
                            groupId = groupId,
                            artifactId = artifactId,
                            property = property,
                            type = type,
                            hasChange = hasChange,
                            hasVulnerabilities = hasVulnerabilities
                        ),
                        FilterCriteria(
                            searchText = searchText,
                            typeFilter = typeFilter,
                            changesFilter = changesFilter,
                            vulnerabilitiesFilter = vulnerabilitiesFilter
                        )
                    )
                }
            }
            filterResetToolbar?.updateActionsAsync()
        }

        /**
         * Aktualisiert die Auswahlmöglichkeiten der Typ-Combobox anhand der aktuell in der
         * Tabelle vorhandenen Typen.
         *
         * Die bisherige Auswahl bleibt erhalten, sofern der Typ weiterhin existiert; andernfalls
         * wird auf "alle Typen" zurückgesetzt.
         */
        internal fun updateTypeFilterOptions() {
            val model = table.model as DefaultTableModel
            val types = (0 until model.rowCount)
                .mapNotNull { model.getValueAt(it, TYPE_COLUMN) as? String }
                .filter { it.isNotBlank() }
                .distinct()
                .sorted()
            val previouslySelected = typeFilterComboBox.selectedItem as? String ?: allTypesFilterLabel
            typeFilterComboBox.model =
                DefaultComboBoxModel((listOf(allTypesFilterLabel) + types).toTypedArray())
            typeFilterComboBox.selectedItem =
                if (types.contains(previouslySelected)) previouslySelected else allTypesFilterLabel
        }

        /**
         * Bricht eine aktive Zell-Bearbeitung der Haupttabelle ab, bevor deren Zeilen entfernt
         * oder neu aufgebaut werden.
         *
         * Ohne diesen Abbruch kann ein noch geöffneter Zell-Editor der Spalte "New Version" beim
         * späteren Layout (`columnMarginChanged` → `editingStopped`) versuchen, seinen Wert in eine
         * bereits entfernte Zeile zu schreiben, was eine [ArrayIndexOutOfBoundsException] im
         * `DefaultTableModel` auslöst.
         *
         * @return `true`, wenn eine laufende Bearbeitung abgebrochen wurde, sonst `false`.
         */
        internal fun cancelActiveCellEditing(): Boolean {
            if (table.isEditing) {
                table.cellEditor?.cancelCellEditing()
                return true
            }
            return false
        }

        /**
         * Baut die obere Aktionsleiste neu auf, damit ein geänderter Text-/Icon-Modus wirksam wird.
         * Ersetzt die bestehende Aktionsleiste im Nordbereich durch eine neu erstellte Instanz.
         * Muss auf dem Event Dispatch Thread laufen.
         */
        private fun rebuildToolbar() {
            actionToolbar?.let { topPanel.remove(it.component) }
            val toolbar = ActionManager.getInstance()
                .createActionToolbar("MavenUpToolWindow", toolbarGroup, true)
            toolbar.targetComponent = table
            actionToolbar = toolbar
            topPanel.add(toolbar.component, BorderLayout.NORTH)
            topPanel.revalidate()
            topPanel.repaint()
        }

        /**
         * Wendet die Auto-Selektionsstrategie nur dann erneut an, wenn sich ihr Wert geändert hat.
         *
         * Dadurch setzt das Ändern anderer Einstellungen (z.B. Text-Buttons oder Maven-Sync) die
         * bereits getroffene **New Version**-Auswahl nicht mehr zurück.
         */
        internal fun applySelectLatestVersionSettingIfChanged() {
            val autoSelectionMode = MavenUpSettings.getInstance().state.versionAutoSelectionMode
            if (autoSelectionMode != lastVersionAutoSelectionMode) {
                lastVersionAutoSelectionMode = autoSelectionMode
                applySelectLatestVersionSetting()
            }
        }

        /**
         * Wendet die konfigurierte Auto-Selektionsstrategie auf alle bereits geladenen Abhängigkeiten an.
         *
         * Wird aufgerufen, wenn sich die Einstellung ändert, damit die "New Version"-Spalte sofort
         * die korrekte Auswahl widerspiegelt.
         */
        internal fun applySelectLatestVersionSetting() {
            if (availableVersions.isEmpty()) return
            val autoSelectionMode = MavenUpSettings.getInstance().state.versionAutoSelectionMode
            for ((key, versions) in availableVersions) {
                if (versions.isEmpty()) continue
                val currentVersion = knownDependencies[key] ?: ""
                if (autoSelectionMode != VersionAutoSelectionMode.DISABLED) {
                    val autoSelectedVersion = chooseAutoSelectedVersion(currentVersion, versions)
                    if (autoSelectedVersion != currentVersion) {
                        selectedVersions[key] = autoSelectedVersion
                    } else {
                        selectedVersions.remove(key)
                    }
                } else {
                    selectedVersions[key] = currentVersion
                }
            }
            cancelActiveCellEditing()
            table.repaint()
            updateUpdateButtonState()
            applyRowFilter()
        }

        /**
         * Wählt für alle geladenen Abhängigkeiten die höchste verfügbare Version (über alle Major-Linien hinweg) aus.
         *
         * Die neueste Version steht jeweils an erster Stelle der von
         * [DependencyApiService.fetchVersions] gelieferten Liste.
         */
        internal fun selectHighestMajorVersionForAll() {
            applyBulkVersionSelection { _, versions -> versions.firstOrNull().orEmpty() }
        }

        /**
         * Wählt für alle geladenen Abhängigkeiten die höchste Version innerhalb derselben Major-Linie
         * wie die aktuell verwendete Version aus.
         *
         * Existiert keine passende Version derselben Major-Linie, bleibt die aktuelle Version erhalten.
         */
        internal fun selectHighestMinorVersionForAll() {
            applyBulkVersionSelection { current, versions ->
                latestVersionWithinSameMajor(current, versions) ?: current
            }
        }

        /**
         * Verwirft alle getroffenen Versionsauswahlen und setzt jede Abhängigkeit auf ihre aktuell
         * verwendete Version zurück.
         */
        internal fun resetAllVersionsToCurrent() {
            applyBulkVersionSelection { current, _ -> current }
        }

        /**
         * Wendet eine Auswahlstrategie auf alle geladenen Abhängigkeiten an und aktualisiert die Tabelle.
         *
         * Für jede Abhängigkeit wird die von [chooser] gelieferte Zielversion übernommen. Entspricht sie
         * der aktuellen Version (oder ist leer), wird der Eintrag aus [selectedVersions] entfernt, sodass
         * keine Änderung angezeigt wird.
         *
         * @param chooser Funktion, die aus der aktuellen Version und den verfügbaren Versionen die Zielversion ermittelt.
         */
        private fun applyBulkVersionSelection(chooser: (String, List<String>) -> String) {
            if (availableVersions.isEmpty()) return
            for ((key, versions) in availableVersions) {
                if (versions.isEmpty()) continue
                val currentVersion = knownDependencies[key] ?: ""
                val chosen = chooser(currentVersion, versions)
                if (chosen.isNotEmpty() && chosen != currentVersion) {
                    selectedVersions[key] = chosen
                } else {
                    selectedVersions.remove(key)
                }
            }
            cancelActiveCellEditing()
            table.repaint()
            updateUpdateButtonState()
            applyRowFilter()
        }

        /**
         * Prüft, ob die Sammelaktionen zur Versionsauswahl ausführbar sind.
         *
         * @return `true`, wenn keine Aktualisierung läuft und mindestens eine Abhängigkeit auswählbare Versionen besitzt.
         */
        internal fun isBulkVersionSelectionEnabled(): Boolean =
            !isUpdating && availableVersions.values.any { it.isNotEmpty() }

        /**
         * Prüft, ob das Zurücksetzen aller Versionsauswahlen ausführbar ist.
         *
         * @return `true`, wenn keine Aktualisierung läuft und mindestens eine abweichende Version ausgewählt ist.
         */
        internal fun isResetVersionsEnabled(): Boolean =
            !isUpdating && hasSelectedUpdates()

        /**
         * Synchronisiert die Version-Auswahl über alle Einträge, die dieselbe Maven-Property verwenden.
         *
         * @param key Der Schlüssel (`groupId:artifactId`) der geänderten Dependency.
         * @param selected Die neu ausgewählte Version.
         */
        private fun synchronizePropertyVersions(key: String, selected: String) {
            selectedVersions[key] = selected
            val property = dependencyToProperty[key]
            if (property != null) {
                dependencyToProperty.forEach { (depKey, prop) ->
                    if (prop == property) {
                        selectedVersions[depKey] = selected
                    }
                }
            }
        }

        /**
         * Aktualisiert die visuelle Darstellung (Farbe, Font, Status-Glyph, Tooltip) des Editor-Panels
         * sofort nach einer Versionsauswahl, ohne dass der Fokus die Zelle verlassen muss.
         *
         * @param combo Die ComboBox im Editor.
         * @param panel Das umgebende JPanel mit Status-Glyph und ComboBox.
         * @param selected Die aktuell gewählte Version.
         * @param currentVersion Die im Projekt verwendete Version.
         * @param newestVersion Die höchste bekannte Version.
         */
        private fun updateEditorVisuals(
            combo: ComboBox<String>,
            panel: JPanel?,
            selected: String?,
            currentVersion: String,
            newestVersion: String
        ) {
            val newUpToDate = isVersionUpToDate(selected ?: "", newestVersion)
            val newHasChange = selected != currentVersion && !selected.isNullOrEmpty()
            val newColor = if (newHasChange) versionStatusColor(newUpToDate) else null
            val newFontStyle = if (newHasChange) java.awt.Font.BOLD else java.awt.Font.PLAIN
            combo.foreground = newColor
            combo.font = combo.font.deriveFont(newFontStyle)
            combo.repaint()
            if (panel != null) {
                val statusLabel = panel.getComponent(0) as? JLabel
                statusLabel?.text = versionStatusText(newUpToDate)
                statusLabel?.foreground = newColor
                panel.toolTipText = versionStatusTooltip(currentVersion, selected ?: currentVersion, newestVersion)
                panel.repaint()
            }
        }

        private fun updateUpdateButtonState() {
            refreshToolbar()
        }

        /**
         * Prüft, ob mindestens eine Abhängigkeit mit einer von der aktuellen Version abweichenden
         * neuen Version ausgewählt wurde.
         *
         * @return `true`, wenn mindestens ein anwendbares Update ausgewählt ist.
         */
        internal fun hasSelectedUpdates(): Boolean {
            return knownDependencies.any { (key, currentVersion) ->
                val newVersion = selectedVersions[key]
                newVersion != null && newVersion != currentVersion
            }
        }

        /**
         * Prüft, ob die Update-Aktion ausführbar ist (ausgewählte Updates und keine laufende Operation).
         *
         * @return `true`, wenn die Update-Aktion aktiviert sein soll.
         */
        internal fun isUpdateActionEnabled(): Boolean = hasSelectedUpdates() && !isUpdating

        /**
         * Prüft, ob die Vulnerability-Prüfung derzeit gestartet werden darf.
         *
         * @return `true`, wenn keine laufende Aktualisierung oder Prüfung aktiv ist.
         */
        internal fun isCheckVulnerabilitiesEnabled(): Boolean =
            canCheckVulnerabilities(isRefreshing, isUpdating)

        /**
         * Prüft, ob für die aktuell selektierte Zeile die Repository-Browser-Aktion verfügbar ist.
         *
         * @return `true`, wenn eine Zeile selektiert ist.
         */
        internal fun isOpenInRepositoryEnabled(): Boolean =
            table.selectedRow >= 0

        /**
         * Prüft, ob für die aktuell selektierte Zeile Vulnerability-Details angezeigt werden können.
         *
         * @return `true`, wenn die selektierte Zeile mindestens eine Sicherheitswarnung besitzt.
         */
        internal fun isVulnerabilityDetailsEnabled(): Boolean {
            val row = table.selectedRow
            val cell = if (row >= 0) table.getValueAt(row, VULNERABILITIES_COLUMN) as? VulnerabilityCell else null
            return !isUpdating && cell?.allAdvisories?.isNotEmpty() == true
        }

        /**
         * Liefert die aktuelle Beschriftung der Repository-Browser-Aktion inklusive des
         * konfigurierten Browser-Namens.
         *
         * @return Der lokalisierte Aktionstext mit eingesetztem Browser-Namen.
         */
        internal fun currentOpenInRepositoryText(): String {
            val browserName = MavenUpSettings.getInstance().state.repositoryBrowser.displayName
            return MyMessageBundle.message(
                TOOLWINDOW_MY_TOOL_WINDOW_CONTEXT_MENU_OPEN_IN_MVN_REPOSITORY, browserName
            )
        }

        /**
         * Öffnet die aktuell selektierte Abhängigkeit im konfigurierten Repository-Browser.
         */
        internal fun openInMavenRepositoryForSelectedRow() {
            val row = table.selectedRow
            if (row < 0) return
            val groupId = table.getValueAt(row, GROUP_ID_COLUMN)?.toString().orEmpty()
            val artifactId = table.getValueAt(row, ARTIFACT_ID_COLUMN)?.toString().orEmpty()
            val currentVersion = table.getValueAt(row, CURRENT_VERSION_COLUMN)?.toString().orEmpty()
            openInMavenRepository(groupId, artifactId, currentVersion)
        }

        /**
         * Öffnet den Vulnerability-Details-Dialog für die aktuell selektierte Abhängigkeit.
         */
        internal fun openVulnerabilityDetailsForSelectedRow() {
            val row = table.selectedRow
            if (row < 0) return
            val cell = table.getValueAt(row, VULNERABILITIES_COLUMN) as? VulnerabilityCell
            val findings = cell?.detailFindings() ?: emptyMap()
            val groupId = table.getValueAt(row, GROUP_ID_COLUMN)?.toString().orEmpty()
            val artifactId = table.getValueAt(row, ARTIFACT_ID_COLUMN)?.toString().orEmpty()
            if (findings.isNotEmpty()) {
                VulnerabilityDetailDialog(
                    project,
                    findings,
                    "$groupId:$artifactId - ${MyMessageBundle.message(VULNERABILITY_DETAILS_TITLE)}"
                ).show()
            }
        }

        /**
         * Sammelt alle in der UI ausgewählten Updates, für die eine neue Version gewählt wurde.
         */
        internal fun collectSelectedUpdates(): List<DependencyUpdate> {
            return selectedVersions.mapNotNull { (key, newVersion) ->
                val currentVersion = knownDependencies[key] ?: return@mapNotNull null
                val type = knownTypes[key] ?: return@mapNotNull null
                if (newVersion == currentVersion) return@mapNotNull null

                DependencyUpdate(
                    key.substringBefore(":"),
                    key.substringAfter(":"),
                    type,
                    currentVersion,
                    newVersion
                )
            }
        }

        /**
         * Löst einen Versionswert auf, der als Maven-Property-Platzhalter der Form
         * `${property.name}` vorliegen kann. Wird häufig für Einträge im
         * `dependencyManagement` benötigt, deren Version über eine Property (z. B.
         * `${netty-bom.version}`) definiert ist und die nicht im aufgelösten
         * Abhängigkeitsbaum auftauchen.
         *
         * Ist [value] kein Platzhalter oder die Property in [properties] nicht (mit
         * nicht-leerem Wert) vorhanden, wird [value] unverändert zurückgegeben.
         *
         * @param value Der möglicherweise als Platzhalter vorliegende Versionswert.
         * @param properties Die effektiven Maven-Properties (Property-Name -> Wert).
         * @return Die aufgelöste Version oder der ursprüngliche Wert.
         */
        internal fun resolveVersionPlaceholder(value: String, properties: Map<String, String>): String {
            if (!value.startsWith("\${") || !value.endsWith("}")) return value
            val propertyName = value.substring(2, value.length - 1)
            return properties[propertyName]?.takeIf { it.isNotBlank() } ?: value
        }

        @Suppress("unused")
        private fun collectDependenciesAndProperties(
            parentTag: XmlTag?,
            wrapperTagName: String,
            itemTagName: String,
            targetMap: MutableMap<String, String>
        ) {
            collectDependenciesAndProperties(
                parentTag,
                wrapperTagName,
                itemTagName,
                targetMap,
                dependencyToProperty
            )
        }

        /**
         * Durchsucht die XML-Tags nach Abhängigkeiten und extrahiert deren Koordinaten sowie
         * mögliche Platzhalter (Properties).
         */
        private fun collectDependenciesAndProperties(
            parentTag: XmlTag?,
            wrapperTagName: String,
            itemTagName: String,
            targetMap: MutableMap<String, String>,
            propertyTargetMap: MutableMap<String, String>
        ) {
            val wrapperTag = parentTag?.findFirstSubTag(wrapperTagName) ?: parentTag
            wrapperTag?.findSubTags(itemTagName)?.forEach { tag ->
                val g = tag.findFirstSubTag("groupId")?.value?.text?.trim().orEmpty()
                if (g.isEmpty()) return@forEach
                val a = tag.findFirstSubTag("artifactId")?.value?.text ?: ""
                val v = tag.findFirstSubTag("version")?.value?.text ?: ""
                val key = "$g:$a"
                targetMap[key] = v

                val versionText = tag.findFirstSubTag("version")?.value?.trimmedText
                if (versionText != null && versionText.startsWith("\${") && versionText.endsWith("}")) {
                    propertyTargetMap[key] = versionText.substring(2, versionText.length - 1)
                }
            }
        }

        /**
         * Liest die Parent-Dependency aus dem `<parent>`-Tag der `pom.xml` und erzeugt eine [RefreshRow].
         * Gibt `null` zurück, wenn kein Parent-Tag vorhanden ist oder groupId/artifactId leer sind.
         *
         * @param rootTag Das Root-Tag der `pom.xml`.
         * @param propertyTargetMap Ziel-Map für erkannte Maven-Property-Platzhalter.
         * @return Eine [RefreshRow] mit Typ [PARENT_TYPE] oder `null`.
         */
        internal fun collectParentDependency(
            rootTag: XmlTag?,
            propertyTargetMap: MutableMap<String, String>
        ): RefreshRow? {
            val parentTag = rootTag?.findFirstSubTag("parent") ?: return null
            val g = parentTag.findFirstSubTag("groupId")?.value?.text?.trim().orEmpty()
            if (g.isEmpty()) return null
            val a = parentTag.findFirstSubTag("artifactId")?.value?.text?.trim().orEmpty()
            if (a.isEmpty()) return null
            val v = parentTag.findFirstSubTag("version")?.value?.text?.trim().orEmpty()
            val key = "$g:$a"

            val versionText = parentTag.findFirstSubTag("version")?.value?.trimmedText
            var propertyName = ""
            if (versionText != null && versionText.startsWith("\${") && versionText.endsWith("}")) {
                propertyName = versionText.substring(2, versionText.length - 1)
                propertyTargetMap[key] = propertyName
            }

            return RefreshRow(
                groupId = g,
                artifactId = a,
                propertyName = propertyName,
                type = PARENT_TYPE,
                currentVersion = v
            )
        }

        /**
         * Erstellt einen Schnappschuss der aktuellen Abhängigkeiten im Projekt.
         * Muss außerhalb des Event Dispatch Threads aufgerufen werden.
         */
        internal fun collectRefreshSnapshot(managedDependencyType: String): RefreshSnapshot {
            check(!ApplicationManager.getApplication().isDispatchThread) {
                "Refresh data must not be collected on the Event Dispatch Thread"
            }

            val rows = mutableListOf<RefreshRow>()
            val properties = mutableMapOf<String, String>()
            MavenProjectsManager.getInstance(project).projects.forEach { mavenProject ->
                val psiFile = PsiManager.getInstance(project).findFile(mavenProject.file) as? XmlFile
                val localDependencies = mutableMapOf<String, String>()
                val managedDependencies = mutableMapOf<String, String>()
                val localPlugins = mutableMapOf<String, String>()
                val managedPlugins = mutableMapOf<String, String>()

                if (psiFile != null) {
                    val documentElement = psiFile.document?.rootTag

                    // Parent-Dependency erfassen
                    collectParentDependency(documentElement, properties)?.let { parentRow ->
                        rows.add(parentRow)
                    }

                    collectDependenciesAndProperties(
                        documentElement,
                        "dependencies",
                        "dependency",
                        localDependencies,
                        properties
                    )
                    val dependencyManagement = documentElement?.findFirstSubTag("dependencyManagement")
                    collectDependenciesAndProperties(
                        dependencyManagement,
                        "dependencies",
                        "dependency",
                        managedDependencies,
                        properties
                    )
                    val buildTag = documentElement?.findFirstSubTag("build")
                    collectDependenciesAndProperties(buildTag, "plugins", "plugin", localPlugins, properties)
                    val pluginManagement = buildTag?.findFirstSubTag("pluginManagement")
                    collectDependenciesAndProperties(
                        pluginManagement,
                        "plugins",
                        "plugin",
                        managedPlugins,
                        properties
                    )
                }

                val effectiveProperties =
                    mavenProject.properties.entries.associate { (k, v) -> k.toString() to v.toString() }
                val resolvedDependencies =
                    mavenProject.dependencyTree.associateBy { "${it.artifact.groupId}:${it.artifact.artifactId}" }
                val seenLocalDependencyKeys = mutableSetOf<String>()
                val seenManagedDependencyKeys = mutableSetOf<String>()
                localDependencies.forEach { (key, value) ->
                    if (!seenLocalDependencyKeys.add(key)) return@forEach
                    rows.add(
                        RefreshRow(
                            groupId = key.substringBefore(":"),
                            artifactId = key.substringAfter(":"),
                            propertyName = properties[key].orEmpty(),
                            type = "dependency",
                            currentVersion = resolvedDependencies[key]?.artifact?.version
                                ?: resolveVersionPlaceholder(value, effectiveProperties)
                        )
                    )
                }
                managedDependencies.forEach { (key, value) ->
                    if (!seenManagedDependencyKeys.add(key)) return@forEach
                    rows.add(
                        RefreshRow(
                            groupId = key.substringBefore(":"),
                            artifactId = key.substringAfter(":"),
                            propertyName = properties[key].orEmpty(),
                            type = managedDependencyType,
                            currentVersion = resolvedDependencies[key]?.artifact?.version
                                ?: resolveVersionPlaceholder(value, effectiveProperties)
                        )
                    )
                }

                val resolvedPlugins = mavenProject.plugins.associateBy { "${it.groupId}:${it.artifactId}" }
                val seenLocalPluginKeys = mutableSetOf<String>()
                val seenManagedPluginKeys = mutableSetOf<String>()
                localPlugins.forEach { (key, value) ->
                    if (!seenLocalPluginKeys.add(key)) return@forEach
                    rows.add(
                        RefreshRow(
                            groupId = key.substringBefore(":"),
                            artifactId = key.substringAfter(":"),
                            propertyName = properties[key].orEmpty(),
                            type = "plugin",
                            currentVersion = resolvedPlugins[key]?.version
                                ?: resolveVersionPlaceholder(value, effectiveProperties)
                        )
                    )
                }
                managedPlugins.forEach { (key, value) ->
                    if (!seenManagedPluginKeys.add(key)) return@forEach
                    rows.add(
                        RefreshRow(
                            groupId = key.substringBefore(":"),
                            artifactId = key.substringAfter(":"),
                            propertyName = properties[key].orEmpty(),
                            type = MANAGED_PLUGIN,
                            currentVersion = resolvedPlugins[key]?.version
                                ?: resolveVersionPlaceholder(value, effectiveProperties)
                        )
                    )
                }
            }

            return RefreshSnapshot(rows, properties)
        }

        /**
         * Orchestriert den Prozess der Versionssuche und aktualisiert die Aktionsleiste.
         *
         * @param refreshAfterCheck Callback, der nach Abschluss der Prüfung ausgeführt wird.
         */
        private fun performUpdateCheck(
            refreshAfterCheck: () -> Unit
        ) {
            isUpdating = true
            refreshToolbar()

            availableVersions.clear()
            selectedVersions.clear()
            checkForUpdates {
                ApplicationManager.getApplication().invokeLater {
                    isUpdating = false
                    refreshAfterCheck()
                    refreshToolbar()
                }
            }
        }

        /**
         * Wendet die ausgewählten Updates auf die `pom.xml` des Projekts an.
         */
        private fun applyUpdateToPom(
            mavenProject: MavenProject,
            updates: List<DependencyUpdate>
        ) {
            val pomFile = mavenProject.file
            val psiFile = ApplicationManager.getApplication().runReadAction<XmlFile?> {
                PsiManager.getInstance(project).findFile(pomFile) as? XmlFile
            } ?: return

            WriteCommandAction.runWriteCommandAction(project) {
                val documentElement = psiFile.document?.rootTag ?: return@runWriteCommandAction
                val propertiesTag = documentElement.findFirstSubTag("properties")

                updates.forEach { update ->
                    val managedDependencyType = MyMessageBundle.message(
                        TOOLWINDOW_MY_TOOL_WINDOW_TYPE_MANAGED_DEPENDENCY
                    )
                    when (update.type) {
                        PARENT_TYPE -> {
                            updateParent(documentElement, update, propertiesTag)
                        }
                        "dependency", managedDependencyType -> {
                            updateDependencies(documentElement, update, propertiesTag)
                        }
                        "plugin", MANAGED_PLUGIN -> {
                            updatePlugins(documentElement, update, propertiesTag)
                        }
                    }
                }
            }
        }

        /**
         * Schreibt die zuvor über PSI geänderten `pom.xml`-Dateien auf die Festplatte.
         *
         * PSI-/Document-Änderungen liegen zunächst nur im Speicher vor. Der anschließende
         * Maven-Sync (`forceUpdateAllProjectsOrFindAllAvailablePomFiles`) liest die POM-Dateien
         * jedoch von der Festplatte neu ein. Ohne vorheriges Speichern würde Maven den alten,
         * unveränderten Inhalt importieren und das Projekt bliebe unsynchronisiert. Das Speichern
         * erfolgt synchron auf dem EDT, damit es vor dem Auslösen des Sync abgeschlossen ist.
         *
         * @param pomFiles Die POM-Dateien, deren offene Documents gespeichert werden sollen.
         */
        private fun persistPomChanges(pomFiles: List<VirtualFile>) {
            ApplicationManager.getApplication().invokeAndWait {
                val psiDocumentManager = PsiDocumentManager.getInstance(project)
                val fileDocumentManager = FileDocumentManager.getInstance()
                pomFiles.forEach { pomFile ->
                    val document = fileDocumentManager.getDocument(pomFile) ?: return@forEach
                    psiDocumentManager.doPostponedOperationsAndUnblockDocument(document)
                    fileDocumentManager.saveDocument(document)
                }
            }
        }

        /**
         * Aktualisiert Abhängigkeitsversionen in `<dependencies>` und `<dependencyManagement>`
         * des angegebenen POM-Root-Tags für ein einzelnes Update.
         */
        private fun updateDependencies(
            documentElement: XmlTag,
            update: DependencyUpdate,
            propertiesTag: XmlTag?
        ) {
            val dependenciesTag = documentElement.findFirstSubTag("dependencies")
            val dependencyManagementTag = documentElement.findFirstSubTag("dependencyManagement")
            val dmDependenciesTag = dependencyManagementTag?.findFirstSubTag("dependencies")

            // Search in <dependencies>
            dependenciesTag?.findSubTags("dependency")?.forEach { depTag ->
                updateIfMatch(depTag, update, propertiesTag)
            }
            // Search in <dependencyManagement><dependencies>
            dmDependenciesTag?.findSubTags("dependency")?.forEach { depTag ->
                updateIfMatch(depTag, update, propertiesTag)
            }
        }

        /**
         * Aktualisiert die Version im `<parent>`-Tag der `pom.xml` für ein Parent-Update.
         *
         * @param documentElement Das Root-Tag der `pom.xml`.
         * @param update Das anzuwendende Update.
         * @param propertiesTag Das `<properties>`-Tag für Property-basierte Versionsupdates.
         */
        private fun updateParent(
            documentElement: XmlTag,
            update: DependencyUpdate,
            propertiesTag: XmlTag?
        ) {
            val parentTag = documentElement.findFirstSubTag("parent") ?: return
            val g = parentTag.findFirstSubTag("groupId")?.value?.text
            val a = parentTag.findFirstSubTag("artifactId")?.value?.text
            if (g == update.groupId && a == update.artifactId) {
                updateXmlTagVersion(parentTag, update.newVersion, propertiesTag)
            }
        }

        /**
         * Aktualisiert Plugin-Versionen in `<build><plugins>` und `<build><pluginManagement>`
         * des angegebenen POM-Root-Tags für ein einzelnes Update.
         */
        private fun updatePlugins(
            documentElement: XmlTag,
            update: DependencyUpdate,
            propertiesTag: XmlTag?
        ) {
            val buildTag = documentElement.findFirstSubTag("build")
            val pluginsTag = buildTag?.findFirstSubTag("plugins")
            val pluginManagementTag = buildTag?.findFirstSubTag("pluginManagement")
            val pmPluginsTag = pluginManagementTag?.findFirstSubTag("plugins")

            // Search in <build><plugins>
            pluginsTag?.findSubTags("plugin")?.forEach { pluginTag ->
                updateIfMatch(pluginTag, update, propertiesTag)
            }
            // Search in <build><pluginManagement><plugins>
            pmPluginsTag?.findSubTags("plugin")?.forEach { pluginTag ->
                updateIfMatch(pluginTag, update, propertiesTag)
            }
        }

        /**
         * Prüft, ob ein XML-Tag mit groupId und artifactId des Updates übereinstimmt,
         * und aktualisiert in diesem Fall die Version.
         */
        private fun updateIfMatch(
            tag: XmlTag,
            update: DependencyUpdate,
            propertiesTag: XmlTag?
        ) {
            val g = tag.findFirstSubTag("groupId")?.value?.text
            val a = tag.findFirstSubTag("artifactId")?.value?.text

            if (g == update.groupId && a == update.artifactId) {
                updateXmlTagVersion(tag, update.newVersion, propertiesTag)
            }
        }

        /**
         * Führt den Vulnerability-Scan für die erfassten Abhängigkeiten durch.
         * Nutzt OSV und optional den Sonatype OSS Index.
         */
        private fun performVulnerabilityCheck(onFinished: () -> Unit) {
            ProgressManager.getInstance().run(object : Task.Backgroundable(
                project,
                MyMessageBundle.message("toolwindow.MyToolWindow.checkVulnerabilities.progress"),
                true
            ) {
                override fun run(indicator: ProgressIndicator) {
                    val directDependencies = knownDependencies.entries
                        .filter { it.value.isNotEmpty() }
                        .map { (key, version) -> Triple(key.substringBefore(":"), key.substringAfter(":"), version) }
                    val scanTargets = collectVulnerabilityScanTargets(directDependencies)
                    val dependencies = scanTargets.dependencies
                    LOG.info("Starting vulnerability check for ${dependencies.size} dependencies/plugins.")

                    val osvResults = vulnerabilityApiService.fetchVulnerabilityAdvisories(dependencies.toList(), indicator)
                    val ossIndexScan = resolveOssIndexResults(dependencies.toList(), indicator)
                    val results = VulnerabilityMerger.merge(osvResults, ossIndexScan.advisories)
                    val vulnerableEntries = results.values.count { it.isNotEmpty() }
                    LOG.info(
                        "Finished vulnerability check. " +
                            "Results for ${results.size} entries, $vulnerableEntries entries with known vulnerabilities."
                    )

                    ApplicationManager.getApplication().invokeLater {
                        applyVulnerabilityResults(results, scanTargets)
                        ossIndexScan.errorMessage?.let(::showOssIndexError)
                        onFinished()
                    }
                }
            })
        }

        /**
         * Bündelt das Ergebnis einer OSS-Index-Abfrage: die gefundenen Advisories und eine
         * optionale, bereits benutzergerecht formulierte Fehlermeldung.
         */
        private inner class OssIndexScanResult(
            val advisories: Map<String, List<VulnerabilityAdvisory>>,
            val errorMessage: String?
        )

        /**
         * Ermittelt die OSS-Index-Befunde für die angegebenen Abhängigkeiten.
         *
         * Ist der OSS Index deaktiviert oder der Vorgang abgebrochen, wird ein leeres Ergebnis
         * ohne Fehler zurückgegeben. Fehlt das API-Token oder lehnt Sonatype es ab (ungültig bzw.
         * abgelaufen), enthält das Ergebnis eine qualifizierte Fehlermeldung.
         */
        private fun resolveOssIndexResults(
            dependencies: List<Triple<String, String, String>>,
            indicator: ProgressIndicator
        ): OssIndexScanResult {
            val settings = MavenUpSettings.getInstance().state
            if (!settings.ossIndexEnabled || indicator.isCanceled) {
                return OssIndexScanResult(emptyMap(), null)
            }
            return try {
                val token = ossIndexCredentialService.retrieve()?.getPasswordAsString().orEmpty()
                if (token.isBlank()) {
                    LOG.warn("Skipping OSS Index vulnerability check because the API token is missing.")
                    OssIndexScanResult(
                        emptyMap(),
                        MyMessageBundle.message("vulnerability.ossIndex.credentialsMissing")
                    )
                } else {
                    OssIndexScanResult(
                        ossIndexApiService.fetchVulnerabilityAdvisories(dependencies, token, indicator),
                        null
                    )
                }
            } catch (exception: OssIndexAuthenticationException) {
                LOG.warn("OSS Index vulnerability check failed due to an invalid or expired API token", exception)
                OssIndexScanResult(
                    emptyMap(),
                    MyMessageBundle.message("vulnerability.ossIndex.authenticationFailed")
                )
            } catch (exception: Exception) {
                LOG.warn("OSS Index vulnerability check failed", exception)
                OssIndexScanResult(emptyMap(), exception.message ?: exception.javaClass.simpleName)
            }
        }

        /**
         * Übernimmt die zusammengeführten Scan-Ergebnisse in den Zustand des Tool-Windows.
         */
        private fun applyVulnerabilityResults(
            results: Map<String, List<VulnerabilityAdvisory>>,
            scanTargets: VulnerabilityScanTargets
        ) {
            vulnerabilityAdvisories.clear()
            vulnerabilityAdvisories.putAll(results)
            transitiveCoordinates.clear()
            transitiveCoordinates.addAll(scanTargets.transitiveCoordinates)
            transitiveDependenciesByDirect.clear()
            transitiveDependenciesByDirect.putAll(scanTargets.transitiveDependenciesByDirect)
        }

        /**
         * Zeigt die qualifizierte OSS-Index-Fehlermeldung an und bietet einen direkten Sprung
         * in die Plugin-Einstellungen.
         */
        private fun showOssIndexError(errorMessage: String) {
            val choice = Messages.showDialog(
                project,
                errorMessage,
                MyMessageBundle.message("vulnerability.ossIndex.error.title"),
                arrayOf(
                    MyMessageBundle.message("vulnerability.ossIndex.error.openSettings"),
                    MyMessageBundle.message("button.close")
                ),
                0,
                Messages.getWarningIcon()
            )
            if (choice == 0) {
                openSettings()
            }
        }

        /**
         * Sammelt alle direkten und (falls konfiguriert) transitiven Abhängigkeiten für den Scan.
         */
        private fun collectVulnerabilityScanTargets(
            directDependencies: List<Triple<String, String, String>>
        ): VulnerabilityScanTargets {
            val dependencies = LinkedHashSet(directDependencies)
            if (!MavenUpSettings.getInstance().state.checkTransitiveDependencies) {
                return VulnerabilityScanTargets(dependencies, emptySet(), emptyMap())
            }

            val transitiveCoordinates = linkedSetOf<String>()
            val transitiveDependenciesByDirect = linkedMapOf<String, Set<String>>()
            collectResolvedDependencyRelations(MavenProjectsManager.getInstance(project).projects)
                .forEach { (directDependency, transitiveDependencies) ->
                    dependencies.add(directDependency)
                    dependencies.addAll(transitiveDependencies)
                    transitiveDependenciesByDirect[coordinateString(directDependency)] =
                        transitiveDependencies.mapTo(linkedSetOf(), ::coordinateString)
                    transitiveDependencies
                        .filterNotTo(linkedSetOf()) { it in directDependencies }
                        .mapTo(transitiveCoordinates, ::coordinateString)
                }
            return VulnerabilityScanTargets(
                dependencies,
                transitiveCoordinates,
                transitiveDependenciesByDirect
            )
        }

        /**
         * Ermittelt die Beziehungen zwischen direkten und transitiven Abhängigkeiten aus dem Maven-Modell.
         */
        internal fun collectResolvedDependencyRelations(
            projects: Collection<MavenProject>
        ): Map<Triple<String, String, String>, Set<Triple<String, String, String>>> {
            val dependenciesByDirect = linkedMapOf<Triple<String, String, String>, MutableSet<Triple<String, String, String>>>()

            fun collectTransitive(node: MavenArtifactNode, target: MutableSet<Triple<String, String, String>>) {
                node.dependencies.forEach { dependency ->
                    artifactNodeCoordinate(dependency)?.let(target::add)
                    collectTransitive(dependency, target)
                }
            }

            projects.forEach { project ->
                project.dependencyTree.forEach { root ->
                    val directDependency = artifactNodeCoordinate(root) ?: return@forEach
                    val transitiveDependencies = dependenciesByDirect.getOrPut(directDependency) { linkedSetOf() }
                    collectTransitive(root, transitiveDependencies)
                }
            }
            return dependenciesByDirect
        }

        /**
         * Bestimmt die Hintergrundfarbe für ein Tabellenfeld basierend auf dem Schweregrad der Sicherheitslücke.
         */
        private fun vulnerabilityColor(
            severity: VulnerabilitySeverity,
            defaultColor: Color
        ): Color = when (severity) {
            VulnerabilitySeverity.CRITICAL -> com.intellij.ui.JBColor(0xFFB3B3, 0x6E2C2C)
            VulnerabilitySeverity.HIGH -> com.intellij.ui.JBColor(0xFFD5A3, 0x714A21)
            VulnerabilitySeverity.MEDIUM -> com.intellij.ui.JBColor(0xFFF0A6, 0x665A21)
            VulnerabilitySeverity.LOW -> com.intellij.ui.JBColor(0xC7E3FF, 0x294E6B)
            VulnerabilitySeverity.UNKNOWN -> defaultColor
        }

        /**
         * Startet die Hintergrundaufgabe zur Suche nach verfügbaren neuen Versionen.
         */
        private fun checkForUpdates(onFinished: () -> Unit) {
            ProgressManager.getInstance().run(object : Task.Backgroundable(
                project,
                MyMessageBundle.message("toolwindow.MyToolWindow.checkUpdates.progress"),
                true
            ) {
                override fun run(indicator: ProgressIndicator) {
                    val projects = MavenProjectsManager.getInstance(project).projects
                    projects.forEach { mavenProject ->
                        processProjectUpdates(mavenProject, indicator)
                    }

                    postProcessPropertyUpdates()

                    ApplicationManager.getApplication().invokeLater {
                        onFinished()
                    }
                }
            })
        }

        /**
         * Verarbeitet alle Abhängigkeiten und Plugins eines einzelnen Maven-Projekts
         * und fragt deren verfügbare Updates ab.
         */
        private fun processProjectUpdates(mavenProject: MavenProject, indicator: ProgressIndicator) {
            val allKeysWithVersions = mutableMapOf<String, String>()

            // Collect from dependency tree
            mavenProject.dependencyTree.forEach { node ->
                val dep = node.artifact
                allKeysWithVersions["${dep.groupId}:${dep.artifactId}"] = dep.version ?: ""
            }

            // Collect from plugins
            mavenProject.plugins.forEach { plugin ->
                allKeysWithVersions["${plugin.groupId}:${plugin.artifactId}"] = plugin.version ?: ""
            }

            // Collect from PSI for managed or unused dependencies/plugins
            collectFromPsi(mavenProject, allKeysWithVersions)

            allKeysWithVersions.forEach { (key, version) ->
                if (indicator.isCanceled) return
                val groupId = key.substringBefore(":")
                val artifactId = key.substringAfter(":")
                checkArtifactUpdate(groupId, artifactId, version, indicator)
            }
        }

        /**
         * Liest Abhängigkeiten und Plugins direkt aus der PSI-Struktur der `pom.xml`,
         * um auch nicht aufgelöste oder verwaltete Einträge zu erfassen.
         */
        private fun collectFromPsi(mavenProject: MavenProject, allKeysWithVersions: MutableMap<String, String>) {
            val psiFile = ApplicationManager.getApplication().runReadAction<XmlFile?> {
                PsiManager.getInstance(project).findFile(mavenProject.file) as? XmlFile
            } ?: return

            val effectiveProperties =
                mavenProject.properties.entries.associate { (k, v) -> k.toString() to v.toString() }

            ApplicationManager.getApplication().runReadAction {
                val rootTag = psiFile.document?.rootTag ?: return@runReadAction

                // parent
                val parentTag = rootTag.findFirstSubTag("parent")
                if (parentTag != null) {
                    val g = parentTag.findFirstSubTag("groupId")?.value?.text ?: ""
                    val a = parentTag.findFirstSubTag("artifactId")?.value?.text ?: ""
                    val v = parentTag.findFirstSubTag("version")?.value?.text ?: ""
                    if (g.isNotEmpty() && a.isNotEmpty() && !allKeysWithVersions.containsKey("$g:$a")) {
                        allKeysWithVersions["$g:$a"] = resolveVersionPlaceholder(v, effectiveProperties)
                    }
                }

                // dependencies
                collectTags(rootTag.findFirstSubTag("dependencies"), "dependency", allKeysWithVersions, effectiveProperties)

                // dependencyManagement
                val dmTag = rootTag.findFirstSubTag("dependencyManagement")
                collectTags(dmTag?.findFirstSubTag("dependencies"), "dependency", allKeysWithVersions, effectiveProperties)

                // build/plugins
                val buildTag = rootTag.findFirstSubTag("build")
                collectTags(buildTag?.findFirstSubTag("plugins"), "plugin", allKeysWithVersions, effectiveProperties)

                // build/pluginManagement
                val pmTag = buildTag?.findFirstSubTag("pluginManagement")
                collectTags(pmTag?.findFirstSubTag("plugins"), "plugin", allKeysWithVersions, effectiveProperties)
            }
        }

        /**
         * Extrahiert groupId, artifactId und Version aus den Kind-Tags eines XML-Parent-Tags
         * und fügt neue Einträge der Ziel-Map hinzu (bereits vorhandene Schlüssel werden nicht überschrieben).
         *
         * Property-basierte Versionen (z. B. `${netty-bom.version}`) werden über [effectiveProperties]
         * aufgelöst, damit der anschließende Update-Check gegen die tatsächliche Version filtert und
         * nicht gegen den rohen Platzhalter.
         */
        private fun collectTags(
            parentTag: XmlTag?,
            tagName: String,
            allKeysWithVersions: MutableMap<String, String>,
            effectiveProperties: Map<String, String>
        ) {
            parentTag?.findSubTags(tagName)?.forEach { tag ->
                val g = tag.findFirstSubTag("groupId")?.value?.text ?: ""
                val a = tag.findFirstSubTag("artifactId")?.value?.text ?: ""
                val v = tag.findFirstSubTag("version")?.value?.text ?: ""
                if (g.isNotEmpty() && a.isNotEmpty() && !allKeysWithVersions.containsKey("$g:$a")) {
                    allKeysWithVersions["$g:$a"] = resolveVersionPlaceholder(v, effectiveProperties)
                }
            }
        }

        /**
         * Berechnet Schnittmengen von verfügbaren Versionen, wenn mehrere Abhängigkeiten dieselbe Property nutzen.
         */
        /**
         * Berechnet für alle Abhängigkeiten, die dieselbe Maven-Property verwenden,
         * die Schnittmenge der verfügbaren Versionen, damit die Property konsistent aktualisiert wird.
         */
        private fun postProcessPropertyUpdates() {
            val propertyToDependencies: Map<String, List<String>> = dependencyToProperty
                .entries
                .groupBy({ it.value }, { it.key })

            propertyToDependencies.forEach { (_, depKeys) ->
                if (depKeys.size > 1) {
                    intersectVersions(depKeys)
                }
            }
        }

        /**
         * Reduziert die verfügbaren Versionen für eine Gruppe von Abhängigkeiten auf deren gemeinsame
         * Schnittmenge und wählt bei aktivierter Einstellung automatisch die konfigurierte Zielversion vor.
         */
        private fun intersectVersions(depKeys: List<String>) {
            var commonVersions: List<String>? = null
            depKeys.forEach { depKey ->
                val versions = availableVersions[depKey] ?: emptyList()
                commonVersions = if (commonVersions == null) {
                    versions
                } else {
                    commonVersions.intersect(versions.toSet()).toList()
                }
            }

            val sortedCommonVersions = commonVersions?.sortedWith { v1, v2 ->
                ComparableVersion(v2).compareTo(ComparableVersion(v1))
            } ?: emptyList()

            depKeys.forEach { depKey ->
                availableVersions[depKey] = sortedCommonVersions
                if (sortedCommonVersions.isNotEmpty() &&
                    MavenUpSettings.getInstance().state.versionAutoSelectionMode != VersionAutoSelectionMode.DISABLED
                ) {
                    val currentVersion = knownDependencies[depKey] ?: ""
                    val autoSelectedVersion = chooseAutoSelectedVersion(currentVersion, sortedCommonVersions)
                    if (autoSelectedVersion != currentVersion) {
                        selectedVersions[depKey] = autoSelectedVersion
                    } else {
                        selectedVersions.remove(depKey)
                    }
                } else if (sortedCommonVersions.isNotEmpty()) {
                    selectedVersions[depKey] = knownDependencies[depKey] ?: ""
                }
            }
        }

        /**
         * Ruft die verfügbaren Versionen für ein einzelnes Artefakt ab und speichert
         * die Ergebnisse in [availableVersions] sowie die Vorauswahl in [selectedVersions].
         */
        private fun checkArtifactUpdate(
            groupId: String?,
            artifactId: String?,
            currentVersion: String?,
            indicator: ProgressIndicator
        ) {
            indicator.text2 = "$groupId:$artifactId"

            if (groupId != null && artifactId != null) {
                val version = currentVersion ?: ""
                val versions = fetchVersions(groupId, artifactId, version)
                if (versions.isNotEmpty()) {
                    val key = "$groupId:$artifactId"
                    availableVersions[key] = versions
                    if (MavenUpSettings.getInstance().state.versionAutoSelectionMode != VersionAutoSelectionMode.DISABLED) {
                        val autoSelectedVersion = chooseAutoSelectedVersion(version, versions)
                        if (autoSelectedVersion != version) {
                            selectedVersions[key] = autoSelectedVersion
                        } else {
                            selectedVersions.remove(key)
                        }
                    } else {
                        selectedVersions[key] = version
                    }
                }
            }
        }

        /**
         * Ermittelt die automatisch vorausgewählte Zielversion für eine Abhängigkeit.
         *
         * Die neueste Version ist das erste Element von [versions]; die Liste wird von
         * [DependencyApiService.fetchVersions] so aufgebaut, dass die vom Repository deklarierte
         * neueste Version (`<release>`/`<latest>`) vorne steht.
         *
         * Bei [VersionAutoSelectionMode.LATEST_MINOR] wird die höchste Version innerhalb derselben
         * Major-Linie wie [currentVersion] verwendet. Existiert keine passende Version derselben
         * Major-Linie, bleibt die aktuelle Version erhalten (keine Fremd-Major-Vorauswahl).
         */
        private fun chooseAutoSelectedVersion(currentVersion: String, versions: List<String>): String {
            val newestVersion = versions.firstOrNull().orEmpty()
            if (newestVersion.isEmpty() || newestVersion == currentVersion) return currentVersion
            return when (MavenUpSettings.getInstance().state.versionAutoSelectionMode) {
                VersionAutoSelectionMode.DISABLED -> currentVersion
                VersionAutoSelectionMode.LATEST -> newestVersion
                VersionAutoSelectionMode.LATEST_MINOR ->
                    latestVersionWithinSameMajor(currentVersion, versions) ?: currentVersion
            }
        }

        /**
         * Sucht die höchste verfügbare Version mit derselben numerischen Major-Version wie [currentVersion].
         *
         * @return Die gefundene Version oder `null`, wenn die aktuelle Version keine numerische Major-Version
         * besitzt oder keine passende Kandidaten-Version vorhanden ist.
         */
        private fun latestVersionWithinSameMajor(currentVersion: String, versions: List<String>): String? {
           val currentMajor = extractLeadingMajorNumber(currentVersion) ?: return null
           return versions
               .sortedWith { v1, v2 -> ComparableVersion(v2).compareTo(ComparableVersion(v1)) }
               .firstOrNull { extractLeadingMajorNumber(it) == currentMajor }
        }

        /**
         * Extrahiert die führende numerische Major-Version aus einem Versionsstring.
         *
         * Beispiele: `2.7.18` -> `2`, `1-RC1` -> `1`. Bei nicht-numerischem Präfix wird `null` geliefert.
         */
        private fun extractLeadingMajorNumber(version: String): Int? {
            val match = Regex("""^(\d+)""").find(version.trim()) ?: return null
            return match.groupValues[1].toIntOrNull()
        }

        /**
         * Aktualisiert die Versionsnummer in einem XML-Tag. Berücksichtigt dabei, ob die Version
         * direkt oder über eine Maven-Property definiert ist.
         */
        internal fun updateXmlTagVersion(tag: XmlTag, newVersion: String, propertiesTag: XmlTag?) {
            val versionTag = tag.findFirstSubTag("version")
            if (versionTag != null) {
                // Use versionTag.value.trimmedText for recognition
                val versionContent = versionTag.value.trimmedText

                if (versionContent.startsWith("\${") && versionContent.endsWith("}")) {
                    val propertyName = versionContent.substring(2, versionContent.length - 1)
                    val propertyTag = propertiesTag?.findFirstSubTag(propertyName)
                    if (propertyTag != null) {
                        propertyTag.value.text = newVersion
                        return // Property updated, don't overwrite version tag
                    }
                }
                // Fallback or direct update: overwrite version tag
                versionTag.value.text = newVersion
            } else {
                val newTag = tag.createChildTag("version", null, newVersion, false)
                tag.addSubTag(newTag, false)
            }
        }

        private fun fetchVersions(groupId: String, artifactId: String, currentVersion: String): List<String> {
            return dependencyApiService.fetchVersions(groupId, artifactId, currentVersion)
        }

        /**
         * Liefert die UI-Komponente des Tool Windows zurück.
         */
        fun getContent(): JBPanel<JBPanel<*>> = content

        /**
         * Sucht innerhalb eines XML-Parent-Tags nach einem Kind-Tag (z. B. `dependency` oder `plugin`)
         * das mit der angegebenen groupId und artifactId übereinstimmt.
         */
        private fun findTag(parentTag: XmlTag?, tagName: String, groupId: String, artifactId: String): XmlTag? {
            return parentTag?.findSubTags(tagName)?.find { tag ->
                val g = tag.findFirstSubTag("groupId")?.value?.text
                val a = tag.findFirstSubTag("artifactId")?.value?.text
                g == groupId && a == artifactId
            }
        }

        /**
         * Sucht nach einer Abhängigkeit in der `pom.xml`.
         * Unterstützt sowohl direkte Abhängigkeiten als auch Einträge im `dependencyManagement`.
         */
        internal fun findDependency(
            rootTag: XmlTag?,
            groupId: String,
            artifactId: String,
            isManaged: Boolean
        ): XmlTag? {
            if (!isManaged) {
                val dependenciesTag = rootTag?.findFirstSubTag("dependencies")
                val localDep = findTag(dependenciesTag, "dependency", groupId, artifactId)
                if (localDep != null) return localDep
            }

            val dmTag = rootTag?.findFirstSubTag("dependencyManagement")
            val dmDepsTag = dmTag?.findFirstSubTag("dependencies")
            return findTag(dmDepsTag, "dependency", groupId, artifactId)
        }

        /**
         * Sucht nach dem `<parent>`-Tag in der `pom.xml`, das mit der angegebenen groupId
         * und artifactId übereinstimmt.
         *
         * @param rootTag Das Root-Tag der `pom.xml`.
         * @param groupId Die gesuchte Group-ID.
         * @param artifactId Die gesuchte Artefakt-ID.
         * @return Das `<parent>`-Tag oder `null`, wenn keines passt.
         */
        internal fun findParent(rootTag: XmlTag?, groupId: String, artifactId: String): XmlTag? {
            val parentTag = rootTag?.findFirstSubTag("parent") ?: return null
            val g = parentTag.findFirstSubTag("groupId")?.value?.text
            val a = parentTag.findFirstSubTag("artifactId")?.value?.text
            return if (g == groupId && a == artifactId) parentTag else null
        }

        /**
         * Sucht nach einem Plugin in `<build><plugins>` oder `<build><pluginManagement>`.
         */
        private fun findPlugin(rootTag: XmlTag?, groupId: String, artifactId: String, isManaged: Boolean): XmlTag? {
            val buildTag = rootTag?.findFirstSubTag("build")
            if (!isManaged) {
                val pluginsTag = buildTag?.findFirstSubTag("plugins")
                val localPlugin = findTag(pluginsTag, "plugin", groupId, artifactId)
                if (localPlugin != null) return localPlugin
            }

            val pmTag = buildTag?.findFirstSubTag("pluginManagement")
            val pmPluginsTag = pmTag?.findFirstSubTag("plugins")
            return findTag(pmPluginsTag, "plugin", groupId, artifactId)
        }

        /**
         * Öffnet den Editor und springt zur Definition der angegebenen Abhängigkeit in der `pom.xml`.
         */
        private fun navigateToDependency(groupId: String, artifactId: String, type: String = "dependency") {
            ProgressManager.getInstance().run(object : Task.Backgroundable(
                project,
                MyMessageBundle.message("toolwindow.MyToolWindow.refresh.button"),
                false
            ) {
                override fun run(indicator: ProgressIndicator) {
                    for (mavenProject in MavenProjectsManager.getInstance(project).projects) {
                        val pomFile = mavenProject.file
                        val psiFile = ApplicationManager.getApplication().runReadAction<XmlFile?> {
                            PsiManager.getInstance(project).findFile(pomFile) as? XmlFile
                        } ?: continue

                        val targetTag = ApplicationManager.getApplication().runReadAction<XmlTag?> {
                            val rootTag = psiFile.document?.rootTag
                            val managedDepType =
                                MyMessageBundle.message(TOOLWINDOW_MY_TOOL_WINDOW_TYPE_MANAGED_DEPENDENCY)
                            val isManaged = type == managedDepType || type == MANAGED_PLUGIN

                            if (type == PARENT_TYPE) {
                                findParent(rootTag, groupId, artifactId)
                            } else if (type == "dependency" || type == managedDepType) {
                                findDependency(rootTag, groupId, artifactId, isManaged)
                            } else {
                                findPlugin(rootTag, groupId, artifactId, isManaged)
                            }
                        }

                        if (targetTag != null) {
                            ApplicationManager.getApplication().invokeLater {
                                val descriptor = OpenFileDescriptor(project, pomFile, targetTag.textOffset)
                                FileEditorManager.getInstance(project).openTextEditor(descriptor, true)
                            }
                            return
                        }
                    }
                }
            })
        }

        /**
         * Öffnet die Plugin-Einstellungen.
         */
        private fun openSettings() {
            com.intellij.openapi.options.ShowSettingsUtil.getInstance()
                .showSettingsDialog(project, MavenUpConfigurable::class.java)
        }

        /**
         * Öffnet die Repository-Browser-Seite der angegebenen Abhängigkeit im Standard-Browser.
         * Der verwendete Repository-Browser wird aus den Plugin-Einstellungen gelesen.
         */
        private fun openInMavenRepository(groupId: String, artifactId: String, version: String) {
            val browser = MavenUpSettings.getInstance().state.repositoryBrowser
            BrowserUtil.browse(buildMavenRepositoryUrl(groupId, artifactId, version, browser))
        }
    }
}
