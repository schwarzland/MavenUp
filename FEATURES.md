# MavenUp Plugin Features

This document describes the key features of the MavenUp IntelliJ plugin, grouped by area.

---

## Tool Window & UI

- **Maven-only tool window availability**: The MavenUp tool window is shown for Maven projects and becomes available after Maven import if needed.
- **Dependency and plugin overview**: Displays dependencies, plugins, and the parent POM from `pom.xml` in a table.
- **Parent POM support**: The `<parent>` section of each `pom.xml` is listed as a dependency with type "parent", including version checks and updates.
- **Support for managed scopes**: Includes `dependencyManagement` and `pluginManagement` entries.
- **Input validation for displayed entries**: Skips dependencies and plugins without `groupId` so invalid entries are not shown.
- **Navigation to source in `pom.xml`**: Jumps directly to the matching dependency/plugin entry (single or double click, configurable). A tooltip on each dependency row adapts its text to the configured click mode ("Click to navigate to pom.xml | Right-click for more options" or "Double-click to navigate to pom.xml | Right-click for more options").
- **Context menu on dependency rows**: Right-clicking any dependency row opens a context menu with **Navigate to pom.xml** (jumps to the entry in the editor), **Open in Maven Repository** (opens the matching version page in the configured repository browser), and optionally **Show Vulnerability Details** (opens the vulnerability findings dialog when findings are available for the dependency).
- **Open in Repository action**: A selection-dependent action in the top action toolbar opens the current version of the selected dependency in the configured repository browser. Its tooltip dynamically reflects the configured repository browser name (e.g. **Open on MVN Repository** or **Open on Sonatype Central**), equivalent to the **Open in Maven Repository** context menu entry.
- **Safe update workflow**: Shows a confirmation dialog before applying changes to `pom.xml`, with a **Sync Maven Changes after update** checkbox (enabled by default) to automatically trigger the IDE's Maven sync after the update. The checkbox is bound to the persistent **Sync Maven changes after update** setting, so both stay in sync and the last choice is remembered.
- **Two-tier action toolbar**: Tool window and Vulnerability Details dialog present their actions in a top IntelliJ `ActionToolbar`; core actions (Refresh, Find New Versions, Scan for Vulnerabilities, Update) are separated from the selection-dependent actions (Open in Repository, Vulnerability Details / References) by a separator, with Settings placed at the end. Actions render either as icon-only buttons (with tooltips) or as buttons with text labels (default), configurable in the settings.
- **Fixed column order**: All plugin tables enforce single-row selection and prevent column reordering, so columns keep their defined order and cannot be dragged around.
- **Dependency table filtering**: A filter row above the main table combines a text field that filters by GroupId, ArtifactId or Property (case-insensitive substring match) with a type combo box; only rows matching both filters remain visible, and the type combo box lists the dependency types currently present in the table plus an **All** option.

---

## Version Management

- **Repository-based version lookup**: Retrieves available versions from Maven repositories via `maven-metadata.xml`.
- **Selectable target versions**: Shows available versions in dropdowns for dependencies and plugins.
- **Version status indicators**: The **New Version** column displays a green checkmark icon when the selected version equals the highest known version, or an orange arrow-up icon otherwise; hovering shows a tooltip with status details. When a version different from the current one is selected, the dropdown text is additionally displayed in bold and color-coded (green for latest, orange otherwise) to clearly indicate a pending change. When the selected version matches the current version, the text uses the default color (same as the **Current Version** column). Colors adapt to Light and Dark themes.
- **Property-aware version handling**: Detects property-based versions (for example `${spring.version}`) and updates the property value instead of overwriting the reference.
- **Shared-property synchronization**: Synchronizes version selection across entries that use the same Maven property.
- **Optional unstable-version filtering**: Can hide versions by configurable qualifiers (for example `rc,beta,milestone`). The qualifier list is shown as an indented sub-setting and grows with the settings dialog width so longer lists stay readable.

---

## Repository & Authentication

- **Private repository authentication**: Uses credentials from Maven `settings.xml` for authenticated repository access.
- **Credential placeholder resolution**: Resolves `${env.VAR_NAME}` and `${VAR_NAME}` placeholders for repository credentials.
- **Credential matching fallbacks**: Matches credentials by repository ID, then repository URL, then repository host.
- **Central-first repository strategy**: Queries Maven Central first and skips additional private repository requests for the same dependency when Central succeeds.
- **Configurable Maven Repository Browser**: The repository browser used for opening artifact version pages can be selected under **Settings > Tools > MavenUp**. Two options are available: **MVN Repository** (default, `mvnrepository.com`) and **Sonatype Central** (`central.sonatype.com`). The selection applies to both the context menu in the main table and the Vulnerability Details row context menu (all columns except **References**).

---

## Vulnerability Scanning

- **Multi-source vulnerability check**: Uses [OSV.dev](https://osv.dev) as the primary source and can optionally enrich results with Maven-focused findings from [Sonatype OSS Index](https://ossindex.sonatype.org/).
- **Resolved transitive dependency coverage**: Includes the resolved Maven dependency tree by default and associates transitive findings with the direct dependency that introduced them.
- **Detailed vulnerability intelligence**: Retrieves advisory identifiers, aliases, summaries, severity/CVSS information, references, and source attribution; unsupported CVSS versions fall back to source severity without dropping the advisory.
- **Cross-source deduplication**: Merges matching CVE, GHSA, OSV, and Sonatype findings by intersecting advisory identifiers and aliases.
- **Withdrawn advisory filtering**: Excludes withdrawn OSV advisories from results.
- **Severity-aware current-version vulnerability column**: Displays **Vulnerabilities (Current)** directly after **Current Version**, showing the total finding count, transitive finding count, and highest known severity for the direct dependency and its resolved transitive dependencies; stays empty until a check has been run. When findings are present, a link icon indicates that the cell is clickable to open the vulnerability details dialog.
- **Vulnerability details dialog**: Shows direct and related transitive findings for an individual dependency, including transitive component markers and browser-accessible references. Two row-based toolbar actions (**Open in ...** and **References...**) in the dialog's top action toolbar stay disabled until a vulnerability row is selected; they then open the selected component in the configured repository browser or the selected advisory's references dialog. Right-clicking the selected row in any column except **References** opens a context menu with **Open in Maven Repository** and **References...** for that advisory. Clicking a cell in the **References** column opens a dedicated list dialog showing all links for that advisory; clicking any link opens it in the browser. Both dialogs show only a **Close** button (no OK/Cancel) in line with JetBrains UI guidelines for read-only dialogs. The **Vulnerability Details** action in the main tool window is enabled only when a dependency row with findings is selected and opens the findings exclusively for that selected dependency.

---

## OSS Index Integration

- **Validated OSS Index authentication**: Requires an API token when OSS Index is enabled, visibly marks the token field as required, and skips OSS Index requests when the stored token is missing.
- **Secure and responsive OSS Index credentials**: Stores the required API token in IntelliJ Password Safe instead of project settings and loads it outside the Event Dispatch Thread.
- **OSS Index token guidance**: Provides a direct settings link to the Sonatype account page where users can create or copy their API token.
- **Qualified OSS Index token errors**: Detects rejected requests (HTTP 401/403) and shows a dedicated message that the API token is invalid or expired instead of a generic HTTP error; the error dialog offers an **Open Settings** button to jump straight to the plugin configuration.

---

## Settings & Configuration

- **Project-level plugin settings**: Supports configurable behavior in `Settings > Tools > MavenUp` (click behavior, auto-select latest, unstable-version filtering), organized into three headed groups: **Appearance**, **Versions & Updates**, and **Vulnerability Check**.
- **Immediate auto-select setting application**: Toggling the "Automatically select the newest version" setting and applying it immediately updates all **New Version** selections in the open tool window without requiring a new update check.
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
