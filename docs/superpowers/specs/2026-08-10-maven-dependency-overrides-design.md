# Maven Dependency Overrides — Design

## Problem

Teams that build Java/Spring Boot applications with Maven sometimes must pin a
specific dependency version in their own `dependencyManagement` — e.g. to pick
up a CVE fix — ahead of the version Spring Boot's own dependency management
(`spring-boot-dependencies` / `spring-boot-starter-parent`, or other imported
BOMs) provides. Once the upstream BOM catches up, this manual override becomes
stale and should be removed, but there's currently no tooling that flags it.
Overrides get forgotten, and the pom.xml accumulates dead pins that nobody
revisits.

## Goal

An IntelliJ IDEA plugin (Kotlin, Maven support) that:

- Detects dependency version overrides in a project's `pom.xml` files that
  shadow a version managed by an imported BOM.
- Marks them visually in the editor.
- Offers quick fixes to resolve the override once it's no longer needed, or to
  keep it current.
- Provides a project-wide overview via a tool window.

## Scope

- **Maven only** (no Gradle).
- Only `<dependencyManagement>` entries in the project's own POM(s) are
  monitored (not ad-hoc `<dependencies>` version pins outside management).
- Compared against **all** imported BOMs generically (not just
  `spring-boot-dependencies`) — any `<dependencyManagement>` entry with
  `<scope>import</scope><type>pom</type>`, including transitively through
  parent POMs.
- **Multi-module Maven projects** are supported: `dependencyManagement` is
  typically declared in a parent POM and inherited by child modules; the
  plugin resolves and reports overrides in that inherited context.
- Target IDE: matches the existing project scaffold (IntelliJ Platform Gradle
  Plugin, `intellijIdea("2025.3.5")`), IntelliJ IDEA Community or Ultimate —
  both bundle Maven support (`org.jetbrains.idea.maven`) needed by this
  plugin. Requires `com.intellij.java` and Maven plugin as platform
  dependencies.

## Non-goals

- No support for Gradle dependency constraints.
- No enforcement/blocking behavior (CI checks, build failures) — purely an
  IDE-assist tool.
- No general Maven dependency graph visualization.

## Architecture

Four components, each isolated behind a clear interface so they can be
developed, tested, and iterated on independently:

### 1. BOM Version Resolver

Given a Maven module, computes the "natural" (BOM-managed) version for a
`groupId:artifactId` — i.e. the version that would apply if the local literal
override in `dependencyManagement` did not exist.

Implemented by embedding Maven's own `maven-model-builder` /
`maven-resolver` libraries to build the effective POM model, rather than
hand-rolling POM/property/precedence resolution. Rationale: this is a
security-relevant tool — a wrong "safe to remove" recommendation can silently
reintroduce a patched CVE. Reusing Maven's own resolution logic removes an
entire class of correctness bugs that a custom resolver would otherwise
accumulate over time as edge cases (profiles, multi-level parent chains,
property inheritance across parents) are discovered in the field.

Runs the model builder twice conceptually: once against the real POM (to
confirm what's actually in effect), and against a variant with the local
literal override entry removed, to recover the value the BOM chain would
have provided. Repository URLs used for resolution are read from the
project's `pom.xml` / Maven `settings.xml` (mirrors, internal
Nexus/Artifactory included), matching what Maven itself would use — not
hardcoded to Maven Central.

Pure library code, no dependency on IntelliJ platform APIs — unit-testable
in isolation with fixture POMs and a local test repository directory.

### 2. Override Detector

Runs against the PSI/DOM of an open `pom.xml`, in two stages:

- **Fast synchronous pre-filter**: scans `<dependencyManagement>` for literal
  `<version>` entries (including ones set via a resolvable Maven property)
  whose `groupId:artifactId` also appears in at least one BOM imported by
  the module. This is cheap PSI-tree matching, used to avoid invoking the
  full resolver for every dependency in the file.
- **Async precise check**: for each candidate, invokes the BOM Version
  Resolver to get the natural version and compares it to the locally
  declared one. Publishes the confirmed/rejected result back to update the
  editor markers and tool window (candidates that turn out not to differ are
  silently dropped, no flicker of a warning that then disappears).

Also reads, for each override:

- **Reason**: the free-text XML comment immediately preceding the
  `<dependency>` block (e.g.
  `<!-- CVE-2024-XXXXX, entfernen sobald Spring nachzieht -->`), shown
  verbatim in the quick-fix popup and tool window. No comment present is a
  valid state ("kein Grund angegeben").
- **Suppress marker**: a distinct recognizable XML comment (e.g.
  `<!-- maven-dependency-overrides: suppress -->`) directly at the
  dependency. Suppressed entries are committed to the repo (so the whole
  team shares the suppression) and are fully excluded from both inspection
  warnings and the tool window — no partial/greyed-out display.

### 3. Version Advisor

Separate, lower-priority async task, decoupled from the detector so it never
blocks the core override/BOM comparison:

- Latest available version of the overridden artifact.
- Latest available version of the owning parent/BOM artifact, and whether
  that newer BOM version would already provide a version >= the override's
  version (enables the "bump parent/BOM instead" quick fix).

Queries the same repositories resolved from `pom.xml`/`settings.xml` (per
Scope above). Results are cached with a TTL to avoid re-querying repositories
on every keystroke; cache is invalidated on relevant POM changes, project
reimport, or TTL expiry. If repositories are unreachable, this component
fails gracefully — the core override detection (which needs no network,
only locally resolved BOM data) keeps working; only the "update available"
suggestions are withheld, surfaced with an offline indicator instead of an
error.

### 4. Editor Integration & Tool Window

- **Line marker (gutter icon)** on the `<dependency>` line of a confirmed
  override.
- **Local inspection** (weak-warning wavy underline) on the `<version>` text,
  with quick fixes reachable via Alt+Enter:
  1. Update override version to the latest available release.
  2. Bump the parent/BOM version to the release that already provides the
     needed version (only offered when the Version Advisor confirms this).
  3. Remove the override (only offered when the BOM-managed version is
     already >= the override's version — i.e. actually safe).
  4. Suppress this override (inserts the suppress marker comment).
- **Tool window** ("Dependency Overrides"): project-wide list across all
  modules (parent + children), columns: artifact, your version, resolved
  BOM version, status (🔴 diverges from BOM / 🟢 BOM has caught up, update
  available / ⏳ pending resolution / 📡 offline — no network check done),
  reason, actions. Clicking a row navigates to the location in the relevant
  `pom.xml`. Suppressed overrides are excluded here too, consistent with the
  editor.

## Data Flow

1. Maven project (re)import in IntelliJ (automatic on `pom.xml` changes or
   explicit sync) triggers the plugin's listener.
2. Override Detector's fast pre-filter runs synchronously, marking
   candidates provisionally (neutral, non-warning visual state) while the
   precise check is pending.
3. BOM Version Resolver runs in the background per candidate; results
   update editor markers/inspection to their final state (confirmed warning,
   or silently cleared if not actually an override).
4. Version Advisor runs separately, lower priority, network-bound; quick-fix
   list and tool-window row update incrementally as results arrive (initial
   state: "prüfe neue Versionen…").
5. Results are cached per module; recomputed only on relevant POM file
   changes, project reimport, or cache TTL expiry — not on every keystroke.

## Error Handling & Edge Cases

- **Maven project not yet resolved** (fresh checkout, no sync run yet):
  plugin stays silent in the editor; tool window shows a "waiting for Maven
  sync" state.
- **Repository unreachable**: core detection (local-only) keeps working;
  only latest-version suggestions are withheld with an offline indicator,
  no error dialog.
- **Override version set via a Maven property** (`${jackson.version}`):
  resolved before comparison; still detected correctly.
- **Same artifact overridden independently in multiple modules**: each
  module is evaluated in its own inheritance context, reported separately.
- **No reason comment present**: popup/tool-window shows "kein Grund
  angegeben"; all functionality still works.

## Testing Strategy

- **Unit tests** (no IDE context) for the BOM Version Resolver: fixture POMs
  plus a local test repository directory, covering property resolution,
  multi-BOM precedence, and parent-chain inheritance.
- **Platform tests** (`TestFrameworkType.Platform`, already scaffolded in
  the project template) for the inspection and quick fixes: verify the
  correct PSI range is highlighted and that each quick fix produces the
  expected resulting `pom.xml` content.
- **Manual verification** via the `runIde` Gradle task against a sample
  multi-module Spring Boot project fixture with deliberately configured
  overrides (fresh, suppressed, stale, and BOM-caught-up cases).

## Open Assumptions (not further clarified, low-risk defaults)

- "Latest available version" for quick fixes/advisor means the latest
  stable release (pre-release qualifiers such as `-RC`, `-M1`, `-alpha`
  excluded unless that's already the qualifier style in use).
- Plugin ID/name follow the existing project scaffold:
  group `cloud.schneidoa`, plugin name "Maven Dependency Overrides".
