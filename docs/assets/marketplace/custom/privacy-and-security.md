# Privacy, Security & Data Compliance

Maven Up is built with a strict **Security-First** and **Data Minimization** philosophy. It is designed to meet the rigorous compliance and security standards required in enterprise, financial, and confidential corporate environments.

---

## 1. Core Privacy Guarantees

* **Zero Source Code Transmission:** Maven Up never uploads source code, local POM contents, project file paths, or environment variables as project data.
* **Coordinate-Based Queries:** Version and vulnerability query data is limited to standard Maven coordinates: `groupId`, `artifactId`, and `version`; required authentication is sent separately only to the corresponding configured repository or Sonatype OSS Index.
* **Encrypted Communication:** Maven Up's public API requests use **HTTPS**; private-repository requests follow the URL scheme configured in the POM or Maven `settings.xml`.
* **Local Processing:** Dependency hierarchy analysis, POM parsing, version resolution, and redundancy checks are executed 100% locally on your machine within IntelliJ IDEA.

---

## 2. External Services and Network Communication

Maven Up connects only to industry-standard repository endpoints and public vulnerability databases:

```text
 ┌────────────────────────────────────────────────────────────────────────┐
 │                          IntelliJ IDEA / Maven Up                       │
 └───────┬──────────────────────┬───────────────────────┬─────────────────┘
         │                      │                       │
         │ GET maven-metadata   │ POST /v1/querybatch   │ POST /api/v3/component-report
         ▼                      ▼                       ▼
 ┌───────────────┐      ┌───────────────┐       ┌───────────────────────┐
 │ Maven Central │      │    OSV.dev    │       │  Sonatype OSS Index   │
 │ & Private Repos      │ (Google/OpenSSF)      │ (Optional, Opt-in)    │
 └───────────────┘      └───────────────┘       └───────────────────────┘
```

| Service | Endpoint | Purpose | Authentication |
| :--- | :--- | :--- | :--- |
| **Maven Central** | `repo1.maven.org` | Version checks (`maven-metadata.xml`) | None (Public) |
| **Private Repositories** | Configured in `settings.xml` | Company/private artifact versions | Basic Auth / Token (local) |
| **OSV.dev** | `api.osv.dev` (API); `osv.dev/vulnerability/{id}` (advisory links) | Multi-source vulnerability database (Google / OpenSSF) | None (Public) |
| **Sonatype OSS Index** | `ossindex.sonatype.org` | Extended Maven vulnerability intelligence *(Optional opt-in)* | Personal API Token (IntelliJ Password Safe) |
| **Repository Browser** | `mvnrepository.com` or `central.sonatype.com` | User-initiated browser jump (opens in default web browser) | None |

---

## 3. Enterprise Protection Features

### Exclude Private GroupIds from Maven Central
The **Private GroupId prefixes** setting excludes matching coordinates from Maven Central version lookups only. Coordinates may still be sent to OSV.dev and, when enabled, Sonatype OSS Index during vulnerability scans.

* Maven Up provides a dedicated **Private GroupId prefixes** configuration setting (under `Settings > Tools > Maven Up > Versions and Updates`).
* Any dependency, plugin, parent POM, or managed entry whose GroupId matches one of your configured prefixes is **excluded from Maven Central version queries (`repo1.maven.org`)**.
* Queries for private artifacts are routed exclusively to your internally configured repository servers (e.g. Nexus or Artifactory).
* This filter affects Maven Central version lookups only; vulnerability scans still send the relevant coordinates to OSV.dev and, when enabled, Sonatype OSS Index.

### Secure Credential Handling
* **IntelliJ Password Safe:** The optional Sonatype OSS Index API token is stored securely in the native OS credential store / IntelliJ Password Safe and is never written to project XML files or version-controlled settings.
* **`settings.xml` Credentials:** Credentials for private Maven repositories are read locally from `settings.xml`, including any resolved environment-variable placeholders, and authentication headers are sent only to the respective repository server. The optional OSS Index token is stored in IntelliJ Password Safe and sent only to OSS Index.

### Disk-Backed Caching & Request Coalescing
To minimize network traffic, eliminate duplicate requests, and respect external rate limits:
* Version lookup and vulnerability scan results are stored in the local IDE cache directory with a configurable Time-To-Live (TTL).
* **Request Coalescing:** Concurrent requests for the same missing or expired artifact execute at most one external network request, sharing the result across all callers.
* **Cache Inspection & Invalidation:** You can inspect active cache entries and remaining TTLs via `Settings > Tools > Maven Up > Show Cache Contents...` and manually invalidate them at any time.

### Local Diagnostic Logs
* When debug logging is enabled, Maven Up logs coordinate lookups and cache decisions to the IntelliJ IDEA log without including authentication headers, tokens, or private payload data.
