# MavenUp Plugin Features

This document is the index of all MavenUp features. It is the single source of truth for what the plugin
can do; the detailed feature descriptions are split by area into the files under [`docs/features/`](docs/features/).

## Feature areas

- [Tool Window & UI](docs/features/tool-window-and-ui.md) - table content, navigation, context menus, toolbar, filtering, and sorting.
- [Version Management](docs/features/version-management.md) - version lookup, selection strategies, bulk actions, and status indicators.
- [Repositories & Authentication](docs/features/repositories-and-authentication.md) - repository queries, credentials from Maven `settings.xml`, and the repository browser.
- [Vulnerability Scanning](docs/features/vulnerability-scanning.md) - multi-source scanning, transitive findings, the details dialog, and OSS Index integration.
- [Settings & Configuration](docs/features/settings-and-configuration.md) - the options under `Settings > Tools > MavenUp`.
- [Architecture & Reliability](docs/features/architecture-and-reliability.md) - background execution, logging, dynamic loading, and package structure.

## Maintaining this document

- Add a new feature as a bullet point in the matching file under `docs/features/` - one thought per line.
- Update the existing bullet point when a feature changes; remove it when the feature is dropped.
- Add a new area only when it does not fit an existing file, and link it in the list above.
