# Privacy & Data Transmission

MavenUp places great importance on transparency and data minimization when accessing external network services.

## Transmitted data
- **Maven coordinates and authentication:** Version and vulnerability request data is limited to standard Maven coordinates (`groupId`, `artifactId`, `version`). When authentication is required, private-repository credentials are sent only to the configured repository, and the optional Sonatype OSS Index token is sent only to OSS Index.
- **No project content transmission:** MavenUp does not upload source code, local POM contents, file paths, or environment variables as project data.
- **Secure credential handling:** Credentials for private repositories are read locally from Maven `settings.xml` (which may resolve environment-variable placeholders) and used only with the respective configured repository server. The optional Sonatype OSS Index API token is stored in IntelliJ Password Safe and is not placed in configuration files.
- **Connection security:** MavenUp's public API endpoints use HTTPS; requests to private repositories use the scheme configured in the POM or Maven `settings.xml`.
- **Disk-backed result caching:** Version lists and vulnerability scan results are cached in the IDE cache directory for a configurable duration to reduce redundant requests to the services listed below; cached entries survive IDE restarts, and the cache is cleared selectively whenever cache durations or relevant repository or scanner settings are changed. See [configuration.md](configuration.md) for the cache duration settings.
- **Local diagnostic logs:** DEBUG diagnostics record artifact coordinates, version-cache decisions, and the host of each Maven metadata request in the IDE log, without adding authentication headers or tokens; treat logs as project metadata when sharing them.

## External services and endpoints
1. **Maven Central & repositories (`repo1.maven.org` / configured servers):**
   - **Purpose:** Determining newer versions via `maven-metadata.xml`.
   - **Transmission:** HTTP GET requests with paths based on `groupId` and `artifactId`.
   - **Trigger:** The version search action, and — when enabled — automatically after the Maven project data is loaded and after every finished Maven import or resync, regardless of whether the MavenUp tool window has already been opened. A finished vulnerability scan additionally queries the versions of the vulnerable transitive coordinates. The automatic trigger can be switched off in the settings; vulnerability scans are never started automatically.
2. **OSV.dev (`api.osv.dev`):**
   - **Purpose:** Default multi-source vulnerability check (Google / OpenSSF); advisory links may open on `osv.dev`.
   - **Transmission:** Batch and detail API queries with `groupId`, `artifactId`, and `version` (PURL / ecosystem `Maven`).
   - **Authentication:** None required.
3. **Sonatype OSS Index (`ossindex.sonatype.org`):**
   - **Purpose:** Optional enrichment with Maven-specific security findings (opt-in).
   - **Transmission:** Component queries with Maven PURLs (`pkg:maven/groupId/artifactId@version`).
   - **Authentication:** The user's personal API token.
4. **Repository browser (web browser):**
   - **Purpose:** Optional user-initiated navigation to `mvnrepository.com` or `central.sonatype.com` when clicking web links in the user's default web browser.

## Note for corporate environments
In confidential corporate environments, note that during a version or vulnerability check the coordinates of internal or private dependencies (e.g. `com.mycompany.internal:my-module:1.0.0`) may also appear in requests to the configured external services, provided they exist in the project.

## Excluding private GroupIds from Maven Central
The **Private GroupId prefixes** setting (see [configuration.md](configuration.md)) lets you configure a comma-separated list of GroupId prefixes (e.g. `com.myCompany, de.meineFirma.produkt`) that are considered private/internal. Any dependency, plugin, managed dependency, managed plugin, or parent POM whose GroupId matches one of these prefixes (evaluated case-insensitively, with `*` and `$` characters stripped) is excluded from queries to Maven Central (`repo1.maven.org`), so no company information is transmitted to that external server. Other configured private repositories are still queried for matching GroupIds, and OSV.dev/OSS Index vulnerability checks are unaffected by this setting.
