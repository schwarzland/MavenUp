# Privacy, Security & Data Compliance

MavenUp is built with a strict **Security-First** and **Data Minimization** philosophy. It is designed to meet the rigorous compliance and security standards required in enterprise, financial, and confidential corporate environments.

---

## 1. Core Privacy Guarantees

* **Zero Source Code Transmission:** MavenUp **never** transmits source code, file contents, project file paths, passwords, environment variables, or local user information to any external service.
* **Standard Maven Coordinates Only:** All network queries for version availability and vulnerability scans are strictly limited to standard Maven coordinates: `groupId`, `artifactId`, and `version`.
* **Encrypted Communication:** All external requests are performed strictly over encrypted **HTTPS** connections.
* **Local Processing:** Dependency hierarchy analysis, POM parsing, version resolution, and redundancy checks are executed 100% locally on your machine within IntelliJ IDEA.

---

## 2. External Services and Network Communication

MavenUp connects only to industry-standard repository endpoints and public vulnerability databases:

```text
 ┌────────────────────────────────────────────────────────────────────────┐
 │                          IntelliJ IDEA / MavenUp                       │
 └───────┬──────────────────────┬───────────────────────┬─────────────────┘
         │                      │                       │
         │ GET maven-metadata   │ POST /v1/querybatch   │ POST /api/v3/authorized/biomed
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
| **OSV.dev** | `api.osv.dev` | Multi-source vulnerability database (Google / OpenSSF) | None (Public) |
| **Sonatype OSS Index** | `ossindex.sonatype.org` | Extended Maven vulnerability intelligence *(Optional opt-in)* | Personal API Token (IntelliJ Password Safe) |
| **Repository Browser** | `mvnrepository.com` or `central.sonatype.com` | User-initiated browser jump (opens in default web browser) | None |

---

## 3. Enterprise Protection Features

### Private GroupId Prefixes (Prevent Data Leakage)
In corporate environments, internal dependencies (e.g. `com.mycompany.internal:my-service:1.0.0`) must never leak to public external services.

* MavenUp provides a dedicated **Private GroupId prefixes** configuration setting (under `Settings > Tools > Maven Up > Versions and Updates`).
* Any dependency, plugin, parent POM, or managed entry whose GroupId matches one of your configured prefixes is **strictly excluded from queries to Maven Central (`repo1.maven.org`)**.
* Queries for private artifacts are routed exclusively to your internally configured repository servers (e.g. Nexus or Artifactory).

### Secure Credential Handling
* **IntelliJ Password Safe:** The optional Sonatype OSS Index API token is stored securely in the native OS credential store / IntelliJ Password Safe and is never written to project XML files or version-controlled settings.
* **`settings.xml` Credentials:** Credentials for private Maven repositories are read from your local Maven `settings.xml` or user-level environment. Authentication headers are only sent directly to the respective repository server and are never exposed in log outputs.

### Disk-Backed Caching & Request Coalescing
To minimize network traffic, eliminate duplicate requests, and respect external rate limits:
* Version lookup and vulnerability scan results are stored in the local IDE cache directory with a configurable Time-To-Live (TTL).
* **Request Coalescing:** Concurrent requests for the same missing or expired artifact execute at most one external network request, sharing the result across all callers.
* **Cache Inspection & Invalidation:** You can inspect active cache entries and remaining TTLs via `Settings > Tools > Maven Up > Show Cache Contents...` and manually invalidate them at any time.

### Local Diagnostic Logs
* When debug logging is enabled, MavenUp logs coordinate lookups and cache decisions to the IntelliJ IDEA log without including authentication headers, tokens, or private payload data.
