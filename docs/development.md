# Development

## Tests

The unit tests live under `src/test/kotlin` and mirror the package structure of the main code (`model`, `service`, `ui`). Run them via:
```
./gradlew test
```

Notes:
- Tests that require pure logic without the IntelliJ platform (e.g. `VulnerabilityApiServiceTest`, `VersionAutoSelectionTest`) use plain JUnit. Tests that require a project/PSI environment (e.g. `MavenUpWindowFactoryTest`, `RefreshSnapshotCollectorTest`, `PomNavigationServiceTest`, `PomUpdateServiceTest`, `VersionStatusUiTest`, `MavenUpConfigurableTest`) extend `BasePlatformTestCase`.
- In the test sandbox, the bundled Vue.js plugin (`org.jetbrains.plugins.vue`) is disabled via `tasks.named("prepareTestSandbox") { disabledPlugins.add(...) }` in `build.gradle.kts`. MavenUp has no dependency on Vue; its initialization caused sporadic `TestLoggerAssertionError` failures in some test sandbox setups, unrelated to the actual test code.

## Code quality

Static analysis and test coverage are integrated via Gradle:

- **detekt** (static code analysis): runs automatically as part of `./gradlew check`/`build`. The configuration lives under `config/detekt/detekt.yml` (among others `LargeClass = 800` expressing the 800–1000 line rule). Existing findings are frozen in `config/detekt/baseline.xml` so that only **newly** introduced violations fail the build. Manually:
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

## Gradle proxy configuration

If access to Maven Central in a corporate network only works via a proxy, proxy settings should **not** be maintained in the project-wide `gradle.properties`.
Use the user-local file instead:

`./<USER>/.gradle/gradle.properties`

Example:
```properties
systemProp.java.net.useSystemProxies=true
org.gradle.jvmargs=-Djava.net.useSystemProxies=true -Djava.net.preferIPv4Stack=true

# If a fixed proxy is required:
# systemProp.http.proxyHost=<proxy-host>
# systemProp.http.proxyPort=<proxy-port>
# systemProp.https.proxyHost=<proxy-host>
# systemProp.https.proxyPort=<proxy-port>
# systemProp.http.nonProxyHosts=localhost|127.0.0.1|*.local
```

Afterwards, run `gradlew --stop` once so the Gradle daemon picks up the new settings.

## Troubleshooting

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
