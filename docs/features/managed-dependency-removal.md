# Managed Dependency Removal

Features for automatically detecting redundant `<dependencyManagement>` declarations when parent POMs or direct dependencies are upgraded, and providing one-click recommendations for cleaning up the `pom.xml`.

Back to the [feature overview](../../FEATURES.md).

- **Redundancy Analysis**: Scans declared managed dependencies in the project's `<dependencyManagement>` and compares them with newer candidate versions available for parent POMs (`<parent>`) and direct dependencies (`<dependencies><dependency>`).
- **In-Memory Candidate Graph Resolution**: Uses a dedicated `TemporaryDependencyTreeResolver` to load and parse candidate POMs from local repository caches (`~/.m2/repository`) and remote repositories in memory, resolving `<parent>` hierarchies, BOM imports, and property placeholders without mutating project files.
- **Multi-Consumer Consistency Validation**: Strictly verifies that when a managed dependency is consumed across multiple transitive dependency trees, **all** consumer paths receive a compatible version (`>=` current pinned version) under the proposed candidate upgrade before emitting a recommendation.
- **Interactive Cleanup Recommendations Dialog**: Presents recommendations in `ManagedDependencyRemovalDialog` accessible via the toolbar button (**Cleanup**) and table context menu (**Check Managed Dependency Cleanup Recommendations...**). Displays triggering components, candidate target versions, provided versions, clear explanations, and detailed consumer path trees with individual selection checkboxes.
- **One-Click Application & Confirmation Pipeline**: Applying selected recommendations updates target versions in the MavenUp table and marks redundant managed dependencies as pending removal (`removeFromPom = true`). Changes can be reviewed in the standard `UpdateConfirmationDialog` before being committed to `pom.xml` via `PomUpdateService`.
