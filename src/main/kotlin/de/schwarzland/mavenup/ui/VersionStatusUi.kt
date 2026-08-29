package de.schwarzland.mavenup.ui

import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.scale.JBUIScale
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.BorderFactory
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.UIManager

/** Farbe für Abhängigkeiten, die bereits auf der neuesten Version sind (Light-/Dark-Mode). */
private val VERSION_UP_TO_DATE_COLOR = com.intellij.ui.JBColor(Color(0, 128, 0), Color(80, 200, 80))

/** Farbe für Abhängigkeiten, für die ein Update verfügbar ist (Light-/Dark-Mode). */
private val VERSION_UPDATE_AVAILABLE_COLOR = com.intellij.ui.JBColor(Color(204, 120, 0), Color(255, 180, 50))

/** Glyph für Abhängigkeiten auf der neuesten Version (grüner Haken „✓"). */
private const val VERSION_UP_TO_DATE_GLYPH = "\u2713"

/** Glyph für Abhängigkeiten mit verfügbarem Update (Pfeil nach oben „↑"). */
private const val VERSION_UPDATE_AVAILABLE_GLYPH = "\u2191"

/**
 * Icon, das den Aufwärtspfeil („↑") der Spalte New Version als Toolbar-Icon rendert.
 *
 * Der Pfeil signalisiert in der Tabelle ein verfügbares Update; dasselbe Zeichen wird
 * dadurch auch für das Aufklappmenü „Select Highest Version" verwendet, sodass die
 * Aktion optisch denselben Pfeil wie die Statusanzeige der Tabelle nutzt. Das Glyph
 * wird zentriert und fett in der Vordergrundfarbe der aufrufenden Komponente gezeichnet;
 * die IntelliJ-Plattform erzeugt daraus bei Bedarf automatisch eine ausgegraute
 * Deaktiviert-Variante.
 */
internal object VersionUpdateArrowIcon : Icon {

    /** Kantenlänge des Icons in logischen Pixeln (Standard-Toolbar-Größe). */
    private const val ICON_SIZE = 16

    /** Schriftgröße des Glyphs in logischen Pixeln. */
    private const val GLYPH_FONT_SIZE = 13f

    override fun getIconWidth(): Int = JBUIScale.scale(ICON_SIZE)

    override fun getIconHeight(): Int = JBUIScale.scale(ICON_SIZE)

    /**
     * Zeichnet das Aufwärtspfeil-Glyph zentriert in das Icon-Rechteck.
     *
     * @param c Die aufrufende Komponente; liefert Vordergrundfarbe und Basis-Schriftart.
     * @param g Der Grafikkontext, in den gezeichnet wird.
     * @param x Die linke Kante des Icon-Rechtecks.
     * @param y Die obere Kante des Icon-Rechtecks.
     */
    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g2.color = c?.foreground ?: com.intellij.ui.JBColor.foreground()
            val baseFont = c?.font ?: UIManager.getFont("Label.font")
            g2.font = baseFont.deriveFont(Font.BOLD, JBUIScale.scale(GLYPH_FONT_SIZE))
            val metrics = g2.fontMetrics
            val glyph = VERSION_UPDATE_AVAILABLE_GLYPH
            val drawX = x + (iconWidth - metrics.stringWidth(glyph)) / 2
            val drawY = y + (iconHeight - metrics.height) / 2 + metrics.ascent
            g2.drawString(glyph, drawX, drawY)
        } finally {
            g2.dispose()
        }
    }
}

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
 * Bestimmt, ob für eine Abhängigkeit eine neuere Version verfügbar ist.
 *
 * Eine neuere Version liegt vor, wenn eine höchste bekannte Version existiert und diese
 * von der aktuell verwendeten Version abweicht.
 *
 * @param currentVersion Die aktuell verwendete Version.
 * @param newestVersion Die höchste bekannte Version (erstes Element der Versionsliste).
 * @return `true`, wenn eine neuere Version verfügbar ist.
 */
internal fun hasNewerVersion(currentVersion: String, newestVersion: String): Boolean =
    newestVersion.isNotEmpty() && newestVersion != currentVersion

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
 * Ermittelt Anzeigetext und Fettschrift-Status für einen Eintrag der Versions-Dropdown-Liste in
 * einem Durchgang, damit der Vergleich mit der aktuellen und der empfohlenen Version nicht doppelt
 * ausgewertet wird.
 *
 * Entspricht [value] der aktuell verwendeten Version [currentVersion], wird der Eintrag mit
 * einem lokalisierten Marker („(current)") versehen, damit die aktuelle Version – insbesondere
 * bei aktivierter Option „alle Versionen anzeigen" – in der Liste hervorsticht. Entspricht [value]
 * der empfohlenen Fix-Version [recommendedVersion], erhält der Eintrag den Marker „(recommended)".
 * Der „(current)"-Marker hat Vorrang, falls beide Versionen übereinstimmen. In beiden Fällen wird
 * der Eintrag hervorgehoben (fett); andernfalls wird der unveränderte Versionswert ohne Hervorhebung
 * zurückgegeben.
 *
 * @param value Der Versionswert des Dropdown-Eintrags.
 * @param currentVersion Die aktuell verwendete Version der Abhängigkeit.
 * @param recommendedVersion Die empfohlene Fix-Version oder ein leerer String, wenn keine vorliegt.
 * @return Der ggf. mit Marker versehene Anzeigetext und ob der Eintrag hervorgehoben werden soll.
 */
internal fun versionDropdownItemDisplay(
    value: String,
    currentVersion: String,
    recommendedVersion: String = ""
): Pair<String, Boolean> = when {
    currentVersion.isNotEmpty() && value == currentVersion ->
        MyMessageBundle.message("toolwindow.MyToolWindow.version.currentMarker", value) to true
    recommendedVersion.isNotEmpty() && value == recommendedVersion ->
        MyMessageBundle.message("toolwindow.MyToolWindow.version.recommendedMarker", value) to true
    else -> value to false
}

/**
 * Ermittelt den Anzeigetext für einen Eintrag der Versions-Dropdown-Liste.
 *
 * Reine Textvariante von [versionDropdownItemDisplay] ohne den Hervorhebungs-Status; wird von
 * Aufrufern verwendet, die nur den Text benötigen.
 *
 * @param value Der Versionswert des Dropdown-Eintrags.
 * @param currentVersion Die aktuell verwendete Version der Abhängigkeit.
 * @param recommendedVersion Die empfohlene Fix-Version oder ein leerer String, wenn keine vorliegt.
 * @return Der ggf. mit Marker versehene Anzeigetext.
 */
internal fun versionDropdownItemText(
    value: String,
    currentVersion: String,
    recommendedVersion: String = ""
): String = versionDropdownItemDisplay(value, currentVersion, recommendedVersion).first

/**
 * Setzt den einheitlichen Dropdown-Renderer der Versions-ComboBox.
 *
 * Das Anzeigefeld (Index `-1`) übernimmt Farbe und Font der ComboBox, damit die Statusfarbe einer
 * ausstehenden Änderung erhalten bleibt. In der aufgeklappten Liste werden die aktuelle Version und
 * die empfohlene Fix-Version über [versionDropdownItemDisplay] mit Marker versehen und fett dargestellt.
 * Der Renderer wird von der Haupttabelle und der Ansicht der transitiven Sicherheitslücken gemeinsam
 * genutzt, damit beide Listen identisch aussehen.
 *
 * @param box Die zu konfigurierende ComboBox.
 * @param currentVersion Die aktuell verwendete Version der Abhängigkeit.
 * @param recommendedVersion Die empfohlene Fix-Version oder ein leerer String, wenn keine vorliegt.
 */
internal fun applyVersionDropdownRenderer(
    box: ComboBox<String>,
    currentVersion: String,
    recommendedVersion: String
) {
    box.setRenderer { _, itemValue, index, _, _ ->
        JLabel(itemValue ?: "").apply {
            if (index == -1) {
                foreground = box.foreground
                font = box.font
            } else if (itemValue != null) {
                val (displayText, highlighted) = versionDropdownItemDisplay(itemValue, currentVersion, recommendedVersion)
                text = displayText
                if (highlighted) {
                    font = font.deriveFont(Font.BOLD)
                }
            }
        }
    }
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
            font = font.deriveFont(Font.BOLD)
            if (statusColor != null) {
                foreground = statusColor
            }
        }
        add(statusLabel, BorderLayout.WEST)
        if (hasChange) {
            combo.font = combo.font.deriveFont(Font.BOLD)
        }
        add(combo, BorderLayout.CENTER)
        toolTipText = tooltip
    }
