# Maven property examples

These independent Maven projects are fixtures for examining property recognition. Examples
`01`–`08` are covered by `PropertiesInPomExamplesIntegrationTest`; examples `09`–`18` remain
manual fixtures for later analysis. Open the relevant `pom.xml` as a Maven project in IntelliJ
and compare MavenUp with the effective Maven model. Entries below describe Maven
resolution, not a claim that MavenUp currently supports every form. Versions and
coordinates under `example.properties` are illustrative and are not published artifacts.
Dependency resolution or plugin execution can require network access; the illustrative
artifacts and the snapshot version in example 10 are not expected to resolve.

| Fixture | Reference and expected result |
| --- | --- |
| `01-local-property` | `junit.version` in the same POM: JUnit `4.13.2`. |
| `02-shared-property` | `jackson.version` in the same POM: both Jackson dependencies `2.17.2`. |
| `03-plugin-property` | Local `compiler.version` in `pluginManagement`: compiler plugin `3.13.0`, also inherited by the plugin declaration; local `surefire.version`: Surefire `3.2.5`. |
| `04-dependency-management` | Local `junit.version` in `dependencyManagement`: managed and direct JUnit `4.13.2`. |
| `05-parent-property` | Child references `junit.version` from its parent: `4.13.2`. |
| `06-child-override` | Parent manages JUnit using `junit.version` (`4.12`); the child's override makes its managed and direct version `4.13.2`. |
| `07-multilevel-inheritance` | Child uses `junit.version` from grandparent (`4.13.2`), `hamcrest.version` from parent (`2.2`) and local `slf4j.version` (`2.0.13`). |
| `08-multi-module` | `common` uses the parent's `junit.version` (`4.12`); `override` uses its local value (`4.13.2`). |
| `09-chained-properties` | `junit.version` → `shared.version` → `base.version`, all local: `4.13.2`. |
| `10-composite-value` | Local `major`, `minor` and `patch` form `4.13.2`; local `slf4j.base` plus the literal suffix `.13` forms `2.0.13`. |
| `11-project-values` | In the child, `${project.version}` is `2.3.4` and `${project.parent.version}` is `1.2.3`. |
| `12-ci-friendly` | Local `revision`, `sha1` and `changelist` form project version `1.2.3-abc123-SNAPSHOT`; `${project.version}` in the dependency has the same value. |
| `13-profiles` | Local default `junit.version` is `4.12`; the `modern` POM profile overrides it to `4.13.2`. |
| `14-external-values` | The supplied settings profile provides `settings.junit.version` (`4.13.2`); a CLI property provides `cli.hamcrest.version` (`2.2`); an environment variable provides `env.MAVENUP_SLF4J_VERSION` (`2.0.13`). |
| `15-imported-bom` | Consumer `bom.version` is local (`1.0`); the imported BOM manages JUnit at `4.13.2` using its own `bom.junit.version`. The BOM property itself is not imported into the consumer's properties. |
| `16-coordinate-properties` | Local `library.group`, `library.artifact` and `library.version` form `junit:junit:4.13.2`. |
| `17-unresolved` | `missing` refers to an undefined `missing.version`; `cycle` refers to mutually recursive `first.version` and `second.version`. Neither has a valid effective version. |
| `18-formatting` | The local `junit.version` resolves to `4.13.2` despite surrounding whitespace and the comment. |


## Gefundene Fehler
- 9 chained properties werden nicht sauber aufgelöst - aber aus meiner Sicht okay
- 10
    ${major}.${minor}.${patch} wird aufgelöst in major}.${minor}.${patch. Die Version ist aber korrekt angezeigt: 4.13.2
    ${slf4j.base}.13 wird nicht als property aufgelöst, dafür aber die korrekte Version 2.0.13
- 11 springt nicht sauber zur property
- 12 springt nicht sauber zur property
- 13 springt nicht sauber zur korrekten property
- 14 bekommt er nicht aufgelöst
- 15 Maven-Projekt wird nicht erkannt
- 16 API-Error: Versions could not be retrieved from repository "central": Illegal character in path at index 32: https://repo1.maven.org/maven2/${library/group}/${library.artifact}/maven-metadata.xml. Please check your network connection or try again later.
- 17 cycle-version error in ide


## Activation and setup

Run Maven from the relevant fixture directory. `mvn help:effective-pom` displays the
effective model; an initial invocation can download the help plugin. In IntelliJ, enable
the corresponding Maven profile and configure the Maven runner environment or settings
where necessary. Refresh the Maven project after changing these inputs.

- `13-profiles`: compare `mvn help:effective-pom` with
  `mvn -Pmodern help:effective-pom`.
- `14-external-values` in PowerShell:
  `$env:MAVENUP_SLF4J_VERSION = '2.0.13'`, then
  `mvn -s settings.xml "-Dcli.hamcrest.version=2.2" help:effective-pom`.
  Without all three inputs, the relevant versions remain unresolved.
- `15-imported-bom`: first run `mvn -f bom\pom.xml install` to install the local BOM,
  then run `mvn -f consumer\pom.xml help:effective-pom`. Use an isolated Maven local
  repository if the fixture should not modify the usual one.
- `17-unresolved`: each subdirectory is a separate negative fixture. Maven may reject
  the model rather than produce an effective POM, particularly for the cyclic reference.
