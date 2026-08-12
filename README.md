# Maven Dependency Overrides

[![Build](https://github.com/Schneidoa/maven-dependency-overrides/actions/workflows/build.yml/badge.svg?branch=develop)](https://github.com/Schneidoa/maven-dependency-overrides/actions/workflows/build.yml)
[![Release](https://github.com/Schneidoa/maven-dependency-overrides/actions/workflows/release.yml/badge.svg)](https://github.com/Schneidoa/maven-dependency-overrides/actions/workflows/release.yml)
![IntelliJ IDEA](https://img.shields.io/badge/IntelliJ%20IDEA-2025.3-000000?logo=intellijidea)
![License](https://img.shields.io/badge/license-MIT-blue)

An IntelliJ IDEA plugin that flags Maven `<dependencyManagement>` version overrides that have
become stale because an imported BOM chain has caught up.

The typical case: a version was pinned by hand ahead of a CVE fix, and some time later Spring
Boot's own dependency management shipped that version or newer. The pin is now redundant — but
nothing tells you, so it sits in the POM indefinitely, quietly pinning a version that will fall
behind the BOM again on the next upgrade.

## What it does

- **Flags overrides that are worth a second look in the editor**, as a weak warning on the
  `<version>` tag: overrides the BOM chain has exactly caught up to (**redundant**) and ones
  that hold the version *below* what the BOM already ships (**behind the BOM**) both get quick
  fixes to remove the override or suppress the warning with a shared marker comment. Overrides
  that still raise the version above the BOM, and ones whose version can't be compared at all
  (unresolved properties, version ranges), are left alone in the editor — they aren't stale, so
  flagging them would just be noise.
- **Lists every override in one place** via the *Maven Overrides* tool window — all `pom.xml`
  files in the project in a single table, with a Status column (icon, label, and a full
  explanation on hover) covering every override including the ones the editor stays quiet
  about, plus add / edit / remove actions and click-to-navigate to the exact `<version>` tag.
- **Shows where the version actually comes from**: the full parent-chain path from the module
  down to the BOM that manages it, e.g.
  `spring-boot-starter-parent → spring-boot-dependencies → jackson-bom:2.21.2` — not just the
  bare BOM name, which is usually something nobody wrote by hand. Right-click a row and choose
  **Show BOM Chain** to see every BOM that was consulted, in precedence order, including the
  ones that don't manage the dependency and the ones that couldn't be resolved locally — the
  detail behind an inconclusive verdict. **Analyze Dependencies** hands the same coordinate off
  to IntelliJ's own Maven Dependency Analyzer.
- **Guides you when pinning a new override**: the tool window's **+ Add** action offers separate
  Group ID and Artifact ID fields, each backed by what the target module's BOM chain actually
  manages — type to filter or use the arrow button to browse the full list. Picking a group
  narrows the artifact suggestions to it, and typing an artifactId owned by only one group fills
  the group back in for you. The version field prefills from what the BOM already manages, with a
  live note on what the version you type would mean — redundant, above the BOM, below it, or not
  comparable — before you commit to it.

## How it decides

Detection is **local-only**. The plugin reads whatever BOM POMs already sit in your local Maven
repository; it never goes to the network and never resolves anything itself.

That has a deliberate consequence: when a BOM in the chain can't be resolved locally, the
override is reported as **inconclusive** rather than confirmed, and no "safe to remove" quick fix
is offered. A wrong "safe to remove" recommendation could silently reintroduce a patched CVE, so
the plugin misses rather than guesses.

## Requirements

IntelliJ IDEA 2025.3 (Community or Ultimate) with the bundled Maven support enabled. Any BOM
involved in a check must be present in the local Maven repository — in practice this means the
project has been imported at least once.

## Development

```bash
./gradlew check         # unit tests + platform tests
./gradlew runIde        # sandbox IDE with the plugin loaded
./gradlew verifyPlugin  # IntelliJ plugin-verifier compatibility check
./gradlew buildPlugin   # installable zip in build/distributions/
```

The same tasks are available as predefined run configurations in `.run/`.

### Branches and CI

Work happens on **`develop`**; **`main`** is the release branch and nothing else. Every push to
`develop`, and every pull request into either branch, runs `check buildPlugin verifyPlugin` on
GitHub Actions ([`build.yml`](./.github/workflows/build.yml)) and attaches the installable zip to
the run as an artifact. `verifyPlugin` is the gate that matters — it fails on internal-API usage,
which is what the JetBrains Marketplace review rejects on too.

The target platform is pinned in [build.gradle.kts][file:build.gradle.kts]
(`intellijIdea("2025.3.5")`), and `sinceBuild`/`untilBuild` are deliberately restricted to
`253.*`: the plugin is verified only against that baseline, and opening the range without a
re-verification pass would risk shipping broken detection.

### Source layout

```
src/main/kotlin/cloud/schneidoa/
├── resolver/    Pure JVM — no IntelliJ Platform APIs, unit-tested against fixture POMs.
│                Embeds maven-model-builder to compute effective BOM models and walk
│                an ordered BOM list looking for a managed version.
├── detection/   PSI/DOM layer over org.jetbrains.idea.maven. Walks the parent chain for
│                imported BOMs, scans <dependencyManagement> for literal versions, and
│                produces Confirmed / Inconclusive results plus the inspection.
└── ui/          Swing tool window and the Add/Edit override dialogs.
```

The split is a hard rule: everything under `resolver/` stays free of IntelliJ Platform APIs so it
remains testable as plain JVM code. Tests mirror it — `resolver/` tests are plain JUnit, while
the PSI-facing tests extend `BasePlatformTestCase` and run against fixture POMs laid out in real
local-repository structure under `src/test/resources/fixtures/local-repo/`.

Longer-form design context — problem framing, per-phase decisions, and open assumptions — lives
under `docs/superpowers/specs/`, with the matching implementation plans under
`docs/superpowers/plans/`. Start with the
[initial design doc](./docs/superpowers/specs/2026-08-10-maven-dependency-overrides-design.md).
User-facing changes are tracked in [CHANGELOG.md][file:CHANGELOG.md].

## Releasing

Merging `develop` into `main` publishes a release. [`release.yml`](./.github/workflows/release.yml)
reads the version from `gradle.properties` and, if no `v<version>` tag exists yet:

1. renames the changelog's `[Unreleased]` section to `[<version>]` (skipped when that section was
   already written by hand), so the change notes shipped inside the plugin come from a real
   release section rather than the `[Unreleased]` fallback;
2. runs the same `check buildPlugin verifyPlugin` gate as `develop`;
3. uploads to [JetBrains Marketplace][jb:marketplace] via `publishPlugin`;
4. tags `v<version>` — only after a successful upload, since the tag is what stops the next push
   to `main` from repeating the release;
5. creates a GitHub Release carrying that changelog section and the installable zip;
6. opens a pull request back to `develop` with the patched changelog.

A push to `main` that doesn't change the version releases nothing and reports as skipped, so
merging a documentation fix is harmless. **To cut a release, bump `version` in `gradle.properties`
on `develop` first** — that value is the whole trigger.

A version with a pre-release suffix (`0.3.0-beta.1`, `0.3.0-rc.2`) is published to the Marketplace's
`eap` channel and marked as a pre-release on GitHub; a plain `0.3.0` goes to the default channel
every user sees. All suffixes share the one channel, so a tester subscribes once rather than
re-subscribing when a beta becomes an rc.

### One-time setup

- **`PUBLISH_TOKEN`** as a repository secret (Settings → Secrets and variables → Actions),
  generated at [plugins.jetbrains.com/author/me/tokens][jb:tokens]. Nothing else is needed;
  `GITHUB_TOKEN` is provided by Actions itself.
- **Allow GitHub Actions to create pull requests** (Settings → Actions → General → Workflow
  permissions), or step 6 fails at the very end of an otherwise successful release.
- Make `develop` the default branch, and consider protecting `main` so it only ever receives
  merges from `develop`.

The plugin can still be [uploaded manually][jb:upload], and `./gradlew publishPlugin` works locally
with `PUBLISH_TOKEN` set in the environment.

## License

MIT — see [LICENSE](./LICENSE).

[file:build.gradle.kts]: ./build.gradle.kts
[file:CHANGELOG.md]: ./CHANGELOG.md

[jb:marketplace]: https://plugins.jetbrains.com
[jb:upload]: https://plugins.jetbrains.com/plugin/upload
[jb:tokens]: https://plugins.jetbrains.com/author/me/tokens
