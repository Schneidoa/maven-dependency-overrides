# Maven Dependency Overrides — Phase 6: Panel Remove / Edit / Add — Design

## Problem

The Override Overview tool window (Phase 4) is read-only: it lists detected overrides
and lets the developer navigate to them, but every actual change — removing a
now-unneeded override, updating its reason or pinned version, or pinning a brand-new
override — still requires opening the file and either using the editor quick fixes
(Remove/Suppress, Phase 3) or hand-editing the XML. For a multi-module project where
the whole point of the panel is to see everything at once, having to leave the panel to
act on what it shows is friction the panel was supposed to remove.

## Goal

Let the developer remove, edit, and add `dependencyManagement` overrides directly from
the Override Overview tool window, without needing to open the corresponding pom.xml.

## Scope

- **Remove**: delete an existing override's `<dependency>` block. Triggered via a
  right-click context menu on a table row, gated by a confirmation dialog (unlike the
  existing editor quick fix, which removes immediately — the panel action is one step
  removed from looking directly at the code being deleted, so a confirmation is
  warranted here even though it isn't in the editor).
- **Edit**: change an existing override's pinned version and/or its free-text reason
  comment. Triggered via the same context menu. Does **not** change the override's
  `groupId:artifactId` — that's the entry's identity; changing it would really be a
  remove-and-add, which stays two separate actions rather than one ambiguous "edit".
- **Add**: create a brand-new `dependencyManagement` override in a chosen module,
  entering `groupId:artifactId`, the version to pin, and an optional reason. Triggered
  via a new toolbar button (not row-scoped, since it's not acting on an existing row).
- Both Add and Edit show a live hint of what the BOM chain currently manages the typed
  (Add) or existing (Edit) `groupId:artifactId` at, reusing the exact same
  `BomChainResolver`/`BomVersionResolver` logic the rest of the plugin already relies
  on for detection — not a new resolution mechanism.
- **Out of scope:**
  - Detecting/preventing a duplicate override for the same GA already present in the
    target module — Maven's own behavior with two `dependencyManagement` entries for
    the same GA is to use the later one, which is confusing but not this plugin's
    problem to solve in v1. Documented as a known limitation, not silently guarded
    against.
  - Validating that a typed `groupId:artifactId` actually exists anywhere (no network
    lookup against Maven Central or any remote repository) — this plugin works offline
    by design (see the original design doc), and this phase doesn't change that.
  - Changing an override's GA via "Edit" (see above — that's remove-and-add, not
    edit).
  - Suppress/unsuppress from the panel — not requested for this phase, and suppressed
    overrides never appear as panel rows in the first place (`OverrideDetector`
    filters them out before `ProjectOverrideScanner` ever sees them), so there'd be no
    row to unsuppress from even if it were in scope.

## Architecture

Four new pieces, following this project's established split between pure-Kotlin PSI
logic (independently testable) and thin Swing glue (not unit-tested, matching Phase
4's precedent for `OverrideOverviewToolWindowFactory`):

- **`OverrideMutations.kt`** (`cloud.schneidoa.detection`) — plain functions performing
  the actual PSI edits, each wrapped in its own `WriteCommandAction`:
  - `removeOverride(project, candidate)` — deletes the candidate's `<dependency>` tag.
    Deliberately a *separate* implementation from `RemoveOverrideQuickFix`, not a
    shared extraction — the existing quick fix is small, already reviewed, and used in
    a different calling context (`ProblemDescriptor`-based). Duplicating four lines of
    PSI deletion logic is cheaper and lower-risk than restructuring an
    already-shipped, already-tested quick fix to share code with a new caller.
  - `setVersion(project, candidate, newVersion)` — mutates the `<version>` tag's text
    content.
  - `setReason(project, candidate, newReason)` — creates, replaces, or removes the
    preceding reason comment, reusing `findPrecedingComment`/`createXmlComment` from
    `PomComments.kt` the same way `SuppressOverrideQuickFix` already does, but for
    arbitrary reason text instead of the suppress marker. `newReason == null` removes
    the comment entirely (if a suppress-marker comment happens to precede the entry,
    it's left alone — `setReason` is only ever invoked from the Edit dialog on a
    candidate that reached the panel in the first place, which by construction is
    never suppressed).
  - `addOverride(project, model, ga, version, reason)` — creates a new `<dependency>`
    entry (plus a preceding reason comment, if given) inside `model`'s
    `<dependencyManagement>`, using the Maven DOM API (`MavenDomProjectModel`'s typed
    accessors) rather than hand-built XML text — consistent with how the rest of this
    project reads Maven POMs, and the DOM framework auto-creates missing intermediate
    tags (`<dependencyManagement>`, `<dependencies>`) when you write through it, which
    hand-built text would have to replicate manually. Verified by a spike (see below)
    before being relied on.

- **`ManagedVersionHint.kt`** (`cloud.schneidoa.detection`) — a small function,
  `lookup(project, model, ga): ManagedVersionLookup`, that builds the module's BOM
  chain via `BomChainResolver` and calls the existing
  `BomVersionResolver.resolveManagedVersion(bomChain.map { it.bom }, ga)` — the exact
  same call `OverrideDetector` already makes, just for a GA that isn't necessarily
  declared in `dependencyManagement` yet. No new resolution logic; this is a thin
  reuse wrapper so the dialogs don't need to know about `BomChainResolver` directly.

- **`OverrideOverviewToolWindowFactory.kt`** (modified) — adds a right-click listener
  on the table (selecting the row under the cursor if it isn't already selected, then
  showing a popup menu with "Edit..." and "Remove"), and a new "+ Add" toolbar action
  next to the existing Refresh action. "Remove" shows `Messages.showYesNoDialog(...)`
  before calling `removeOverride`. Both dialogs, on success, trigger the same
  `refresh()` the toolbar's Refresh action already uses, so the table reflects the
  change immediately.

- **`AddOverrideDialog.kt` / `EditOverrideDialog.kt`** (new, `cloud.schneidoa.ui`,
  `DialogWrapper` subclasses) — Add has a module dropdown (populated from the same
  project-wide `pom.xml` discovery `ProjectOverrideScanner` already uses), a
  `groupId:artifactId` text field, a version field, and an optional reason field; the
  GA field's live hint recomputes on a short debounce (~300ms after the last
  keystroke) via `ManagedVersionHint`, run off the EDT the same way the panel's
  `refresh()` already runs scans off the EDT. Edit has the module and GA fixed
  (from the row being edited), version and reason pre-filled and editable, and the
  hint computed once when the dialog opens (module+GA are immutable in Edit, so there's
  nothing for it to react to). Both dialogs disable their confirm button until the GA
  is well-formed (`groupId:artifactId`, exactly one colon) and the version is
  non-blank.

## Spike: verify DOM-API auto-creation of missing tags

Before building `addOverride` for real, a spike (mirroring Phase 3's
PSI-Mutation-Spike) confirms — in this project's own test sandbox — that writing
through `MavenDomProjectModel`'s dependencyManagement/dependencies accessors on a POM
that has neither tag yet actually creates both, correctly nested, with reasonable
formatting; and that setting a newly-created `MavenDomDependency`'s
groupId/artifactId/version fields produces the expected literal XML. If the DOM API
doesn't behave as expected, this spike is where that gets discovered — before the real
`addOverride` implementation and its tests are built on top of an unverified
assumption, not after.

## Error Handling

- Add/Edit confirm buttons are disabled (not just silently no-op) until inputs are
  valid — no partial/malformed writes are possible through the dialog.
- If `ManagedVersionHint.lookup` throws (e.g. local repository path unavailable), the
  hint area shows a neutral "Could not check BOM" message rather than propagating the
  exception into the dialog's UI thread.
- Remove requires explicit confirmation (see Scope) precisely because it's the one
  destructive action in this set with no dialog of its own otherwise — Edit and Add
  are already reviewed by the user through their dialog's own fields before commit.

## Testing

- `OverrideMutationsTest` (`BasePlatformTestCase`, same fixture pattern as the existing
  quick-fix tests): one test per function — `removeOverride` deletes the tag,
  `setVersion` changes only the version text, `setReason` covers add/replace/remove-
  comment, `addOverride` produces a new entry with correct GA/version/comment,
  including the case where `<dependencyManagement>` doesn't exist yet in the target
  POM (this is the case the spike de-risks).
- `ManagedVersionHintTest`: confirms it returns the same `Found`/`NotFound` shape
  `BomVersionResolver` itself returns, for both a GA that's managed and one that
  isn't, using the existing fixture repo.
- No automated tests for `OverrideOverviewToolWindowFactory`'s context menu wiring or
  the two dialog classes — consistent with this project's established precedent for
  Swing-only glue in this file; verified manually via `runIde`.
