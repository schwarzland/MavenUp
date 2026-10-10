package de.schwarzland.mavenup.model

/**
 * Beschreibt den Grund, warum eine verwaltete Abhängigkeit in `<dependencyManagement>` redundant ist.
 */
enum class RedundancyReason {
    /**
     * Die effektive Parent-Verwaltung liefert dieselbe oder eine höhere Version als der lokale Eintrag.
     */
    PARENT_MANAGED,

    /**
     * Die Abhängigkeit ist direkt mit derselben oder einer höheren expliziten Version deklariert.
     */
    DIRECT_DEPENDENCY_MATCH,

    /**
     * Alle bekannten Konsumentenpfade erhalten auch ohne lokale Verwaltung dieselbe oder eine höhere transitive Version.
     */
    TRANSITIVE_MATCH,

    /**
     * Das Artefakt wird im gesamten Projekt weder direkt noch transitiv von irgendeinem Modul genutzt.
     */
    UNUSED
}
