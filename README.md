# MavenUp

[![Build and Test](https://github.com/schwarzland/MavenUp/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/schwarzland/MavenUp/actions/workflows/ci.yml)
[![Create Draft Release](https://github.com/schwarzland/MavenUp/actions/workflows/create-draft-release.yml/badge.svg)](https://github.com/schwarzland/MavenUp/actions/workflows/create-draft-release.yml)
[![Publish Release to Marketplace](https://github.com/schwarzland/MavenUp/actions/workflows/publish-release.yml/badge.svg)](https://github.com/schwarzland/MavenUp/actions/workflows/publish-release.yml)

MavenUp is an IntelliJ plugin built specifically for Maven projects to simplify the management of dependencies and plugins. It provides a clear table view of all declared components and enables easy updates to newer versions, online version checks against Maven Central and private repositories, and multi-source vulnerability scanning.

## Installation

Install **MavenUp** from the JetBrains Marketplace via `Settings > Plugins > Marketplace`, or download the plugin ZIP from the [releases](https://github.com/schwarzland/MavenUp/releases) and install it via `Settings > Plugins > Install Plugin from Disk…`.

## Quick start

1. Open a Maven project and open the **MavenUp** tool window.
2. The table is populated automatically and new versions are searched right away; use **Refresh and Search for New Versions** to repeat it manually.
3. Pick target versions and click **Update**, or run **Scan for Vulnerabilities**.

See the [usage guide](docs/usage.md) for details.

## Documentation

- [Features](FEATURES.md) — full feature overview (single source of truth).
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
- **[.github/copilot-project-context.md](.github/copilot-project-context.md)**: detailed project context, architecture overview, and component description.

These files must be considered and kept up to date with every change.
