# MavenUp Plugin Features

This document lists the key features of the MavenUp IntelliJ plugin.

- **Maven-only tool window availability**: The MavenUp tool window is shown for Maven projects and becomes available after Maven import if needed.
- **Dependency and plugin overview**: Displays dependencies and plugins from `pom.xml` in a table.
- **Support for managed scopes**: Includes `dependencyManagement` and `pluginManagement` entries.
- **Property-aware version handling**: Detects property-based versions (for example `${spring.version}`) and updates the property value instead of overwriting the reference.
- **Repository-based version lookup**: Retrieves available versions from Maven repositories via `maven-metadata.xml`.
- **Private repository authentication**: Uses credentials from Maven `settings.xml` for authenticated repository access.
- **Credential placeholder resolution**: Resolves `${env.VAR_NAME}` and `${VAR_NAME}` placeholders for repository credentials.
- **Credential matching fallbacks**: Matches credentials by repository ID, then repository URL, then repository host.
- **Central-first repository strategy**: Queries Maven Central first and skips additional private repository requests for the same dependency when Central succeeds.
- **Selectable target versions**: Shows available versions in dropdowns for dependencies and plugins.
- **Optional unstable-version filtering**: Can hide versions by configurable qualifiers (for example `rc,beta,milestone`).
- **Shared-property synchronization**: Synchronizes version selection across entries that use the same Maven property.
- **Safe update workflow**: Shows a confirmation dialog before applying changes to `pom.xml`.
- **Navigation to source in `pom.xml`**: Jumps directly to the matching dependency/plugin entry (single or double click, configurable).
- **Background execution for long operations**: Collects Maven/PSI refresh data through non-blocking background read actions and also runs update checks, navigation tasks, and write operations outside the UI thread.
- **Input validation for displayed entries**: Skips dependencies and plugins without `groupId` so invalid entries are not shown.
- **Logging and diagnostics**: Logs parsing errors, credential resolution issues, and repository request failures (including HTTP status).
- **Project-level plugin settings**: Supports configurable behavior in `Settings > Tools > MavenUp` (click behavior, auto-select latest, unstable-version filtering).
- **Multi-source vulnerability check**: Uses [OSV.dev](https://osv.dev) as the primary source and can optionally enrich results with Maven-focused findings from [Sonatype OSS Index](https://ossindex.sonatype.org/).
- **Resolved transitive dependency coverage**: Includes the resolved Maven dependency tree by default, while keeping transitive findings out of the update table.
- **Detailed vulnerability intelligence**: Retrieves advisory identifiers, aliases, summaries, severity/CVSS information, references, and source attribution; unsupported CVSS versions fall back to source severity without dropping the advisory.
- **Cross-source deduplication**: Merges matching CVE, GHSA, OSV, and Sonatype findings by intersecting advisory identifiers and aliases.
- **Withdrawn advisory filtering**: Excludes withdrawn OSV advisories from results.
- **Severity-aware vulnerability column**: Displays the deduplicated finding count and highest known severity; stays empty until a check has been run.
- **Vulnerability details dialog**: Shows findings for individual components or the complete scan, including transitive component markers and browser-accessible references.
- **Secure and responsive OSS Index credentials**: Stores the optional API token in IntelliJ Password Safe instead of project settings and loads it outside the Event Dispatch Thread.
- **OSS Index token guidance**: Provides a direct settings link to the Sonatype account page where users can create or copy their API token.
- **Layered internal architecture**: Code is organized into explicit `model`, `service`, and `ui` packages to keep responsibilities separated and maintainable.
- **Service-based API access**: External OSV, OSS Index, and Maven metadata API requests are handled through dedicated service-layer components instead of UI classes.
