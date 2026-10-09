package de.schwarzland.mavenup.model

/**
 * Beschreibt den Grund, warum eine verwaltete Abhängigkeit in `<dependencyManagement>` redundant ist.
 */
enum class RedundancyReason {
    /**
     * Die Abhängigkeit wird bereits durch ein übergeordnetes `<parent>`-POM verwaltet.
     */
    PARENT_MANAGED,

    /**
     * Die Abhängigkeit ist im Projekt als direkte `<dependency>` mit identischer Version deklariert.
     */
    DIRECT_DEPENDENCY_MATCH,

    /**
     * Alle Konsumenten im Abhängigkeitsbaum erhalten auch ohne lokale Verwaltung dieselbe (oder neuere) transitive Version.
     */
    TRANSITIVE_MATCH,

    /**
     * Das Artefakt wird im gesamten Projekt weder direkt noch transitiv von irgendeinem Modul genutzt.
     */
    UNUSED
}
