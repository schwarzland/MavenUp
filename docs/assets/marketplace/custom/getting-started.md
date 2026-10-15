# Getting Started with Maven Up

Maven Up is an intelligent dependency and vulnerability management plugin for Apache Maven projects in JetBrains IntelliJ IDEA. It displays dependencies, plugins, and parent entries directly from your `pom.xml` files, queries Maven Central and private repositories for available updates, identifies direct and transitive security vulnerabilities (CVEs), and applies planned updates cleanly and atomically back to your POM files.

---

## 1. Quick Start Workflow

Follow these five steps to inspect and update your project dependencies:

```text
 ┌────────────────┐     ┌────────────────┐     ┌────────────────┐     ┌────────────────┐     ┌────────────────┐
 │ 1. Open Window │ ──> │ 2. Check State │ ──> │ 3. Pick Version│ ──> │ 4. Bulk Select │ ──> │ 5. Apply Update│
 └────────────────┘     └────────────────┘     └────────────────┘     └────────────────┘     └────────────────┘
   Tool window on         Automatic or           Dropdown with          Highest Major /        Atomic write to
   left/bottom bar        manual refresh         sync properties        Minor / Fixes          pom.xml + sync
```

### Step 1: Open the Maven Up Tool Window
* After installation, restart IntelliJ IDEA.
* Click the **Maven Up** icon in the tool window bar (usually on the left or bottom edge of your IDE).
* The tool window automatically inspects the active Maven modules and displays dependencies, plugins, `<parent>` POM declarations, `<dependencyManagement>`, and `<pluginManagement>` entries.

### Step 2: Automatic Background Check & Refresh
* **Automatic search:** When enabled (default), Maven Up checks for newer versions in the background upon project loading and after every Maven project sync/import.
* **Manual refresh:** Click **Refresh and Search for New Versions** in the top action toolbar at any time to reload the project state, discard current selections, and query repositories.
* **Status indicators:**
  * `✓` (Green checkmark): Dependency is up to date.
  * `↑` (Upwards arrow): A newer version is available.
  * **Tool window badge dot:** A colored badge appears on the tool window icon in the IDE bar (red for high/critical CVEs, yellow for low/medium CVEs, blue for available updates).

### Step 3: Choose Target Versions
* In the **New Version** column, click the dropdown to select the version you want to apply.
* **Property awareness:** If multiple dependencies share a Maven property (e.g. `${spring.version}` or `${jackson.version}`), Maven Up automatically synchronizes the selection across all associated entries.
* **Selection markers:** In the dropdown list, your current version is marked with **(current)**, and recommended vulnerability fix versions are highlighted with **(recommended)**.

### Step 4: One-Click Bulk Selections
Use the **Select Highest Version** dropdown in the top toolbar to target multiple dependencies at once:
* **Select Highest Major Version:** Upgrades all visible dependencies to the latest available release across major lines.
* **Select Highest Minor Version:** Upgrades dependencies within their current major version line (minimizing breaking changes).
* **Select Recommended Version:** Instantly selects the recommended fix version for all visible dependencies affected by security advisories.
* **Reset All to Current Versions:** Discards all pending selections with a single click.

### Step 5: Review and Apply Changes Atomically
* Click **Update** in the toolbar.
* A confirmation dialog displays an overview of all planned modifications, showing old and new versions, affected files, and dependency types. Its **Module** column is always visible in the third position, matching the main table, including in single-module projects, and previews the modules selected by **Target POM for transitive fixes**.
* The confirmation dialog opens with a wide default layout but can be narrowed when you need a more compact window width.
* The **Target POM for transitive fixes** selector is always visible and shows the count of unassigned transitive pins it affects; it is enabled only for transitive updates in multi-module projects and does not change the target of regular version updates.
* **Sync Maven Changes:** Keep **Sync Maven Changes after update** checked (default) to immediately trigger IntelliJ's Maven sync upon writing changes to `pom.xml`.

---

## 2. Navigating and Inspecting Dependencies

### Multi-Criteria Filtering
Filter the table with zero friction using the filter row above the table:
* **Search text:** Case-insensitive search by `groupId`, `artifactId`, Maven module, or property.
* **Type filter:** Filter by dependency type. Options stay in a fixed order: All, Dependency, Plugin, Parent, Managed plugin, Managed dependency.
* **Version filter:** Differentiate between explicitly **Declared** versions and **Inherited** versions (from parent POMs or BOMs).
* **Update filter:** Show only dependencies where updates are **Available** or **Up to date**.
* **Pending filter:** Filter by pending actions (**All changes**, **Will update**, **Will be removed**, **Unchanged**).
* **CVEs filter:** Narrow down by security findings (**Direct**, **Transitive**, **Any**, **None**).

### Inherited Versions & BOM Imports
* Entries whose version is derived from a parent POM or an imported BOM (via `<scope>import</scope>`) show the resolved version in italics with an *(inherited)* indicator.
* Selecting an update for an inherited entry automatically writes an explicit `<version>` tag into the local `pom.xml`.

### Jump to Source Code
* **Double-click** (or single-click if configured) any row to navigate directly to the dependency's declaration in `pom.xml`.
* Clicking a cell in the **Property** column navigates directly to the `<properties>` definition (including profiles).

### Context Menu & Repository Browser
Right-click any row to access convenient context actions:
* **Filter by "..."**: Instantly filter by the clicked GroupId, ArtifactId, Module, or Property.
* **Open on [Browser]**: Jump directly to the artifact's release and documentation page on **MVN Repository** or **Sonatype Central**.
* **Show Dependency Hierarchy**: Open the side panel visualizing BOM imports, parent chains, and transitive trees, including inclusion paths through repeated artifacts with different child dependencies. From either tab's panel, **Select in Dependencies** selects the declaration with the matching source POM and type; **Select in Transitive CVEs** selects the aggregated scanned coordinate when no matching declaration is available.
* **Remove / Comment out in pom.xml**: Mark unused or redundant managed entries for clean removal or XML commenting.

---

## 3. Detecting Redundant Managed Dependencies

Maven projects frequently accumulate unnecessary `<dependencyManagement>` declarations over time. Maven Up provides an automated redundancy analyzer:
1. Click **Managed Entries > Check Managed Dependencies for Redundancy...** (or right-click a managed row).
2. Maven Up analyzes your project against parent POM management, explicit direct declarations, transitive availability across all consumers, and unused entries; it preserves repeated consumer paths and does not suggest transitive removals when conflicting versions cannot be reliably resolved without the local management entry.
3. Review the redundancy explanation and exact consumer paths in the split-view dialog.
4. Click **Mark Selected Entries for Removal** to stage removals safely, then review and apply them via **Update**.

---

## 4. Next Steps

* Visit the **Security & Privacy** tab to learn about Maven Up's zero-source-code data minimization and private GroupId protection.
* Visit the **Vulnerability Management** tab for details on direct and transitive CVE scanning and remediation.
* Visit the **Configuration & FAQ** tab for enterprise repository configuration and troubleshooting tips.
