package de.schwarzland.mavenup.model

/**
 * Bestimmt das Ziel-POM für transitive Pins (`<dependencyManagement>`) beim Anwenden von Updates.
 */
enum class TargetPomMode {
    /** Schreibt transitive Pins in das Root-/Parent-POM des Projekts (empfohlener Standard). */
    ROOT_POM,

    /** Schreibt transitive Pins nur in die Submodule, deren Abhängigkeitsbaum die transitive Koordinate enthält. */
    AFFECTED_MODULES,

    /** Schreibt transitive Pins in alle pom.xml-Dateien im Projekt (bisheriges Fallback-Verhalten). */
    ALL_MODULES
}
