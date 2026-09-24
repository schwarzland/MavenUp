# Architecture & Reliability

Features covering background execution, logging, plugin lifecycle reliability, and the internal code structure.

Back to the [feature overview](../../FEATURES.md).

## Performance & Reliability

- **Background execution for long operations**: Collects Maven/PSI refresh data through IntelliJ read actions and also runs update checks, navigation tasks, and write operations outside the UI thread.
- **Project-scoped automatic search coordination**: A project service runs configuration-gated version lookups after startup and Maven imports, retains the newest completed snapshot for the tool window, and prevents stale import generations from publishing results.
- **Safe action availability**: Disables **Scan for Vulnerabilities** while a refresh or update check is running to prevent overlapping background operations.
- **Compact logging and diagnostics**: Logs parsing errors, credential resolution issues, repository request failures (including HTTP status), and every OSV.dev or Sonatype OSS Index vulnerability request at DEBUG level without logging access tokens; verbose component lists remain truncated where appropriate.
- **Reliable plugin lifecycle**: Explicitly requires an IDE restart after plugin installation, updates, or disablement instead of relying on dynamic loading and unloading.

## Architecture

- **Layered internal architecture**: Code is organized into explicit `model`, `service`, and `ui` packages to keep responsibilities separated and maintainable.
- **Service-based API access**: External OSV, OSS Index, and Maven metadata API requests are handled through dedicated service-layer components instead of UI classes.
