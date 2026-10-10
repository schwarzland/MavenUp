# Configuration, Enterprise Setup & FAQ

MavenUp is highly customizable to match your team's development workflows, enterprise security policies, and private repository infrastructure.

---

## 1. Settings Overview

Open **Settings / Preferences (`Ctrl+Alt+S` or `Cmd+,`) > Tools > Maven Up** to configure global plugin behavior. Settings are stored at the application level and apply to all your projects:

```text
Tools
└── Maven Up                  Appearance, navigation, and repository browser
    ├── Versions and Updates  Lookup triggers, caching, private GroupIds, version filters
    ├── Vulnerability Check   Scan scope and Sonatype OSS Index credentials
    └── pom.xml Changes       Maven sync, managed entry commenting, and XML comments
```

### Maven Up (Root Page)
* **Maven Repository Browser:** Choose between **MVN Repository** (`mvnrepository.com`) and **Sonatype Central** (`central.sonatype.com`) for one-click browser navigation.
* **Show text labels on toolbar buttons:** Toggles descriptive button labels on or off in the tool window action bar.
* **Jump to pom.xml on a single click:** Open dependency declarations with a single click instead of a double click.
* **Status badge on tool window icon:** Configure when the colored status dot appears on the IDE tool window bar (**All**, **Vulnerabilities only**, or **Never**).

### Versions and Updates
* **Search for new versions automatically:** Automatically runs online version checks in the background upon project opening and after every Maven import or sync.
* **Stop after a successful Maven Central lookup:** When enabled (default), skips querying private repositories once an artifact is found on Maven Central, accelerating lookups.
* **Version cache duration (minutes):** Controls how long fetched version metadata is cached in local memory/disk (default: 120 minutes). Set to `0` to disable.
* **Private GroupId prefixes:** Comma-separated list of company GroupId prefixes (e.g. `com.mycompany, de.internal`) to exclude from queries to Maven Central.
* **Version Filters & Preselection:** Hide unstable qualifiers (RC, Beta, Milestone), allow older version downgrades, and configure automatic preselection strategies (**None**, **Highest**, or **Latest Minor**).

### Vulnerability Check
* **Include resolved transitive dependencies:** Toggles whether the vulnerability scan analyzes the complete resolved dependency tree (default: on).
* **Use Sonatype OSS Index:** Enables the secondary data source using your personal API token (stored securely in the IntelliJ Password Safe).
* **Vulnerability cache duration (minutes):** Configures scan result cache duration (default: 180 minutes).

### pom.xml Changes
* **Sync Maven changes after update:** Automatically triggers IntelliJ's Maven sync immediately after writing changes to `pom.xml`.
* **Comment out managed entries instead of deleting:** Comments out removed managed dependencies/plugins as XML comments (`<!-- ... -->`) instead of permanently deleting them.
* **Explanatory comment when pinning:** Configures XML comments generated when fixing transitive vulnerabilities (format, custom text prefix, and maximum number of identifiers).

---

## 2. Enterprise Setup & Private Repositories

### Connecting to Nexus, Artifactory, or GitLab Package Registry
MavenUp automatically discovers all active repositories declared in your project POMs, parent POMs, and your user-local `~/.m2/settings.xml`.

* **Authentication:** Repository credentials (passwords or access tokens) configured in `<servers>` within `settings.xml` are resolved locally and sent exclusively to the respective repository server.
* **Mirrors:** Repository `<mirrors>` configured in `settings.xml` (e.g. internal caching proxies) are respected automatically.
* **HTTP/HTTPS Proxies:** MavenUp honors IDE-level proxy settings (`Settings > Appearance & Behavior > System Settings > HTTP Proxy`) and user-level Gradle proxy configurations.

---

## 3. Frequently Asked Questions (FAQ)

### How does MavenUp handle multi-module Maven projects?
MavenUp inspects all active modules within your IntelliJ Maven project. Dependencies and plugins from each module's `pom.xml` are aggregated into the unified tool window table, displaying their respective origin modules. When you apply updates, changes are written precisely into the module POM that defines the dependency or property.

### How are shared properties like `${spring.version}` handled?
When multiple dependencies (e.g. `spring-core`, `spring-web`, `spring-context`) share the same property expression, selecting a new version for any of them automatically synchronizes the target version across all associated entries. Upon clicking **Update**, MavenUp updates the central `<properties>` block in `pom.xml` in a single atomic operation.

### What happens with inherited dependencies from Parent POMs or BOMs?
Inherited dependencies are clearly marked with italics and an *(inherited)* badge. If you choose to update an inherited dependency, MavenUp writes an explicit `<version>` tag into the local module's `pom.xml`, allowing you to override the inherited version without modifying parent POM files.

### How do I inspect or clear cached results?
Go to `Settings > Tools > Maven Up > Versions and Updates` (or `Vulnerability Check`) and click **Show Cache Contents...**. You will see an overview of cached artifacts, query timestamps, and remaining TTLs. Click **Invalidate** to clear the cache immediately.

### What happens if a repository or vulnerability service is unreachable?
If Maven Central, a private repository, or OSV.dev experiences network issues or downtime, MavenUp displays a closable warning banner detailing the issue. It gracefully falls back to cached results where available, ensuring you can continue working without IDE freezes.
