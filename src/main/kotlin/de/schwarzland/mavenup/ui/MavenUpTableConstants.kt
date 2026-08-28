package de.schwarzland.mavenup.ui

/** Typ-Bezeichner für Parent-Abhängigkeiten in der Abhängigkeitstabelle. */
internal const val PARENT_TYPE = "parent"

/** Typ-Bezeichner für verwaltete Plugins ("managed plugins") in der Abhängigkeitstabelle. */
internal const val MANAGED_PLUGIN = "managed plugin"

/** Message-Bundle-Schlüssel für den Typ-Anzeigetext einer verwalteten Abhängigkeit. */
internal const val TOOLWINDOW_MY_TOOL_WINDOW_TYPE_MANAGED_DEPENDENCY = "toolwindow.MyToolWindow.type.managedDependency"

/** Spaltenindex der GroupId in der Abhängigkeitstabelle. */
internal const val GROUP_ID_COLUMN = 0

/** Spaltenindex der ArtifactId in der Abhängigkeitstabelle. */
internal const val ARTIFACT_ID_COLUMN = 1

/** Spaltenindex des Property-Namens in der Abhängigkeitstabelle. */
internal const val PROPERTY_COLUMN = 2

/** Spaltenindex des Typs in der Abhängigkeitstabelle. */
internal const val TYPE_COLUMN = 3

/** Spaltenindex der aktuellen Version in der Abhängigkeitstabelle. */
internal const val CURRENT_VERSION_COLUMN = 4

/** Spaltenindex der Sicherheitslücken-Zelle in der Abhängigkeitstabelle. */
internal const val VULNERABILITIES_COLUMN = 5

/** Spaltenindex der auszuwählenden neuen Version in der Abhängigkeitstabelle. */
internal const val NEW_VERSION_COLUMN = 6

/** Message-Bundle-Schlüssel für den Titel des Sicherheitslücken-Detaildialogs. */
internal const val VULNERABILITY_DETAILS_TITLE = "vulnerability.details.title"

/** CardLayout-Name der Hauptabhängigkeitstabelle im Zentrum des Tool-Windows. */
internal const val CARD_MAIN_TABLE = "mainTable"

/** CardLayout-Name der Ansicht der transitiven, verwundbaren Abhängigkeiten. */
internal const val CARD_TRANSITIVE_VIEW = "transitiveVulnerabilities"

/** Message-Bundle-Schlüssel für den Kontextmenü-Eintrag zum Öffnen im Maven-Repository-Browser. */
internal const val TOOLWINDOW_MY_TOOL_WINDOW_CONTEXT_MENU_OPEN_IN_MVN_REPOSITORY = "toolwindow.MyToolWindow.contextMenu.openInMvnRepository"
