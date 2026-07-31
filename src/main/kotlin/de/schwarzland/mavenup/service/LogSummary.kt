package de.schwarzland.mavenup.service

private const val MAX_LOG_ITEMS = 10

/**
 * Hilfsfunktionen zur Formatierung und Zusammenfassung von Daten für Logging-Zwecke.
 *
 * Diese Datei enthält Funktionen, die dabei helfen, lange Listen von Informationen
 * (wie z. B. Versionsnummern) für Debug-Logs lesbar aufzubereiten, ohne das Log
 * mit zu vielen Einträgen zu überfluten.
 */

/**
 * Erstellt eine kompakte Zusammenfassung einer Liste von Strings für das Debug-Log.
 * Es werden maximal [MAX_LOG_ITEMS] Elemente angezeigt. Wenn die Liste länger ist,
 * wird die Anzahl der ausgelassenen Elemente angehängt.
 *
 * @param values Die Liste der zu protokollierenden Strings.
 * @return Ein formatierter String für die Log-Ausgabe.
 */
internal fun summarizeForDebugLog(values: List<String>): String {
    val displayedValues = values.take(MAX_LOG_ITEMS).joinToString(", ")
    val omittedCount = values.size - MAX_LOG_ITEMS
    return if (omittedCount > 0) {
        "$displayedValues, ... (+$omittedCount more)"
    } else {
        displayedValues
    }
}
