# Settings & Configuration

Features of the plugin settings under `Settings > Tools > MavenUp`.

Back to the [feature overview](../../FEATURES.md).

- **Global plugin settings**: Supports configurable behavior in `Settings > Tools > MavenUp` (click behavior, automatic version search, auto-select strategy, unstable-version filtering, Central-short-circuit behavior, the private GroupId filter, and the explanatory comment written for pinned vulnerability fixes), stored at application level so the configuration applies to all projects. Following the IntelliJ UI guidelines the options are split across a settings tree instead of one long page: the **MavenUp** root page holds appearance and behavior plus quick links to its sub-pages, and the sub-pages **Versions and Updates** (groups *Version Lookup*, *Privacy*, *Version Selection*), **Vulnerability Check**, and **Pom.xml Changes** hold the remaining topics. Dependent fields such as the qualifier list or the comment options enable and disable themselves automatically, and every option carries an explanatory comment below its control.
- **Immediate auto-select setting application**: Changing the auto-selection mode in settings and applying it immediately updates all **New Version** selections in the open tool window without requiring a new update check.
- **Configurable toolbar button style**: Lets users switch the tool window toolbar between icon-only buttons and buttons with text labels (default); the change applies immediately to the open tool window.
- **Configurable status badge**: Lets users choose whether the badge on the tool window icon reports vulnerabilities and available updates (default), only vulnerabilities, or nothing at all; the change applies immediately to the open tool window.
