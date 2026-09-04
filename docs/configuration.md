# Configuration

## Settings

Under `Settings > Tools > MavenUp`, the following options can be configured. The settings are stored globally at the application level and therefore apply to all projects. Following the IntelliJ Platform UI guidelines, they are split across a root page and three sub-pages in the settings tree, so every page stays readable without scrolling:

```
Tools
└─ MavenUp                  Appearance and behavior
   ├─ Versions and Updates  Version lookup, privacy, version selection
   ├─ Vulnerability Check   Scan scope and Sonatype OSS Index credentials
   └─ Pom.xml Changes       Maven sync and explanatory XML comment
```

### MavenUp (root page)

- **Maven repository browser**: Selects the browser for artifact version pages — **MVN Repository** (default, `mvnrepository.com`) or **Sonatype Central** (`central.sonatype.com`). The selection applies to the right-click context menu in the main window and the right-click context menu in the vulnerability details dialog (all columns except **References**).
- **Show text labels on toolbar buttons**: Renders the actions in the top action bar of the tool window and the vulnerability details dialog as buttons with text labels instead of icon-only buttons (default: on); the labels are shortened (the full text remains as a tooltip) so the action bar also fits on low screen resolutions. The change is applied to the open tool window immediately.
- **Jump to pom.xml on a single click**: Enables navigation to the `pom.xml` with a single click instead of a double click.
- **Status badge on the tool window icon**: Controls the badge dot on the MavenUp tool window icon — **Show a badge for vulnerabilities and available updates** (default), **Show a badge for vulnerabilities only**, or **Never show a badge**. The change is applied to the open tool window immediately. See [usage.md](usage.md) for what the badge colors mean.
- **More Settings**: Quick links at the bottom of the page open the three sub-pages directly; each link carries a short description of the page content. Inside the settings dialog the link selects the corresponding tree node without opening another dialog.

### Versions and Updates

#### Version Lookup

- **Search for new versions automatically**: Determines whether the online version search runs automatically after the tool window loads the project data and after every finished Maven import or resync (default: on). When disabled, versions are only fetched when you trigger **Refresh and Search for New Versions** yourself. The automatic search only happens while the MavenUp tool window is open, so no network requests are made otherwise.
- **Stop after a successful Maven Central lookup**: Determines whether no further private repositories are queried after a successful Maven Central lookup (default: on). When the option is disabled, private repositories continue to be queried even after a successful Central lookup in order to find private-only versions.

#### Privacy

- **Private GroupId prefixes (comma-separated)**: List of GroupId prefixes considered private/internal, e.g. `com.myCompany, de.meineFirma.produkt`. Any dependency, plugin, managed dependency, managed plugin, or parent POM whose GroupId equals one of these prefixes or starts with `<prefix>.` is excluded from queries to Maven Central (`repo1.maven.org`), so no company information is transmitted to that external server. Other configured private repositories are still queried for matching GroupIds. See [privacy-and-security.md](privacy-and-security.md) for details on transmitted data.

#### Version Selection

- **Offer all versions**: Offers versions older than the currently used one in the version drop-downs as well, enabling downgrades (default: off). When the option is disabled, only versions `>=` the current version are offered. The change is applied to the open tool window immediately, without a renewed version search.
- **Hide unstable versions**: Hides unstable versions (e.g. RC/Beta) from the selectable update versions. The change is applied to the open tool window immediately, without a renewed version search.
- **Hidden version qualifiers (comma-separated)**: List of the types to hide, e.g. `rc,beta,milestone` (as an indented sub-item in the IntelliJ settings style; the row is only enabled when the filter is active; the field adapts to the dialog width so longer lists remain readable). The change is applied to the open tool window immediately, without a renewed version search.
- **Preselect version after an update check**: Sets the pre-selection strategy for **New Version** as a combo box with three states: **Keep current version (no auto-selection)**, **Select highest available version**, or **Select latest minor version in current major line**. The strategy is applied to the versions that remain after the two filters above. The change is applied to the open tool window immediately with **Apply** or **OK**, without a renewed update check; other settings do not change the current selection.
- **Confirm before resetting all version selections**: Determines whether the **Reset All to Current Versions** action shows a confirmation dialog before discarding all version selections (default: on). The dialog offers a "Don't ask again" option that also disables this setting. This setting only takes effect when no filter is active; with an active filter, you are always asked instead whether to reset all or only the filtered dependencies.

### Vulnerability Check

- **Include resolved transitive dependencies**: By default, includes the resolved Maven dependency tree in the vulnerability check.
- **Use Sonatype OSS Index as an additional source**: Enables the optional second data source. Sonatype authenticates requests exclusively via the API token; therefore only the token is required and is shown as a mandatory field when the option is enabled. The token is stored exclusively in the IntelliJ Password Safe, loaded off the Event Dispatch Thread, and not written to `mavenup_settings.xml`. Until the token has been loaded, the option and the token field stay disabled. If the token is missing for an already saved configuration, the OSS Index query is skipped; OSV.dev is still queried. If the token is invalid or expired, a qualified error message is shown. A link opens the Sonatype account settings for creating or copying a token.

### Pom.xml Changes

- **Sync Maven changes after update**: Determines whether the IDE's Maven sync is triggered automatically after writing the `pom.xml` (default: on). This setting is synchronized with the identically named checkbox in the **Confirm Changes** confirmation dialog; the last choice made there is saved.
- **Explanatory comment when pinning a dependency**: Combo box that controls the explanatory XML comment written into newly pinned `dependencyManagement` entries created from vulnerability fixes, with five states: **No comment**, **Comment text only (no identifiers)**, **Comment with advisory IDs (e.g. GHSA)** (default), **Comment with aliases (e.g. CVE)**, and **Comment with all identifiers (GHSA and CVE)**.
- **Comment text**: The text written in front of the listed identifiers (default: `Pinned by MavenUp to fix:`). A trailing colon is dropped when no identifiers are listed; when the text is left empty, a generic `Added by MavenUp` comment is written. Line breaks are collapsed into single spaces and hyphen sequences such as `--` or `-->` are broken up, so the text can never terminate the XML comment. The field is disabled when **No comment** is selected.
- **Maximum number of identifiers**: Limits how many identifiers are listed (default: 3); the remaining ones are replaced with `and more`, for example `Pinned by MavenUp to fix: GHSA-1, GHSA-2, GHSA-3 and more`. Use `0` for no limit. Identifiers are ordered by severity, most severe first, so a truncated list keeps the most critical findings. The field is only enabled for the states that list identifiers.
