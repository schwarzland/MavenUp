# Managed Dependency Removal

Features for automatically detecting redundant `<dependencyManagement>` declarations in the current project configuration and staging them for removal from `pom.xml`.

Back to the [feature overview](../../FEATURES.md).

- **Redundancy Analysis**: Scans declared managed dependencies in the project's `<dependencyManagement>` across the current project configuration to detect entries already managed by the parent POM, declared directly with matching version, satisfied transitively across all consumers, or unused in the project.
- **In-Memory Dependency Tree Resolution**: Uses a dedicated `TemporaryDependencyTreeResolver` to load and parse POMs from local repository caches (`~/.m2/repository`) and remote repositories in memory, resolving `<parent>` hierarchies, BOM imports, and property placeholders without mutating project files.
- **Multi-Consumer Consistency Validation**: Strictly verifies that when a managed dependency is consumed across multiple transitive dependency trees, **all** consumer paths receive a compatible version (`>=` current pinned version) before identifying a transitive match.
- **Interactive Redundant Managed Dependencies Dialog**: Reviews scoped or project-wide redundant entries with individual selection checkboxes in a resizable master-detail dialog (`RedundantManagedDependencyDialog`) with a visible, theme-aware divider and centered grip with hover feedback, a remembered divider position, and independently scrollable table and details, including redundancy reasons and consumer paths.
- **Background Progress and Cancellation**: Runs the analysis as a cancellable IntelliJ background task and shows the current Maven project while analyzing redundant entries.
- **Optional Pending Changes Overview**: The initially unchecked **Show all pending changes after applying** checkbox clears all main-table filters and activates **Pending: All Changes** after recommendations are applied, including previously pending changes while preserving sorting and selections; the choice is not remembered.
- **One-Click Application & Confirmation Pipeline**: Applying selected entries marks redundant managed dependencies as pending removal (`removeFromPom = true`). Changes can be reviewed in the standard `UpdateConfirmationDialog` before being committed to `pom.xml` via `PomUpdateService`.
