# Architecture & Reliability

Features covering background execution, logging, plugin lifecycle reliability, and the internal code structure.

Back to the [feature overview](../../FEATURES.md).

## Performance & Reliability

- **Background execution for long operations**: Collects Maven/PSI refresh data through IntelliJ read actions and also runs update checks, navigation tasks, and write operations outside the UI thread.
- **Project-scoped automatic search coordination**: A project service runs configuration-gated version lookups after startup and Maven imports, retains the newest completed snapshot for the tool window, and prevents stale import generations from publishing results.
- **Safe action availability**: Disables **Scan for Vulnerabilities** while a refresh or update check is running to prevent overlapping background operations.
- **Compact logging and diagnostics**: Logs cache decisions, individual version and vulnerability requests, and redundant managed dependency analysis scope, results, POM sources, HTTP GET attempts and response statuses at DEBUG level without exposing repository credentials, while keeping verbose lists truncated.
- **Request coalescing and thundering-herd prevention**: Merges concurrent version searches and vulnerability checks for identical artifacts or coordinates into a single active in-flight request, sharing results across all waiting callers.
- **Cache content inspection**: Dedicated buttons in the **Versions and Updates** and **Vulnerability Check** settings pages open a sortable diagnostic table for the respective cache, showing the artifact or coordinate, cached count, query timestamp, and remaining TTL in seconds at snapshot time.
- **Disk-backed cache persistence across IDE restarts**: Persists retrieved version metadata and merged vulnerability scan results in the IDE cache directory so cached information survives IDE restarts without redundant network traffic.
- **Manual cache invalidation**: The cache dialog's **Invalidate** action clears the respective application-wide cache after a confirmation prompt and refreshes the snapshot without starting a search or scan.
- **Reliable plugin lifecycle**: Explicitly requires an IDE restart after plugin installation, updates, or disablement instead of relying on dynamic loading and unloading.

## Architecture

- **Layered internal architecture**: Code is organized into explicit `model`, `service`, and `ui` packages to keep responsibilities separated and maintainable.
- **Service-based API access**: External OSV, OSS Index, and Maven metadata API requests are handled through dedicated service-layer components instead of UI classes.
