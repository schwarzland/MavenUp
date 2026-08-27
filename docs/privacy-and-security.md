# Privacy & Data Transmission

MavenUp places great importance on transparency and data minimization when accessing external network services.

## Transmitted data
- **Maven coordinates only:** For version checks and vulnerability scans, only the standard Maven coordinates (`groupId`, `artifactId`, `version`) of the components declared or resolved in the project are transmitted over HTTPS.
- **No sensitive project data:** **No source code, file contents, file paths, passwords, or user data** is transmitted to external services.
- **Secure credential handling:** Credentials for private repositories (from `settings.xml`) remain local or are used exclusively against the respective configured repository server. The optional API token for Sonatype OSS Index is stored securely in the IntelliJ Password Safe and is not placed in configuration files.

## External services and endpoints
1. **Maven Central & repositories (`repo1.maven.org` / configured servers):**
   - **Purpose:** Determining newer versions via `maven-metadata.xml`.
   - **Transmission:** HTTP GET requests with paths based on `groupId` and `artifactId`.
2. **OSV.dev (`api.osv.dev`):**
   - **Purpose:** Default multi-source vulnerability check (Google / OpenSSF).
   - **Transmission:** Batch and detail queries with `groupId`, `artifactId`, and `version` (PURL / ecosystem `Maven`).
   - **Authentication:** None required.
3. **Sonatype OSS Index (`ossindex.sonatype.org`):**
   - **Purpose:** Optional enrichment with Maven-specific security findings (opt-in).
   - **Transmission:** Component queries with Maven PURLs (`pkg:maven/groupId/artifactId@version`).
   - **Authentication:** The user's personal API token.
4. **Repository browser (web browser):**
   - **Purpose:** Optional user-initiated navigation to `mvnrepository.com` or `central.sonatype.com` when clicking web links in the user's default web browser.

## Note for corporate environments
In confidential corporate environments, note that during a version or vulnerability check the coordinates of internal or private dependencies (e.g. `com.mycompany.internal:my-module:1.0.0`) may also appear in requests to the configured external services, provided they exist in the project.
