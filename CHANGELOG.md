<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# MavenUp Changelog
## [Unreleased]
### Added
- Added a link in the OSS Index settings that opens the Sonatype account page for creating or copying an API token.
- Added optional Sonatype OSS Index enrichment for Maven vulnerability checks, with credentials stored securely in IntelliJ Password Safe.
- Added resolved transitive dependencies to vulnerability scans by default.
- Added detailed vulnerability results with advisory IDs, aliases, severity/CVSS, summaries, sources, references, and a dedicated details dialog.
- Added cross-source advisory deduplication and severity-aware vulnerability table cells.
- Additional unit test coverage for `DependencyApiService` (credential resolution edge cases, server credential
  fallbacks, repository version collection, version filtering, qualifier detection) and
  `VulnerabilityApiService` (result-order parsing, size mismatches, empty input, HTTP error handling, end-to-end
  chunk fetch).
- New `MavenUpConfigurableTest` covering display name, reset, change detection (`isModified`), and `apply`
  behavior of the settings UI.
- New `DependencyUpdate` equality/copy test.

### Changed
- Refactored internal project structure into dedicated `model`, `service`, and `ui` packages without changing plugin behavior.
- Moved external API requests for dependency version lookup and vulnerability checks from UI code into dedicated service-layer classes.
- OSV batch results are now enriched with full advisory metadata instead of being reduced to counts.
- CVSS v2/v3 vector scores are normalized with the CVSS Calculator library for consistent severity display.

### Fixed
- Maven project and PSI data for tool-window refreshes are now collected in a non-blocking background read action, preventing workspace file-index updates from running on the Event Dispatch Thread after Maven imports or manual refreshes.
- OSS Index credentials are now loaded outside the Event Dispatch Thread and cached for settings change detection, preventing IntelliJ slow-operation violations when opening or checking the MavenUp settings page.
- Prevented a `NullPointerException` when OSV returns CVSS 4.0 vectors that are not supported by the bundled CVSS calculator; MavenUp now keeps the advisory and falls back to its source severity.
- Withdrawn OSV advisories are no longer included in vulnerability results.
- Updated unit tests that still used reflection to call `resolveCredentialValue`, `findServerCredentials`,
  `collectVersionsFromRepositories`, `filterVersionsBySettings`, `buildVulnerabilityQuery`,
  `parseVulnerabilityCounts`, and `fetchVulnerabilityCountsForChunk` on `MavenUpWindowFactory.MyToolWindow`.
  These methods were moved to `DependencyApiService`/`VulnerabilityApiService` during the package refactor, which
  made the reflective lookups fail with `NoSuchMethodException`. The tests were rewritten to call the public
  service methods directly.
- Disabled the bundled Vue.js plugin (`org.jetbrains.plugins.vue`) in the test sandbox via `prepareTestSandbox`,
  which fixed a sporadic `TestLoggerAssertionError` unrelated to MavenUp's own code that could fail
  `MavenUpWindowFactoryTest` depending on test execution order.

## [1.2.0]
### Added
- New **Check Vulnerabilities** button that queries [OSV.dev](https://osv.dev) for known vulnerabilities of the current version of every listed dependency and plugin.
- New **Vulnerabilities** table column showing the number of vulnerabilities found for the current version. The column stays empty until the check has been run explicitly.

### Fixed
- Added diagnostic logging (request size, number of matches, HTTP error body) for the OSV.dev vulnerability lookup so failures can be diagnosed via `idea.log` instead of silently appearing as "0".
- Expanded vulnerability lookup logging with start/finish summaries, chunk progress, request previews, cancellation logging, and response-size mismatch warnings.

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
