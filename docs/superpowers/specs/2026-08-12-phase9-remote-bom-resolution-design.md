# Maven Dependency Overrides — Phase 9: Remote BOM Resolution — Design

## Problem

Every answer this plugin gives rests on reading BOM POMs out of the local Maven
repository. When a POM in the chain is not there, the plugin correctly refuses to
answer — `Inconclusive` — but that refusal has become the common case rather than
the edge case:

**A fresh checkout answers nothing.** Before the first Maven sync completes, no BOM
in the chain is on disk. `ProjectOverrideScanner` deliberately walks `FilenameIndex`
rather than `MavenProjectsManager.projects` so that it works before sync — but the
resolver behind it cannot, so the tool window fills with `Inconclusive` rows.

**A pruned or partial local repository degrades silently.** Corporate BOMs served
from an internal Nexus/Artifactory are present only after something has pulled them.
Nothing in the plugin can pull them.

**The Add dialog's suggestions shrink without saying why.** `DependencyCandidates`
reports `unreadableBomCount`, but a user seeing a short artifact list has no way to
act on it. The catalog is incomplete precisely when the BOM chain is most worth
consulting.

**A missing parent POM is worse than incomplete — it is invisible.**
`BomChainResolver.resolveParentViaLocalRepository` returns `null` when the parent's
POM is not on disk, which ends the `while` loop that walks the parent chain. The
chain is silently truncated: no `uncheckedBoms` entry, no failure record, no
exception. Every BOM imported by a parent above the break simply does not exist as
far as detection is concerned. This is the one place in the codebase where
"miss rather than false-safe" does not hold — a truncated chain can produce a
confident `NotFound` (dropping the override from the report entirely) on evidence
that was never gathered.

The information needed to fix all four is already configured in the user's Maven
`settings.xml` and already reachable through the IDE's bundled Maven integration.
The plugin just never asks for it.

## Goal

Let the plugin fetch a missing BOM or parent POM from the remote repositories Maven
itself would use — mirrors, proxies and credentials included — so that
`Inconclusive` means "genuinely unverifiable" rather than "not downloaded yet".
And close the silent parent-chain truncation, so that an unverifiable chain always
says so.

## Scope

- **Fetching `.pom` artifacts only.** Never jars, sources or javadoc.
- **Through the IDE's Maven integration**, not through a hand-rolled repository
  client — mirror selection, proxies, and authentication (including encrypted
  credentials from `settings-security.xml`) are applied by Maven's own machinery.
- **Enabled for**: the tool window's background scan, the Add dialog's catalog, the
  Edit dialog's managed-version hint, and the "Show BOM Chain" report.
- **Disabled for**: `OverrideInspection`. The editor stays offline, always.
- **Respects `MavenGeneralSettings.isWorkOffline()`** as an unconditional stop.
- **Fixes the parent-chain truncation** so that a break in the chain surfaces as
  `uncheckedBoms` rather than as silence.
- **Out of scope:**
  - The Version Advisor (`spring-boot-dependencies` has a newer release; the
    override could be dropped by bumping the BOM instead). That reads
    `maven-metadata.xml` and asks "which versions exist", a different data source
    with different failure modes and its own caching problem. Its own spec.
  - IntelliJ's Maven repository *index*, and index-backed completion. Fetching a
    POM whose coordinate we already know is a different thing from searching for
    coordinates we do not.
  - Validating that a hand-typed coordinate exists anywhere.
  - Any persistent cache of fetch results across IDE sessions.

### On reversing a stated non-goal

The Phase 8 design lists "any other remote or index-backed source" as out of scope,
reasoning that it "would put a network dependency into a tool whose central claim is
that it has none." That claim is narrowed here, deliberately, and the narrowing is
the load-bearing part:

The claim worth keeping is **that detection never needs the network to be correct**
— the editor answers from disk, an unreachable repository degrades to `Inconclusive`
rather than to a wrong answer, and no verdict is ever derived from a network
response. That claim survives intact: `OverrideInspection` gains no network path at
all, and a failed fetch changes nothing about how an answer is classified.

The claim being dropped is the stronger one, that the plugin never touches the
network under any circumstance. It was never worth the cost it imposed: refusing to
fetch a POM that Maven would fetch does not make the tool safer, it just makes it
answer "I don't know" to questions it could have answered.

## Architecture

The obvious design — a `PomProvider` seam that downloads on demand, inside the model
build — does not work, for two independent reasons. Both shape what follows.

**Threading.** A freshly downloaded file is invisible to the VFS until refreshed,
and `BomChainResolver` needs the VFS (`LocalFileSystem.findFileByIoFile` →
`MavenDomUtil.getMavenDomProjectModel`). A synchronous VFS refresh under the read
lock is forbidden, and every call site here resolves inside `ReadAction.compute`
(`OverrideOverviewToolWindowFactory.kt`, `AddOverrideDialog.kt`,
`EditOverrideDialog.kt`). Network I/O and VFS refresh must happen outside the
read action.

**Visibility.** `BomModelResult.Failure` carries the GAV of the BOM whose build
failed, not the GAV of the POM that was actually missing. For the `broken-parent-bom`
fixture, the failure names `com.example:broken-parent-bom:1.0.0` — which is present
on disk — while the thing to fetch, `com.example:does-not-exist-parent:1.0.0`, is
named only inside an exception message string. An on-demand provider would therefore
re-fetch an artifact it already has and make no progress.

### The shape: report, then fetch, then retry

1. **Resolve (read action, offline).** Existing code path, unchanged in behavior —
   but it now *reports* each POM it could not find.
2. **Fetch (pooled thread, outside the read action).** The reported GAVs are
   downloaded and the VFS refreshed.
3. **Repeat**, bounded at three rounds. A round is only worth running if the
   previous one downloaded something new; a newly fetched BOM can reveal nested
   imports and parents that were not visible before.

The bound is a termination guarantee, not a tuning parameter: each round either
strictly grows the set of locally available POMs or ends the loop.

### `resolver/` — a reporting callback, and nothing else

The layer stays free of IntelliJ Platform APIs. The only change is an optional
callback, defaulting to a no-op so that every existing construction and every
existing test keeps its current behavior:

```kotlin
class LocalRepositoryModelResolver(
    private val localRepositoryDir: File,
    private val onMissingPom: (Gav) -> Unit = {},
) : ModelResolver
```

`resolve` invokes it immediately before throwing `UnresolvableModelException`.
This is the highest-value seam in the codebase for this purpose: the model builder
calls it for parents *and* nested BOM imports, so it sees exactly the transitive
POMs whose absence causes the failure — the ones that are invisible today.

`BomEffectiveModelResolver` and `BomVersionResolver` take the same parameter and
thread it down; `BomEffectiveModelResolver` also reports its own top-level miss.

`newCopy()` must carry the callback across, or the model builder's internal copies
report nothing.

### `detection/BomChainResolver` — the truncation fix

`resolveBomChain` currently returns `List<BomImport>`, which has no channel for
"the walk stopped early". It gains one:

```kotlin
data class BomChain(
    val imports: List<BomImport>,
    val truncatedAt: List<Gav>,
)
```

`resolveParentViaLocalRepository` records the parent GAV it could not read instead of
returning `null` into silence. `OverrideDetector` merges `truncatedAt` into the
`uncheckedBoms` it already carries, so a broken parent chain yields `Inconclusive`.

This is a correctness fix that stands on its own — it closes a false-confidence path
that exists today, with or without any remote fetching. Remote fetching then turns
most occurrences of it from a refusal into an answer.

### `detection/RemotePomFetcher` — the only class that touches the network

```kotlin
class RemotePomFetcher(private val project: Project) {
    /** Returns the subset of [gavs] whose POM is now present locally. */
    fun fetch(gavs: Set<Gav>): Set<Gav>
}
```

- `ThreadingAssertions.assertBackgroundThread()` on entry. This class must never run
  on the EDT and must never run under a read action.
- `MavenGeneralSettings.isWorkOffline()` → return the empty set immediately.
- Embedder via
  `MavenProjectsManager.getInstance(project).embeddersManager.getEmbedder(MavenEmbeddersManager.FOR_DEPENDENCIES_RESOLVE, project.basePath)`.
  The `(Key, String workingDirectory)` overload is chosen over `(MavenProject, Key)`
  precisely because it needs no synced `MavenProject` — the fresh-checkout case is
  the one this feature most needs to serve. Released in a `finally`.
- **Only concrete coordinates are fetched.** `MavenPropertyResolver.resolve` returns its
  input unchanged until Maven sync has run, so before the first sync a BOM or parent
  declared at `${spring-boot.version}` arrives with the placeholder intact. A
  `Gav.isConcrete()` filter drops those: they are coordinates not yet known, not misses
  that can be downloaded. This bounds the feature honestly — on a completely unsynced
  project, property-versioned BOM imports stay `Inconclusive` no matter how reachable
  the repository is, and only literal-versioned ones can be recovered.
- One `MavenArtifactResolutionRequest` per GAV, wrapping
  `MavenArtifactInfo(groupId, artifactId, version, "pom", null)`, with the repository
  list from `MavenProjectsManager.getRemoteRepositories()`; if that is empty (project
  not yet synced), fall back to Maven Central.
- `resolveArtifacts(requests, progressReporter = null, MavenLogEventHandler)` is a
  `suspend` function, invoked through `runBlockingCancellable`. The reporter parameter
  is nullable and its type is `@ApiStatus.Experimental`, so it stays null.
  `MavenLogEventHandler` is a Kotlin `object`, so it is written bare from Kotlin — no
  `.INSTANCE` suffix.
- **Snapshot freshness is not handled here.** `RemotePomFetcher` never reads
  `MavenGeneralSettings.isAlwaysUpdateSnapshots()` and passes no snapshot policy of its
  own; the embedder resolves under whatever policy the IDE's own Maven settings give it.
  The case this feature exists to serve is a POM that is absent, not one that is stale
  (see the open assumption on `updateSnapshots` below).
- Downloaded files are VFS-refreshed before returning.

Every API on that path was audited against `@ApiStatus.Internal` on the **2026.2.1**
bytecode, since `./gradlew verifyPlugin` fails the build on `INTERNAL_API_USAGES` and
the Marketplace review fails with it. This mirrors the existing constraint recorded for
`AnalyzeDependencyAction`.

Of the two embedder APIs, only the older one is usable. `MavenEmbeddersManager` is
`@ApiStatus.Obsolete`, which the verifier accepts; its modern replacement
`MavenEmbedderWrappers`/`MavenEmbedderWrappersManager` is class-level
`@ApiStatus.Internal`, which it does not. Confirmed twice on the pinned 2026.2.1 jar:
by decompiling the class annotations, and by `verifyPlugin` failing with four
`INTERNAL_API_USAGES` on an implementation that tried the modern route.
Obsolete-but-allowed beats modern-but-rejected — the plugin cannot ship otherwise.
Re-check this on every platform bump rather than assuming it holds.

### `detection/MissingPomResolution` — the loop, kept out of Swing

```kotlin
fun <T> resolvingMissingPoms(
    project: Project,
    fetcher: (Set<Gav>) -> Set<Gav> = RemotePomFetcher(project)::fetch,
    maxRounds: Int = 3,
    resolve: (onMissingPom: (Gav) -> Unit) -> T,
): T
```

It runs `resolve` inside `ReadAction.compute`, collects the reported GAVs, calls
`fetcher` outside the read action, and repeats while a round fetched something new.
GAVs that failed to fetch are remembered for the duration of the call and not
retried.

Callers pass a lambda and get a result; no orchestration leaks into `ui/`. This is
the same reasoning that put `DependencyCandidates` in `detection/` rather than in
`AddOverrideDialog`. The injectable `fetcher` is what makes the loop testable with
no network.

### Wiring

| Call site | Fetches? | Why |
|---|---|---|
| `OverrideInspection` | **No** | Runs synchronously per file on every edit. Network here means editor latency and a hammered repository. |
| `ProjectOverrideScanner` (tool window) | Yes | Already on a pooled thread with a generation guard. |
| `ManagedVersionHint` (Add + Edit dialogs) | Yes | Loads once per module choice, not per keystroke. |
| `BomChainReportFactory` | Yes | Explicitly opened by the user. |

The inspection therefore keeps answering from disk alone, and keeps answering
`Inconclusive` where the tool window — having fetched — can answer confidently. That
divergence is intended: the tool window is an inventory the user consults, the
inspection is an interruption the user did not ask for.

### Failure semantics

A fetch that fails — offline, 404, unreachable, unauthenticated — leaves the BOM in
`uncheckedBoms`. The result stays `Inconclusive`. It is **never** downgraded to
"the BOM does not manage this", because that would turn an unanswerable question
into a "safe to remove" recommendation, which is the exact failure this plugin
exists to avoid.

The reason a BOM went unchecked — offline versus unreachable — is not threaded
through `DetectedOverride` or `OverrideFormatting`. It is global (Maven's own
offline setting), not per-row, so surfacing it per override would put the same
sentence on every `Inconclusive` row and bury it rather than explain it. Instead,
`OverrideOverviewToolWindowFactory` reads `MavenGeneralSettings.isWorkOffline()`
once per refresh and shows a single status line below the table when it is set and
at least one row is `Inconclusive`: stating it once is both cheaper — one setting
read instead of a reason carried through every `DetectedOverride` — and clearer than
repeating it per row. The verdict enum does not grow a case; `INCONCLUSIVE` still
covers all of it, and the unreachable-but-online case is left to the IDE log
warning `RemotePomFetcher` already emits.

### Dependencies

None added. `maven-model-builder` and `maven-artifact` remain the only Maven
libraries; everything network-facing comes from the bundled `org.jetbrains.idea.maven`
plugin, already declared in `build.gradle.kts`.

## Testing

`resolver/` stays plain JUnit, per the rule that a test extends `BasePlatformTestCase`
exactly when its class under test touches PSI/DOM.

- **`LocalRepositoryModelResolverTest`** — the callback reports the requested GAV on
  a miss.
- **`BomEffectiveModelResolverTest`** — building `broken-parent-bom` reports
  `com.example:does-not-exist-parent:1.0.0`. This is the case that is invisible
  today, and the test that proves the seam sits deep enough to be useful.
- **`BomChainResolverTest`** (`BasePlatformTestCase`) — a module whose parent POM is
  absent from the fixture repo yields a non-empty `truncatedAt` instead of a quietly
  short chain.
- **`OverrideDetectorTest`** — a truncated parent chain yields `Inconclusive`.
- **`MissingPomResolutionTest`** — the loop, driven by a fake
  `(Set<Gav>) -> Set<Gav>`: converges when a fetch succeeds, terminates when fetches
  fail, does not retry a GAV that already failed, and stops at the round bound.
  No network.
- **`RemotePomFetcher`** is hand-verified via `runIde`, like the `ui/` layer — its
  content is entirely IDE-integration plumbing, which is the same reason `ui/` has no
  tests and `DependencyCandidates` does.

Manual verification via `runIde` against a project whose BOM has been deleted from
the local repository: the tool window's rows go from `Inconclusive` to a real verdict
after a refresh, and stay `Inconclusive` with the IDE's Maven offline mode enabled.

## Open assumptions

- **`MavenEmbeddersManager.getEmbedder(Key, String)` works with no synced Maven project.** The
  API shape says it should — it takes a working directory rather than a `MavenProject`.
  Verify on a fresh unsynced checkout: if it does not hold, the fresh-checkout case is
  dropped and the scope narrows to already-synced projects. There is no alternative entry
  point to move to — the newer `MavenEmbedderWrappers`/`MavenEmbedderWrappersManager` is
  `@ApiStatus.Internal` and cannot ship (above) — and nothing else in this design changes,
  because the retry loop treats a fetcher that returns nothing as an ordinary outcome and
  falls back to the residual `Inconclusive`. Note that on such a project the property-versioned BOM
  imports are unfetchable anyway (see the `isConcrete` filter above), so what remains
  recoverable there is literal-versioned imports and parents.
- **`resolveArtifacts` with packaging `"pom"` downloads the POM alone**, not the POM
  plus its jar. If it pulls the jar too, the feature still works but costs
  materially more bandwidth, and `updateSnapshots` handling deserves a second look.
- **Three rounds suffice in practice.** The bound guarantees termination regardless;
  if real chains need more, the cost of being wrong is a residual `Inconclusive`,
  not a wrong answer.
