# Development

## Tests

The unit tests live under `src/test/kotlin` and mirror the package structure of the main code (`model`, `service`, `ui`). Run them via:
```
./gradlew test
```

Notes:
- Tests that require pure logic without the IntelliJ platform (e.g. `VulnerabilityApiServiceTest`, `VersionAutoSelectionTest`) use plain JUnit. Tests that require a project/PSI environment (e.g. `MavenUpWindowFactoryTest`, `RefreshSnapshotCollectorTest`, `PomNavigationServiceTest`, `PomUpdateServiceTest`, `VersionStatusUiTest`, `MavenUpConfigurableTest`, `RedundantManagedExamplesIntegrationTest`) extend `BasePlatformTestCase`.
- `RedundantManagedExamplesIntegrationTest` loads reference scenarios 01–15 and 17 from `src/test/resources/redundant-managed-integrations-test/` and executes redundancy detection against `RedundantManagedDependencyService` offline with stubbed POMs and BOM models. Scenario 17 covers both BeanUtils versions, the absence of an unambiguous fallback, and preservation of the management pin; its unmanaged POM is checked independently.
- Conflict reproducers 16 (same-depth Jackson requests) and 17 (multi-level BeanUtils requests, with and without management) can also be imported manually as documented in the [example index](../src/test/resources/redundant-managed-integrations-test/README.md). Scenario 16 is not part of the offline suite; simplified external POMs in scenario 17 reproduce the relevant paths, not full Maven mediation.
- In the test sandbox, the bundled Vue.js plugin (`org.jetbrains.plugins.vue`) is disabled via `tasks.named("prepareTestSandbox") { disabledPlugins.add(...) }` in `build.gradle.kts`. MavenUp has no dependency on Vue; its initialization caused sporadic `TestLoggerAssertionError` failures in some test sandbox setups, unrelated to the actual test code.

## Code quality

Static analysis and test coverage are integrated via Gradle:

- **detekt 2.0.0-alpha.6** (static code analysis): runs automatically as part of `./gradlew check`/`build`. The configuration lives under `config/detekt/detekt.yml` (among others `LargeClass = 800` expressing the 800–1000 line rule). Existing findings are frozen in `config/detekt/baseline.xml` so that only **newly** introduced violations fail the build. Manually:
  ```
  ./gradlew detekt
  ```
  Regenerate the baseline after a deliberate refactoring: `./gradlew detektBaseline`.
- **Kover** (test coverage): generates coverage reports via
  ```
  ./gradlew koverHtmlReport   # HTML under build/reports/kover/html
  ./gradlew koverXmlReport    # XML under build/reports/kover/report.xml
  ```
  A blocking minimum-coverage gate can be added via `koverVerify` if needed.

The CI (`.github/workflows/ci.yml`) runs `build verifyPlugin detekt koverXmlReport`. In doing so, `verifyPlugin` also checks compatibility with the configured IntelliJ IDE builds and uploads the test and analysis reports as an artifact.

## Build classpath dependency pinning

The IntelliJ Platform Gradle Plugin pulls jsoup and Jackson transitively onto the settings buildscript classpath, and the transitive versions are reported as vulnerable. These artifacts run at build time only and are never packaged into the plugin JAR, so they do not affect users of the plugin.

`settings.gradle.kts` pins them twice on purpose:

- `resolutionStrategy.force(...)` guarantees the patched version wins conflict resolution.
- An explicit `classpath("...")` declaration makes the pinned version visible to dependency scanners and to Dependabot, which do not evaluate `force(...)`.

When bumping the IntelliJ Platform Gradle Plugin, verify both lists still match the version you want and drop entries whose transitive version is already patched.

## Test classpath dependency pinning

The `com.jetbrains.intellij.platform:test-framework` artifact (pulled in via `testFramework(TestFrameworkType.Platform)`) transitively brings an older, vulnerable Jackson version onto `testCompileClasspath`/`testRuntimeClasspath` through its `jackson-bom` platform constraint. These artifacts run at test time only and are never packaged into the plugin JAR.

`build.gradle.kts` pins `jackson-core` and `jackson-databind` on those two configurations the same way as the settings buildscript classpath (explicit `testImplementation(...)` declaration plus `resolutionStrategy.force(...)` scoped to `testCompileClasspath`/`testRuntimeClasspath`). The force is intentionally **not** applied via `configurations.all` and does **not** include `jackson-module-kotlin`: forcing a newer `jackson-module-kotlin` pulls in an incompatible `kotlin-reflect`/`kotlin-stdlib` pair that breaks coroutine-based `BasePlatformTestCase` tests with a "Debug metadata version mismatch" error. Since the plugin code never uses Jackson directly, pinning only the two vulnerable artifacts is sufficient.

## Gradle proxy configuration

If access to Maven Central in a corporate network only works via a proxy, proxy settings should **not** be maintained in the project-wide `gradle.properties`.
Use the user-local file instead (for example, `%USERPROFILE%\.gradle\gradle.properties` on Windows):

```properties
systemProp.java.net.useSystemProxies=true
org.gradle.jvmargs=-Djava.net.useSystemProxies=true -Djava.net.preferIPv4Stack=true
```
If a fixed proxy is required, configure it there:
```properties
systemProp.http.proxyHost=<proxy-host>
systemProp.http.proxyPort=<proxy-port>
systemProp.https.proxyHost=<proxy-host>
systemProp.https.proxyPort=<proxy-port>
systemProp.http.nonProxyHosts=localhost|127.0.0.1|*.local
```

After changing Gradle proxy properties, run `gradlew --stop` once so the Gradle daemon picks up the new settings.

### Plugin Verifier cannot resolve `jb.gg`

The `verifyPlugin` task launches the Plugin Verifier in a separate Java process. The verifier may not use the same proxy configuration as the IDE or Gradle. An error such as `java.net.UnknownHostException: jb.gg` while fetching `https://jb.gg/ij-api-changes-raw?flush_cache=true` means the verifier could not resolve the host; it can occur even when the URL is reachable in the browser and Gradle proxy settings are correct.

Pass the proxy JVM properties to the verifier through `JAVA_TOOL_OPTIONS`. For a one-off PowerShell run:
```powershell
$env:JAVA_TOOL_OPTIONS = "-Dhttps.proxyHost=<proxy-host> -Dhttps.proxyPort=<proxy-port> -Dhttp.proxyHost=<proxy-host> -Dhttp.proxyPort=<proxy-port>"
.\gradlew verifyPlugin
Remove-Item Env:JAVA_TOOL_OPTIONS
```

To keep this setting for `verifyPlugin` in IntelliJ IDEA, create or edit a **Gradle** run configuration for the `verifyPlugin` task and add the following under **Environment variables**:
```text
JAVA_TOOL_OPTIONS=-Dhttps.proxyHost=<proxy-host> -Dhttps.proxyPort=<proxy-port> -Dhttp.proxyHost=<proxy-host> -Dhttp.proxyPort=<proxy-port>
```
`JAVA_TOOL_OPTIONS` is read by Java processes started from that environment, including the verifier. Keep proxy details out of shared project configuration, and do not store proxy credentials in a shared run configuration.

## Troubleshooting

### Trace cache usage and API requests

Open **Help > Diagnostic Tools > Debug Log Settings** and add `#de.schwarzland.mavenup.service`.
Repeat a version search, vulnerability scan, or redundant managed dependency check, then inspect `idea.log` via **Help > Show Log in Explorer**
(the menu name varies by operating system).

Version searches emit `Version cache hit`, `Version cache miss`, `Version cache expired`, or
`Version cache disabled` with the artifact coordinate. `Querying version metadata` identifies each
actual HTTP GET attempt and its repository host, including attempts that subsequently fail; a cache hit
does not produce a repository request. Successful repository responses retain their INFO summary and
failed responses their WARN diagnostics. See [FEATURES.md](../FEATURES.md) for the diagnostic feature overview.

Redundancy checks emit `Redundant managed dependencies analysis started` with the scope,
and `Redundant managed dependencies analysis completed` with the result count.
`POM cache hit` and `Local Maven POM hit` explain why no remote request is needed; corresponding misses
identify fallback lookups. `Querying POM` identifies each HTTP GET attempt by artifact path and repository
host, and `POM response` includes the HTTP status (including 404 and server errors). Request failures
report the exception class instead of potentially sensitive URL or exception text; credentials, headers,
full repository URLs, and POM contents are not included in these request diagnostics.

### Increase the Git buffer size

`git push` failed with the `/assets/*.png` because the Git buffer size was too small. Fix:
```
git config --global http.postBuffer 524288000
```

### ClassNotFoundException: MavenUpWindowFactory at plugin startup

**Symptom:** The IDE log shows `Cannot process toolwindow MavenUp` / `ClassNotFoundException: de.schwarzland.mavenup.ui.MavenUpWindowFactory`.

**Cause:** A corrupt Gradle build-cache entry for `compileKotlin`. Gradle reports the task as `FROM-CACHE` but restores an empty output, so the plugin JAR contains no compiled classes.

**Fix:**
```bash
.\gradlew.bat clean compileKotlin --rerun-tasks
.\gradlew.bat prepareSandbox --rerun-tasks
```

If the problem persists, clear the Gradle build cache completely:
```bash
.\gradlew.bat --stop
Remove-Item -Recurse "$env:USERPROFILE\.gradle\caches\build-cache-*"
.\gradlew.bat prepareSandbox
```
