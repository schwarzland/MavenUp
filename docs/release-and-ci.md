# Release & CI

## Branching strategy and GitHub Actions

Development happens on `feature/*` branches. Changes are merged into `main` via pull request. To stabilize a release, a `release/*` branch is used, which is also merged into `main` via pull request. The uniform scheme is: release branch `release/x.y.z`, Git tag `x.y.z` — each without a leading `V`.

### Automated workflows

- **Build and Test** (`.github/workflows/ci.yml`): Runs on every push to `main` or `hotfix/**` as well as on every newly created, updated, or reopened pull request against `main`. As a result, feature and release branches are checked exclusively via their pull request; hotfix branches are additionally checked on direct pushes. For feature and release branches, duplicate builds on pushes with an open pull request are avoided. A direct push to `main` (without a pull request) is still covered by the push trigger. The workflow compiles the plugin, runs tests and static analysis (detekt), generates the coverage report (Kover), checks the plugin structure, and runs the IntelliJ Plugin Verifier against the configured compatibility. The reports are stored as an artifact on every run — whether successful or failed. Each job is limited to 30 minutes. The trigger only fires on changes to source code, plugin resources, or build configuration.
- **Manual Build and Test** (`.github/workflows/manual-build.yml`): Started manually via the **Actions** tab in GitHub (`Run workflow`) and can be run on any chosen branch — for example on a feature branch without an open pull request. Runs the same build as **Build and Test** including the Plugin Verifier, but without path filters, i.e. on every manual trigger. The button only appears once the workflow exists on `main`; this job is also limited to 30 minutes.
- **Create Draft Release** (`.github/workflows/create-draft-release.yml`): Runs when a tag is pushed to GitHub, for example `2.3.0`. The current state of the tag is built, checked with `verifyPlugin` and the IntelliJ Plugin Verifier, and then created as a GitHub draft release with a ZIP file and release notes. Only pushing the tag to GitHub starts the workflow. The job is limited to 30 minutes.
- **Dependency Graph** (`.github/workflows/dependency-graph.yml`): Resolves all Gradle configurations and produces the full dependency graph — including transitive dependencies. On a push to `main` that touches the build or dependency configuration, and on a manual run via the **Actions** tab, the graph is submitted directly to GitHub via the Dependency Submission API (`contents: write`). This is what enables Dependabot to report security alerts for transitive dependencies of this Gradle project. On a pull request against `main`, the graph is only generated and stored as a workflow artifact with `contents: read`, because pull requests from forks receive a read-only `GITHUB_TOKEN`. Each job is limited to 30 minutes.
- **Dependency Graph Submit** (`.github/workflows/dependency-graph-submit.yml`): Triggered by `workflow_run` once a successful pull request run of **Dependency Graph** has stored its artifact. It downloads that artifact and submits it with `contents: write`. Running outside the pull request context is what allows fork pull requests to be covered without granting write access to untrusted code. The job is limited to 10 minutes.
- **Dependency Review** (`.github/workflows/dependency-review.yml`): Runs on pull requests against `main` that touch the build or dependency configuration and compares the submitted graph of the pull request against `main` with `actions/dependency-review-action`. It needs neither a checkout nor a Gradle run, because the graph comes from **Dependency Graph**; it waits for that submission for up to ten minutes. The job fails as soon as the pull request introduces a dependency with a known vulnerability of severity `moderate` or higher and posts a summary as a pull request comment in that case. For fork pull requests the comment is disabled — the result is available in the job summary. The job is limited to 30 minutes.

The path filters of **Dependency Graph** and **Dependency Review** must stay identical, otherwise the review waits for a graph that is never submitted. Because of the path filters, neither workflow should be configured as a required status check.
- **Publish Release to Marketplace** (`.github/workflows/publish-release.yml`): Runs as soon as a draft release is published manually in the GitHub UI (`release: published`). Before the upload, `verifyPlugin` checks the plugin with the IntelliJ Plugin Verifier; on a verifier failure the report is stored as the `plugin-verifier-report` artifact (7 days). Afterwards the plugin is uploaded to the JetBrains Marketplace with `publishPlugin`. For this, the repository secret `JB_MARKETPLACE_TOKEN` must be set. The job is limited to 30 minutes and uses one concurrency group per release ref, which does not abort running publish runs.

### Dependabot

`.github/dependabot.yml` monitors both GitHub Actions and Gradle dependencies monthly. Among other things, this proposes updates to the IntelliJ Platform and the used libraries as pull requests.

Dependabot security alerts additionally rely on the dependency graph submitted by the **Dependency Graph** workflow. Enable **Dependency graph**, **Dependabot alerts**, and optionally **Dependabot security updates** under `Settings > Code security` so the submitted graph is evaluated against the GitHub Advisory Database.

## Run `publishPlugin` manually

For a manual publication, a JetBrains Marketplace token is required. Create the token in the JetBrains Marketplace account and never commit it to `build.gradle.kts`, `gradle.properties`, or Git. The Gradle plugin expects the token in the environment variable `PUBLISH_TOKEN`.

In the IntelliJ terminal, the plugin can be published as a hidden Marketplace release like this:

```bash
PUBLISH_TOKEN="<JETBRAINS_MARKETPLACE_TOKEN>" ./gradlew publishPlugin -PmarketplaceHidden=true --no-daemon
```

On Windows PowerShell:

```powershell
$env:PUBLISH_TOKEN = "<JETBRAINS_MARKETPLACE_TOKEN>"
.\gradlew.bat publishPlugin -PmarketplaceHidden=true --no-daemon
```

Alternatively, create a Gradle run configuration in IntelliJ: **Run → Edit Configurations… → + → Gradle**, select the project and the `publishPlugin` task, and under **Environment variables** enter `PUBLISH_TOKEN=<JETBRAINS_MARKETPLACE_TOKEN>`. For a hidden release, additionally enter the option `-PmarketplaceHidden=true` under **Arguments/Gradle options**. The run configuration must not be added to version control if it contains the token in plain text.

Without `-PmarketplaceHidden=true`, the plugin is published normally. The call builds the plugin automatically and then uploads it to the JetBrains Marketplace.

## Perform a release manually

The following commands are run, for example, in the terminal. The tag should be created on the verified release branch:

```bash
git switch release/2.3.0
git pull --ff-only origin release/2.3.0
git tag 2.3.0
git push origin 2.3.0
```
Alternatively, in IntelliJ the tag can be created via **Git → New Tag** and then pushed to the remote repository via **Git → Push**.

The tag push starts **Create Draft Release**. Afterwards, check the run in GitHub under **Actions** and inspect the created draft release under **Releases**. **Publish release** publishes it and thereby starts **Publish Release to Marketplace**.

For a new release branch, the steps can look like this, for example:

```bash
git switch main
git pull --ff-only origin main
git switch -c release/2.3.0
git push -u origin release/2.3.0
```

A tag alone does not start a local IntelliJ run task: the workflows run on GitHub Actions as soon as the branch, pull request, or tag has been pushed to the remote repository.

## Marketplace screenshots and visual documentation

To provide clear, high-quality, and easily maintainable screenshots for the JetBrains Marketplace and project documentation, visual assets follow a structured vector overlay workflow using Draw.io / Diagrams.net.

For a detailed step-by-step tutorial on using the master template, adding callouts, and exporting production images, see the [Marketplace Assets Guide](assets/marketplace/README.md).

### Tooling and file format

- **IntelliJ Plugin**: Use the official **Diagrams.net Integration** plugin in IntelliJ IDEA to edit diagram files directly within the IDE without external tool switching.
- **Single Source of Truth (`.drawio.svg`)**: Screenshot diagrams are stored as `.drawio.svg`. This format is a valid SVG vector image containing embedded XML diagram metadata. The raw screenshot serves as a background image, while callout bubbles, arrows, and highlight frames are placed on top as editable vector elements. If the UI changes, only the background image needs updating while callout positions and text are preserved.

### Directory structure

Marketplace graphics are structured under `docs/assets/marketplace/`:

```text
docs/assets/marketplace/
├── raw/                  # Unprocessed raw UI screenshots (HiDPI / 2x resolution)
│   ├── dependencies_raw.png
│   └── vulnerabilities_raw.png
├── src/                  # Editable source diagrams (.drawio.svg)
│   ├── screenshot_01_main_view.drawio.svg
│   └── screenshot_02_cve_details.drawio.svg
└── dist/                 # Rendered distribution images for Marketplace & README (.png)
    ├── 01_dependency_management.png
    └── 02_vulnerability_scanner.png
```

### Visual guidelines and JetBrains look & feel

1. **Resolution and scaling**:
   - Capture screenshots on HiDPI / Retina displays (2x resolution), maintaining at least 1280×800 px or a standard 16:10 / 16:9 aspect ratio.
2. **Visual hierarchy and density**:
   - Keep 2–4 callouts per screenshot to avoid clutter.
   - Use numbered badges (❶, ❷, ❸) with a concise legend or speech bubbles with targeted arrows.
3. **Color palette (JetBrains theme compliant)**:
   - **Accent / Primary highlight**: `#3574F0` (IntelliJ blue)
   - **Warning / Vulnerability highlight**: `#F28B25` (Orange) / `#E55765` (Red)
   - **Badge text**: `#FFFFFF` for high contrast against dark or colored badge backgrounds.
4. **Framing and focus**:
   - Crop tightly to the relevant UI components (e.g., MavenUp Tool Window, POM editor split, or dialogs) rather than showing full desktop screenshots.

### Step-by-step workflow

1. **Capture raw screenshot**: Take a high-resolution screenshot of the MavenUp UI state and save it in `docs/assets/marketplace/raw/`.
2. **Create / Edit source diagram**: Open or create `.drawio.svg` under `docs/assets/marketplace/src/` using the Diagrams.net plugin.
3. **Compose callouts**: Embed the raw image as the background, add speech bubbles or numbered badges matching the color guidelines, and describe the feature concisely.
4. **Export distribution image**: Export the diagram as PNG into `docs/assets/marketplace/dist/`.
5. **Publish / Reference**: Upload the generated `.png` files to the JetBrains Marketplace portal and reference them in `README.md` or documentation where needed.
