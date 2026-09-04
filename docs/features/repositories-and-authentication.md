# Repositories & Authentication

Features for repository lookups, credentials from Maven `settings.xml`, and repository-related privacy options.

Back to the [feature overview](../../FEATURES.md).

- **Private repository authentication**: Uses credentials from Maven `settings.xml` for authenticated repository access and falls back to `${user.home}/.m2/settings.xml` when no explicit Maven user settings path is configured in the IDE.
- **Credential placeholder resolution**: Resolves `${env.VAR_NAME}` and `${VAR_NAME}` placeholders for repository credentials.
- **Credential matching fallbacks**: Matches credentials by repository ID, then repository URL, then repository host.
- **Configurable Central-first repository strategy**: Queries Maven Central first and can either stop after a successful Central response (default, faster) or continue with private repositories to include private-only versions. The option sits in the *Version Lookup* group of the **Versions and Updates** settings page, next to the automatic version search that triggers the lookup.
- **Configurable Maven Repository Browser**: The repository browser used for opening artifact version pages can be selected under **Settings > Tools > MavenUp**. Two options are available: **MVN Repository** (default, `mvnrepository.com`) and **Sonatype Central** (`central.sonatype.com`). The selection applies to both the context menu in the main table and the Vulnerability Details row context menu.
- **Private GroupId filter for Maven Central**: A configurable comma-separated list of private/internal GroupId prefixes (e.g. `com.myCompany, de.meineFirma.produkt`) excludes any dependency, plugin, managed dependency, managed plugin, or parent POM whose GroupId matches one of these prefixes from queries to Maven Central (`repo1.maven.org`), so no company information is transmitted to that external server; other configured private repositories are still queried for matching GroupIds.
