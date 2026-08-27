# MavenUp

MavenUp is an IntelliJ plugin built specifically for Maven projects to simplify the management of dependencies and plugins. It provides a clear table view of all declared components and enables easy updates to newer versions, online version checks against Maven Central and private repositories, and multi-source vulnerability scanning.

## Installation

Install **MavenUp** from the JetBrains Marketplace via `Settings > Plugins > Marketplace`, or download the plugin ZIP from the [releases](https://github.com/schwarzland/MavenUp/releases) and install it via `Settings > Plugins > Install Plugin from Disk…`.

## Quick start

1. Open a Maven project and open the **MavenUp** tool window.
2. Click **Refresh** to populate the table, then **Search for New Versions**.
3. Pick target versions and click **Update**, or run **Scan for Vulnerabilities**.

See the [usage guide](docs/usage.md) for details.

## Documentation

- [Features](FEATURES.md) — full feature overview (single source of truth).
- [Usage](docs/usage.md) — how to use the tool window, filters, and actions.
- [Configuration](docs/configuration.md) — all settings and Gradle proxy setup.
- [Privacy & Security](docs/privacy-and-security.md) — transmitted data and external endpoints.
- [Architecture](docs/architecture.md) — package structure and components.
- [Development](docs/development.md) — tests, code quality, and troubleshooting.
- [Release & CI](docs/release-and-ci.md) — branching, workflows, and publishing.
- [Third-party licenses](docs/licenses.md) — bundled libraries and their licenses.

## AI instructions

Binding instructions and context information exist for the further development of this project by AI agents (such as GitHub Copilot or Junie):

- **[.github/copilot-instructions.md](.github/copilot-instructions.md)**: binding work instructions for documentation, testing, KDoc, and processes.
- **[.github/copilot-project-context.md](.github/copilot-project-context.md)**: detailed project context, architecture overview, and component description.

These files must be considered and kept up to date with every change.
