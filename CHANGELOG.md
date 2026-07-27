<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# MavenUp Changelog

## [1.1.0]
### Added
- Auflösung von Credential-Platzhaltern aus Maven `settings.xml` beim Repository-Zugriff:
  - `${env.VAR_NAME}` über Environment-Variablen
  - `${VAR_NAME}` über System-Property, mit Fallback auf Environment-Variable

### Fixed
- Fehlende Warnung bei HTTP-Fehlern (z.B. 401/404) beim Abruf von `maven-metadata.xml` wird jetzt geloggt.
- Reihenfolge der Repository-Abfrage präzisiert: Maven Central wird bevorzugt zuerst abgefragt; bei erfolgreicher Central-Abfrage werden keine privaten Repositories mehr für dieselbe Dependency angefragt.

## [1.0.4]
### Fixed
- Improved tool window visibility after plugin installation and updates in Maven projects

## [1.0.3]
### Fixed
- Refactor tool window initialization

## [1.0.2]
### Fixed
- Enable tool window availability for Maven projects
- Move time-consuming operations to background tasks to prevent EDT violations and improve UI responsiveness

### Added
- Add Settings button to tool window and associated functionality

## [1.0.1]
### Added
- Add a new Plugin-Icon
- Added support for private Maven repositories (e.g., Nexus, Artifactory)
- Error logging for failed Maven settings file parsing (credentials and repositories).
- Warning logging when fetching versions for an artifact from a repository fails.

## [1.0.0]
### Added
- Initial release of MavenUp.
- Dependency & Plugin Overview for Maven projects.
- Support for Maven Properties (Variables) in a dedicated column.
- Update check for newer versions in configured repositories.
- Easy update of pom.xml via dropdown selection.
- Navigation to pom.xml (Single or Double Click).
- Support for `dependencyManagement` and `pluginManagement`.
