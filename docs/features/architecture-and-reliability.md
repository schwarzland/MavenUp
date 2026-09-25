# Architecture & Reliability

Features covering background execution, logging, plugin lifecycle reliability, and the internal code structure.

Back to the [feature overview](../../FEATURES.md).

## Performance & Reliability

- **Background execution for long operations**: Collects Maven/PSI refresh data through IntelliJ read actions and also runs update checks, navigation tasks, and write operations outside the UI thread.
- **Project-scoped automatic search coordination**: A project service runs configuration-gated version lookups after startup and Maven imports, retains the newest completed snapshot for the tool window, and prevents stale import generations from publishing results.
- **Safe action availability**: Disables **Scan for Vulnerabilities** while a refresh or update check is running to prevent overlapping background operations.
- **Compact logging and diagnostics**: Logs cache hits and live lookups for versions and vulnerabilities, including version-cache misses, expired entries, disabled caching, and each Maven metadata, OSV.dev, and Sonatype OSS Index request at DEBUG level, while keeping verbose lists truncated.
- **Cache content inspection**: A toolbar action opens two sortable tables for the version metadata and vulnerability result caches, showing the coordinate or artifact, cached count, query timestamp, and remaining TTL in seconds at snapshot time.
- **Manual cache invalidation**: The cache dialog's **Invalidate** action clears the entire application-wide cache of its active tab and refreshes the snapshots and tab counts without starting a search or scan.
- **Reliable plugin lifecycle**: Explicitly requires an IDE restart after plugin installation, updates, or disablement instead of relying on dynamic loading and unloading.

## Architecture

- **Layered internal architecture**: Code is organized into explicit `model`, `service`, and `ui` packages to keep responsibilities separated and maintainable.
- **Service-based API access**: External OSV, OSS Index, and Maven metadata API requests are handled through dedicated service-layer components instead of UI classes.
