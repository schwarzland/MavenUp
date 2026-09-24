# MavenUp

[![JetBrains Marketplace Version](https://img.shields.io/jetbrains/plugin/v/de.schwarzland.mavenup.svg?label=marketplace)](https://plugins.jetbrains.com/plugin/33068-mavenup)
[![JetBrains Marketplace Downloads](https://img.shields.io/jetbrains/plugin/d/de.schwarzland.mavenup.svg)](https://plugins.jetbrains.com/plugin/33068-mavenup)
[![Build and Test](https://github.com/schwarzland/MavenUp/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/schwarzland/MavenUp/actions/workflows/ci.yml)
[![Dependency Graph](https://github.com/schwarzland/MavenUp/actions/workflows/dependency-graph.yml/badge.svg?branch=main)](https://github.com/schwarzland/MavenUp/actions/workflows/dependency-graph.yml)
[![Create Draft Release](https://github.com/schwarzland/MavenUp/actions/workflows/create-draft-release.yml/badge.svg)](https://github.com/schwarzland/MavenUp/actions/workflows/create-draft-release.yml)
[![Publish Release to Marketplace](https://github.com/schwarzland/MavenUp/actions/workflows/publish-release.yml/badge.svg)](https://github.com/schwarzland/MavenUp/actions/workflows/publish-release.yml)

MavenUp is an IntelliJ plugin built specifically for Maven projects to simplify the management of dependencies and plugins. It provides a clear table view of all declared components and enables easy updates to newer versions, online version checks against Maven Central and private repositories, and multi-source vulnerability scanning.

**Dependency Tab**

![Dependency Tab](/docs/assets/main.png "Dependency Tab")

**Transitive CVEs Tab**

![Transitive CVEs Tab](/docs/assets/transitive_cves_kontext.png "Transitive CVEs Tab")

## Installation

Install **MavenUp** from the JetBrains Marketplace via `Settings > Plugins > Marketplace`, or download the plugin ZIP from the [releases](https://github.com/schwarzland/MavenUp/releases) and install it via `Settings > Plugins > Install Plugin from Disk…`. Restart the IDE after installing, updating, or disabling MavenUp.

## Quick start

1. Open a Maven project and open the **MavenUp** tool window.
2. MavenUp checks for newer versions in the background after the Maven project loads and after Maven resyncs; open the tool window to review the results or use **Refresh and Search for New Versions** to repeat it manually. Refreshes reuse fresh vulnerability results from the project-session cache and can rescan cache misses automatically.
3. Pick target versions and click **Update**, or run **Scan for Vulnerabilities**. 

See the [usage guide](docs/usage.md) for details.

Open **Settings > Tools > Maven Up > Vulnerability Check** to configure scanning, inspect the current
project-session vulnerability cache, and manage optional OSS Index access.

## Documentation

- [Features](FEATURES.md) — feature index (single source of truth), split by area under [docs/features/](docs/features/).
- [Usage](docs/usage.md) — how to use the tool window, filters, and actions.
- [Configuration](docs/configuration.md) — all settings.
- [Privacy & Security](docs/privacy-and-security.md) — transmitted data and external endpoints.
- [Architecture](docs/architecture.md) — package structure and components.
- [Development](docs/development.md) — tests, code quality, and troubleshooting.
- [Release & CI](docs/release-and-ci.md) — branching, workflows, and publishing.
- [Third-party licenses](docs/licenses.md) — bundled libraries and their licenses.

## AI instructions

Binding instructions and context information exist for the further development of this project by AI agents (such as GitHub Copilot or Junie):

- **[AGENTS.md](AGENTS.md)**: cross-tool entry point read by many AI coding agents (e.g. OpenAI Codex / Codex CLI, GitHub Copilot CLI, Cursor, Aider, Jules, Zed); it points every agent to the binding repository instructions below.
- **[.github/copilot-instructions.md](.github/copilot-instructions.md)**: binding work instructions for documentation, testing, KDoc, and processes.
- **[.github/copilot-project-context.md](.github/copilot-project-context.md)**: project context overview, package structure, and links to the component references.
- **[.github/context/](.github/context/)**: detailed component descriptions per package — [`components-ui.md`](.github/context/components-ui.md), [`components-ui-toolwindow.md`](.github/context/components-ui-toolwindow.md), [`components-ui-dialogs.md`](.github/context/components-ui-dialogs.md) and [`components-service.md`](.github/context/components-service.md).
