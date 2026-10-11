# MavenUp – Redundant Managed Dependency Examples

This directory contains real Maven example projects that demonstrate the scenarios detected by the MavenUp plugin's **"Check Managed Dependencies for Redundancy"** and **"Check for Redundancy"** (context menu) features.

Each example can be imported into IntelliJ IDEA as a standalone Maven project to verify the redundancy detection behavior.

## Scenarios Overview

| # | Scenario | Redundancy Reason | Description |
|---|----------|-------------------|-------------|
| 1 | [Parent Managed – Same Version](01-parent-managed-same-version/) | `PARENT_MANAGED` | Child POM declares `dependencyManagement` with the same version as the parent already manages |
| 2 | [Parent Managed – Higher Version](02-parent-managed-higher-version/) | `PARENT_MANAGED` | Child POM declares `dependencyManagement` with a lower version than the parent manages |
| 3 | [Direct Dependency Match](03-direct-dependency-match/) | `DIRECT_DEPENDENCY_MATCH` | A managed dependency has an explicit direct dependency with the same version |
| 4 | [Transitive Match](04-transitive-match/) | `TRANSITIVE_MATCH` | A managed dependency is provided transitively by another dependency at an equal or higher version |
| 5 | [Unused Managed](05-unused-managed/) | `UNUSED` | A managed dependency is not used directly or transitively in any module |
| 6 | [Multi-Module Mixed](06-multi-module-mixed/) | Multiple | A multi-module project demonstrating mixed redundancy scenarios across modules |
| 7 | [Multi-Module BOM Import](07-multi-module-bom-import/) | `TRANSITIVE_MATCH` | A multi-module project with BOM import where a module redundantly re-declares a BOM-managed dependency |

## How to Use

1. Open IntelliJ IDEA
2. Select **File > New > Project from Existing Sources**
3. Navigate to the scenario folder of interest (e.g., `01-parent-managed-same-version/`)
4. Select the root `pom.xml` and import as a Maven project
5. Use the MavenUp plugin to check for redundant managed dependencies

## Redundancy Reasons

The MavenUp plugin identifies four types of redundant managed dependencies, evaluated in priority order:

### `PARENT_MANAGED`
A child module's `dependencyManagement` entry is redundant because the parent POM already manages the same artifact at an equal or higher version. The parent's managed version takes precedence.

### `DIRECT_DEPENDENCY_MATCH`
A `dependencyManagement` entry is redundant because the same artifact is declared as a direct dependency with an explicit version that matches or exceeds the managed version.

### `TRANSITIVE_MATCH`
A `dependencyManagement` entry is redundant because all consumer paths receive the artifact transitively at an equal or higher version from other dependencies.

### `UNUSED`
A `dependencyManagement` entry is redundant because the artifact is not used in any module — neither as a direct dependency nor pulled in transitively.

## Real Artifacts Used

These examples use real, stable Maven artifacts:

- `org.apache.commons:commons-lang3`
- `org.slf4j:slf4j-api`
- `com.fasterxml.jackson.core:jackson-core`
- `com.fasterxml.jackson.core:jackson-databind`
- `org.apache.logging.log4j:log4j-core`
- `org.springframework:spring-web` (via Spring Boot BOM)
