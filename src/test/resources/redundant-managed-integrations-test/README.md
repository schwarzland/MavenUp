# MavenUp – Redundant Managed Dependency Examples

This directory contains real Maven example projects that demonstrate the scenarios detected by the MavenUp plugin's **"Check Managed Dependencies for Redundancy"** and **"Check for Redundancy"** (context menu) features, plus a dependency-hierarchy version-conflict reproducer.

Each example can be imported into IntelliJ IDEA as a standalone Maven project. Use scenarios 01–15 to verify redundancy detection, scenario 16 for a same-depth version conflict, and scenario 17 for the multi-level BeanUtils conflict with and without a management pin.

## Scenarios Overview

| # | Scenario | Redundancy Reason | Description |
|---|----------|-------------------|-------------|
| 1 | [Parent Managed – Same Version](01-parent-managed-same-version/) | `PARENT_MANAGED` | Child POM declares `dependencyManagement` with the same version as the parent already manages |
| 2 | [Parent Managed – Higher Version](02-parent-managed-higher-version/) | `PARENT_MANAGED` | Child POM declares `dependencyManagement` with a lower version than the parent manages |
| 3 | [Direct Dependency Match](03-direct-dependency-match/) | `DIRECT_DEPENDENCY_MATCH` | A managed dependency has an explicit direct dependency with the same version |
| 4 | [Transitive Match (Higher Version)](04-transitive-match/) | `TRANSITIVE_MATCH` | A managed dependency is provided transitively by another dependency at a higher version |
| 5 | [Unused Managed](05-unused-managed/) | `UNUSED` | A managed dependency is not used directly or transitively in any module |
| 6 | [Multi-Module Mixed](06-multi-module-mixed/) | Multiple | A multi-module project demonstrating mixed redundancy scenarios across modules |
| 7 | [Multi-Module BOM Import](07-multi-module-bom-import/) | `TRANSITIVE_MATCH` | A multi-module project with BOM import where a module redundantly re-declares a BOM-managed dependency |
| 8 | [Direct Dependency Higher Version](08-direct-dependency-higher-version/) | `DIRECT_DEPENDENCY_MATCH` | A managed dependency where the explicit direct dependency specifies a higher version |
| 9 | [Transitive Match (Same Version)](09-transitive-match-same-version/) | `TRANSITIVE_MATCH` | A managed dependency is transitively provided at the exact same version |
| 10 | [Multi-Tier Grandparent Inheritance](10-multi-tier-grandparent-inheritance/) | `PARENT_MANAGED` | Multi-level hierarchy (Grandparent → Parent → Child) where child redundantly manages grandparent version |
| 11 | [Property-Based Redundancy](11-property-managed-redundancy/) | `DIRECT_DEPENDENCY_MATCH` | Managed and direct dependency versions defined via Maven properties |
| 12 | [Rule Precedence Overlap](12-rule-precedence-overlap/) | `PARENT_MANAGED` | Both `PARENT_MANAGED` and `DIRECT_DEPENDENCY_MATCH` conditions met; parent rule takes precedence |
| 13 | [Parent Unused in Multi-Module](13-parent-unused-multi-module/) | `UNUSED` | Root/Parent POM declares a managed dependency that is not used in any sub-module |
| 14 | [Version Pinning Override (Boundary)](14-version-pinning-override/) | _None (Not Redundant)_ | Child POM overrides parent with a higher version; removing it would cause a downgrade |
| 15 | [Transitive Exclusion (Boundary)](15-transitive-exclusion-non-redundant/) | _None (Not Redundant)_ | Transitive artifact is excluded in direct dependency, making the managed entry essential |
| 16 | [Transitive Version Conflict in Dependency Hierarchy](16-transitive-version-conflict/) | _Not applicable_ | Two direct dependencies request different versions of `jackson-core` at the same depth; Maven resolves one version and omits the losing path from the resolved dependency hierarchy |
| 17 | [Managed BeanUtils Transitive Conflict](17-transitive-beanutils-managed-conflict/) | _None (Conservative Conflict Handling)_ | MockServer requests BeanUtils 1.9.4 via Velocity Tools and 1.8.3 via Commons Digester; the management pin must not receive a transitive removal recommendation based on an ambiguous fallback |

## How to Use

1. Open IntelliJ IDEA
2. Select **File > New > Project from Existing Sources**
3. Navigate to the scenario folder of interest (e.g., `01-parent-managed-same-version/`)
4. Select the root `pom.xml` and import as a Maven project
5. Use the MavenUp plugin to check for redundant managed dependencies

## Inspect scenario 17

Open `17-transitive-beanutils-managed-conflict/pom.xml` to test the explicit
`commons-beanutils:commons-beanutils:1.9.4` management pin. Run **Check Managed Dependencies
for Redundancy...**: BeanUtils must not be suggested for transitive removal when its
fallback contains conflicting versions and there is no authoritative parent/BOM fallback.

Open `17-transitive-beanutils-managed-conflict/unmanaged/pom.xml` separately to inspect
the original version requests. It is a standalone project, not a child module, and does
not inherit the pin. The relevant paths beneath `mockserver-netty:5.15.0` are:

```text
mockserver-core:5.15.0
└─ velocity-tools-generic:3.1
   ├─ commons-beanutils:1.9.4
   └─ commons-digester3:3.2
      └─ commons-beanutils:1.8.3
```

Maven selects the nearer request for 1.9.4; 1.8.3 is a conflicting request, not a second
effective classpath version. With the pin enabled, verbose output can show
`version managed from 1.8.3` rather than `omitted for conflict with 1.9.4`.
IntelliJ's imported tree and CVE rows may retain or omit losing occurrences depending on
the Maven model; use verbose Maven output to distinguish requested and selected versions.

From the scenario directory, compare:

```powershell
mvn -f pom.xml dependency:tree -Dverbose "-Dincludes=commons-beanutils:commons-beanutils"
mvn -f unmanaged\pom.xml dependency:tree -Dverbose "-Dincludes=commons-beanutils:commons-beanutils"
```

Historical, potentially vulnerable artifacts are intentional test inputs, not recommended
production dependencies. Dependency-tree resolution requires access to the configured
Maven repositories. `RedundantManagedExamplesIntegrationTest` also loads both scenario
17 POMs offline, alongside scenarios 01–15, using simplified external POMs for the paths
shown above. It verifies both BeanUtils versions, the absence of an unambiguous fallback,
and that the management pin receives no removal recommendation. This does not validate
full Maven mediation or fetch the real external dependency graph.

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
- `org.apache.commons:commons-collections4`
- `org.slf4j:slf4j-api`
- `ch.qos.logback:logback-classic`
- `com.fasterxml.jackson.core:jackson-core`
- `com.fasterxml.jackson.core:jackson-databind`
- `org.apache.logging.log4j:log4j-core`
- `org.springframework:spring-web` (via Spring Boot BOM)
