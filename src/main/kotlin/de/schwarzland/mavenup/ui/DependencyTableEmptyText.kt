package de.schwarzland.mavenup.ui

/** Hinweistext, solange die Abhängigkeiten aus den `pom.xml`-Dateien neu eingelesen werden. */
internal const val EMPTY_TEXT_KEY_REFRESHING = "toolwindow.MyToolWindow.emptyText.refreshing"

/** Hinweistext, solange online nach neuen Versionen gesucht wird. */
internal const val EMPTY_TEXT_KEY_SEARCHING = "toolwindow.MyToolWindow.emptyText.searching"

/** Hinweistext, wenn keine Abhängigkeiten geladen wurden. */
internal const val EMPTY_TEXT_KEY_NO_DEPENDENCIES = "toolwindow.MyToolWindow.emptyText.noDependencies"

/** Hinweistext, wenn der aktive Filter alle geladenen Zeilen ausblendet. */
internal const val EMPTY_TEXT_KEY_NO_MATCHES = "toolwindow.MyToolWindow.emptyText.noMatches"

/**
 * Ermittelt den Bundle-Schlüssel des Hinweistexts, der anstelle der Tabelle der Abhängigkeiten
 * angezeigt wird, solange diese keine sichtbaren Zeilen enthält.
 *
 * Laufende Vorgänge haben Vorrang: Während eines Refreshs oder einer Versionssuche bleibt die
 * Tabelle bewusst ausgeblendet und erklärt den laufenden Vorgang. Ohne laufenden Vorgang wird
 * unterschieden, ob überhaupt keine Abhängigkeiten geladen wurden oder ob lediglich der aktive
 * Filter alle Zeilen ausblendet.
 *
 * @param isRefreshing `true`, solange die `pom.xml`-Dateien neu eingelesen werden.
 * @param isSearchingVersions `true`, solange die Online-Versionssuche läuft.
 * @param hasLoadedRows `true`, wenn das Tabellenmodell mindestens eine (ggf. gefilterte) Zeile enthält.
 * @return Der Bundle-Schlüssel des anzuzeigenden Hinweistexts.
 */
internal fun dependencyTableEmptyTextKey(
    isRefreshing: Boolean,
    isSearchingVersions: Boolean,
    hasLoadedRows: Boolean
): String = when {
    isSearchingVersions -> EMPTY_TEXT_KEY_SEARCHING
    isRefreshing -> EMPTY_TEXT_KEY_REFRESHING
    hasLoadedRows -> EMPTY_TEXT_KEY_NO_MATCHES
    else -> EMPTY_TEXT_KEY_NO_DEPENDENCIES
}
