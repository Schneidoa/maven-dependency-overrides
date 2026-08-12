# Maven Dependency Overrides — Phase 5: BOM Provenance Chain — Design

## Problem

When the plugin confirms an override (`DetectedOverride.Confirmed`), both the inline
editor warning and the Override Overview tool window (Phase 4) currently only name the
BOM that manages the dependency — e.g. `com.fasterxml.jackson:jackson-bom:2.21.2`. For a
real Spring Boot project, that BOM is rarely something the developer wrote themselves:
it's reached transitively, e.g. `camperchat`'s own `pom.xml` only declares `<parent>
spring-boot-starter-parent</parent>`, and `jackson-bom` is actually imported three levels
up, inside `spring-boot-dependencies`'s own `<dependencyManagement>`. The developer sees
a BOM name they never wrote and has to go spelunking through the parent chain themselves
to understand *why* their override is redundant.

## Goal

Show the full parent-chain path from the module's own POM down to whichever ancestor
POM actually declares the managing BOM import — e.g. `spring-boot-starter-parent →
spring-boot-dependencies → com.fasterxml.jackson:jackson-bom:2.21.2` — in both the
inline editor warning and the Override Overview tool window.

## Scope

- Applies only to `DetectedOverride.Confirmed` results. `Inconclusive` results have no
  single managing BOM to attribute (that's the whole reason they're inconclusive), so
  no chain is computed or shown for them.
- Tracks provenance through the **parent chain only** (`<parent>` → its own `<parent>`
  → …), the same traversal `BomChainResolver` already performs for `resolveParent`.
- **Explicitly out of scope:** provenance *inside* a single BOM's own effective model.
  If BOM A's own `<dependencyManagement>` imports BOM B (a nested import resolved by
  the real Maven model builder via `BomEffectiveModelResolver`), and B is what actually
  manages the artifact, the chain still ends at A — the outer BOM — exactly as today
  (`DetectedOverride.Confirmed.declaredInBom` already only ever names the outer BOM,
  never a nested one, since `BomVersionResolver` matches against the fully-merged
  effective model without tracking which nested import contributed which entry). This
  is an existing, unchanged limitation of relying on `maven-model-builder` for BOM
  merging, not a regression introduced by this phase.
- Chain links for parent-chain hops are displayed by `artifactId` only (matching the
  precedent already set by `ProjectOverrideScanner.moduleLabelFor` in Phase 4), not
  full GAV — simpler, and full GAV resolution for a POM's own groupId/version would
  need to handle Maven's parent-inheritance-of-groupId/version rules, which isn't
  needed just for a human-readable label.

## Architecture

### `BomChainResolver`

`resolveBomChain(model, project): List<Gav>` becomes `resolveBomChain(model, project):
List<BomImport>`, where:

```kotlin
data class BomImport(val bom: Gav, val declaredVia: List<String>)
```

`declaredVia` is the ordered list of `artifactId`s of the modules climbed from the
*original* model (exclusive) down to (and including) the module whose own
`<dependencyManagement>` declares this particular `<scope>import</scope>` entry. Empty
when the BOM is declared directly in the original model itself.

The existing traversal loop already visits each level of the parent chain in order;
this phase adds path-tracking to it — at the point `importedBomsOf(current)` is called,
the accumulated path *up to `current`* is attached to every BOM found there, then the
path grows by one artifactId before climbing to the next parent.

### `OverrideDetector`

`BomVersionResolver.resolveManagedVersion` keeps its existing signature
(`List<Gav>`) — `OverrideDetector` passes `bomChain.map { it.bom }`, unchanged from
today. After a `ManagedVersionLookup.Found(version, declaredIn, unchecked)`, the
provenance for `declaredIn` is looked up via
`bomChain.firstOrNull { it.bom == declaredIn }?.declaredVia ?: emptyList()` (first
match — a BOM re-imported at multiple chain depths with the exact same GAV is a
theoretical, functionally-redundant edge case not worth disambiguating further) and
attached to a new field on `DetectedOverride.Confirmed`:

```kotlin
data class Confirmed(
    val candidate: OverrideCandidate,
    val bomVersion: String,
    val declaredInBom: Gav,
    val managedByChain: List<String>
) : DetectedOverride()
```

### Formatting

A new function in `cloud.schneidoa.detection.OverrideFormatting`:

```kotlin
fun managedByChain(declaredVia: List<String>, bom: Gav): String =
    (declaredVia + bom.toString()).joinToString(" → ")
```

Degenerates cleanly to just `bom.toString()` when `declaredVia` is empty (declared
directly in the module itself, no parent hops).

### UI

- **Inline editor warning** (`OverrideInspection`): message changes from
  `"Overrides $declaredInBom which already manages this dependency at $bomVersion"` to
  `"Overrides ${managedByChain(...)} which already manages this dependency at
  $bomVersion"` — same sentence shape, richer subject.
- **Override Overview tool window** (`OverrideOverviewToolWindowFactory`): a new
  "Managed By" column (5th column, after "Status") shows the same chain string for
  `Confirmed` rows. `Inconclusive` rows show an empty string in that column (no
  single BOM to attribute, consistent with the scope decision above).

## Testing

- `BomChainResolverTest`: all existing tests updated to assert against `BomImport`
  values (`bom` + `declaredVia`) instead of bare `Gav`. New test(s) covering a
  multi-level parent chain (module → parent → grandparent, where the grandparent
  declares the import) asserting `declaredVia == listOf("parent-artifact-id",
  "grandparent-artifact-id")`, and confirming a directly-declared BOM still gets
  `declaredVia == emptyList()`.
- `OverrideDetectorTest`: `Confirmed` assertions extended to check `managedByChain`
  matches the expected parent-chain artifactIds for the existing multi-module test
  fixtures (parent/child pattern already used by `` `test confirms an override in a
  child module against a BOM imported by its parent` ``).
- `OverrideInspectionTest`: assert the highlight description contains the full chain
  string, not just the BOM name.
- `OverrideFormattingTest`: new tests for `managedByChain` — empty `declaredVia`
  (direct), single-hop, multi-hop.
- No new test needed for the panel's "Managed By" column specifically (consistent with
  Phase 4's precedent of not unit-testing the Swing table itself) — verified manually
  alongside the rest of the tool window.
