package de.schwarzland.mavenup.ui

/**
 * Beschreibt den Koordinatenfilter und den für Anwender sichtbaren Umfang einer Cleanup-Prüfung.
 *
 * @property managedCoordinate Optionaler Filter auf den verwalteten Eintrag.
 * @property triggerCoordinate Optionaler Filter auf Parent- oder Dependency-Auslöser.
 * @property description Lokalisierte Erklärung des projektweiten Suchumfangs und der Filter.
 */
internal data class ManagedDependencyCleanupScope(
    val managedCoordinate: String?,
    val triggerCoordinate: String?,
    val description: String
) {
    /** Erstellt aus Kontextmenü-Zeilen die Scope-Filter und passende Erklärungstexte. */
    companion object {
        /**
         * Erzeugt den globalen oder zeilenbezogenen Prüfumfang.
         *
         * @param target Kontextmenü-Zeile oder `null` für die globale Aktion.
         * @param managedDependencyType Lokalisierter Typname verwalteter Dependencies.
         * @return Der passende Scope samt sichtbarer Erklärung.
         */
        fun fromTarget(
            target: DependencyContextMenuTarget?,
            managedDependencyType: String
        ): ManagedDependencyCleanupScope {
            if (target == null) {
                return ManagedDependencyCleanupScope(
                    managedCoordinate = null,
                    triggerCoordinate = null,
                    description = MyMessageBundle.message("managed.dependency.removal.scope.global")
                )
            }

            val coordinate = target.dependencyKey
            return when (target.type) {
                managedDependencyType -> ManagedDependencyCleanupScope(
                    managedCoordinate = coordinate,
                    triggerCoordinate = null,
                    description = MyMessageBundle.message("managed.dependency.removal.scope.managed", coordinate)
                )
                PARENT_TYPE -> ManagedDependencyCleanupScope(
                    managedCoordinate = null,
                    triggerCoordinate = coordinate,
                    description = MyMessageBundle.message("managed.dependency.removal.scope.parent", coordinate)
                )
                "dependency" -> ManagedDependencyCleanupScope(
                    managedCoordinate = null,
                    triggerCoordinate = coordinate,
                    description = MyMessageBundle.message("managed.dependency.removal.scope.dependency", coordinate)
                )
                else -> ManagedDependencyCleanupScope(
                    managedCoordinate = null,
                    triggerCoordinate = null,
                    description = MyMessageBundle.message("managed.dependency.removal.scope.global")
                )
            }
        }
    }
}

/**
 * Erstellt eine begrenzte, lokalisierte Zusammenfassung unvollständiger Datenabfragen.
 *
 * @param incompleteLookups Die Koordinaten, deren Versions- oder POM-Daten fehlten.
 * @return Eine Warnung mit höchstens fünf konkreten Koordinaten.
 */
internal fun incompleteCleanupLookupSummary(incompleteLookups: Set<String>): String {
    val shown = incompleteLookups.sorted().take(5).joinToString(", ")
    val remaining = (incompleteLookups.size - 5).coerceAtLeast(0)
    val more = if (remaining > 0) {
        MyMessageBundle.message("managed.dependency.removal.incomplete.more", remaining)
    } else {
        ""
    }
    return MyMessageBundle.message(
        "managed.dependency.removal.incomplete",
        incompleteLookups.size,
        shown,
        more
    )
}
