# Maven Dependency Overrides — Phase 7: Version Relation & BOM Chain View — Design

## Problem

Two related shortcomings, both surfaced by looking at a real panel row:

**The Status column carries no information.** It shows `Confirmed` or
`Inconclusive` — where `Confirmed` only means "we successfully resolved the BOM
chain", not anything about whether the developer should act. Worse, it collapses
two opposite situations into one label:

- The pinned version is **above** what the BOM manages (e.g. declared `2.21.3`,
  managed `2.21.2`). This is the plugin's motivating case *before* the BOM catches
  up: the pin is actively raising the version and is still doing its job. Nothing
  should be removed.
- The pinned version is **below** what the BOM manages. The pin is holding the
  dependency back under the BOM's floor — the opposite problem, and a potentially
  serious one, since it can mean a downgrade past a fix the BOM already provides.

A developer reading `Confirmed` on either row learns nothing about which of these
they are looking at.

**The genuinely actionable case is invisible.** `OverrideDetector.evaluate`
returns `null` when the declared version equals the managed version, so a pin that
the BOM has exactly caught up to — the redundant pin this plugin exists to find —
never appears in the panel or the editor at all. The one row that should say
"this can go now" is the one row that is filtered out.

Separately, the panel shows the BOM provenance chain as a single truncated string
(`spring-boot-starter-parent → spring-boot…`), with no way to see the full picture:
which BOMs were considered, which resolved, which one actually won, and what the
losers manage the artifact at.

## Goal

Replace the Status column with a verdict the developer can act on, make redundant
pins visible, and give each panel row a way to inspect where its version comes
from — both within the BOM chain the plugin itself computes, and in the wider
transitive dependency graph.

## Scope

- **Version relation.** Compare the declared version against the BOM-managed
  version using Maven's own version ordering, and carry the result through
  detection into both the panel and the editor inspection.
- **Redundant pins become visible.** `evaluate` stops discarding the
  declared-equals-managed case.
- **Status column** communicates the relation, with a short label plus an icon and
  the full explanation as a tooltip.
- **Context menu: Show BOM Chain** — a dialog rendering the full BOM chain for that
  row.
- **Context menu: Analyze Dependencies** — hands the artifact coordinate to
  IntelliJ's own Maven Dependency Analyzer.
- **Out of scope:**
  - Any change to how BOM chains are resolved, or to the local-only,
    never-hit-the-network rule. This phase reads the same data differently; it
    resolves nothing new.
  - Computing a transitive dependency tree ourselves. The Dependency Analyzer
    already does this and is bundled with the Maven support this plugin already
    depends on; reimplementing it against `MavenProjectsManager`'s resolved model
    would duplicate a well-tested IDE feature.
  - Sorting or filtering the panel by relation. Useful, but not requested, and the
    table has no filtering infrastructure yet.
  - A dedicated "raise this pin to the BOM version" quick fix that rewrites the
    `<version>` in place. The "Behind BOM" case is remediated by the existing
    *remove* fix, which lets the BOM's version apply without hardcoding it into
    the POM a second time — a pin restating the BOM's current version is exactly
    the redundant pin this plugin flags, so writing one would be self-defeating.

## Architecture

### `resolver/VersionRelation.kt` (pure JVM, no Platform APIs)

```kotlin
enum class VersionRelation { SAME, NEWER, OLDER, INCOMPARABLE }

fun compareDeclaredToManaged(declared: String, managed: String): VersionRelation
```

Backed by `org.apache.maven.artifact.versioning.ComparableVersion`, so ordering
matches what Maven itself would do (`1.0` < `1.0.1`, `1.0-alpha` < `1.0`,
`2.21.10` > `2.21.9` — the last one being exactly where a naive string compare
gets it wrong).

`maven-artifact` is currently only present transitively via
`maven-model-builder`. Because this phase imports from it directly, it gets an
explicit entry in `gradle/libs.versions.toml` rather than relying on a transitive
dependency staying where it is.

**`INCOMPARABLE` is the safety valve**, and exists for two inputs where
`ComparableVersion` will happily return an answer that means nothing:

- **Unresolved properties.** `DependencyManagementScanner` resolves versions via
  `MavenPropertyResolver.resolve`, but when a property is undefined that call
  returns the raw `${...}` text. Comparing `${jackson.version}` against `2.21.2`
  produces a confident-looking ordering derived from nonsense.
- **Version ranges.** `[1.0,2.0)` is a valid `<version>` in Maven and has no single
  point on the version line to compare against.

Both are detected before comparison (presence of `${` / range bracket syntax) and
short-circuit to `INCOMPARABLE`, which never produces an actionable verdict. This
is the same "miss rather than false-safe" principle that drives the existing
`Inconclusive` case: a wrong "safe to remove" can silently reintroduce a patched
CVE, so the plugin declines to answer rather than guessing.

### `detection/OverrideDetector.kt`

`DetectedOverride.Confirmed` gains one field:

```kotlin
data class Confirmed(
    val candidate: OverrideCandidate,
    val bomVersion: String,
    val declaredInBom: Gav,
    val managedByChain: List<String>,
    val relation: VersionRelation,      // new
) : DetectedOverride()
```

The name `Confirmed` stays. It has always meant "the BOM chain resolved cleanly and
we know what it manages" — that is still exactly what it means, and the new field
is what varies within it. Renaming would churn tests for no gain in accuracy.

`evaluate` changes in one place: the `else -> null` branch for equal versions
becomes `Confirmed(relation = SAME)`. Everything else — the `Inconclusive`
construction, the `uncheckedBoms` handling, the suppression filter — is untouched.

**Consequence, accepted deliberately:** every `dependencyManagement` entry that any
BOM in the chain also manages now produces a row, including all the ones that
merely restate the BOM. In a Spring Boot project this can be a lot of rows at once.
This is wanted: those pins are all genuinely removable, and the panel is the
inventory view where you would go to find them.

### `detection/OverrideFormatting.kt`

`statusText` is replaced by a richer verdict carrying a short label, an icon, and a
full explanation:

| Case | Icon | Label | Explanation (tooltip / inspection text) |
|---|---|---|---|
| `SAME` | `AllIcons.General.GreenCheckmark` | Redundant | The BOM chain already manages this at the same version — the override can be removed. |
| `NEWER` | `AllIcons.General.ArrowUp` | Ahead of BOM | The override raises this above the BOM's version. Still effective; keep it while the pin is needed. |
| `OLDER` | `AllIcons.General.Warning` | Behind BOM | The override holds this **below** the version the BOM provides. |
| `INCOMPARABLE` | `AllIcons.General.Information` | Not comparable | The declared version is a property or range that can't be ordered against the BOM's version. |
| `Inconclusive` | `AllIcons.General.ShowWarning` | Inconclusive (N BOMs) | Unchanged from today. |

All five constants verified present in `AllIcons` on the pinned platform. The Status
column gets a `DefaultTableCellRenderer` carrying icon + label, with the explanation
as the cell tooltip; the table model stores the verdict object rather than a string,
so the renderer and the inspection read the same source.

### `detection/OverrideInspection.kt`

The inspection reports the same three-way distinction, but does **not** warn on
every case:

- `SAME` → weak warning, with `RemoveOverrideQuickFix` + `SuppressOverrideQuickFix`.
  This is the redundant pin; removal is the point.
- `OLDER` → weak warning, with `RemoveOverrideQuickFix` + `SuppressOverrideQuickFix`.
  Removing the pin here does change the effective version — it lets the BOM's
  newer one apply — and that is the point rather than a hazard: a pin sitting
  below its BOM is nearly always one that was set once and never revisited, and
  is now holding the dependency back below what Spring Boot (or whichever BOM)
  already ships. Offering only "suppress" would leave the developer with a
  warning and no way to act on it. The inspection message says explicitly that
  removal lets the newer BOM version apply, so the fix is not silently
  build-affecting.
- `NEWER` → **no editor problem at all.** This pin is not stale; it is doing the
  job it was written for. Warning about it is precisely the noise that motivated
  this phase. It remains visible in the panel, which is an inventory rather than a
  list of complaints.
- `INCOMPARABLE` → no editor problem. No verdict was reached, so there is nothing
  to say.
- `Inconclusive` → unchanged.

### `ui/BomChainDialog.kt` + `detection/BomChainReport.kt`

**Show BOM Chain** opens a dialog with a tree of the complete BOM chain in
precedence order:

```
com.fasterxml.jackson.core:jackson-databind
declared 2.21.3 in camperchat

  spring-boot-starter-parent
    spring-boot-dependencies
      com.fasterxml.jackson:jackson-bom:2.21.2
        manages this at 2.21.2          ← wins
      org.example:some-other-bom:1.4.0
        does not manage this
      org.example:unresolvable-bom:2.0
        could not be resolved locally
```

The data is **re-resolved on demand** for the one selected row rather than carried
in every scan result. `DetectedOverride` deliberately keeps only the winning BOM
and the unchecked ones; widening it to hold the whole per-entry chain would make
every project scan heavier to serve a dialog that is opened rarely.

The tree-building lives in `BomChainReport.kt` as a pure function over
`List<BomImport>` + `BomVersionResolver` results, returning a plain data model.
The dialog only renders it. This keeps the interesting logic testable without a
platform fixture, matching how `resolver/` is structured.

For an `Inconclusive` row the same view is what makes the verdict legible: the
unresolvable BOMs are exactly the reason no answer could be given, and here they
are named.

### `ui/OverrideOverviewToolWindowFactory.kt`

Two context menu entries added alongside the existing Edit / Remove:

- **Show BOM Chain** — always available; the plugin can always describe what it
  resolved, even when the answer is "not much".
- **Analyze Dependencies** — calls
  `DependencyAnalyzerManager.getInstance(project).getOrCreate(MavenUtil.SYSTEM_ID)
  .setSelectedDependency(module, DAArtifact(groupId, artifactId, version))`.

  The Dependency Analyzer reads `MavenProjectsManager`'s resolved model, so unlike
  every other feature in this plugin it **requires a completed Maven sync**. When
  `MavenProjectsManager.findProject(...)` returns null for the row's module, the
  menu entry is shown but disabled, with a tooltip explaining that it requires a
  completed Maven sync. Opening an empty analyzer would look like the plugin was
  broken; hiding the entry would leave the developer wondering where it went.

  Resolution happens off the EDT — this is the one context-menu action that
  touches the synced project model, and the panel already has the
  `executeOnPooledThread` + `SwingUtilities.invokeLater` pattern established by
  `refresh()` and `openAddDialog()`.

## Testing

Following the project's existing split:

**Pure JVM (`resolver/`)**
- `VersionRelationTest` — ordering cases including the ones a string compare gets
  wrong (`2.21.10` vs `2.21.9`), qualifier ordering (`1.0-alpha` < `1.0`), and both
  `INCOMPARABLE` triggers (unresolved property, version range).

**Pure logic (`detection/`)**
- `BomChainReportTest` — report model over a synthetic chain: a winning BOM, a
  non-managing BOM, and an unresolvable BOM all rendered in the right order with
  the right annotations.

**Platform (`detection/`, `BasePlatformTestCase`)**
- `OverrideDetectorTest` — the `SAME` case, which today produces no result at all,
  now produces a `Confirmed` with `relation = SAME`; plus `NEWER` and `OLDER`
  against fixture BOMs.
- `OverrideInspectionTest` — `SAME` registers a problem offering both fixes;
  `OLDER` registers a problem offering both fixes; `NEWER` registers **no**
  problem. That last assertion is the one guarding the noise-reduction decision, so
  it is worth stating explicitly rather than leaving as an absence.

Fixture BOMs go under the existing `src/test/resources/fixtures/local-repo/` layout.

## Open assumptions

- **`Ga` is still groupId+artifactId only.** The version relation inherits the
  known v1 gap documented in `MavenCoordinates.kt`: an artifact managed at
  different versions under different classifiers can be compared against the wrong
  BOM entry. Unchanged by this phase, not fixed by it.
- **The Dependency Analyzer's own behavior is not wrapped or tested.** It is a
  bundled IDE feature; this phase only hands it a coordinate.
