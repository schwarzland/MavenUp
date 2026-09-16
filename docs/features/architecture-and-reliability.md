# Architecture & Reliability

Features covering background execution, logging, dynamic plugin loading, and the internal code structure.

Back to the [feature overview](../../FEATURES.md).

## Performance & Reliability

- **Background execution for long operations**: Collects Maven/PSI refresh data through IntelliJ read actions and also runs update checks, navigation tasks, and write operations outside the UI thread.
- **Project-scoped automatic search coordination**: A project service runs configuration-gated version lookups after startup and Maven imports, retains the newest completed snapshot for the tool window, and prevents stale import generations from publishing results.
- **Safe action availability**: Disables **Scan for Vulnerabilities** while a refresh or update check is running to prevent overlapping background operations.
- **Compact logging and diagnostics**: Logs parsing errors, credential resolution issues, and repository request failures (including HTTP status), while limiting verbose version and component lists to truncated DEBUG messages.
- **Restart-free installation and updates**: Uses only dynamic extension points and registers the Maven import listener declaratively, so the plugin can be loaded, unloaded, and updated without restarting the IDE.

## Architecture

- **Layered internal architecture**: Code is organized into explicit `model`, `service`, and `ui` packages to keep responsibilities separated and maintainable.
- **Service-based API access**: External OSV, OSS Index, and Maven metadata API requests are handled through dedicated service-layer components instead of UI classes.
