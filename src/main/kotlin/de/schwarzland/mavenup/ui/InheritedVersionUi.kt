package de.schwarzland.mavenup.ui

import com.intellij.ui.JBColor
import java.awt.Component
import java.awt.Font
import javax.swing.JLabel
import javax.swing.JTable
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableCellRenderer

/** Gedämpfte Textfarbe für geerbte Versionen (Light-/Dark-Mode). */
private val INHERITED_VERSION_COLOR = JBColor.GRAY

/** Message-Bundle-Schlüssel für den Marker hinter einer geerbten Version. */
internal const val INHERITED_VERSION_MARKER_KEY = "toolwindow.MyToolWindow.version.inheritedMarker"

/** Message-Bundle-Schlüssel für den Tooltip einer geerbten Version. */
internal const val INHERITED_VERSION_TOOLTIP_KEY = "toolwindow.MyToolWindow.version.inheritedTooltip"

/**
 * Erzeugt den Anzeigetext der Spalte **Current Version**.
 *
 * Wird die Version nicht in der `pom.xml` deklariert, sondern vom Parent-POM oder einem
 * importierten BOM geerbt, erhält der Text den lokalisierten Marker „(inherited)".
 *
 * @param version Die aufgelöste, aktuell verwendete Version.
 * @param inherited `true`, wenn die `pom.xml` kein eigenes `<version>`-Tag deklariert.
 * @return Der Anzeigetext, bei geerbter Version mit Marker.
 */
internal fun inheritedVersionCellText(version: String, inherited: Boolean): String =
    if (inherited) MyMessageBundle.message(INHERITED_VERSION_MARKER_KEY, version) else version

/**
 * Liefert den Tooltip-Text der Spalte **Current Version**.
 *
 * @param inherited `true`, wenn die `pom.xml` kein eigenes `<version>`-Tag deklariert.
 * @return Der erklärende Tooltip bei geerbter Version, sonst `null`.
 */
internal fun inheritedVersionTooltip(inherited: Boolean): String? =
    if (inherited) MyMessageBundle.message(INHERITED_VERSION_TOOLTIP_KEY) else null

/**
 * Erstellt den Zellen-Renderer der Spalte **Current Version**, der geerbte Versionen kennzeichnet.
 *
 * Der Renderer prüft primär die Eigenschaft `versionInherited` der konkreten Modellzeile über [modelRowsProvider]
 * bzw. schlägt den Zeilenschlüssel (`entryKey` oder `groupId:artifactId`) in [inheritedKeys] nach – die
 * Menge wird als lebende Referenz gehalten, sodass ein Refresh ohne erneutes Setzen des Renderers
 * wirkt. Geerbte Versionen werden kursiv und in gedämpfter Farbe mit dem Marker „(inherited)"
 * dargestellt und erhalten einen erklärenden Tooltip. Der Modellwert der Zelle bleibt unverändert,
 * damit Filter, Sortierung, Versionsauswahl und Sicherheitsprüfungen weiterhin mit der reinen
 * Versionsnummer arbeiten.
 *
 * Die Vordergrundfarbe wird bei **jedem** Aufruf explizit gesetzt: `DefaultTableCellRenderer`
 * verwendet dieselbe Komponenteninstanz für alle Zellen und merkt sich eine über `setForeground`
 * gesetzte Farbe als `unselectedForeground`. Ohne das erneute Setzen würden nach der ersten
 * geerbten Zeile alle folgenden Zeilen gedämpft dargestellt.
 *
 * @param inheritedKeys Die Schlüssel der Abhängigkeiten ohne eigenes `<version>`-Tag.
 * @param modelRowsProvider Optionaler Provider für die aktuellen Modellzeilen der Tabelle.
 * @return Der konfigurierte [TableCellRenderer].
 */
@JvmOverloads
internal fun createCurrentVersionRenderer(
    inheritedKeys: Set<String>,
    modelRowsProvider: (() -> List<RefreshRow>)? = null
): TableCellRenderer =
    object : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable?,
            value: Any?,
            isSelected: Boolean,
            hasFocus: Boolean,
            row: Int,
            column: Int
        ): Component {
            val component = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
            val label = component as? JLabel ?: return component
            val inherited = table != null && isInheritedRow(table, row, inheritedKeys, modelRowsProvider?.invoke())
            label.text = inheritedVersionCellText(value?.toString().orEmpty(), inherited)
            label.font = label.font.deriveFont(if (inherited) Font.ITALIC else Font.PLAIN)
            label.foreground = when {
                isSelected -> table?.selectionForeground ?: label.foreground
                inherited -> INHERITED_VERSION_COLOR
                else -> table?.foreground ?: label.foreground
            }
            label.toolTipText = inheritedVersionTooltip(inherited)
            return label
        }
    }

/**
 * Prüft, ob die Version der angegebenen Tabellenzeile geerbt ist.
 *
 * @param table Die Abhängigkeitstabelle.
 * @param row Der Zeilenindex in der Ansicht.
 * @param inheritedKeys Die Schlüssel der Abhängigkeiten ohne eigenes `<version>`-Tag.
 * @param modelRows Die aktuellen Modellzeilen der Tabelle, falls verfügbar.
 * @return `true`, wenn die Modellzeile als geerbt markiert ist oder der Zeilenschlüssel in [inheritedKeys] enthalten ist.
 */
private fun isInheritedRow(
    table: JTable,
    row: Int,
    inheritedKeys: Set<String>,
    modelRows: List<RefreshRow>? = null
): Boolean {
    if (row < 0 || row >= table.rowCount) return false
    val modelRow = if (row in 0 until table.rowCount) table.convertRowIndexToModel(row) else -1
    if (modelRows != null && modelRow in modelRows.indices) {
        val refreshRow = modelRows[modelRow]
        return refreshRow.versionInherited || inheritedKeys.contains(refreshRow.entryKey)
    }
    if (inheritedKeys.isEmpty()) return false
    val groupId = table.getValueAt(row, GROUP_ID_COLUMN)?.toString().orEmpty()
    val artifactId = table.getValueAt(row, ARTIFACT_ID_COLUMN)?.toString().orEmpty()
    val key = "$groupId:$artifactId"
    return inheritedKeys.contains(key)
}
