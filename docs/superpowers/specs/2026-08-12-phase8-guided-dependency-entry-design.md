# Maven Dependency Overrides — Phase 8: Guided Dependency Entry — Design

## Problem

Adding an override means typing the coordinate by hand into a single
`Group ID : Artifact ID` field, which the dialog splits on `:`. Three things are
wrong with that:

**The field asks for something nobody remembers.** Developers know
`jackson-databind`; almost nobody types `com.fasterxml.jackson.core` from memory.
The field demands both halves at once, in the right order, with the right
separator, before it will enable OK.

**Nothing is offered, though everything is known.** The plugin already resolves
the module's BOM chain and builds each BOM's fully-interpolated effective model —
which contains the complete `dependencyManagement` list. Every coordinate the user
could sensibly want is already in memory at the moment the dialog asks them to
type it blind. `BomVersionResolver` simply projects one lookup out of that data and
discards the rest.

**A typo produces silence, not an error.** Mistype the groupId and the override is
created, resolves against no BOM, and `OverrideDetector` drops it — correctly, since
nothing manages it. The user gets a `dependencyManagement` entry that does nothing
and no indication why.

## Goal

Split the coordinate into two fields and make both of them offer what the module's
BOM chain actually manages, so that adding an override is a matter of recognition
rather than recall — without ever restricting entry to the offered set.

## Scope

- **Two fields**, Group ID and Artifact ID, replacing the combined one.
- **Completion plus a dropdown** on each: typing filters, a button opens the full
  list with a search field.
- **The two fields inform each other** — a chosen group narrows the artifact list;
  an unambiguous artifact fills in its group.
- **Version prefill** from the BOM's managed version, with a live verdict below the
  fields saying what the entered version would mean.
- **Out of scope:**
  - `EditOverrideDialog`. It deliberately does not change an entry's
    `groupId:artifactId` — that is the entry's identity, and changing it is a
    remove-and-add (established in the Phase 6 design). With no GA to enter, there
    is nothing to complete.
  - IntelliJ's Maven repository index, and any other remote or index-backed source.
    Detection is local-only by design; a completion source that needs a downloaded
    index would make the dialog's suggestions inconsistent with what the plugin can
    actually reason about, and would put a network dependency into a tool whose
    central claim is that it has none.
  - Validating that a hand-typed coordinate exists anywhere. Same reason as Phase 6:
    there is no offline way to check, and guessing is worse than not answering.
  - Remembering recently used coordinates across dialog invocations.

## Architecture

### `resolver/BomVersionResolver` — a catalog projection

```kotlin
data class ManagedVersionCatalog(
    val versions: Map<Ga, String>,
    val uncheckedBoms: List<Gav>,
)

fun collectManagedVersions(bomsInPrecedenceOrder: List<Gav>): ManagedVersionCatalog
```

Same walk as `resolveManagedVersion`, same precedence rule — the first BOM in the
list that manages a `Ga` wins, later ones do not overwrite it — and the same
`uncheckedBoms` contract, so a caller can still tell "the BOM chain doesn't have
this" apart from "we couldn't read part of the chain".

The one real difference: it cannot stop early. `resolveManagedVersion` returns as
soon as a BOM matches, usually the first; the catalog has to build every BOM's
effective model. A chain is typically one to five BOMs, and this runs once per
module selection on a pooled thread, so the cost is acceptable — but it is a real
difference and the reason this is a separate function rather than a rewrite of the
existing one. Detection stays on the cheap path.

### `detection/DependencyCandidates` — the coupling logic, deliberately Swing-free

```kotlin
class DependencyCandidates(private val catalog: ManagedVersionCatalog) {
    fun groups(): List<String>
    fun artifactsIn(groupId: String): List<String>
    fun allCoordinates(): List<Ga>
    fun uniqueGroupFor(artifactId: String): String?
    fun managedVersionOf(ga: Ga): String?
    val isComplete: Boolean
}
```

Every rule about how the two fields relate lives here, not in the dialog:

- `artifactsIn` is the narrowing behind "group chosen → artifact list filtered".
- `uniqueGroupFor` returns the group only when exactly one group in the catalog
  offers that artifactId, and `null` when zero or several do — which is what makes
  auto-filling the group safe rather than a guess.
- `isComplete` is false when any BOM in the chain could not be resolved.

This class exists because this project has repeatedly paid for putting logic in the
untested Swing layer. `OverrideOverviewToolWindowFactory` is hand-verified only, and
that is affordable exactly as long as it contains nothing worth testing. The field
coupling is worth testing, so it does not go there.

### `ui/AddOverrideDialog` — two guided fields

Each field is a `TextFieldWithAutoCompletion<String>` wrapped in a
`ComponentWithBrowseButton` whose icon is `AllIcons.General.ArrowDown`. Typing filters
through the field's own completion; the button opens a
`JBPopupFactory.createPopupChooserBuilder` popup with `setFilterAlwaysVisible(true)`,
which is what keeps a ~1400-entry artifact list usable. Both APIs were verified
present on the pinned platform.

The two fields are coupled asymmetrically, because the ambiguity is asymmetric:

| Group field | Artifact dropdown lists | Choosing an entry |
|---|---|---|
| empty | full `groupId:artifactId` strings | fills **both** fields |
| set to a known group | bare artifactIds in that group | fills the artifact field |
| set to an unknown group | all artifactIds | fills the artifact field |

Typing an artifactId with the group field empty fills the group in as soon as
`uniqueGroupFor` returns non-null. Ambiguous artifactIds leave it empty rather than
picking one — the dropdown already shows the full coordinate in that case, so the
user has a way through that does not involve the plugin guessing.

The catalog loads per selected module, off the EDT, and is cached for the lifetime
of the dialog. Changing the module reloads it. Until it arrives the fields accept
free text as they do today; the dropdown buttons are disabled and the hint reads
that the BOM chain is still being read.

### Version prefill, and the trap it would otherwise set

Prefilling the version with what the BOM already manages would, taken alone, lead
the user straight into creating a **redundant** pin — the exact thing this plugin
flags and offers to remove. Filling a field with a value and then complaining about
that value is not a feature.

So the prefill comes with a live verdict, computed with `compareDeclaredToManaged`
from Phase 7 — the same comparison the inspection uses:

| Entered vs managed | Hint |
|---|---|
| identical | Same version the BOM already manages — this override would be redundant |
| above | Raises this above the BOM's *X* |
| below | Holds this below the BOM's *X* |
| not comparable | Can't be compared with the BOM's *X* |
| not in the catalog | No BOM in this module's chain manages this |

The prefill stops as soon as the user types in the version field — a
`versionEditedByUser` flag, not a value comparison, so that deliberately typing the
BOM's own version is still respected rather than being treated as "untouched".

The dialog does **not** block OK on a redundant pin. There are legitimate reasons to
restate a BOM's version — pinning against a future BOM upgrade, for one — and this
plugin's job is to tell the user what a thing means, not to refuse it.

### Incompleteness is stated, not implied

When any BOM in the chain cannot be resolved locally, the catalog is partial. A
dropdown that silently omits entries reads as "this artifact isn't managed", which
is precisely the false-negative this project's "miss rather than false-safe" rule
exists to prevent — here inverted into the UI.

So when `isComplete` is false, the hint area says so, naming the count of
unresolvable BOMs, and it says so whether or not the user has typed anything. The
suggestions remain available; they are simply labelled as possibly incomplete.

## Testing

**Pure JVM (`resolver/`)**
- `collectManagedVersions` against the fixture repository: every managed GA is
  present with its interpolated version, first-BOM-wins precedence holds when two
  BOMs manage the same GA, and an unresolvable BOM lands in `uncheckedBoms` without
  discarding the entries gathered from the readable ones.

**Pure logic (`detection/`)**
- `DependencyCandidates`: groups are distinct and sorted; `artifactsIn` narrows
  correctly and returns empty for an unknown group; `uniqueGroupFor` returns the
  group for a unique artifactId, `null` for an ambiguous one, and `null` for an
  unknown one; `isComplete` tracks `uncheckedBoms`.

**Not tested**
- `AddOverrideDialog` itself, matching the precedent set for the tool window in
  Phase 4. This is only defensible because the coupling rules moved into
  `DependencyCandidates` — the dialog is left with widget wiring.

## Open assumptions

- **`Ga` is still groupId+artifactId only.** A BOM managing the same artifactId
  under different classifiers collapses to one catalog entry. Same known v1 gap
  documented in `MavenCoordinates.kt`; this phase inherits it rather than widening
  it.
- **Catalog cost scales with chain length, not project size.** If a chain ever
  contained dozens of BOMs the per-module load would become noticeable. Nothing in
  the wild suggests that today, and the load is already off the EDT with the
  dialog usable meanwhile, so it is not designed around.
