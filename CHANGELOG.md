<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# MavenUp Changelog
## [Unreleased]

### Added
- Added a right-click context menu action in the Vulnerability Details dialog Component column to open the selected artifact version page in the configured repository browser.
- Added two selection-based actions to the Vulnerability Details dialog: **Open in ...** and **References...**, both disabled until a vulnerability row is selected.
- Added a **References...** entry to the Vulnerability Details Component-column right-click context menu to open the selected advisory links directly from the menu.

### Changed
- Changed the Vulnerability Details dialog Component-column interaction from single-click navigation to a right-click context menu, aligned with the main table behavior.
- Changed the Vulnerability Details workflow to row-driven actions so repository navigation and reference opening explicitly target the currently selected advisory.
- Changed the Vulnerability Details right-click behavior to work on the selected row from every column except **References**.

### Fixed
- Improved discoverability and interaction consistency for repository navigation from the Vulnerability Details dialog by replacing direct click navigation with an explicit context menu action.

## [1.2.0]
### Added
- Right-click context menu on dependency rows with two actions: **Navigate to pom.xml** (jumps to the entry in the editor) and **Open in Maven Repository** (opens the matching version page in the configured repository browser).
- Configurable Maven Repository Browser: users can choose between **MVN Repository** (default, `mvnrepository.com`) and **Sonatype Central** (`central.sonatype.com`) under **Settings > Tools > MavenUp**. The selection applies to both the context menu in the main table and the Component column link in the Vulnerability Details dialog.
- Added a tooltip on dependency rows in the main table indicating whether a single or double click will open the entry in pom.xml (adapts to the "jump on single click" setting).
- Added a link in the OSS Index settings that opens the Sonatype account page for creating or copying an API token.
- Added a **Check Vulnerabilities** action that queries OSV.dev and optionally enriches results with Sonatype OSS Index data, with credentials stored securely in IntelliJ Password Safe.
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
- Updated row tooltip to reflect the right-click menu: "Click to navigate to pom.xml | Right-click for more options" or "Double-click to navigate to pom.xml | Right-click for more options" depending on the configured click mode.
- The Component column tooltip in the Vulnerability Details dialog now dynamically shows the name of the configured repository browser instead of a fixed "MVN Repository" label.
- Clicking a component in the **Component** column of the Vulnerability Details dialog now opens its MVN Repository page in the browser.
- Replaced the OK and Cancel buttons in the **Vulnerability Details** dialog and the **References** dialog with a single **Close** button, matching the JetBrains UI guidelines for read-only informational dialogs.
- Clicking a cell in the **References** column of the Vulnerability Details dialog now opens a dedicated references list dialog instead of directly navigating to the first link. Clicking any link in that list opens it in the browser.
- Added a link icon to the **Vulnerabilities (Current)** column cell when findings are present, making it visually clear that the cell is clickable to open the details dialog.
- Refocused the plugin description on user-visible capabilities while keeping internal implementation details concise.
- Transitive vulnerability findings are now included in the **Vulnerabilities (Current)** cell of their direct dependency, marked with a transitive count, and included in that row's detail view.
- Moved the **Vulnerabilities (Current)** column directly behind **Current Version** to make clear that findings apply to the currently used component version.
- Sonatype OSS Index now requires username/email and API token when enabled because its API authentication uses both values; the settings UI visibly marks both fields as required.
- Refactored internal project structure into dedicated `model`, `service`, and `ui` packages without changing plugin behavior.
- Moved external API requests for dependency version lookup and vulnerability checks from UI code into dedicated service-layer classes.
- OSV batch results are now enriched with full advisory metadata instead of being reduced to counts.
- CVSS v2/v3 vector scores are normalized with the CVSS Calculator library for consistent severity display.

### Fixed
- Moved the link icon to the beginning of the **Vulnerabilities** column cell, so it appears before the summary text.
- Disabled **Check Vulnerabilities** while a refresh or update check is running to prevent overlapping background operations.
- OSS Index requests are no longer sent when the configured username/email or API token is missing; OSV checks continue and the user receives a configuration warning.
- Improved OSV diagnostic logging with request and chunk summaries, response-size mismatch warnings, HTTP error bodies, and cancellation details.
- Reduced high-volume INFO logging for repository versions and OSV batch coordinates; detailed lists are now truncated and emitted only at DEBUG level to prevent excessive `idea.log` growth and UI freezes while the IDE monitors the log file.
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
