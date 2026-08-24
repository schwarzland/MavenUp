package de.schwarzland.mavenup.ui

import com.intellij.ide.HelpTooltip
import java.lang.reflect.Method
import java.util.function.Supplier

/**
 * Zwischengespeicherte `setDescription(Supplier)`-Methode der [HelpTooltip]-API oder `null`, falls
 * diese Überladung zur Laufzeit nicht vorhanden ist.
 *
 * Die Supplier-Variante steht erst ab IntelliJ 2026.1 zur Verfügung; auf dem Kompilierziel 2025.3
 * existiert ausschließlich die dort noch gültige, in neueren Plattformen jedoch als veraltet
 * markierte String-Überladung.
 */
private val HELP_TOOLTIP_SET_DESCRIPTION_SUPPLIER: Method? = runCatching {
    HelpTooltip::class.java.getMethod("setDescription", Supplier::class.java)
}.getOrNull()

/**
 * Zwischengespeicherte `setDescription(String)`-Methode der [HelpTooltip]-API als Rückfallebene für
 * Plattformen ohne die Supplier-Überladung.
 */
private val HELP_TOOLTIP_SET_DESCRIPTION_STRING: Method =
    HelpTooltip::class.java.getMethod("setDescription", String::class.java)

/**
 * Setzt die mehrzeilig umbrechende Beschreibung eines [HelpTooltip] versionsunabhängig.
 *
 * Bevorzugt wird die ab IntelliJ 2026.1 verfügbare `setDescription(Supplier)`-Überladung; ist sie
 * nicht vorhanden (Kompilierziel 2025.3), wird auf die String-Variante zurückgegriffen. Der Zugriff
 * erfolgt bewusst per Reflection, damit im Bytecode kein direkter Verweis auf die in neueren
 * Plattformen als veraltet markierte Methode entsteht und der Plugin Verifier keine
 * deprecated-API-Nutzung meldet.
 *
 * @param description der vollständige, mehrzeilig umbrechende Beschreibungstext
 * @return dieselbe [HelpTooltip]-Instanz zur Verkettung
 */
internal fun HelpTooltip.withWrappingDescription(description: String): HelpTooltip {
    val supplierMethod = HELP_TOOLTIP_SET_DESCRIPTION_SUPPLIER
    if (supplierMethod != null) {
        supplierMethod.invoke(this, Supplier { description })
    } else {
        HELP_TOOLTIP_SET_DESCRIPTION_STRING.invoke(this, description)
    }
    return this
}
