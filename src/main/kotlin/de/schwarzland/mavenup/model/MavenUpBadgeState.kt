package de.schwarzland.mavenup.model

/**
 * Beschreibt den Zustand, den das MavenUp-Tool-Window als Badge (kleiner farbiger Punkt) auf
 * seinem Stripe-Icon signalisiert.
 *
 * Der Zustand folgt den IntelliJ-UI-Guidelines: Es wird immer nur genau ein Badge angezeigt, und
 * ein Badge signalisiert ausschließlich Handlungsbedarf. Liegt nichts an, bleibt das Icon
 * unverändert ([NONE]).
 */
enum class MavenUpBadgeState {
    /** Kein Badge; es liegt weder ein Sicherheitsfund noch ein verfügbares Update vor. */
    NONE,

    /** Blaues Info-Badge; es sind neue Versionen verfügbar, aber keine Sicherheitslücken bekannt. */
    UPDATES,

    /** Gelbes Warn-Badge; es liegen Sicherheitslücken bis einschließlich Schweregrad `MEDIUM` vor. */
    VULNERABILITIES,

    /** Rotes Fehler-Badge; es liegt mindestens eine Sicherheitslücke mit `HIGH` oder `CRITICAL` vor. */
    SEVERE_VULNERABILITIES
}
