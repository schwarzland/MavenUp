# JetBrains Marketplace Custom Pages for MavenUp

This directory contains the Markdown source files for the **Custom Pages** (custom tabs) displayed on the [JetBrains Marketplace](https://plugins.jetbrains.com/) plugin detail page.

---

## Available Custom Pages

| File | Proposed Tab Title | Target Audience & Purpose |
| :--- | :--- | :--- |
| [`getting-started.md`](getting-started.md) | **Getting Started** *(or User Guide)* | Step-by-step workflow guide for new users and day-to-day dependency inspection. |
| [`privacy-and-security.md`](privacy-and-security.md) | **Privacy & Security** *(or Compliance)* | Transparent breakdown of data transmission, endpoints, zero-code-leakage guarantees, and private GroupId security. |
| [`vulnerability-management.md`](vulnerability-management.md) | **Vulnerability Management** | DevSecOps guide covering OSV.dev & OSS Index scanning, CVE details, and automated transitive remediation. |
| [`configuration-and-faq.md`](configuration-and-faq.md) | **Configuration & FAQ** | Comprehensive configuration reference, private repository setup (Nexus/Artifactory), and enterprise FAQ. |

---

## Recommended Marketplace Tab Setup

In the **JetBrains Marketplace Vendor Portal** (`Plugins > MavenUp > Custom Pages`):

1. **Tab 1: `Getting Started`** — Paste content from `getting-started.md`.
2. **Tab 2: `Privacy & Security`** — Paste content from `privacy-and-security.md`.
3. **Tab 3: `Vulnerability Management`** — Paste content from `vulnerability-management.md`.
4. **Tab 4 (Optional): `Configuration & FAQ`** — Paste content from `configuration-and-faq.md` (or merge into Getting Started / Security as needed).

---

## Maintenance & Updating

* Keep these files updated whenever UI workflows, security endpoints, configuration options, or remediation capabilities change.
* Synchronize changes with the main documentation under `docs/` (`usage.md`, `configuration.md`, `privacy-and-security.md`, `features/`).
