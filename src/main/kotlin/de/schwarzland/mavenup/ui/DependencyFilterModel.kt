package de.schwarzland.mavenup.ui

import java.awt.Component
import javax.swing.DefaultListCellRenderer
import javax.swing.JList
import javax.swing.ListCellRenderer

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
 * Bündelt die Message-Bundle-Schlüssel der kontextspezifischen Optionstexte eines
 * dreiwertigen Filters.
 *
 * Damit lassen sich für jeden Filter (Änderungen, Updates, Sicherheitslücken) eigene,
 * selbsterklärende Bezeichnungen anzeigen, ohne die generischen [TriStateFilter]-Werte
 * zu verändern.
 *
 * @property allKey Schlüssel des Textes für [TriStateFilter.ALL].
 * @property yesKey Schlüssel des Textes für [TriStateFilter.YES].
 * @property noKey Schlüssel des Textes für [TriStateFilter.NO].
 */
internal data class TriStateFilterLabels(
    val allKey: String,
    val yesKey: String,
    val noKey: String
)

/**
 * Liefert den kontextspezifischen Anzeigetext einer Filteroption.
 *
 * @param option Die darzustellende Filteroption.
 * @param labels Die Message-Bundle-Schlüssel des jeweiligen Filters.
 * @return Der lokalisierte, selbsterklärende Anzeigetext der Option.
 */
internal fun triStateFilterOptionLabel(option: TriStateFilter, labels: TriStateFilterLabels): String =
    when (option) {
        TriStateFilter.ALL -> MyMessageBundle.message(labels.allKey)
        TriStateFilter.YES -> MyMessageBundle.message(labels.yesKey)
        TriStateFilter.NO -> MyMessageBundle.message(labels.noKey)
    }

/**
 * Erzeugt einen Renderer, der die [TriStateFilter]-Werte einer Filter-Combobox mit
 * kontextspezifischen, selbsterklärenden Texten anzeigt.
 *
 * Wird von allen Filterzeilen (Haupttabelle und transitive Ansicht) gemeinsam genutzt.
 *
 * @param labels Die Message-Bundle-Schlüssel der Optionstexte des jeweiligen Filters.
 * @return Ein [ListCellRenderer] für die Filter-Combobox.
 */
internal fun triStateFilterRenderer(labels: TriStateFilterLabels): ListCellRenderer<in TriStateFilter> =
    object : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>?,
            value: Any?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): Component {
            val text = (value as? TriStateFilter)?.let { triStateFilterOptionLabel(it, labels) }
                ?: value?.toString()
            return super.getListCellRendererComponent(list, text, index, isSelected, cellHasFocus)
        }
    }

/** Kontextspezifische Optionstexte des Änderungs-Filters. */
internal val CHANGES_FILTER_LABELS = TriStateFilterLabels(
    "toolwindow.MyToolWindow.filter.changes.option.all",
    "toolwindow.MyToolWindow.filter.changes.option.yes",
    "toolwindow.MyToolWindow.filter.changes.option.no"
)

/** Kontextspezifische Optionstexte des Updates-Filters. */
internal val UPDATES_FILTER_LABELS = TriStateFilterLabels(
    "toolwindow.MyToolWindow.filter.updates.option.all",
    "toolwindow.MyToolWindow.filter.updates.option.yes",
    "toolwindow.MyToolWindow.filter.updates.option.no"
)

/** Kontextspezifische Optionstexte des Filters nach der Herkunft der Version. */
internal val VERSION_SOURCE_FILTER_LABELS = TriStateFilterLabels(
    "toolwindow.MyToolWindow.filter.versionSource.option.all",
    "toolwindow.MyToolWindow.filter.versionSource.option.yes",
    "toolwindow.MyToolWindow.filter.versionSource.option.no"
)

/**
 * Filteroption des Sicherheitslücken-Filters.
 *
 * Im Gegensatz zu [TriStateFilter] unterscheidet dieser Filter zusätzlich, ob die Befunde einer
 * Zeile aus der Abhängigkeit selbst oder aus ihren transitiven Abhängigkeiten stammen.
 *
 * @property labelKey Der Schlüssel des lokalisierten Anzeigetextes im Message-Bundle.
 */
internal enum class VulnerabilityFilter(val labelKey: String) {
    /** Alle Zeilen anzeigen (keine Filterung nach Sicherheitslücken). */
    ALL("toolwindow.MyToolWindow.filter.vulnerabilities.option.all"),

    /** Nur Zeilen mit beliebigen Befunden (eigene oder transitive) anzeigen. */
    VULNERABLE("toolwindow.MyToolWindow.filter.vulnerabilities.option.vulnerable"),

    /** Nur Zeilen anzeigen, deren Abhängigkeit selbst betroffen ist. */
    SELF_VULNERABLE("toolwindow.MyToolWindow.filter.vulnerabilities.option.self"),

    /** Nur Zeilen anzeigen, die Befunde in ihren transitiven Abhängigkeiten haben. */
    TRANSITIVE_VULNERABLE("toolwindow.MyToolWindow.filter.vulnerabilities.option.transitive"),

    /** Nur Zeilen ohne jegliche Befunde anzeigen. */
    NOT_VULNERABLE("toolwindow.MyToolWindow.filter.vulnerabilities.option.notVulnerable");

    /** Liefert die lokalisierte Bezeichnung der Filteroption. */
    val label: String
        get() = MyMessageBundle.message(labelKey)

    override fun toString(): String = label
}

/**
 * Erzeugt einen Renderer, der die [VulnerabilityFilter]-Werte einer Filter-Combobox mit ihren
 * lokalisierten Texten anzeigt.
 *
 * @return Ein [ListCellRenderer] für die Sicherheitslücken-Filter-Combobox.
 */
internal fun vulnerabilityFilterRenderer(): ListCellRenderer<in VulnerabilityFilter> =
    object : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>?,
            value: Any?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): Component {
            val text = (value as? VulnerabilityFilter)?.label ?: value?.toString()
            return super.getListCellRendererComponent(list, text, index, isSelected, cellHasFocus)
        }
    }

/**
 * Fasst die filterrelevanten Werte einer einzelnen Tabellenzeile zusammen.
 *
 * @property groupId Die GroupId der Zeile.
 * @property artifactId Die ArtifactId der Zeile.
 * @property property Der Property-Name der Zeile.
 * @property type Der Typ der Zeile.
 * @property hasChange `true`, wenn für die Zeile eine Versionsänderung vorliegt.
 * @property hasUpdate `true`, wenn für die Zeile eine neuere Version verfügbar ist.
 * @property hasDirectVulnerabilities `true`, wenn die Abhängigkeit der Zeile selbst betroffen ist.
 * @property hasTransitiveVulnerabilities `true`, wenn mindestens eine transitive Abhängigkeit der Zeile betroffen ist.
 * @property versionInherited `true`, wenn die `pom.xml` für die Zeile kein eigenes `<version>`-Tag
 * deklariert und die Version daher vom Parent-POM oder einem importierten BOM stammt.
 */
internal data class FilterRow(
    val groupId: String,
    val artifactId: String,
    val property: String,
    val type: String,
    val hasChange: Boolean = false,
    val hasUpdate: Boolean = false,
    val hasDirectVulnerabilities: Boolean = false,
    val hasTransitiveVulnerabilities: Boolean = false,
    val versionInherited: Boolean = false
) {
    /** `true`, wenn für die Zeile eigene oder transitive Sicherheitslücken gemeldet sind. */
    val hasVulnerabilities: Boolean
        get() = hasDirectVulnerabilities || hasTransitiveVulnerabilities
}

/**
 * Fasst die aktiven Filterkriterien der Haupttabelle zusammen.
 *
 * @property searchText Der eingegebene Suchtext (wird getrimmt und case-insensitiv verglichen).
 * @property typeFilter Der ausgewählte Typ oder ein leerer String für "alle Typen".
 * @property changesFilter Die ausgewählte Filteroption für Änderungen (Alle, Ja, Nein).
 * @property updatesFilter Die ausgewählte Filteroption für verfügbare Updates (Alle, Ja, Nein).
 * @property vulnerabilitiesFilter Die ausgewählte Filteroption für Sicherheitslücken.
 * @property versionSourceFilter Die ausgewählte Filteroption für die Herkunft der Version
 * ([TriStateFilter.YES] zeigt nur geerbte, [TriStateFilter.NO] nur in der `pom.xml` deklarierte Versionen).
 */
internal data class FilterCriteria(
    val searchText: String,
    val typeFilter: String,
    val changesFilter: TriStateFilter = TriStateFilter.ALL,
    val updatesFilter: TriStateFilter = TriStateFilter.ALL,
    val vulnerabilitiesFilter: VulnerabilityFilter = VulnerabilityFilter.ALL,
    val versionSourceFilter: TriStateFilter = TriStateFilter.ALL
)

/**
 * Prüft, ob eine Tabellenzeile den aktuellen Filterkriterien entspricht.
 *
 * Der Textfilter wird case-insensitiv gegen GroupId, ArtifactId und Property geprüft;
 * die Zeile passt, sobald einer dieser Werte den Suchtext enthält. Ein leerer Suchtext
 * lässt alle Zeilen zu. Der Typfilter passt bei leerem Wert auf jeden Typ, sonst nur bei
 * exakter Übereinstimmung des Typs. Der Änderungs- und Updates-Filter prüfen,
 * ob Änderungen bzw. verfügbare Updates vorliegen (`YES`), nicht vorliegen (`NO`) oder
 * der Filter inaktiv ist (`ALL`). Der Sicherheitslücken-Filter unterscheidet zusätzlich zwischen
 * beliebigen, eigenen und transitiven Befunden. Der Filter nach der Herkunft der Version zeigt
 * bei `YES` nur Zeilen mit geerbter Version und bei `NO` nur Zeilen mit einem eigenen
 * `<version>`-Tag in der `pom.xml`.
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
    val updatesMatches = when (criteria.updatesFilter) {
        TriStateFilter.ALL -> true
        TriStateFilter.YES -> row.hasUpdate
        TriStateFilter.NO -> !row.hasUpdate
    }
    val vulnerabilitiesMatches = when (criteria.vulnerabilitiesFilter) {
        VulnerabilityFilter.ALL -> true
        VulnerabilityFilter.VULNERABLE -> row.hasVulnerabilities
        VulnerabilityFilter.SELF_VULNERABLE -> row.hasDirectVulnerabilities
        VulnerabilityFilter.TRANSITIVE_VULNERABLE -> row.hasTransitiveVulnerabilities
        VulnerabilityFilter.NOT_VULNERABLE -> !row.hasVulnerabilities
    }
    val versionSourceMatches = when (criteria.versionSourceFilter) {
        TriStateFilter.ALL -> true
        TriStateFilter.YES -> row.versionInherited
        TriStateFilter.NO -> !row.versionInherited
    }
    return textMatches && typeMatches && changesMatches && updatesMatches &&
        vulnerabilitiesMatches && versionSourceMatches
}
