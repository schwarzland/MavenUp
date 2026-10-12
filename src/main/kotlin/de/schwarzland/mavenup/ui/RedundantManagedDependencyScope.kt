package de.schwarzland.mavenup.ui

/**
 * Beschreibt den Koordinatenfilter und den sichtbaren Prüfumfang für eine Analyse redundanter verwalteter Abhängigkeiten.
 *
 * @property managedCoordinate Optionaler Filter auf den verwalteten Eintrag (`groupId:artifactId`).
 * @property description Lokalisierte Erklärung des Suchumfangs.
 */
internal data class RedundantManagedDependencyScope(
    val managedCoordinate: String?,
    val description: String
) {
    companion object {
        /**
         * Erzeugt den globalen oder zeilenbezogenen Prüfumfang.
         *
         * @param target Kontextmenü-Ziel oder `null` für die globale Toolbar-Aktion.
         * @param managedDependencyType Lokalisierter Typname verwalteter Dependencies.
         * @return Der passende Scope samt lokalisierter Erklärung.
         */
        fun fromTarget(
            target: DependencyContextMenuTarget?,
            managedDependencyType: String
        ): RedundantManagedDependencyScope {
            if (target == null || target.type != managedDependencyType) {
                return RedundantManagedDependencyScope(
                    managedCoordinate = null,
                    description = MyMessageBundle.message("redundant.managed.dependency.scope.global")
                )
            }
            return RedundantManagedDependencyScope(
                managedCoordinate = target.dependencyKey,
                description = MyMessageBundle.message("redundant.managed.dependency.scope.managed", target.dependencyKey)
            )
        }
    }
}
