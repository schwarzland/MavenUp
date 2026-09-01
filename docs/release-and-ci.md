# Release & CI

## Branching strategy and GitHub Actions

Development happens on `feature/*` branches. Changes are merged into `main` via pull request. To stabilize a release, a `release/*` branch is used, which is also merged into `main` via pull request. The uniform scheme is: release branch `release/x.y.z`, Git tag `x.y.z` — each without a leading `V`.

### Automated workflows

- **Build and Test** (`.github/workflows/ci.yml`): Runs on every push to `main` or `hotfix/**` as well as on every newly created, updated, or reopened pull request against `main`. As a result, feature and release branches are checked exclusively via their pull request; hotfix branches are additionally checked on direct pushes. For feature and release branches, duplicate builds on pushes with an open pull request are avoided. A direct push to `main` (without a pull request) is still covered by the push trigger. The workflow compiles the plugin, runs tests and static analysis (detekt), generates the coverage report (Kover), checks the plugin structure, and runs the IntelliJ Plugin Verifier against the configured compatibility. The reports are stored as an artifact on every run — whether successful or failed. Each job is limited to 30 minutes. The trigger only fires on changes to source code, plugin resources, or build configuration.
- **Manual Build and Test** (`.github/workflows/manual-build.yml`): Started manually via the **Actions** tab in GitHub (`Run workflow`) and can be run on any chosen branch — for example on a feature branch without an open pull request. Runs the same build as **Build and Test** including the Plugin Verifier, but without path filters, i.e. on every manual trigger. The button only appears once the workflow exists on `main`; this job is also limited to 30 minutes.
- **Create Draft Release** (`.github/workflows/create-draft-release.yml`): Runs when a tag is pushed to GitHub, for example `2.3.0`. The current state of the tag is built, checked with `verifyPlugin` and the IntelliJ Plugin Verifier, and then created as a GitHub draft release with a ZIP file and release notes. Only pushing the tag to GitHub starts the workflow. The job is limited to 30 minutes.
- **Dependency Submission** (`.github/workflows/dependency-submission.yml`): Runs on every push to `main` that touches the build or dependency configuration, and can also be started manually via the **Actions** tab. It resolves all Gradle configurations and submits the full dependency graph — including transitive dependencies — to GitHub via the Dependency Submission API (`contents: write`). This is what enables Dependabot to report security alerts for transitive dependencies of this Gradle project. The job is limited to 30 minutes.
- **Publish Release to Marketplace** (`.github/workflows/publish-release.yml`): Runs as soon as a draft release is published manually in the GitHub UI (`release: published`). Before the upload, `verifyPlugin` checks the plugin with the IntelliJ Plugin Verifier; on a verifier failure the report is stored as the `plugin-verifier-report` artifact (7 days). Afterwards the plugin is uploaded to the JetBrains Marketplace with `publishPlugin`. For this, the repository secret `JB_MARKETPLACE_TOKEN` must be set. The job is limited to 30 minutes and uses one concurrency group per release ref, which does not abort running publish runs.

### Dependabot

`.github/dependabot.yml` monitors both GitHub Actions and Gradle dependencies monthly. Among other things, this proposes updates to the IntelliJ Platform and the used libraries as pull requests.

Dependabot security alerts additionally rely on the dependency graph submitted by the **Dependency Submission** workflow. Enable **Dependency graph**, **Dependabot alerts**, and optionally **Dependabot security updates** under `Settings > Code security` so the submitted graph is evaluated against the GitHub Advisory Database.

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
