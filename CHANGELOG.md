<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# MavenUp Changelog

## [1.1.0]
### Added
- Resolution of credential placeholders from Maven `settings.xml` during repository access:
  - `${env.VAR_NAME}` via environment variables
  - `${VAR_NAME}` via system property, with fallback to environment variable
- New configuration options to hide unstable dependency versions:
  - Toggleable filter for unstable versions
  - Freely configurable qualifier list (e.g. `rc,beta,milestone`)
- Added `FEATURES.MD` with an English overview of all plugin features.

### Fixed
- Missing warning logs for HTTP errors (e.g. 401/404) during `maven-metadata.xml` retrieval are now emitted.
- Repository query order refined: Maven Central is queried first; if the Central query succeeds, no private repositories are queried for the same dependency.
- Dependencies and plugins without `groupId` are now ignored and no longer shown in the list.
- The "Hidden version qualifiers" setting is now visually indented, and both its label and input field are automatically disabled when "Hide unstable versions" is turned off.
- The "Hidden version qualifiers" input field is now wider, so longer qualifier lists are easier to read and edit.
- Refresh now also clears the "New Version" column state.
- Fixed refresh/update interaction: "Check for Updates" now keeps populated "New Version" values instead of clearing them.
- Internal refactor: replaced the implicit one-shot refresh flag with an explicit refresh mode (`clear` vs `keep`) for better maintainability.

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
