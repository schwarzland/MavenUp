# Managed Dependency Removal

Features for automatically detecting redundant `<dependencyManagement>` declarations in the current project configuration and staging them for removal from `pom.xml`.

Back to the [feature overview](../../FEATURES.md).

- **Redundancy Analysis**: Reports why a local `<dependencyManagement>` entry may be removable using the labels **Version Managed by Parent**, **Explicit Direct Dependency**, or **Version Provided Transitively**; equal or higher provided versions are candidates, and the details identify version differences so users can decide whether to apply the removal.
- **In-Memory Dependency Tree Resolution**: Uses a dedicated `TemporaryDependencyTreeResolver` to load and parse POMs from local repository caches (`~/.m2/repository`) and remote repositories in memory, resolving `<parent>` hierarchies, BOM imports, and property placeholders without mutating project files.
- **Multi-Consumer Consistency Validation**: Requires every known transitive consumer path to resolve to at least the currently managed version without relying on the local entry; when the local entry is removed, the check first applies the effective fallback from parent or imported BOM dependency management before validating per-path versions, and lower, missing, or unresolvable versions do not qualify.
- **Interactive Redundant Managed Dependencies Dialog**: Reviews scoped or project-wide candidates in an initially expanded master-detail dialog (`RedundantManagedDependencyDialog`) with individual selection checkboxes, a selected-row details pane with a separate-line source POM and bulleted consumer paths, a project column shown only for multi-project results, and a **Mark Selected Entries for Removal** action that stages removals for later application through **Update**.
- **Background Progress and Cancellation**: Runs the analysis as a cancellable IntelliJ background task and shows the current Maven project while analyzing redundant entries.
- **Optional Pending Changes Overview**: The initially unchecked **Show all pending changes after staging** checkbox clears all main-table filters and activates **Pending: All Changes** after recommendations are staged, including previously pending changes while preserving sorting and selections; the choice is not remembered.
- **One-Click Application & Confirmation Pipeline**: Applying selected entries marks redundant managed dependencies as pending removal (`removeFromPom = true`). Changes can be reviewed in the standard `UpdateConfirmationDialog` before being committed to `pom.xml` via `PomUpdateService`.
