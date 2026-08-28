# Architecture

The plugin is clearly divided into three layers.

## Data model (`de.schwarzland.mavenup.model`)
- Contains lean DTOs for the UI/service data exchange, e.g. `DependencyUpdate` and `VulnerabilityAdvisory`.

## Service (`de.schwarzland.mavenup.service`)
- `MavenUpStartupActivity`: makes the tool window available on project startup as soon as Maven projects already exist.
- `MavenUpMavenImportListener`: declaratively (`<projectListeners>`) registered `MavenImportListener` that makes the tool window available after every completed Maven import; the declarative registration is unregistered automatically when the plugin is unloaded and enables updates without an IDE restart.
- `MavenUpToolWindowActivator`: shared, idempotent helper object used by the startup activity and the import listener to make the tool window available.
- `MavenUpSettings`: application-wide persistence service (`PersistentStateComponent`, `Service.Level.APP`) in `mavenup_settings.xml`; the settings apply globally to all projects and also contain the configurable Central-first short-circuit strategy. The OSS Index token is stored separately via `OssIndexCredentialService` in the Password Safe. For HTTP Basic authentication a fixed placeholder username is used because Sonatype only evaluates the token.
- `DependencyApiService`, `VulnerabilityApiService`, and `OssIndexApiService`: encapsulate external API queries for versions and vulnerabilities outside the UI; `DependencyApiService` robustly determines the Maven `settings.xml` via the IDE path with a fallback to `${user.home}/.m2/settings.xml` and logs the used path at DEBUG level.
- `VulnerabilityMerger`: deduplicates findings from multiple sources based on advisory IDs and aliases.
- `RefreshSnapshotCollector`: reads the dependencies, plugins, and version properties declared in the project via PSI and returns a `RefreshSnapshot`; resolves property placeholders (`${...}`), including the version in the `<parent>` tag.
- `PomUpdateService`: applies selected version updates to the `pom.xml` files via PSI (dependencies, dependencyManagement, plugins, pluginManagement, parent) and saves the changes when Maven sync is active.
- `VulnerabilityScanService`: determines direct and transitive scan targets from the Maven model and encapsulates the Sonatype OSS Index query including error handling.
- `DependencyVersionService`: queries the available versions of all dependencies and plugins and derives a pre-selection from them depending on the auto-selection strategy (`VersionSearchResult`); the stateless auto-selection helpers live in `VersionAutoSelection`.
- `PomNavigationService`: locates dependency, parent, and plugin definitions in the `pom.xml` and opens the editor at the respective location.
- Supported CVSS vectors from OSV are converted into comparable base scores with `us.springett:cvss-calculator`. For not-yet-supported vectors, the finding is retained and uses the severity of the source.

## UI (`de.schwarzland.mavenup.ui`)
- `MavenUpWindowFactory`: tool window factory and UI interaction for the table, update, and vulnerability workflows; the actions live in a top `ActionToolbar`, refresh data is collected via a non-blocking read action off the EDT.
- Stateless UI helper files in the same package encapsulate pure logic and rendering without a dependency on the tool window instance: `MavenUpTableConstants` (column/message-key constants), `VersionStatusUi` (version status glyphs, colors, and `createVersionPanel`), `DependencyFilterModel` (`TriStateFilter`, `FilterRow`/`FilterCriteria`, `rowMatchesFilter`), `VulnerabilityCellModel` (`VulnerabilityCell`, summary, and coordinate helpers), `RefreshSnapshot`, `MavenRepositoryLink` (`buildMavenRepositoryUrl`), `SortableHeaderIcon`, and `HelpTooltipExtensions`.
- `VulnerabilityDetailDialog`: shows direct and transitive security findings in a read-only master-detail split view with only a **Close** button; the upper table lists component, source, advisory, aliases, and severity, while the lower detail pane shows the selected finding including an **Open on [Browser]** hyperlink and the references as clickable hyperlinks. A right-click on the selected row opens a context menu with **Open on [Browser]**.
- `UpdateConfirmationDialog`: `DialogWrapper` that shows the pending updates (GroupId, ArtifactId, type, old/new version) in a read-only table before applying and offers the **Sync Maven Changes** option (pre-filled from `MavenUpSettings`).
- `MavenUpConfigurable`: settings UI under `Settings > Tools > MavenUp`, bound to `MavenUpSettings`; Password Safe accesses are loaded in the background and cached for change detection.
- `MyMessageBundle` (still in the base package): centralized i18n texts for the UI.
