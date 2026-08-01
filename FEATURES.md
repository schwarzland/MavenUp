# MavenUp Plugin Features

This document describes the key features of the MavenUp IntelliJ plugin, grouped by area.

---

## Tool Window & UI

- **Maven-only tool window availability**: The MavenUp tool window is shown for Maven projects and becomes available after Maven import if needed.
- **Dependency and plugin overview**: Displays dependencies and plugins from `pom.xml` in a table.
- **Support for managed scopes**: Includes `dependencyManagement` and `pluginManagement` entries.
- **Input validation for displayed entries**: Skips dependencies and plugins without `groupId` so invalid entries are not shown.
- **Navigation to source in `pom.xml`**: Jumps directly to the matching dependency/plugin entry (single or double click, configurable). A tooltip on each dependency row adapts its text to the configured click mode ("Click to navigate to pom.xml | Right-click for more options" or "Double-click to navigate to pom.xml | Right-click for more options").
- **Context menu on dependency rows**: Right-clicking any dependency row opens a context menu with two entries: **Navigate to pom.xml** (jumps to the entry in the editor) and **Open in Maven Repository** (opens the matching version page in the configured repository browser).
- **Safe update workflow**: Shows a confirmation dialog before applying changes to `pom.xml`.

---

## Version Management

- **Repository-based version lookup**: Retrieves available versions from Maven repositories via `maven-metadata.xml`.
- **Selectable target versions**: Shows available versions in dropdowns for dependencies and plugins.
- **Property-aware version handling**: Detects property-based versions (for example `${spring.version}`) and updates the property value instead of overwriting the reference.
- **Shared-property synchronization**: Synchronizes version selection across entries that use the same Maven property.
- **Optional unstable-version filtering**: Can hide versions by configurable qualifiers (for example `rc,beta,milestone`).

---

## Repository & Authentication

- **Private repository authentication**: Uses credentials from Maven `settings.xml` for authenticated repository access.
- **Credential placeholder resolution**: Resolves `${env.VAR_NAME}` and `${VAR_NAME}` placeholders for repository credentials.
- **Credential matching fallbacks**: Matches credentials by repository ID, then repository URL, then repository host.
- **Central-first repository strategy**: Queries Maven Central first and skips additional private repository requests for the same dependency when Central succeeds.
- **Configurable Maven Repository Browser**: The repository browser used for opening artifact version pages can be selected under **Settings > Tools > MavenUp**. Two options are available: **MVN Repository** (default, `mvnrepository.com`) and **Sonatype Central** (`central.sonatype.com`). The selection applies to both the context menu in the main table and the Component column in the Vulnerability Details dialog.

---

## Vulnerability Scanning

- **Multi-source vulnerability check**: Uses [OSV.dev](https://osv.dev) as the primary source and can optionally enrich results with Maven-focused findings from [Sonatype OSS Index](https://ossindex.sonatype.org/).
- **Resolved transitive dependency coverage**: Includes the resolved Maven dependency tree by default and associates transitive findings with the direct dependency that introduced them.
- **Detailed vulnerability intelligence**: Retrieves advisory identifiers, aliases, summaries, severity/CVSS information, references, and source attribution; unsupported CVSS versions fall back to source severity without dropping the advisory.
- **Cross-source deduplication**: Merges matching CVE, GHSA, OSV, and Sonatype findings by intersecting advisory identifiers and aliases.
- **Withdrawn advisory filtering**: Excludes withdrawn OSV advisories from results.
- **Severity-aware current-version vulnerability column**: Displays **Vulnerabilities (Current)** directly after **Current Version**, showing the total finding count, transitive finding count, and highest known severity for the direct dependency and its resolved transitive dependencies; stays empty until a check has been run. When findings are present, a link icon indicates that the cell is clickable to open the vulnerability details dialog.
- **Vulnerability details dialog**: Shows direct and related transitive findings for an individual dependency, or the complete scan, including transitive component markers and browser-accessible references. Clicking a cell in the **Component** column opens the component's MVN Repository page in the browser. Clicking a cell in the **References** column opens a dedicated list dialog showing all links for that advisory; clicking any link opens it in the browser. Both dialogs show only a **Close** button (no OK/Cancel) in line with JetBrains UI guidelines for read-only dialogs.

---

## OSS Index Integration

- **Validated OSS Index authentication**: Requires both username/email and API token when OSS Index is enabled, visibly marks both fields as required, and skips OSS Index requests when stored credentials are incomplete.
- **Secure and responsive OSS Index credentials**: Stores the required API token in IntelliJ Password Safe instead of project settings and loads it outside the Event Dispatch Thread.
- **OSS Index token guidance**: Provides a direct settings link to the Sonatype account page where users can create or copy their API token.

---

## Settings & Configuration

- **Project-level plugin settings**: Supports configurable behavior in `Settings > Tools > MavenUp` (click behavior, auto-select latest, unstable-version filtering).

---

## Performance & Reliability

- **Background execution for long operations**: Collects Maven/PSI refresh data through non-blocking background read actions and also runs update checks, navigation tasks, and write operations outside the UI thread.
- **Safe action availability**: Disables **Check Vulnerabilities** while a refresh or update check is running to prevent overlapping background operations.
- **Compact logging and diagnostics**: Logs parsing errors, credential resolution issues, and repository request failures (including HTTP status), while limiting verbose version and component lists to truncated DEBUG messages.

---

## Architecture

- **Layered internal architecture**: Code is organized into explicit `model`, `service`, and `ui` packages to keep responsibilities separated and maintainable.
- **Service-based API access**: External OSV, OSS Index, and Maven metadata API requests are handled through dedicated service-layer components instead of UI classes.
