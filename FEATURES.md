# MavenUp Plugin Features

This document describes the key features of the MavenUp IntelliJ plugin, grouped by area.

---

## Tool Window & UI

- **Maven-only tool window availability**: The MavenUp tool window is shown for Maven projects and becomes available after Maven import if needed. It appears in the tool window bar with a dedicated MavenUp icon (with a Light and Dark theme variant) instead of a generic IntelliJ icon.
- **Dependency and plugin overview**: Displays dependencies, plugins, and the parent POM from `pom.xml` in a table.
- **Parent POM support**: The `<parent>` section of each `pom.xml` is listed as a dependency with type "parent", including version checks and updates.
- **Support for managed scopes**: Includes `dependencyManagement` and `pluginManagement` entries.
- **Input validation for displayed entries**: Skips dependencies and plugins without `groupId` so invalid entries are not shown.
- **Navigation to source in `pom.xml`**: Jumps directly to the matching dependency/plugin entry (single or double click, configurable). A tooltip on each dependency row adapts its text to the configured click mode ("Click to navigate to pom.xml | Right-click for more options" or "Double-click to navigate to pom.xml | Right-click for more options").
- **Context menu on dependency rows**: Right-clicking any dependency row opens a context menu with **Filter by "..."** (only shown when right-clicking the GroupId, ArtifactId, or Property column with a non-empty value; sets the exact clicked value as the sole text filter, replacing any existing filter text, and applies it immediately), **Navigate to pom.xml** (jumps to the entry in the editor), **Open in Maven Repository** (opens the matching version page in the configured repository browser), **Set Highest Major Version** and **Set Highest Minor Version** (select, for the right-clicked dependency only, the highest available version across major lines or the highest version within its current major line; both are disabled until that dependency's available versions have been retrieved), **Reset to Current Version** (discards the selection for the right-clicked dependency only and is disabled unless a differing version is selected), and **Show Vulnerability Details** (opens the vulnerability findings dialog; disabled when the dependency has no findings). All entries stay permanently visible and only toggle their enabled state, so the menu layout is stable and discoverable.
- **Open in Repository action**: A selection-dependent action in the top action toolbar opens the current version of the selected dependency in the configured repository browser. Its tooltip dynamically reflects the configured repository browser name (e.g. **Open on MVN Repository** or **Open on Sonatype Central**), equivalent to the **Open in Maven Repository** context menu entry.
- **Safe update workflow**: Shows a confirmation dialog before applying changes to `pom.xml`, with a **Sync Maven Changes after update** checkbox (enabled by default) to automatically trigger the IDE's Maven sync after the update. The checkbox is bound to the persistent **Sync Maven changes after update** setting, so both stay in sync and the last choice is remembered.
- **Two-tier action toolbar**: Tool window and Vulnerability Details dialog present their actions in a top IntelliJ `ActionToolbar`; core actions (Refresh, Search for New Versions, Scan for Vulnerabilities, Update) are separated by a separator from a **Select Highest Version** dropdown that groups the two highest-version actions (Select Highest Major Version, Select Highest Minor Version) and a standalone Reset All to Current Versions action, followed by the selection-dependent actions (Open in Repository, Vulnerability Details / References), with Settings placed at the end. Actions render either as icon-only buttons (with tooltips) or as buttons with text labels (default), configurable in the settings; when text labels are enabled the buttons show shortened labels (e.g. "Search Versions", "Scan", "Update", "Highest", "Reset", "Open", "Details") while their tooltips keep the full text so the toolbar stays compact on low screen resolutions. The tooltip is identical in both display modes, always showing the full text as a wrapping description.
- **Fixed column order**: All plugin tables enforce single-row selection and prevent column reordering, so columns keep their defined order and cannot be dragged around.
- **Dependency table filtering**: A filter row above the main table combines a text field that filters by GroupId, ArtifactId or Property (case-insensitive substring match) with four combo boxes filtering by dependency type, available updates (All / Update available / Up to date), pending version changes (All / Will update / Unchanged), and security findings (All / Vulnerable / Not vulnerable); the combo box options use self-describing, context-specific wording so the selected value is understandable on its own. Only rows matching all active filters remain visible. The text field can also be filled from the row context menu via **Filter by "..."** on a GroupId, ArtifactId, or Property cell. The updates combo box is enabled only after a successful "Search for New Versions" run and then shows only rows for which a newer version is available; the pending combo box is enabled only once at least one row has a version selection differing from its current version; the vulnerabilities combo box is enabled only after a successful "Scan for Vulnerabilities" run. Each control carries a tooltip explaining its effect, and a reset button at the end of the row clears all filters at once and is disabled while no filter is active.
- **Column-header sorting**: Clicking a column header cycles the dependency table through ascending, descending, and the original pom.xml order; the Current Version and New Version columns stay unsorted. The Vulnerabilities column sorts primarily by the highest severity of its findings and secondarily by the number of findings, so ascending order lists the least critical dependencies first and descending order the most critical first. Sortable columns carry a header indicator icon — a dimmed up/down double arrow when unsorted and a single up/down arrow reflecting the active sort direction — so sortability is discoverable at a glance.

---

## Version Management

- **Repository-based version lookup**: Retrieves available versions from Maven repositories via `maven-metadata.xml`, and determines the newest version from the repository-declared `<release>`/`<latest>` fields (preferring Maven Central) with comparator-based ordering as fallback, so date-based versions do not falsely outrank the actual latest release.
- **Selectable target versions**: Shows available versions in dropdowns for dependencies and plugins; the New Version cell defaults to the current version when no target is preselected.
- **Optional downgrade support**: An "Offer all versions" setting also lists versions older than the current one so downgrades become possible; when disabled (default), only versions greater than or equal to the current version are offered. In the version dropdown the currently used version is marked with a "(current)" label and shown in bold so it stands out among older and newer entries.
- **Configurable auto-selection strategy**: After an update check, MavenUp provides a 3-state strategy for preselecting the target version — disabled (keep current), highest version (may cross major lines), or latest minor within the current major line (never preselects a different major line).
- **Bulk version selection**: The **Select Highest Version** toolbar dropdown applies a target version at once — the highest available version (across major lines) and the highest version within each dependency's current major line both apply only to the currently visible (non-filtered) rows and surface a tooltip hint when a filter hides entries. A separate **Reset All to Current Versions** toolbar action discards all selections and restores the current versions. When no filter is active it asks for confirmation first (with a "Don't ask again" option), controlled by the "Confirm before resetting all version selections" setting; when a filter is active it instead asks whether the reset should apply to all dependencies or only to the currently filtered (visible) ones.
- **Version status indicators**: The **New Version** column displays a green checkmark glyph ("✓") when the selected version equals the highest known version, or an upwards arrow glyph ("↑") otherwise; hovering shows a tooltip with status details. When a version different from the current one is selected, the dropdown text and the status glyph are color-coded together (green for latest, orange otherwise) and the text is shown in bold to clearly indicate a pending change. When the selected version matches the current version, the glyph and text use the default color (same as the **Current Version** column). Colors adapt to Light and Dark themes.
- **Property-aware version handling**: Detects property-based versions (for example `${spring.version}`) and updates the property value instead of overwriting the reference; the **Current Version** column resolves such placeholders against the effective Maven properties so it shows the actual version even for `dependencyManagement` entries that are not part of the resolved dependency tree.
- **Shared-property synchronization**: Synchronizes version selection across entries that use the same Maven property.
- **Optional unstable-version filtering**: Can hide versions by configurable qualifiers (for example `rc,beta,milestone`). The qualifier list is shown as an indented sub-setting and grows with the settings dialog width so longer lists stay readable.

---

## Repository & Authentication

- **Private repository authentication**: Uses credentials from Maven `settings.xml` for authenticated repository access and falls back to `${user.home}/.m2/settings.xml` when no explicit Maven user settings path is configured in the IDE.
- **Credential placeholder resolution**: Resolves `${env.VAR_NAME}` and `${VAR_NAME}` placeholders for repository credentials.
- **Credential matching fallbacks**: Matches credentials by repository ID, then repository URL, then repository host.
- **Configurable Central-first repository strategy**: Queries Maven Central first and can either stop after a successful Central response (default, faster) or continue with private repositories to include private-only versions. In the settings dialog, the Central short-circuit option is placed before the general Maven sync toggle so the lookup flow reads naturally from Central to private repositories before the post-update sync action.
- **Configurable Maven Repository Browser**: The repository browser used for opening artifact version pages can be selected under **Settings > Tools > MavenUp**. Two options are available: **MVN Repository** (default, `mvnrepository.com`) and **Sonatype Central** (`central.sonatype.com`). The selection applies to both the context menu in the main table and the Vulnerability Details row context menu (all columns except **References**).

---

## Vulnerability Scanning

- **Multi-source vulnerability check**: Uses [OSV.dev](https://osv.dev) as the primary source and can optionally enrich results with Maven-focused findings from [Sonatype OSS Index](https://ossindex.sonatype.org/).
- **Privacy & transparent data transmission**: Transmits exclusively Maven coordinates (groupId, artifactId, version) over HTTPS for version checks and vulnerability scanning without sending any source code, file paths, or private project data.
- **Resolved transitive dependency coverage**: Includes the resolved Maven dependency tree by default and associates transitive findings with the direct dependency that introduced them.
- **Detailed vulnerability intelligence**: Retrieves advisory identifiers, aliases, summaries, severity/CVSS information, references, and source attribution; unsupported CVSS vectors fall back to source severity without dropping the advisory.
- **Cross-source deduplication**: Merges matching CVE, GHSA, OSV, and Sonatype findings by intersecting advisory identifiers and aliases.
- **Withdrawn advisory filtering**: Excludes withdrawn OSV advisories from results.
- **Severity-aware current-version vulnerability column**: Displays **Vulnerabilities (Current)** directly after **Current Version**, showing the total finding count, transitive finding count, and highest known severity for the direct dependency and its resolved transitive dependencies; stays empty until a check has been run. When findings are present, a link icon indicates that the cell is clickable to open the vulnerability details dialog.
- **Vulnerability details dialog**: Shows direct and related transitive findings for an individual dependency, including transitive component markers and browser-accessible references. Every column is sortable through the ascending → descending → unsorted cycle, with the same header sort indicators as the tool window; the **Severity** column leads with the most critical findings on the first click, ordered primarily by criticality and secondarily by CVSS score (highest first), and the **References** column sorts by the number of references. The **Severity** column is color-coded by severity using the same scheme as the tool window's vulnerability column. Two row-based toolbar actions (**Open in ...** and **References...**) in the dialog's top action toolbar stay disabled until a vulnerability row is selected; they then open the selected component in the configured repository browser or the selected advisory's references dialog. Right-clicking the selected row in any column except **References** opens a context menu with **Open in Maven Repository** and **References...** for that advisory. Clicking a cell in the **References** column opens a dedicated list dialog showing all links for that advisory; clicking any link opens it in the browser. Both dialogs show only a **Close** button (no OK/Cancel) in line with JetBrains UI guidelines for read-only dialogs. The **Vulnerability Details** action in the main tool window is enabled only when a dependency row with findings is selected and opens the findings exclusively for that selected dependency.

---

## OSS Index Integration

- **Validated OSS Index authentication**: Requires an API token when OSS Index is enabled, visibly marks the token field as required, and skips OSS Index requests when the stored token is missing.
- **Secure and responsive OSS Index credentials**: Stores the required API token in IntelliJ Password Safe instead of project settings and loads it outside the Event Dispatch Thread.
- **OSS Index token guidance**: Provides a direct settings link to the Sonatype account page where users can create or copy their API token.
- **Qualified OSS Index token errors**: Detects rejected requests (HTTP 401/403) and shows a dedicated message that the API token is invalid or expired instead of a generic HTTP error; the error dialog offers an **Open Settings** button to jump straight to the plugin configuration.

---

## Settings & Configuration

- **Global plugin settings**: Supports configurable behavior in `Settings > Tools > MavenUp` (click behavior, auto-select strategy, unstable-version filtering, and Central-short-circuit behavior), stored at application level so the configuration applies to all projects, organized into three headed groups: **Appearance**, **Versions & Updates**, and **Vulnerability Check**.
- **Immediate auto-select setting application**: Changing the auto-selection mode in settings and applying it immediately updates all **New Version** selections in the open tool window without requiring a new update check.
- **Configurable toolbar button style**: Lets users switch the tool window and Vulnerability Details dialog toolbars between icon-only buttons and buttons with text labels (default); the change applies immediately to the open tool window.

---

## Performance & Reliability

- **Background execution for long operations**: Collects Maven/PSI refresh data through non-blocking background read actions and also runs update checks, navigation tasks, and write operations outside the UI thread.
- **Safe action availability**: Disables **Scan for Vulnerabilities** while a refresh or update check is running to prevent overlapping background operations.
- **Compact logging and diagnostics**: Logs parsing errors, credential resolution issues, and repository request failures (including HTTP status), while limiting verbose version and component lists to truncated DEBUG messages.
- **Restart-free installation and updates**: Uses only dynamic extension points and registers the Maven import listener declaratively, so the plugin can be loaded, unloaded, and updated without restarting the IDE.

---

## Architecture

- **Layered internal architecture**: Code is organized into explicit `model`, `service`, and `ui` packages to keep responsibilities separated and maintainable.
- **Service-based API access**: External OSV, OSS Index, and Maven metadata API requests are handled through dedicated service-layer components instead of UI classes.
