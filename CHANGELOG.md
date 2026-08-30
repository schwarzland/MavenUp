<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# MavenUp Changelog

## [Unreleased]

### Added

- Added two tool window tabs in the tool window header — **Dependencies** and **Transitive CVEs** — that switch between the main dependency table and a dedicated view listing all transitive dependencies with known vulnerabilities and their vulnerability count; the **Transitive CVEs** tab shows the number of affected coordinates in its title, stays selectable at all times, and explains in its own empty state how to produce results when no findings exist.
- Added a **Type** column to the transitive vulnerabilities view that shows the same type as the main table (e.g. *managed dependency*) when the coordinate is declared in the pom.xml, or *transitive* otherwise.
- Added a right-click context menu to the transitive vulnerabilities view with **Open on [Browser]** and **Show Vulnerability Details**, mirroring the main table's context menu.
- Added a filter row to the transitive vulnerabilities view that matches the main window's filter row in look and behavior, with a text filter for GroupId and ArtifactId, an **Updates** and a **Pending** combo box, and a reset button; filtering by type and vulnerabilities is omitted.
- Added a **Filter by "..."** entry to the transitive vulnerabilities view context menu that applies the clicked GroupId or ArtifactId as the sole text filter.
- Added **Set Highest Major Version**, **Set Highest Minor Version**, **Set Recommended Version**, and **Reset to Current Version** entries to the transitive vulnerabilities view context menu for the right-clicked coordinate.
- Added a **Select Recommended Version** entry to the toolbar's **Select Highest Version** dropdown that selects the recommended fix version for every transitive coordinate while the transitive vulnerabilities view is shown.
- Added an editable **New Version** column to the transitive vulnerabilities view; available versions for vulnerable transitive dependencies are fetched automatically after a vulnerability scan, and selecting a version pins it in `<dependencyManagement>` (creating the entry if needed) and applies it through the shared **Update** action. The recommended fix version (lowest known version that resolves all vulnerabilities of the coordinate) is highlighted in the version dropdown in bold with a *(recommended)* marker.
- Added an explanatory XML comment as the first line inside each newly pinned transitive `<dependencyManagement>` entry that lists the fixed vulnerability IDs and notes the change was made by MavenUp.
- Added an "Insert explanatory comment when pinning a dependency to fix a vulnerability" setting to make that XML comment optional (default: on), placed in a new **Pom.xml Changes** settings group alongside the relocated "Sync Maven changes after update" setting.
- Added a master-detail split view to the Vulnerability Details dialog whose lower detail pane shows the selected finding's affected component, summary, and references as clickable hyperlinks.
- Added an "Open on ..." hyperlink in the detail pane that opens the selected component in the configured Maven repository browser.
- Added the CVSS vector and the fixed-in versions of the selected finding to the detail pane when available.
- Added the detailed description, CWE identifiers, publish/last-modified dates, and affected version ranges of the selected finding to the detail pane when available.
- Added **Own vulnerability** and **Transitive vulnerability** options to the dependency table's **Vulnerabilities** filter so rows can be narrowed to findings in the dependency itself or in its transitive dependencies.
- Added a **Set Recommended Version** entry to the dependency table's row context menu that selects the recommended fix version for a dependency, plugin, or managed entry affected by security findings of its own.
- Added support for the toolbar's **Select Recommended Version** action in the dependency table, applying the recommended fix version to every visible entry that has security findings of its own.
- Added a bold *(recommended)* marker for the recommended fix version in the dependency table's **New Version** dropdown, matching the transitive vulnerabilities view.
- Added a "Search for new versions automatically when the project data is loaded" setting (default: on) that runs **Refresh and Search for New Versions** automatically when the tool window loads its project data and after every finished Maven import or resync.

### Changed

- Moved the **Vulnerabilities** column in front of the **Current Version** column in both the dependency table and the transitive vulnerabilities view, so **Current Version** and **New Version** sit next to each other for direct comparison.
- Renamed the transitive vulnerabilities view's **Version** column to **Current Version** and shortened the main table's **Vulnerabilities (Current)** header to **Vulnerabilities**, so both tables use identical column names.
- Made the **Open on [Browser]** and **Vulnerability Details** toolbar actions operate on the selected row of whichever view is active, so they target the transitive vulnerabilities view while it is shown.
- Merged the **Refresh** and **Search for New Versions** toolbar actions into a single **Refresh and Search for New Versions** action that reloads the `pom.xml` data, discards previous version selections and vulnerability results, and searches for new versions in one step; it stays available in both tool window tabs.
- Moved the main table's filter row into the **Dependencies** tab so each tab carries its own filter row.
- Made the **Select Highest Version** dropdown and the **Reset All to Current Versions** toolbar action operate on the transitive vulnerabilities view while it is shown.
- Limited the bulk version selection in the transitive vulnerabilities view to the rows visible under its filter, and made the reset action ask whether to reset all transitive coordinates or only the filtered ones while a filter is active.
- Replaced the Summary and References columns in the Vulnerability Details dialog with the detail pane, and removed the separate references list dialog.
- Moved the "Open in ..." toolbar action of the Vulnerability Details dialog into the detail pane as a hyperlink and removed the dialog toolbar.
- Aligned the documentation with the actual UI label of the repository context menu entry, which is **Open on [Browser]** (e.g. *Open on MVN Repository*) rather than *Open in Maven Repository*.
- Moved the "Sync Maven changes after update" setting out of **Versions and Updates** into the new **Pom.xml Changes** settings group.
- Trimmed the Vulnerability Details table columns to their content when the dialog opens, let them scale with the dialog size, and enlarged the dialog for better readability.
- Trimmed the main dependency table and all confirmation-table columns to their content after the table is populated for better readability.
- Soft-wrapped overlong lines (for example long reference URLs) in the Vulnerability Details detail pane so no horizontal scrolling is needed.
- Made the **Group Id**, **Artifact Id**, and **Type** columns of the **Confirm Changes** dialog sortable through the same ascending → descending → unsorted cycle and header indicators as the other tables.
- Marked updates that originate from a purely transitive dependency in the **Type** column of the **Confirm Changes** dialog as `transitive -> managed dependency`, so it is obvious that the dependency is pinned in `<dependencyManagement>` for the first time.
- Restructured the documentation into a slim English README landing page that links to dedicated guides under `docs/` (usage, configuration, privacy, architecture, development, release/CI, licenses); the Gradle proxy configuration is documented under development.
- Added GitHub Actions status badges (Build and Test, Create Draft Release, Publish Release to Marketplace) to the README.
- Simplified the settings change detection by comparing UI and stored values through a value list instead of a long boolean chain, keeping the detekt cyclomatic complexity within its threshold.

### Fixed

- Fixed a mojibake encoding artifact in a KDoc comment of `RowMatchesFilterTest.kt` (`fÃ¼r` instead of `für`).
- Fixed differing row heights between the dependency tables by applying the IntelliJ styleguide row height of 24px (scaled) to every plugin table, instead of letting each table derive its height from platform-dependent font metrics and cell renderers.
- Kept the transitive vulnerabilities view's **New Version** column populated when running "Search for New Versions" by storing the scan-derived transitive versions separately, so the table no longer collapses to a plain list.
- Aligned the transitive vulnerabilities view sorting with the main table by cycling column sorting through ascending, descending, and unsorted states, and made its **Version** column non-sortable.
- Resolved the parent POM version from Maven properties so property-based `<parent>` versions (for example `${revision}`) are scanned for vulnerabilities and version updates instead of being skipped as an unresolved placeholder.
- Recommended a fix version that resolves every known vulnerability of a transitive coordinate, so a version that still lies within an affected range (for example an incomplete fix superseded by a later patch) or that leaves another advisory unresolved is no longer suggested.
- Limited the affected version ranges and fixed versions of an advisory to the artifact actually in use, so advisories covering several Maven artifacts no longer mix fix versions of unrelated artifacts into the recommendation.

## 2.5.0

### Added

- Added a **Filter by "..."** entry to the dependency row context menu that appears when right-clicking the GroupId, ArtifactId, or Property column and sets the clicked value as the sole text filter, replacing any existing filter text and applying it immediately.
- When a filter is active, the "Reset All to Current Versions" action now asks whether the reset should apply to all dependencies or only to the currently filtered (visible) ones; the "Don't ask again" confirmation dialog is used only when no filter is active.
- Added **Set Highest Major Version** and **Set Highest Minor Version** entries to the dependency row context menu that select the target version for the right-clicked dependency only; both are disabled until that dependency's available versions have been retrieved.
- Added a **Reset to Current Version** entry to the dependency row context menu that discards the selection for the right-clicked dependency only and is disabled unless a differing version is selected.
- Made all columns of the vulnerability details dialog sortable through the ascending → descending → unsorted cycle with the same header sort indicators as the tool window; the Severity column leads with the most critical findings on the first click, ordered by criticality then CVSS score (highest first), and the References column sorts by reference count.
- Color-coded the **Severity** column in the vulnerability details dialog using the same severity color scheme as the tool window's vulnerability column.
- Added column-header sorting to the Vulnerabilities column that orders rows primarily by the highest finding severity and secondarily by the finding count, so ascending lists the least critical dependencies first and descending the most critical first.

### Changed

- Changed dependency and vulnerability context menus to use IntelliJ's native ActionSystem and themed Swing menu components for consistent platform styling.
- Kept the **Show Vulnerability Details** dependency row context menu entry permanently visible and only toggled its enabled state (disabled without findings) so all context menu entries behave consistently per the IntelliJ UI guidelines.
- Split the oversized tool window source file by extracting stateless UI helpers (table constants, version status rendering, filter model, vulnerability cell model, refresh snapshot, repository link, sortable header icon, and the help tooltip extension) and the update confirmation dialog into dedicated files without any functional change.
- Extracted the POM update, refresh snapshot collection, and vulnerability scan logic from the tool window into dedicated services (`PomUpdateService`, `RefreshSnapshotCollector`, `VulnerabilityScanService`) and removed reflection-based access from the corresponding tests, without any functional change.
- Extracted the version search and POM navigation logic from the tool window into dedicated services (`DependencyVersionService`, `PomNavigationService`) and the stateless auto-selection helpers into `VersionAutoSelection`, without any functional change.
- Split the oversized tool window test suite by moving the decoupled version-status, refresh snapshot, POM navigation, and POM update tests into dedicated test files mirroring the package structure.
- Added constructor injection of the version-fetch and OSS Index dependencies to `DependencyVersionService` and `VulnerabilityScanService`, and added dedicated unit tests for both services without any functional change.
- Suppressed the detekt `LargeClass` warning for the tightly coupled Swing `MyToolWindow` component with a documented rationale, keeping the rule active project-wide instead of splitting the class.
- Added detekt static analysis (with a baseline for existing findings) and Kover test-coverage reporting to the Gradle build and CI pipeline.
- Removed `feature/**` and `release/**` from the CI `push` trigger so that pushing a branch with an open pull request no longer runs two identical builds; feature and release branches are now validated solely via the `pull_request` trigger against `main`, while `push` only builds `main`.
- Added a separate `manual-build.yml` workflow with a `workflow_dispatch` trigger so the build can be started manually from the GitHub Actions tab for any selected branch (e.g. a feature branch without an open pull request), independent of the automatic CI workflow and its path filters.
- Changed the report upload step in both CI workflows to run on every build (not only on failure) via `if: ${{ !cancelled() }}` and `if-no-files-found: ignore`, so test, detekt, and Kover coverage reports are always available as artifacts.
- Upgraded `actions/upload-artifact` from v4 to v7 across all workflows to run on Node.js 24 and remove the Node.js 20 deprecation warning.
- Integrated the IntelliJ Plugin Verifier into `verifyPlugin` and run it across the CI, manual, draft-release, and publish workflows to check IDE compatibility, replacing the separate standalone verifier steps.
- Added a 30-minute job timeout and, for the publish workflow, a per-release-ref concurrency group that does not cancel running publish runs.
- Extracted the common build setup (checkout, JDK, Gradle) into a reusable `.github/actions/setup` composite action used by all workflows.
- Added support for `hotfix/**` branches to the CI `push` trigger so hotfix branches are validated on direct pushes.
- Standardized the release naming convention to release branch `release/x.y.z` and Git tag `x.y.z`, both without a leading `V`.
- Extended `.github/dependabot.yml` to also monitor Gradle dependencies monthly, in addition to GitHub Actions.
- Bumped Gradle plugins and dependencies: Kotlin JVM 2.3.20 to 2.4.10, detekt 1.23.7 to 1.23.8, Kover 0.9.1 to 0.9.9, `us.springett:cvss-calculator` 1.4.1 to 1.5.1, and the Gradle wrapper 9.6.1 to 9.7.1.

### Fixed

- Calculated CVSS 4.0 base scores from OSV vectors (via the upgraded `cvss-calculator`) instead of falling back to the source severity.

## 2.4.0

### Added

- Added a confirmation dialog before the "Reset All to Current Versions" action discards all version selections, with a "Don't ask again" option; the confirmation can also be turned off via the new "Confirm before resetting all version selections" setting.

### Changed

- Shortened the toolbar button labels shown when text labels are enabled ("Search Versions", "Scan", "Update", "Highest", "Reset", "Open", "Details") while keeping the full text as tooltip, so the toolbar fits on low screen resolutions.
- Renamed the version lookup action from "Find New Versions" to "Search for New Versions" (short label "Search Versions"), and aligned its progress indicator and related tooltips accordingly.
- Grouped the two highest-version actions ("Select Highest Major Version", "Select Highest Minor Version") into a "Select Highest Version" toolbar dropdown (using the same upwards-arrow glyph "↑" as the New Version column) whose tooltip clarifies that only the currently visible dependencies are changed, and kept "Reset All to Current Versions" as a standalone toolbar action whose tooltip states it applies to all dependencies regardless of active filters.

### Fixed

- Fixed toolbar tooltips differing between the text-label and icon-only modes by giving every toolbar action a single explicit tooltip (via the presentation's custom help tooltip) that shows the full, wrapping text identically in both modes.
- Fixed the Plugin Verifier reporting deprecated `HelpTooltip.setDescription(String)` usages on newer IDEs by selecting the `Supplier`-based overload at runtime while staying compatible with the 2025.3 compile target.

## 2.3.0

### Added

- Added a dedicated MavenUp tool window icon (with Light and Dark theme variants) shown in the tool window bar instead of a generic IntelliJ icon.
- Added toolbar actions to bulk-select the highest major version, the highest minor version within the current major line, or reset all selections to the current versions; the two "Select Highest" actions apply only to the currently visible (non-filtered) dependencies and show a tooltip hint when a filter hides entries, while the reset clears all selections regardless of filtering.
- Added a 3-state auto-selection mode setting for update checks (disabled, highest version, latest minor in current major line).
- Added an "Offer all versions" setting that also lists versions older than the current one, enabling downgrades; disabled by default so only versions greater than or equal to the current one are offered.
- Added a "(current)" marker in bold to the currently used version in the version selection dropdown so it stands out, especially when older versions are offered.
- Added column-header sorting to the dependency table that cycles through ascending, descending, and the original pom.xml order, excluding the Current Version, Vulnerabilities, and New Version columns, with a header indicator icon (dimmed double arrow when unsorted, directional arrow when sorted) marking sortable columns.
- Added a reset button at the end of the filter row that clears the search text and the type, pending, and vulnerabilities filters at once.
- Added tooltips to the filter row controls that explain the search field and the type, pending, and vulnerabilities filters.
- Added an "Updates" filter that shows only rows with a newer version available and is enabled only after a successful "Find New Versions".

### Changed

- Changed the settings UI from two checkboxes to a single combobox for version auto-selection strategy.
- Changed the "Vulnerabilities" filter to be enabled only after a successful "Scan for Vulnerabilities".
- Changed the "Pending" filter to be enabled only once at least one row has a differing version selection.
- Changed the filter combo boxes to use self-describing option labels (for example "Will update", "Update available", "Vulnerable") instead of generic "Yes"/"No".
- Changed the newest-version reference to use the repository-declared `<release>`/`<latest>` fields from `maven-metadata.xml` (preferring Maven Central), falling back to comparator-based ordering, so date-based versions no longer outrank the actual latest release.

### Fixed

- Fixed the New Version cell defaulting to the numerically highest version, which caused an unintended jump (for example from 24.0 to a date-based 2023-... version) and could be committed as an update; it now defaults to the current version when no target is preselected.
- Fixed latest-minor auto-selection for version jumps and unsorted version lists by sorting candidates before choosing the newest or same-major release.
- Fixed duplicate dependency rows in the tool window by deduplicating entries within each scope while keeping managed and regular dependencies visible separately.
- Fixed New Version preselection recalculation, so changes to the auto-selection mode are applied immediately in open tool windows.
- Fixed the Current Version column showing the raw property placeholder (for example `${netty-bom.version}`) for `dependencyManagement` entries whose version is defined via a property; it now resolves the placeholder against the effective Maven properties.
- Fixed the update check offering older versions for property-based `dependencyManagement` entries by resolving the version placeholder before filtering, so the "greater than or equal to current" filter compares against the real version instead of the raw placeholder.

## 2.2.1

### Added

- Added DEBUG logging that reports which Maven `settings.xml` file is used for repository and credential lookups.
- Added a setting to control whether version lookup stops after a successful Maven Central response or continues with private repositories.

### Changed

- Changed Maven settings resolution to use the IDE-configured user settings path first and fall back to `${user.home}/.m2/settings.xml` when needed.
- Changed repository version lookup to keep Maven Central first while making the Central short-circuit behavior configurable.
- Reordered the settings dialog so the Central short-circuit option appears before the Maven-sync toggle to match the repository lookup flow.

### Fixed

- Fixed missing private repository discovery on machines where IntelliJ uses the default Maven settings file without an explicit user settings path.
- Fixed fallback path construction for the default Maven `settings.xml` so it resolves correctly on all operating systems.

## 2.2.0

### Added

- Added Changes (All/Yes/No) and Vulnerabilities (All/Yes/No) combo box filters to the tool window filter bar to filter dependencies by pending version changes and security findings.
- Added documentation and transparency notes regarding privacy, external service endpoints, and transmitted Maven coordinates.

### Changed

- Plugin settings are now stored globally (application level) instead of per project, so the configuration applies to all projects.
- The **New Version** status indicator is now a text glyph (an upwards arrow "↑" or a green checkmark "✓") instead of a fixed-color icon, so the arrow renders as a real arrow and shares the version number's color, turning orange only when a version different from the current one is selected.

### Fixed

- Installing or updating the plugin no longer requires an IDE restart by registering the Maven import listener declaratively so the plugin can be unloaded dynamically.

## 2.1.0

### Added

- Show a qualified error message when a Sonatype OSS Index vulnerability scan fails because the API token is invalid or expired, offering a button to jump directly to the plugin settings.
- Added a filter row above the main dependencies table: a text field filters by GroupId, ArtifactId or Property, and a combo box filters by dependency type; both filters combine and only matching rows remain visible.

### Changed

- The settings page groups its options under three headings — **Appearance**, **Versions & Updates** and **Vulnerability Check** — to improve orientation.
- Sonatype OSS Index now authenticates with the API token only; the username/email field was removed because Sonatype does not validate it.
- The "Hidden version qualifiers" setting now follows the IntelliJ settings style: it appears as an indented sub-setting and the input field expands with the settings dialog width so long qualifier lists remain readable while disabled when the feature is off.

### Fixed

- Removed unnecessary `lateinit` modifiers on the tool window's table and row-sorter fields, eliminating two Gradle "'lateinit' is unnecessary" compiler warnings.
- Fixed **Sync Maven Changes after update** not synchronizing the Maven project by saving the modified `pom.xml` documents to disk before triggering the Maven sync, so the reimport reads the updated versions instead of the stale on-disk content.

## 2.0.0

### Added

- Added a **Sync Maven changes after update** setting; the checkbox in the **Confirm Changes** dialog and the setting stay in sync, and the last choice made in the dialog is persisted.
- Added an optional **Sync Maven Changes after update** checkbox to the **Confirm Changes** dialog (enabled by default); when selected, it automatically triggers the IDE's Maven sync after updating the `pom.xml` files.
- Added "Show Vulnerability Details" to the right-click context menu in the main dependencies table, allowing quick access to vulnerability findings when available for a dependency.
- Added a setting to show text labels on the toolbar buttons instead of icons only (enabled by default); it applies to the tool window and the Vulnerability Details dialog and takes effect immediately on the open tool window.
- Added **Open on [browser]** action; it is a selection-dependent action in the top action toolbar, its tooltip dynamically shows the configured repository browser name, and it opens the current version of the selected dependency in the configured Maven repository browser.
- The `<parent>` section of each `pom.xml` is now listed as a dependency with type "parent", including version checks, updates, navigation, and vulnerability scanning.
- The **New Version** column now shows a status icon and color-coded text: a green checkmark with green text when the selected version is the highest known version, or an orange arrow-up icon with orange text otherwise. The icon always reflects the currently **selected** version in the dropdown; when a version different from the current one is selected, the dropdown text is displayed in bold and colored to indicate a pending change. When the selected version matches the current version, the text uses the default table color (same as the **Current Version** column). Hovering over the cell shows a tooltip with status details.
- Toggling the "Automatically select the newest version" setting and applying it now immediately updates all **New Version** selections in the open tool window without requiring a new update check.

### Changed

- Renamed **Check for Updates** to **Find New Versions** and changed its toolbar icon from a download icon to a search/find icon, better conveying the action of searching for newer dependency versions.
- Renamed **Check Vulnerabilities** to **Scan for Vulnerabilities** to use established security terminology that better conveys the systematic nature of the operation.
- Changed the **References** icon in the Vulnerability Details dialog (top toolbar action and References column) to a details preview icon, making it clearer that clicking opens a dialog listing the references.
- Removed the link icon from the **Component** column in the Vulnerability Details dialog, as double-clicking a row no longer triggers an action.
- The **Vulnerability Details** action now requires a dependency row with findings to be selected and shows only the vulnerabilities for that selected dependency instead of all found vulnerabilities.
- Reorganized the tool window and Vulnerability Details dialog actions into a top IntelliJ ActionToolbar with icons and tooltips, separating core actions (Refresh, Find New Versions, Scan for Vulnerabilities, Update) from the selection-dependent actions (Open in ..., Vulnerability Details / References) by a divider, in line with the IntelliJ UI guidelines.
- Removed the bottom button bar and the custom wrapping layout, as the action toolbar handles overflow itself.
- **Find New Versions** now keeps existing vulnerability findings instead of clearing them; only **Refresh** discards the collected vulnerability data.

### Fixed

- The **New Version** selections are now only reset according to the "Automatically select the newest version" setting when that setting itself changes, so applying unrelated settings no longer discards a pending version selection.
- All plugin tables now enforce single-row selection instead of allowing multiple rows to be selected at once.
- All plugin tables now prevent column reordering, so users can no longer drag columns into a different order.
- The **Confirm Changes** dialog table is now read-only, preventing accidental editing of the update overview.
- Fixed an `ArrayIndexOutOfBoundsException` that could occur when refreshing or updating while the "New Version" cell editor was still open, by cancelling active cell editing before the table is rebuilt.

## 1.2.0

### Added

- Right-click context menu on dependency rows with two actions: **Navigate to pom.xml** (jumps to the entry in the editor) and **Open in Maven Repository** (opens the matching version page in the configured repository browser).
- Configurable Maven Repository Browser: users can choose between **MVN Repository** (default, `mvnrepository.com`) and **Sonatype Central** (`central.sonatype.com`) under **Settings > Tools > MavenUp**. The selection applies to both the context menu in the main table and the Component column link in the Vulnerability Details dialog.
- Added a tooltip on dependency rows in the main table indicating whether a single or double click will open the entry in pom.xml (adapts to the "jump on single click" setting).
- Added a link in the OSS Index settings that opens the Sonatype account page for creating or copying an API token.
- Added a **Scan for Vulnerabilities** action that queries OSV.dev and optionally enriches results with Sonatype OSS Index data, with credentials stored securely in IntelliJ Password Safe.
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
- Disabled **Scan for Vulnerabilities** while a refresh or update check is running to prevent overlapping background operations.
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

## 1.1.0

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
- Fixed refresh/update interaction: "Find New Versions" now keeps populated "New Version" values instead of clearing them.
- Internal refactor: replaced the implicit one-shot refresh flag with an explicit refresh mode (`clear` vs `keep`) for better maintainability.

## 1.0.4

### Fixed

- Improved tool window visibility after plugin installation and updates in Maven projects

## 1.0.3

### Fixed

- Refactor tool window initialization

## 1.0.2

### Fixed

- Enable tool window availability for Maven projects
- Move time-consuming operations to background tasks to prevent EDT violations and improve UI responsiveness

### Added

- Add Settings button to tool window and associated functionality

## 1.0.1

### Added

- Add a new Plugin-Icon
- Added support for private Maven repositories (e.g., Nexus, Artifactory)
- Error logging for failed Maven settings file parsing (credentials and repositories).
- Warning logging when fetching versions for an artifact from a repository fails.

## 1.0.0

### Added

- Initial release of MavenUp.
- Dependency & Plugin Overview for Maven projects.
- Support for Maven Properties (Variables) in a dedicated column.
- Update check for newer versions in configured repositories.
- Easy update of pom.xml via dropdown selection.
- Navigation to pom.xml (Single or Double Click).
- Support for `dependencyManagement` and `pluginManagement`.
