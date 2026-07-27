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
- **Background execution for long operations**: Runs update checks, navigation tasks, and write operations in background tasks to keep the IDE responsive.
- **Input validation for displayed entries**: Skips dependencies and plugins without `groupId` so invalid entries are not shown.
- **Logging and diagnostics**: Logs parsing errors, credential resolution issues, and repository request failures (including HTTP status).
- **Project-level plugin settings**: Supports configurable behavior in `Settings > Tools > MavenUp` (click behavior, auto-select latest, unstable-version filtering).
