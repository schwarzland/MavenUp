package de.schwarzland.mavenup.ui

/**
 * Die möglichen Zustände des Empty States der Tabelle im Tab **Transitive CVEs**.
 *
 * Der Tab bleibt gemäß den JetBrains-UI-Guidelines stets auswählbar; statt ihn zu deaktivieren,
 * erklärt der Empty State im Tab-Inhalt, warum keine Einträge vorliegen.
 *
 * @property bundleKey Der Bundle-Schlüssel des anzuzeigenden Hinweistexts.
 */
internal enum class TransitiveEmptyState(val bundleKey: String) {
    /** Es wurde noch kein Sicherheits-Scan ausgeführt. */
    NOT_SCANNED("toolwindow.TransitiveVulnerabilities.emptyText.noScan"),

    /** Der Scan lief durch, hat aber weder direkte noch transitive Befunde ergeben. */
    NO_FINDINGS("toolwindow.TransitiveVulnerabilities.emptyText.noFindings"),

    /** Der Scan hat ausschließlich Befunde an direkt deklarierten Abhängigkeiten ergeben. */
    ONLY_DIRECT("toolwindow.TransitiveVulnerabilities.emptyText.onlyDirect"),

    /** Es liegen transitive Befunde vor, der aktive Filter blendet aber alle Zeilen aus. */
    NO_MATCHES("toolwindow.TransitiveVulnerabilities.emptyText.noMatches")
}

/**
 * Ermittelt den Zustand des Empty States der transitiven Ansicht.
 *
 * Sind Zeilen vorhanden, kann die Ansicht nur durch den aktiven Filter leer sein. Ohne Zeilen hat ein
 * noch nicht ausgeführter Scan Vorrang, da ohne Scan keine Aussage über Befunde möglich ist;
 * andernfalls wird unterschieden, ob der Scan gar keine oder ausschließlich direkte Befunde ergeben hat.
 *
 * @param scanPerformed `true`, wenn mindestens ein Sicherheits-Scan abgeschlossen wurde.
 * @param hasTransitiveRows `true`, wenn das Tabellenmodell mindestens eine (ggf. gefilterte) Zeile enthält.
 * @param hasDirectFindings `true`, wenn mindestens eine direkt deklarierte Abhängigkeit betroffen ist.
 * @return Der anzuzeigende [TransitiveEmptyState].
 */
internal fun transitiveEmptyState(
    scanPerformed: Boolean,
    hasTransitiveRows: Boolean,
    hasDirectFindings: Boolean
): TransitiveEmptyState = when {
    hasTransitiveRows -> TransitiveEmptyState.NO_MATCHES
    !scanPerformed -> TransitiveEmptyState.NOT_SCANNED
    hasDirectFindings -> TransitiveEmptyState.ONLY_DIRECT
    else -> TransitiveEmptyState.NO_FINDINGS
}

/**
 * Prüft, ob der Erfolgshinweis „keine Sicherheitslücken gefunden" im Tab **Dependencies**
 * eingeblendet werden soll.
 *
 * Der Hinweis erscheint ausschließlich nach einem abgeschlossenen Scan, der weder direkte noch
 * transitive Befunde ergeben hat; andernfalls sprechen die Befunde in der Tabelle für sich.
 *
 * @param scanPerformed `true`, wenn mindestens ein Sicherheits-Scan abgeschlossen wurde.
 * @param directFindings Anzahl der betroffenen, direkt deklarierten Abhängigkeiten.
 * @param transitiveFindings Anzahl der betroffenen, transitiven Abhängigkeiten.
 * @return `true`, wenn der Hinweis angezeigt werden soll.
 */
internal fun isNoVulnerabilitiesHintVisible(
    scanPerformed: Boolean,
    directFindings: Int,
    transitiveFindings: Int
): Boolean = scanPerformed && directFindings == 0 && transitiveFindings == 0
