# Maven Dependency Overrides — Phase 4: Project-Wide Override Overview Panel — Design

## Problem

The original design (`2026-08-10-maven-dependency-overrides-design.md`) scoped
a project-wide overview via a tool window as part of the plugin's goal, but
Phases 1–3 only delivered inline editor inspection and quick fixes. There is
currently no way to see all detected overrides across a multi-module project
at a glance — a developer has to open every `pom.xml` individually and wait
for the inspection to highlight anything.

Separately, the project's scaffold still carries the IntelliJ Platform Plugin
Template's demo tool window (`MyToolWindowFactory` — a label and a
"shuffle number" button), registered in `plugin.xml` but never built out or
removed.

## Goal

A tool window that lists every detected `dependencyManagement` override
across all `pom.xml` files in the project, replacing the leftover template
tool window.

## Scope

- Scans **all** `pom.xml` files in the project (multi-module), not just the
  currently open one.
- Manual refresh only for this v1 — no reactive updates on file edits. The
  panel scans once automatically when its content is first created, and
  again whenever the user clicks a Refresh action.
- Each row: module, dependency (`groupId:artifactId`), declared version →
  managed version, and status (Confirmed / Inconclusive).
- Clicking a row navigates to the `<version>` tag in the corresponding
  `pom.xml`. No inline remove/suppress actions in the panel — those remain
  editor quick fixes (Alt+Enter), reached by navigating to the spot.
- Suppressed overrides are excluded, consistent with `OverrideDetector`
  (which already filters them before detection).
- Out of scope: auto-refresh on file change, inline fix buttons, grouping/
  tree view, sorting controls, filtering/search. All YAGNI for v1 — a flat
  table is enough to see what's there and jump to it.

## Architecture

Two new pieces, kept deliberately separate so the aggregation logic is
testable independently of Swing:

- **`ProjectOverrideScanner`** (`cloud.schneidoa.detection`) — plain Kotlin,
  no UI dependency. Finds every `pom.xml` in the project and returns a flat
  list of detected overrides tagged with which module they came from.
- **`OverrideOverviewToolWindowFactory`** (replaces `MyToolWindowFactory`) —
  thin Swing/platform glue: builds a `JBTable` in a panel with a Refresh
  toolbar action, invokes the scanner on a background thread, and wires row
  clicks to navigation.

This mirrors the existing split in this project between PSI/detection logic
(`BomChainResolver`, `OverrideDetector`, `DependencyManagementScanner`) and
thin adapter classes (`OverrideInspection`, the quick fixes) established in
Phases 2–3.

### Why `FilenameIndex` instead of `MavenProjectsManager.projects`

`BomChainResolver` deliberately avoids
`MavenDomProjectProcessorUtils.findParent` because it only works against
`MavenProjectsManager`'s already-synced project index — a dependency that
proved fragile in practice (see Phase 3 follow-up debugging: parent
resolution failures were initially hard to distinguish from sync-timing
issues). The overview panel makes the same choice for the same reason:
finding modules via `FilenameIndex.getVirtualFilesByName("pom.xml",
GlobalSearchScope.projectScope(project))` needs no Maven import/sync to have
completed, and keeps `ProjectOverrideScanner` testable the same way
`BomChainResolverTest` already is — plain `myFixture.addFileToProject` PSI
fixtures, no `MavenProjectsManager` test setup.

`OverrideDetector.forProject(project)` is still used per-module for the
actual detection (it already reads the local repository path via
`MavenProjectsManager.getInstance(project).repositoryPath` — a cheap,
always-available Maven settings value, not dependent on reactor sync).

## Components

### `ProjectOverrideScanner`

```kotlin
data class ProjectOverrideEntry(
    val moduleLabel: String,
    val pomFile: VirtualFile,
    val override: DetectedOverride
)

class ProjectOverrideScanner {
    fun scan(project: Project): List<ProjectOverrideEntry>
}
```

- Finds all `pom.xml` virtual files via `FilenameIndex` in project scope.
- For each, resolves a `MavenDomProjectModel` via `MavenDomUtil`. Files that
  don't parse into one are skipped (not every `pom.xml` on disk is
  necessarily a valid/relevant Maven POM — e.g. a fixture or generated
  file — and one bad file must not abort the whole scan).
- Calls `OverrideDetector.forProject(project).detect(model, project)` per
  module POM and flattens the results into `ProjectOverrideEntry`.
- `moduleLabel` is the module's own `artifactId` (always present on a valid
  POM); falls back to the containing directory name if somehow blank.
- Caller is responsible for running this inside a read action off the EDT —
  the scanner itself does no threading (keeps it simple to unit test).

### `OverrideOverviewToolWindowFactory`

- Registered in `plugin.xml` in place of `MyToolWindowFactory`, replacing
  its `toolWindow` id/factory.
- `createToolWindowContent` builds:
  - A `JBTable` with columns: Module | Dependency | Declared → Managed |
    Status. For `Inconclusive` entries, the last column reads e.g.
    `"Inconclusive (2 BOMs unchecked)"` instead of a target version.
  - A toolbar with one `AnAction` ("Refresh").
- Both initial load and the Refresh action call the same private method:
  `ApplicationManager.getApplication().executeOnPooledThread { ReadAction.run { scanner.scan(project) } }`,
  then `invokeLater` to repopulate the table model on the EDT.
- Row click navigates via
  `OpenFileDescriptor(project, entry.pomFile, offset).navigate(true)`, where
  `offset` comes from the override candidate's `versionXmlTag` (already
  exposed on `OverrideCandidate` since Phase 3).

## Data Flow

1. Tool window content is created (first open) → scan kicked off automatically.
2. User clicks Refresh → same scan path re-runs, table model replaced.
3. User clicks a row → editor opens/focuses at the `<version>` tag; any
   further action (remove/suppress) happens through the existing inline
   quick fixes there.

## Error Handling & Performance

- A `pom.xml` that fails to resolve to a `MavenDomProjectModel` is skipped,
  not fatal to the scan.
- Scanning (file index lookup, PSI/DOM reads, BOM resolution via the real
  Maven model builder) always runs on a background thread wrapped in a read
  action, never on the EDT, since `BomEffectiveModelResolver` does real file
  I/O and model building that can be slow on a large BOM chain.
- No caching in v1 — every scan (initial or Refresh) is a full re-scan. Given
  manual-refresh-only scope, this is acceptable; revisit if it proves slow
  in practice.

## Cleanup

- Delete `MyToolWindowFactory.kt`.
- Remove its now-unused keys from `messages/MyMessageBundle.properties`
  (`toolwindow.MyToolWindow.number.label`,
  `toolwindow.MyToolWindow.shuffle.button`).
- Update the `toolWindow` extension in `plugin.xml` to point at
  `OverrideOverviewToolWindowFactory` with a real id/icon.

## Testing

- `ProjectOverrideScannerTest` (`BasePlatformTestCase`, same pattern as
  `BomChainResolverTest`/`OverrideDetectorTest`):
  - Multiple modules added via `myFixture.addFileToProject`, each with its
    own override — asserts all are found, tagged with the right module
    label.
  - A module with no override contributes nothing.
  - A malformed/non-Maven `pom.xml`-named file in the project doesn't abort
    the scan of the others.
  - Suppressed overrides are excluded (already guaranteed by
    `OverrideDetector`, but worth asserting end-to-end here too).
- No automated test for the Swing table rendering/navigation itself — kept
  as thin, manually-verified UI glue, consistent with how this project has
  treated other platform-UI adapter classes.
