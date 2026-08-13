# Phase 9: Remote BOM Resolution — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the plugin fetch missing BOM and parent POMs from the remote repositories configured in Maven's `settings.xml`, so `Inconclusive` means "genuinely unverifiable" rather than "not downloaded yet" — and close the silent parent-chain truncation that can produce false confidence today.

**Architecture:** Resolution stays offline and unchanged, but now *reports* each POM it could not find through an optional callback. A loop outside the read action fetches those POMs via the IDE's bundled Maven integration and re-resolves, bounded at three rounds. `resolver/` gains only a `(Gav) -> Unit` parameter and stays free of IntelliJ Platform APIs; all network and VFS work lives in one new `detection/` class.

**Tech Stack:** Kotlin, IntelliJ Platform 2026.2 (`intellijIdea("2026.2.1")`, `sinceBuild`/`untilBuild` = `262`), bundled `org.jetbrains.idea.maven`, Apache `maven-model-builder` 3.9.9, JUnit 4.13.2, `BasePlatformTestCase` for PSI/DOM-touching tests.

**API baseline:** every platform symbol used below was verified against the unpacked 2026.2.1 distribution (`plugins/maven-plugin/lib/intellij.maven.jar` — note the directory and jar were renamed from `plugins/maven/lib/maven.jar`). `MavenEmbedderWrapper.resolveArtifacts(Collection, RawProgressReporter, MavenEventHandler, Continuation)`, `MavenProjectsManager.getRemoteRepositories/getGeneralSettings/getRepositoryPath/getEmbeddersManager/isInitialized`, `MavenGeneralSettings.isWorkOffline/isAlwaysUpdateSnapshots`, `ThreadingAssertions.assertBackgroundThread` and `runBlockingCancellable` all carry no `@ApiStatus.Internal`. `MavenEmbeddersManager` is `@ApiStatus.Obsolete`, which `verifyPlugin` accepts; `MavenEmbedderWrappers`/`MavenEmbedderWrappersManager` are `@ApiStatus.Internal` and must not be used. `MavenLogEventHandler` is a Kotlin `object` — write `MavenLogEventHandler` from Kotlin, not `.INSTANCE`.

**Spec:** `docs/superpowers/specs/2026-08-12-phase9-remote-bom-resolution-design.md`

## Global Constraints

- **Commit each task, directly on `develop`.** The repo owner has pre-authorized all six task commits for this plan, waiving the usual "leave it uncommitted" preference so each task has a reviewable diff. Never create a feature branch, never touch `main`. Do not commit anything outside your own task's files.
- **`resolver/` must import nothing from the IntelliJ Platform.** It stays unit-testable against fixture POMs. Anything touching PSI/DOM/VFS lives in `detection/` or `ui/`.
- **No new Gradle dependencies.** `maven-model-builder` and `maven-artifact` stay the only Maven libraries; everything network-facing comes from the already-declared bundled `org.jetbrains.idea.maven` plugin.
- **No `@ApiStatus.Internal` platform API, ever.** `./gradlew verifyPlugin` fails the build on `INTERNAL_API_USAGES` and the Marketplace review rejects it. `@ApiStatus.Obsolete` and `@ApiStatus.Experimental` are acceptable.
- **Never downgrade an unverifiable answer into a confident one.** A failed fetch leaves the BOM in `uncheckedBoms` and the result `Inconclusive`. This is the project's "miss rather than false-safe" rule and it is security-relevant.
- **`OverrideInspection` gets no network path.** It runs synchronously per file on every edit.
- **Default parameter values preserve today's behavior.** Every new parameter defaults to a no-op so existing constructions and tests compile and pass unchanged.
- **Verification command:** `./gradlew check` runs unit + platform tests. Single class: `./gradlew test --tests "cloud.schneidoa.detection.OverrideDetectorTest"`. Backticked test names become globs.

---

### Task 1: `RemotePomFetcher` — settle the risky assumption first

This task exists before the rest because the spec's first open assumption — that an embedder can be obtained without a completed Maven sync — decides whether the feature's most valuable case (fresh checkout) is reachable at all. If it fails here, the scope narrows to already-synced projects — the remaining tasks stand as written, because they all resolve through a loop for which a fetcher that returns nothing is an ordinary outcome, not a broken assumption. Build it, verify it by hand in a sandbox IDE, then continue.

**Files:**
- Create: `src/main/kotlin/cloud/schneidoa/detection/RemotePomFetcher.kt`
- Modify: `src/main/kotlin/cloud/schneidoa/resolver/MavenCoordinates.kt`
- Test: `src/test/kotlin/cloud/schneidoa/resolver/MavenCoordinatesTest.kt` (create if absent)

**Interfaces:**
- Consumes: `cloud.schneidoa.resolver.Gav`, `cloud.schneidoa.resolver.pomFileIn` (both already exist in `MavenCoordinates.kt`).
- Produces:
  - `fun Gav.isConcrete(): Boolean` in `MavenCoordinates.kt`. Task 4's loop depends on it.
  - `class RemotePomFetcher(project: Project)` with `fun fetch(gavs: Set<Gav>): Set<Gav>` — returns the subset whose POM is present locally afterwards. Task 4's loop depends on exactly this signature.

- [ ] **Step 0: Add `Gav.isConcrete()`**

This exists because of a platform behavior confirmed in the 2026.2.1 bytecode: `MavenPropertyResolver.resolve(text, model)` returns its **input unchanged** when `MavenProjectsManager.isInitialized()` is false — i.e. before Maven sync has run — and also when the DOM model has no backing `XmlElement` or `VirtualFile`. `BomChainResolver` runs every BOM version *and* every parent version through it, so on a fresh checkout a BOM imported at `${spring-boot.version}` produces `Gav("org.springframework.boot", "spring-boot-dependencies", "\${spring-boot.version}")`. That coordinate can never be fetched, and asking a repository for it would be a guaranteed-404 request built from placeholder text.

Write the test first, in `src/test/kotlin/cloud/schneidoa/resolver/MavenCoordinatesTest.kt` (plain JUnit — nothing here touches the platform):

```kotlin
package cloud.schneidoa.resolver

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MavenCoordinatesTest {

    @Test
    fun `a fully literal coordinate is concrete`() {
        assertTrue(Gav("com.example", "acme-bom", "1.0.0").isConcrete())
    }

    @Test
    fun `an unresolved property in the version makes a coordinate unfetchable`() {
        assertFalse(Gav("com.example", "acme-bom", "\${acme.version}").isConcrete())
    }

    @Test
    fun `an unresolved property anywhere in the coordinate counts`() {
        assertFalse(Gav("\${acme.group}", "acme-bom", "1.0.0").isConcrete())
        assertFalse(Gav("com.example", "\${acme.artifact}", "1.0.0").isConcrete())
    }

    @Test
    fun `a blank segment is not concrete`() {
        assertFalse(Gav("com.example", "acme-bom", "").isConcrete())
    }
}
```

Run: `./gradlew test --tests "cloud.schneidoa.resolver.MavenCoordinatesTest"` — expect FAIL, `isConcrete` unresolved. Then add to `MavenCoordinates.kt`:

```kotlin
/**
 * Whether every segment is a literal that could actually be looked up or downloaded.
 *
 * MavenPropertyResolver.resolve hands back its input unchanged until Maven sync has run
 * (verified in the 2026.2.1 bytecode: it early-returns unless MavenProjectsManager
 * .isInitialized()), so before the first sync a BOM or parent declared at ${some.version}
 * reaches us with the placeholder still in it. Such a coordinate is not a miss to be
 * fetched - it is a coordinate we do not yet know.
 */
fun Gav.isConcrete(): Boolean =
    listOf(groupId, artifactId, version).all { it.isNotBlank() && !it.contains("\${") }
```

Re-run the test — expect PASS.

- [ ] **Step 1: Write `RemotePomFetcher`**

```kotlin
package cloud.schneidoa.detection

import cloud.schneidoa.resolver.Gav
import cloud.schneidoa.resolver.isConcrete
import cloud.schneidoa.resolver.pomFileIn
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.runBlockingCancellable
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.util.concurrency.ThreadingAssertions
import org.jetbrains.idea.maven.buildtool.MavenLogEventHandler
import org.jetbrains.idea.maven.model.MavenArtifactInfo
import org.jetbrains.idea.maven.model.MavenRemoteRepository
import org.jetbrains.idea.maven.project.MavenEmbeddersManager
import org.jetbrains.idea.maven.project.MavenProjectsManager
import org.jetbrains.idea.maven.server.MavenArtifactResolutionRequest

private val logger = logger<RemotePomFetcher>()

/**
 * Downloads missing BOM/parent POMs from the remote repositories Maven itself would
 * use. This is the only class in the plugin that touches the network.
 *
 * Mirrors, proxies and authentication (including credentials encrypted in
 * settings-security.xml) are NOT handled here and must not be: the IDE's Maven server
 * applies them itself, inside Maven's own RepositorySystem, when it processes the
 * repository list we hand it. Reimplementing that here is precisely the class of
 * correctness bug the design doc rules out by embedding Maven's own machinery.
 *
 * Must run on a background thread and outside a read action - it does network I/O and
 * a synchronous VFS refresh, and the latter throws under the read lock.
 */
class RemotePomFetcher(private val project: Project) {

    fun fetch(gavs: Set<Gav>): Set<Gav> {
        // Both assertions, not just the first: the whole design rests on fetching OUTSIDE the
        // read action, and a background thread can still hold a read lock (ReadAction.nonBlocking).
        // assertBackgroundThread alone would let that through and fail later, obscurely, inside
        // refreshIoFiles.
        ThreadingAssertions.assertBackgroundThread()
        ThreadingAssertions.assertNoReadAccess()

        // Defence in depth - resolvingMissingPoms filters these out already. A coordinate
        // still carrying ${...} is what MavenPropertyResolver hands back before Maven sync
        // has run, and asking a repository for it would be a guaranteed-404 request built
        // from a placeholder.
        val fetchable = gavs.filter { it.isConcrete() }.toSet()
        if (fetchable.isEmpty()) return emptySet()

        val manager = MavenProjectsManager.getInstance(project)
        if (manager.generalSettings.isWorkOffline) {
            logger.debug("Maven is in offline mode; not fetching ${fetchable.size} missing POM(s)")
            return emptySet()
        }

        val basePath = project.basePath ?: return emptySet()
        val localRepositoryDir = manager.repositoryPath.toFile()
        val repositories = manager.remoteRepositories.toList().ifEmpty { listOf(MAVEN_CENTRAL) }
        val requests = fetchable.map { gav ->
            MavenArtifactResolutionRequest(
                MavenArtifactInfo(gav.groupId, gav.artifactId, gav.version, "pom", null),
                repositories
            )
        }

        // The (Key, workingDirectory) overload rather than (MavenProject, Key): it needs no
        // synced MavenProject, which is what makes this work on a fresh checkout - the case
        // this feature most needs to serve.
        val embeddersManager = manager.embeddersManager
        val embedder = embeddersManager.getEmbedder(MavenEmbeddersManager.FOR_DEPENDENCIES_RESOLVE, basePath)
        try {
            runBlockingCancellable {
                embedder.resolveArtifacts(requests, null, MavenLogEventHandler)
            }
        } catch (e: CancellationException) {
            // Must be rethrown before the broad catch below. runBlockingCancellable runs inside a
            // coroutine scope, so a closing project or a cancelled progress indicator arrives as a
            // CancellationException - which IS an Exception. Swallowing it would log a spurious
            // warning and carry on doing work the caller already gave up on.
            throw e
        } catch (e: Exception) {
            // Deliberately broad otherwise: one unreachable repository or one malformed coordinate
            // must degrade to "could not fetch" for the whole batch, never propagate into a tool
            // window refresh or a dialog load.
            logger.warn("Failed to fetch ${fetchable.size} POM(s) from remote repositories", e)
        } finally {
            embeddersManager.release(embedder)
        }

        // Success is decided by what is on disk, not by MavenArtifact.isResolved(): we asked for
        // packaging "pom", and isResolved()'s notion of resolved is about the primary artifact
        // file, which is not the question we need answered.
        val landed = fetchable.filter { it.pomFileIn(localRepositoryDir).isFile }.toSet()
        LocalFileSystem.getInstance().refreshIoFiles(landed.map { it.pomFileIn(localRepositoryDir) })
        return landed
    }

    private companion object {
        val MAVEN_CENTRAL = MavenRemoteRepository(
            "central",
            "Central Repository",
            "https://repo.maven.apache.org/maven2",
            "default",
            null,
            null
        )
    }
}
```

**Why `MavenEmbeddersManager` and not the newer `MavenEmbedderWrappers`:** verified by decompiling the pinned 2026.2.1 jar (`plugins/maven-plugin/lib/intellij.maven.jar`) and confirmed by `./gradlew verifyPlugin` failing with four `INTERNAL_API_USAGES` on an earlier attempt — `MavenEmbedderWrappers` and `MavenEmbedderWrappersManager` are **class-level `@ApiStatus.Internal`**, while `MavenEmbeddersManager` is `@ApiStatus.Obsolete`, which the verifier accepts. Obsolete-but-allowed beats modern-but-rejected: the plugin cannot ship with an internal-API usage. Do not "modernize" this to `MavenEmbedderWrappers` without re-running `verifyPlugin` and seeing it pass.

- [ ] **Step 2: Compile and check for internal-API violations**

Run: `./gradlew compileKotlin verifyPlugin`
Expected: BUILD SUCCESSFUL, and the `verifyPlugin` report contains no `INTERNAL_API_USAGES` entry.

All symbols used above were verified present and non-`Internal` in the 2026.2.1 bytecode, so a compile failure most likely means a wiring mistake rather than a missing API. The plausible one:
- `resolveArtifacts` cannot be called from `runBlockingCancellable` — fall back to the still-present deprecated blocking overload `resolveArtifacts(requests, null, null, null)` and record in a comment why the suspend one was not usable.

`MavenLogEventHandler` is a Kotlin `object` (confirmed: private constructor plus a synthesized `INSTANCE` field), so from Kotlin write `MavenLogEventHandler` — do **not** append `.INSTANCE`. `ThreadingAssertions.assertBackgroundThread()` lives in `com.intellij.util.concurrency` in `lib/intellij.platform.core.jar`.

If `verifyPlugin` reports an internal-API usage, **stop and report it** — do not work around it by suppressing the check.

- [ ] **Step 3: Run the full suite**

Run: `./gradlew check`
Expected: BUILD SUCCESSFUL. Nothing calls `RemotePomFetcher` yet, so this only proves the new code compiles and breaks nothing.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/cloud/schneidoa/detection/RemotePomFetcher.kt \
        src/main/kotlin/cloud/schneidoa/resolver/MavenCoordinates.kt \
        src/test/kotlin/cloud/schneidoa/resolver/MavenCoordinatesTest.kt
git commit -m "feat: add RemotePomFetcher for downloading missing BOM POMs"
```

> **Deferred verification.** `RemotePomFetcher`'s runtime behavior — that an embedder is obtainable without a completed Maven sync, that offline mode is honored, that an unresolvable coordinate degrades quietly — cannot be checked from a test suite and is verified by hand at the end of the plan (see **Manual verification** below). The repo owner accepted that risk deliberately: if the no-sync assumption turns out to be false, the fix is confined to `RemotePomFetcher` itself. Tasks 2–5 already tolerate a fetcher that fetches nothing — `resolvingMissingPoms` takes the fetcher as a parameter and treats an empty fetch result as terminal, returning the residual `Inconclusive` rather than failing, and every Task 5 call site goes through that same loop — so the consequence is that the feature narrows to already-synced projects, not that any of them need rescoping. Do not add a throwaway trigger action for it; Task 5's wiring exercises the same path through the real feature.

---

### Task 2: Report missing POMs from `resolver/`

The reporting seam. `LocalRepositoryModelResolver` is the highest-value place for it: the model builder calls it for parents *and* nested BOM imports, so it sees the transitive POMs whose absence causes a failure — the ones `BomModelResult.Failure` cannot name today.

**Files:**
- Modify: `src/main/kotlin/cloud/schneidoa/resolver/LocalRepositoryModelResolver.kt`
- Modify: `src/main/kotlin/cloud/schneidoa/resolver/BomEffectiveModelResolver.kt`
- Modify: `src/main/kotlin/cloud/schneidoa/resolver/BomVersionResolver.kt`
- Test: `src/test/kotlin/cloud/schneidoa/resolver/LocalRepositoryModelResolverTest.kt`
- Test: `src/test/kotlin/cloud/schneidoa/resolver/BomEffectiveModelResolverTest.kt`

**Interfaces:**
- Consumes: `Gav`, `pomFileIn` from `MavenCoordinates.kt`; the `broken-parent-bom` fixture at `src/test/resources/fixtures/local-repo/com/example/broken-parent-bom/1.0.0/`, whose `<parent>` points at the non-existent `com.example:does-not-exist-parent:1.0.0`.
- Produces:
  - `LocalRepositoryModelResolver(localRepositoryDir: File, onMissingPom: (Gav) -> Unit = {})`
  - `BomEffectiveModelResolver(localRepositoryDir: File, onMissingPom: (Gav) -> Unit = {})`
  - `BomVersionResolver(val localRepositoryDir: File, val onMissingPom: (Gav) -> Unit = {})` — note `onMissingPom` is a **public val**, mirroring how `localRepositoryDir` is already public so `OverrideDetector` and `ManagedVersionHint` can read it back out to build their `BomChainResolver`. Tasks 3 and 5 depend on that.
- [ ] **Step 1: Write the failing test for the direct miss**

Add to `src/test/kotlin/cloud/schneidoa/resolver/LocalRepositoryModelResolverTest.kt`:

```kotlin
    @Test
    fun `reports the coordinates it could not find before throwing`() {
        val reported = mutableListOf<Gav>()
        val fixtureUrl = javaClass.classLoader.getResource("fixtures/local-repo")
            ?: error("Test fixture local-repo not found on classpath")
        val reporting = LocalRepositoryModelResolver(File(fixtureUrl.toURI())) { reported += it }

        try {
            reporting.resolveModel("com.example", "does-not-exist-bom", "9.9.9")
            fail("Expected UnresolvableModelException")
        } catch (e: UnresolvableModelException) {
            // expected - the callback must fire in addition to, not instead of, the throw
        }

        assertEquals(listOf(Gav("com.example", "does-not-exist-bom", "9.9.9")), reported)
    }

    @Test
    fun `newCopy carries the reporting callback`() {
        val reported = mutableListOf<Gav>()
        val fixtureUrl = javaClass.classLoader.getResource("fixtures/local-repo")
            ?: error("Test fixture local-repo not found on classpath")
        val copy = LocalRepositoryModelResolver(File(fixtureUrl.toURI())) { reported += it }.newCopy()

        try {
            copy.resolveModel("com.example", "does-not-exist-bom", "9.9.9")
            fail("Expected UnresolvableModelException")
        } catch (e: UnresolvableModelException) {
            // expected
        }

        assertEquals(1, reported.size)
    }
```

The `newCopy` test is not padding: the model builder makes internal copies of the resolver while walking a parent chain, so a `newCopy` that drops the callback would silently report nothing for exactly the transitive case this seam exists to catch.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests "cloud.schneidoa.resolver.LocalRepositoryModelResolverTest"`
Expected: FAIL — the two-argument constructor does not exist (compilation error).

- [ ] **Step 3: Add the callback to `LocalRepositoryModelResolver`**

Change the class declaration and `resolve`/`newCopy` in `src/main/kotlin/cloud/schneidoa/resolver/LocalRepositoryModelResolver.kt`:

```kotlin
class LocalRepositoryModelResolver(
    private val localRepositoryDir: File,
    private val onMissingPom: (Gav) -> Unit = {}
) : ModelResolver {
```

```kotlin
    override fun newCopy(): ModelResolver = LocalRepositoryModelResolver(localRepositoryDir, onMissingPom)

    private fun resolve(gav: Gav): ModelSource {
        val pomFile = gav.pomFileIn(localRepositoryDir)
        if (!pomFile.isFile) {
            // Reported in addition to the throw, not instead of it: the model builder needs the
            // exception to fail the build, while the caller needs the coordinate to know what to
            // fetch. This is the only place that sees transitively-referenced parents and nested
            // BOM imports by coordinate - BomModelResult.Failure names the outer BOM instead.
            onMissingPom(gav)
            throw UnresolvableModelException(
                "Could not find ${pomFile.name} in local repository $localRepositoryDir",
                gav.groupId,
                gav.artifactId,
                gav.version
            )
        }
        return FileModelSource(pomFile)
    }
```

Also update the class KDoc's "Never touches the network" sentence to say that it never fetches anything itself but reports what it could not find, so a caller outside the read action can.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew test --tests "cloud.schneidoa.resolver.LocalRepositoryModelResolverTest"`
Expected: PASS, all tests including the six that existed before.

- [ ] **Step 5: Write the failing test for the transitive miss**

Add to `src/test/kotlin/cloud/schneidoa/resolver/BomEffectiveModelResolverTest.kt`:

```kotlin
    /**
     * The case that is invisible today: BomModelResult.Failure names broken-parent-bom, which
     * IS on disk, while the POM actually missing is its parent. Without this seam a fetch loop
     * would re-request an artifact it already has and never make progress.
     */
    @Test
    fun `reports the missing parent coordinate, not the BOM that failed to build`() {
        val reported = mutableListOf<Gav>()
        val fixtureUrl = javaClass.classLoader.getResource("fixtures/local-repo")
            ?: error("Test fixture local-repo not found on classpath")
        val reporting = BomEffectiveModelResolver(File(fixtureUrl.toURI())) { reported += it }

        val result = reporting.buildEffectiveModel(Gav("com.example", "broken-parent-bom", "1.0.0"))

        assertTrue(result is BomModelResult.Failure)
        assertTrue(
            "expected the missing parent to be reported, got $reported",
            reported.contains(Gav("com.example", "does-not-exist-parent", "1.0.0"))
        )
    }

    @Test
    fun `reports a BOM whose own POM is not in the repository`() {
        val reported = mutableListOf<Gav>()
        val fixtureUrl = javaClass.classLoader.getResource("fixtures/local-repo")
            ?: error("Test fixture local-repo not found on classpath")
        val reporting = BomEffectiveModelResolver(File(fixtureUrl.toURI())) { reported += it }
        val missing = Gav("com.example", "does-not-exist-bom", "9.9.9")

        reporting.buildEffectiveModel(missing)

        assertEquals(listOf(missing), reported)
    }
```

Before running, open `src/test/resources/fixtures/local-repo/com/example/broken-parent-bom/1.0.0/broken-parent-bom-1.0.0.pom` and confirm the parent coordinate really is `com.example:does-not-exist-parent:1.0.0`. If it differs, use the actual value in the assertion.

- [ ] **Step 6: Run the tests to verify they fail**

Run: `./gradlew test --tests "cloud.schneidoa.resolver.BomEffectiveModelResolverTest"`
Expected: FAIL — the two-argument constructor does not exist.

- [ ] **Step 7: Thread the callback through `BomEffectiveModelResolver`**

```kotlin
class BomEffectiveModelResolver(
    private val localRepositoryDir: File,
    private val onMissingPom: (Gav) -> Unit = {}
) {
```

In `buildEffectiveModelUncached`, report the top-level miss and hand the callback to the model resolver:

```kotlin
    private fun buildEffectiveModelUncached(bom: Gav): BomModelResult {
        val pomFile = bom.pomFileIn(localRepositoryDir)
        if (!pomFile.isFile) {
            onMissingPom(bom)
            return BomModelResult.Failure(bom, "BOM POM not found in local repository: $pomFile")
        }
        ...
        request.setModelResolver(LocalRepositoryModelResolver(localRepositoryDir, onMissingPom))
```

Add a sentence to the `effectiveModels` KDoc: the memoized `Failure` means a BOM missing at the start of a fetch round is reported once, not once per override — which is why the loop in Task 4 must use a *fresh* resolver per round rather than reusing one across rounds.

- [ ] **Step 8: Run the tests to verify they pass**

Run: `./gradlew test --tests "cloud.schneidoa.resolver.BomEffectiveModelResolverTest"`
Expected: PASS, including the eight pre-existing tests.

- [ ] **Step 9: Thread the callback through `BomVersionResolver`**

```kotlin
class BomVersionResolver(
    val localRepositoryDir: File,
    val onMissingPom: (Gav) -> Unit = {}
) {

    private val modelResolver = BomEffectiveModelResolver(localRepositoryDir, onMissingPom)
```

`onMissingPom` is public for the same reason `localRepositoryDir` already is: `OverrideDetector` and `ManagedVersionHint` build their own `BomChainResolver` from this resolver's state rather than taking a second constructor parameter.

- [ ] **Step 10: Run the full suite**

Run: `./gradlew check`
Expected: BUILD SUCCESSFUL. Every pre-existing test still passes — the defaults preserve today's behavior exactly.

- [ ] **Step 11: Commit** (ask first)

```bash
git add src/main/kotlin/cloud/schneidoa/resolver/ src/test/kotlin/cloud/schneidoa/resolver/
git commit -m "feat: report POM coordinates that could not be resolved locally"
```

---

### Task 3: Close the silent parent-chain truncation

A standalone correctness fix. `resolveParentViaLocalRepository` returns `null` when the parent's POM is absent, which ends the `while` loop in `resolveBomChain` — every BOM imported above the break silently ceases to exist, and detection can return a confident `NotFound` on evidence it never gathered. This is the one place where "miss rather than false-safe" does not currently hold, and it is worth fixing whether or not remote fetching ever ships.

**Files:**
- Modify: `src/main/kotlin/cloud/schneidoa/detection/BomChainResolver.kt`
- Modify: `src/main/kotlin/cloud/schneidoa/detection/OverrideDetector.kt`
- Modify: `src/main/kotlin/cloud/schneidoa/detection/ManagedVersionHint.kt`
- Modify: `src/main/kotlin/cloud/schneidoa/detection/BomChainReportFactory.kt`
- Test: `src/test/kotlin/cloud/schneidoa/detection/BomChainResolverTest.kt`
- Test: `src/test/kotlin/cloud/schneidoa/detection/OverrideDetectorTest.kt`

**Interfaces:**
- Consumes: `BomVersionResolver.onMissingPom` (Task 2), `BomImport` (unchanged).
- Produces:
  - `data class BomChain(val imports: List<BomImport>, val truncatedAt: List<Gav>)`
  - `BomChainResolver(localRepositoryDir: File, onMissingPom: (Gav) -> Unit = {})` with `fun resolveBomChain(model, project): BomChain`
  - `DetectedOverride.Inconclusive` now also fires for a truncated chain. Its shape is unchanged.

- [ ] **Step 1: Write the failing test for the truncation**

Add to `src/test/kotlin/cloud/schneidoa/detection/BomChainResolverTest.kt`. Reuse the file's existing `resolver()` helper (line 409). Do **not** try to reuse `configureModule` (line 415) — its POM template has no `<parent>` element, and widening it for one test would change every existing test's fixture. Write the POM inline the way `configureModule` itself does:

```kotlin
    fun `test reports a parent that is not in the local repository instead of silently ending the chain`() {
        val file = myFixture.configureByText(
            "pom.xml",
            """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <parent>
                    <groupId>com.example</groupId>
                    <artifactId>absent-parent</artifactId>
                    <version>7.7.7</version>
                    <relativePath/>
                </parent>
                <artifactId>consumer</artifactId>
            </project>
            """.trimIndent()
        )
        val model = MavenDomUtil.getMavenDomProjectModel(project, file.virtualFile)!!

        val chain = resolver().resolveBomChain(model, project)

        assertEquals(listOf(Gav("com.example", "absent-parent", "7.7.7")), chain.truncatedAt)
        assertTrue(chain.imports.isEmpty())
    }
```

The explicit-empty `<relativePath/>` is load-bearing: it means "skip relative lookup entirely", so resolution falls straight through to the local-repo lookup — the path under test. Without it the test would exercise the VFS branch and pass for the wrong reason.

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests "cloud.schneidoa.detection.BomChainResolverTest"`
Expected: FAIL — `resolveBomChain` returns `List<BomImport>`, which has no `truncatedAt` (compilation error).

- [ ] **Step 3: Introduce `BomChain` and report the truncation**

In `src/main/kotlin/cloud/schneidoa/detection/BomChainResolver.kt`, add next to `BomImport`:

```kotlin
/**
 * The BOM imports found while climbing the parent chain, plus any parent whose POM
 * could not be read - which matters because it means the walk stopped early and the
 * imports list is therefore incomplete rather than exhaustive. A caller that ignores
 * [truncatedAt] will read a short chain as a complete one and can report a confident
 * "nothing manages this" for an artifact a BOM above the break manages.
 */
data class BomChain(val imports: List<BomImport>, val truncatedAt: List<Gav>)
```

Change the class and the walk:

```kotlin
class BomChainResolver(
    private val localRepositoryDir: File,
    private val onMissingPom: (Gav) -> Unit = {}
) {

    fun resolveBomChain(model: MavenDomProjectModel, project: Project): BomChain {
        val chain = mutableListOf<BomImport>()
        val truncatedAt = mutableListOf<Gav>()
        var current: MavenDomProjectModel? = model
        var path: List<String> = emptyList()
        var depth = 0

        while (current != null && depth < MAX_PARENT_CHAIN_DEPTH) {
            val declaredHere = path
            chain += importedBomsOf(current).map { BomImport(it, declaredHere) }

            val parent = resolveParent(current, project) { truncatedAt += it }
            if (parent != null) {
                path = path + artifactIdOf(parent)
            }
            current = parent
            depth++
        }

        return BomChain(chain, truncatedAt.toList())
    }
```

Thread the reporter down through `resolveParent` into `resolveParentViaLocalRepository`, and report there:

```kotlin
    private fun resolveParent(
        model: MavenDomProjectModel,
        project: Project,
        onTruncated: (Gav) -> Unit
    ): MavenDomProjectModel? {
        val parent = model.mavenParent
        if (parent.xmlTag == null) return null

        return resolveParentViaRelativePath(model, parent, project)
            ?: resolveParentViaLocalRepository(parent, model, project, onTruncated)
    }
```

```kotlin
    private fun resolveParentViaLocalRepository(
        parent: MavenDomParent,
        model: MavenDomProjectModel,
        project: Project,
        onTruncated: (Gav) -> Unit
    ): MavenDomProjectModel? {
        val groupId = parent.groupId.rawText?.trim()
        val artifactId = parent.artifactId.rawText?.trim()
        val rawVersion = parent.version.rawText?.trim()
        if (groupId.isNullOrEmpty() || artifactId.isNullOrEmpty() || rawVersion.isNullOrEmpty()) return null
        val version = MavenPropertyResolver.resolve(rawVersion, model)

        val gav = Gav(groupId, artifactId, version)
        val pomFile = gav.pomFileIn(localRepositoryDir)
        val virtualFile = LocalFileSystem.getInstance().findFileByIoFile(pomFile)
        if (virtualFile == null) {
            // Both callbacks fire and they are not the same thing: onMissingPom tells a fetch
            // loop what to download, onTruncated tells the caller this chain is incomplete and
            // must not be read as exhaustive. Fetching may fix the first without the second
            // ever becoming untrue for this pass.
            onMissingPom(gav)
            onTruncated(gav)
            return null
        }
        return MavenDomUtil.getMavenDomProjectModel(project, virtualFile)
    }
```

An incomplete parent GAV (a missing groupId/artifactId/version) still returns `null` without reporting — there is no coordinate to fetch and nothing a caller could do with it.

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests "cloud.schneidoa.detection.BomChainResolverTest"`
Expected: FAIL to compile in *other* files — `OverrideDetector`, `ManagedVersionHint` and `BomChainReportFactory` still expect a `List`. Fix those in the next step, then re-run.

- [ ] **Step 5: Update the three consumers**

`OverrideDetector.kt` — build the chain resolver with the callback, and merge `truncatedAt` into the unchecked list:

```kotlin
    private val bomChainResolver =
        BomChainResolver(bomVersionResolver.localRepositoryDir, bomVersionResolver.onMissingPom)

    fun detect(model: MavenDomProjectModel, project: Project): List<DetectedOverride> {
        val bomChain = bomChainResolver.resolveBomChain(model, project)

        return DependencyManagementScanner.scan(model)
            .filterNot { it.suppressed }
            .mapNotNull { candidate -> evaluate(candidate, bomChain) }
    }

    private fun evaluate(candidate: OverrideCandidate, bomChain: BomChain): DetectedOverride? {
        val lookup = bomVersionResolver.resolveManagedVersion(bomChain.imports.map { it.bom }, candidate.ga)
        // A parent we could not read is exactly as disqualifying as a BOM we could not read:
        // both mean the chain we searched was not the whole chain.
        val unchecked = lookup.uncheckedBoms() + bomChain.truncatedAt

        return when (lookup) {
            is ManagedVersionLookup.Found ->
                if (unchecked.isNotEmpty()) {
                    DetectedOverride.Inconclusive(candidate, unchecked)
                } else {
                    // Equal versions are reported too, not filtered out: a pin the BOM has
                    // exactly caught up to is the redundant one this plugin exists to find.
                    val declaredVia =
                        bomChain.imports.firstOrNull { it.bom == lookup.declaredIn }?.declaredVia ?: emptyList()
                    DetectedOverride.Confirmed(
                        candidate,
                        lookup.version,
                        lookup.declaredIn,
                        declaredVia,
                        compareDeclaredToManaged(candidate.declaredVersion, lookup.version)
                    )
                }
            is ManagedVersionLookup.NotFound ->
                if (unchecked.isNotEmpty()) DetectedOverride.Inconclusive(candidate, unchecked) else null
        }
    }
```

Add this helper to `BomVersionResolver.kt`, next to the sealed class, so both branches read the field the same way:

```kotlin
/** The unchecked list regardless of outcome - both branches carry the same contract. */
fun ManagedVersionLookup.uncheckedBoms(): List<Gav> = when (this) {
    is ManagedVersionLookup.Found -> uncheckedBoms
    is ManagedVersionLookup.NotFound -> uncheckedBoms
}
```

`ManagedVersionHint.kt` — same constructor change, and `.imports` at both call sites:

```kotlin
    private val bomChainResolver =
        BomChainResolver(bomVersionResolver.localRepositoryDir, bomVersionResolver.onMissingPom)

    fun lookup(model: MavenDomProjectModel, project: Project, ga: Ga): ManagedVersionLookup {
        val bomChain = bomChainResolver.resolveBomChain(model, project)
        return bomVersionResolver.resolveManagedVersion(bomChain.imports.map { it.bom }, ga)
    }

    fun catalog(model: MavenDomProjectModel, project: Project): ManagedVersionCatalog {
        val bomChain = bomChainResolver.resolveBomChain(model, project)
        return bomVersionResolver.collectManagedVersions(bomChain.imports.map { it.bom })
    }
```

`BomChainReportFactory.kt` — `buildBomChainReport` takes `List<BomImport>`, so pass `.imports`:

```kotlin
    val chain = BomChainResolver(localRepositoryDir).resolveBomChain(model, project)

    return buildBomChainReport(
        ga = ga,
        declaredVersion = declaredVersion,
        moduleLabel = moduleLabel,
        bomChain = chain.imports,
        resolver = BomVersionResolver(localRepositoryDir)
    )
```

- [ ] **Step 6: Run the affected tests**

Run: `./gradlew test --tests "cloud.schneidoa.detection.BomChainResolverTest"`
Expected: PASS.

- [ ] **Step 7: Write the failing test for the detector's verdict**

Add to `src/test/kotlin/cloud/schneidoa/detection/OverrideDetectorTest.kt`. Reuse its `localRepositoryDir()` helper (lines 13–17), but not `configureModuleImportingAcmeBom` — that helper's template has no `<parent>`, which is exactly what this test needs. Write the POM inline:

```kotlin
    fun `test reports inconclusive when the parent chain could not be fully walked`() {
        val file = myFixture.configureByText(
            "pom.xml",
            """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <parent>
                    <groupId>com.example</groupId>
                    <artifactId>absent-parent</artifactId>
                    <version>7.7.7</version>
                    <relativePath/>
                </parent>
                <artifactId>consumer</artifactId>
                <dependencyManagement>
                    <dependencies>
                        <dependency>
                            <groupId>com.fasterxml.jackson.core</groupId>
                            <artifactId>jackson-databind</artifactId>
                            <version>2.15.0</version>
                        </dependency>
                    </dependencies>
                </dependencyManagement>
            </project>
            """.trimIndent()
        )
        val model = MavenDomUtil.getMavenDomProjectModel(project, file.virtualFile)!!

        val detected = OverrideDetector(BomVersionResolver(localRepositoryDir())).detect(model, project)

        assertEquals(1, detected.size)
        val result = detected.single()
        assertTrue("expected Inconclusive, got $result", result is DetectedOverride.Inconclusive)
        assertEquals(
            listOf(Gav("com.example", "absent-parent", "7.7.7")),
            (result as DetectedOverride.Inconclusive).uncheckedBoms
        )
    }
```

Before the fix this module has no readable parent, therefore no BOM chain, therefore `NotFound` with an empty unchecked list — and the override is dropped entirely (`detected` is empty). That silent drop is the bug.

- [ ] **Step 8: Run the test to verify it passes**

Run: `./gradlew test --tests "cloud.schneidoa.detection.OverrideDetectorTest"`
Expected: PASS — Step 5 already implemented the merge. If it fails with an empty `detected` list, the `truncatedAt` merge in `evaluate` is not wired; re-check Step 5.

- [ ] **Step 9: Run the full suite**

Run: `./gradlew check`
Expected: BUILD SUCCESSFUL. Watch for pre-existing tests that asserted an override is *dropped* for a module with an unresolvable parent — if any now expect `Inconclusive` instead, that is the fix working; update the test and note in its name that the drop was the bug.

- [ ] **Step 10: Commit** (ask first)

```bash
git add src/main/kotlin/cloud/schneidoa/detection/ src/test/kotlin/cloud/schneidoa/detection/
git commit -m "fix: report a truncated parent chain as Inconclusive instead of silently dropping overrides"
```

---

### Task 4: The report-fetch-retry loop

**Files:**
- Create: `src/main/kotlin/cloud/schneidoa/detection/MissingPomResolution.kt`
- Test: `src/test/kotlin/cloud/schneidoa/detection/MissingPomResolutionTest.kt`

**Interfaces:**
- Consumes: `RemotePomFetcher.fetch` (Task 1), `Gav`.
- Produces: `fun <T> resolvingMissingPoms(project: Project, fetcher: (Set<Gav>) -> Set<Gav> = …, maxRounds: Int = 3, resolve: ((Gav) -> Unit) -> T): T`. Task 5's four call sites depend on this signature.

- [ ] **Step 1: Write the failing tests**

Create `src/test/kotlin/cloud/schneidoa/detection/MissingPomResolutionTest.kt`.

This extends `BasePlatformTestCase` even though the class under test touches no PSI or DOM: it takes the read lock via `ReadAction.compute`, which needs a real application. That is a deliberate exception to the project's testing rule, not an oversight — note it in a comment so a future reader does not "correct" it to plain JUnit.

```kotlin
package cloud.schneidoa.detection

import cloud.schneidoa.resolver.Gav
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * BasePlatformTestCase rather than plain JUnit despite the class under test touching no
 * PSI/DOM: resolvingMissingPoms takes the read lock, which needs a real application.
 */
class MissingPomResolutionTest : BasePlatformTestCase() {

    private val missingA = Gav("com.example", "a", "1.0.0")
    private val missingB = Gav("com.example", "b", "1.0.0")

    fun `test returns the first result when nothing is missing`() {
        var rounds = 0
        val fetched = mutableListOf<Set<Gav>>()

        val result = resolvingMissingPoms(project, fetcher = { fetched += it; it }) { _ ->
            rounds++
            "done"
        }

        assertEquals("done", result)
        assertEquals(1, rounds)
        assertTrue("fetcher must not run when nothing was reported missing", fetched.isEmpty())
    }

    fun `test refetches and reresolves until nothing is missing`() {
        var rounds = 0

        val result = resolvingMissingPoms(project, fetcher = { it }) { onMissing ->
            rounds++
            // First pass reports a miss; once "fetched", the second pass finds everything.
            if (rounds == 1) onMissing(missingA)
            rounds
        }

        assertEquals(2, result)
        assertEquals(2, rounds)
    }

    fun `test stops when the fetcher cannot deliver anything`() {
        var rounds = 0

        resolvingMissingPoms(project, fetcher = { emptySet() }) { onMissing ->
            rounds++
            onMissing(missingA)
        }

        // One resolve, one failed fetch, then stop - not maxRounds attempts.
        assertEquals(1, rounds)
    }

    fun `test does not retry a coordinate that already failed to fetch`() {
        val requested = mutableListOf<Set<Gav>>()

        resolvingMissingPoms(
            project,
            fetcher = { request ->
                requested += request
                // B is deliverable, A never is.
                request.filter { it == missingB }.toSet()
            }
        ) { onMissing ->
            // Reports both every round, so the only thing that can shrink the request
            // is the loop remembering that A is unfetchable.
            onMissing(missingA)
            onMissing(missingB)
        }

        assertEquals(2, requested.size)
        assertEquals(setOf(missingA, missingB), requested[0])
        assertEquals(setOf(missingB), requested[1])
    }

    fun `test never asks the fetcher for a coordinate that still contains a property`() {
        val requested = mutableListOf<Set<Gav>>()
        var rounds = 0
        val unresolved = Gav("com.example", "acme-bom", "\${acme.version}")

        resolvingMissingPoms(project, fetcher = { requested += it; it }) { onMissing ->
            rounds++
            onMissing(unresolved)
        }

        // Nothing fetchable was reported, so the loop must stop after one resolve rather than
        // re-reporting the same placeholder until the round budget runs out.
        assertTrue("placeholder coordinates must never be requested, got $requested", requested.isEmpty())
        assertEquals(1, rounds)
    }

    fun `test stops at the round bound even when fetches keep succeeding`() {
        var rounds = 0

        resolvingMissingPoms(project, fetcher = { it }, maxRounds = 3) { onMissing ->
            rounds++
            onMissing(Gav("com.example", "endless-$rounds", "1.0.0"))
        }

        assertEquals(3, rounds)
    }
}
```

The last test is the termination guarantee: a chain that reveals a new missing POM on every round must stop, not spin.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests "cloud.schneidoa.detection.MissingPomResolutionTest"`
Expected: FAIL — `resolvingMissingPoms` is unresolved.

- [ ] **Step 3: Write the loop**

Create `src/main/kotlin/cloud/schneidoa/detection/MissingPomResolution.kt`:

```kotlin
package cloud.schneidoa.detection

import cloud.schneidoa.resolver.Gav
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project

/**
 * Runs a local, offline resolution repeatedly, fetching the POMs it reported missing between
 * attempts, until it stops reporting new ones.
 *
 * The obvious alternative - having the resolver fetch on demand, in the middle of the model
 * build - does not work, for two independent reasons worth recording here so nobody
 * "simplifies" it back:
 *
 *  1. A freshly downloaded file is invisible to the VFS until refreshed, and a synchronous
 *     VFS refresh throws under the read lock. Every caller resolves inside a read action.
 *  2. BomModelResult.Failure names the BOM whose build failed, not the POM that was actually
 *     missing - which for a broken parent is an artifact already on disk. An on-demand
 *     fetcher would keep re-requesting something it already has and never converge.
 *
 * [maxRounds] is a termination guarantee, not a tuning knob: a newly fetched BOM can reveal
 * nested imports and parents that were not visible before, so rounds could otherwise chain
 * indefinitely. Running out of rounds costs a residual Inconclusive, never a wrong answer.
 *
 * Must be called on a background thread: it takes the read lock itself, and [fetcher] does
 * network I/O and a VFS refresh outside it.
 */
fun <T> resolvingMissingPoms(
    project: Project,
    fetcher: (Set<Gav>) -> Set<Gav> = { RemotePomFetcher(project).fetch(it) },
    maxRounds: Int = 3,
    resolve: (onMissingPom: (Gav) -> Unit) -> T
): T {
    val unfetchable = mutableSetOf<Gav>()
    var round = 0

    while (true) {
        val missing = linkedSetOf<Gav>()
        val result = ReadAction.compute<T, Throwable> { resolve { missing += it } }
        round++

        // isConcrete filters out coordinates still carrying ${...}: before Maven sync,
        // MavenPropertyResolver returns its input unchanged, so a BOM declared at a property
        // version arrives as a placeholder. Those are not misses to fetch - and leaving them in
        // would make every round report the same unfetchable coordinate and burn the round
        // budget without ever converging.
        val toFetch = missing.filter { it.isConcrete() }.toSet() - unfetchable
        if (toFetch.isEmpty() || round >= maxRounds) return result

        val fetched = fetcher(toFetch)
        if (fetched.isEmpty()) return result
        unfetchable += toFetch - fetched
    }
}
```

The invariant in the last two lines is that recording failures is *conditioned on* something having been fetched — not the pairwise line order, which is unobservable here because the empty branch returns immediately. What must not happen is `unfetchable += toFetch - fetched` running unconditionally: a fully offline run would then mark every coordinate permanently unfetchable after one resolve and one fetch attempt, and a later round could never retry them.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew test --tests "cloud.schneidoa.detection.MissingPomResolutionTest"`
Expected: PASS, all six.

- [ ] **Step 5: Run the full suite**

Run: `./gradlew check`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit** (ask first)

```bash
git add src/main/kotlin/cloud/schneidoa/detection/MissingPomResolution.kt src/test/kotlin/cloud/schneidoa/detection/MissingPomResolutionTest.kt
git commit -m "feat: add report-fetch-retry loop for missing BOM POMs"
```

---

### Task 5: Wire the four call sites

The inspection is deliberately absent from this list. It runs synchronously per file on every edit; network there means editor latency and a hammered repository. The resulting divergence — the tool window answering confidently where the editor still says `Inconclusive` — is intended: the tool window is an inventory the user consults, the inspection is an interruption they did not ask for.

**Files:**
- Modify: `src/main/kotlin/cloud/schneidoa/detection/ProjectOverrideScanner.kt`
- Modify: `src/main/kotlin/cloud/schneidoa/detection/BomChainReportFactory.kt`
- Modify: `src/main/kotlin/cloud/schneidoa/ui/OverrideOverviewToolWindowFactory.kt:168-197` and `:252-267`
- Modify: `src/main/kotlin/cloud/schneidoa/ui/AddOverrideDialog.kt:197-225` and `:242-270`
- Modify: `src/main/kotlin/cloud/schneidoa/ui/EditOverrideDialog.kt:74-88`
- Test: `src/test/kotlin/cloud/schneidoa/detection/ProjectOverrideScannerTest.kt`

**Interfaces:**
- Consumes: `resolvingMissingPoms` (Task 4), `BomVersionResolver(dir, onMissingPom)` (Task 2), `BomChainResolver(dir, onMissingPom)` (Task 3).
- Produces:
  - `ProjectOverrideScanner(detectorFactory: (Project, (Gav) -> Unit) -> OverrideDetector = ::detectorFor)` with `fun scan(project: Project, onMissingPom: (Gav) -> Unit = {}): List<ProjectOverrideEntry>`
  - `buildBomChainReportFor(project, pomFile, ga, declaredVersion, moduleLabel, onMissingPom: (Gav) -> Unit = {})`

- [ ] **Step 1: Give `ProjectOverrideScanner` a reporting parameter**

In `src/main/kotlin/cloud/schneidoa/detection/ProjectOverrideScanner.kt`:

```kotlin
/** Default factory, extracted so the constructor default stays readable. */
private fun detectorFor(project: Project, onMissingPom: (Gav) -> Unit): OverrideDetector {
    val localRepositoryDir = MavenProjectsManager.getInstance(project).repositoryPath.toFile()
    return OverrideDetector(BomVersionResolver(localRepositoryDir, onMissingPom))
}

class ProjectOverrideScanner(
    private val detectorFactory: (Project, (Gav) -> Unit) -> OverrideDetector = ::detectorFor
) {
    fun scan(project: Project, onMissingPom: (Gav) -> Unit = {}): List<ProjectOverrideEntry> {
        val detector = detectorFactory(project, onMissingPom)
        ...
    }
```

Add the imports it now needs: `cloud.schneidoa.resolver.BomVersionResolver`, `cloud.schneidoa.resolver.Gav`, `org.jetbrains.idea.maven.project.MavenProjectsManager`.

- [ ] **Step 2: Update `ProjectOverrideScannerTest` for the new factory shape**

Its current injection at line 13 is `ProjectOverrideScanner { OverrideDetector(BomVersionResolver(localRepositoryDir)) }`. The lambda now takes two parameters:

```kotlin
ProjectOverrideScanner { _, onMissing -> OverrideDetector(BomVersionResolver(localRepositoryDir(), onMissing)) }
```

- [ ] **Step 3: Run the scanner test**

Run: `./gradlew test --tests "cloud.schneidoa.detection.ProjectOverrideScannerTest"`
Expected: PASS.

- [ ] **Step 4: Wire the tool window refresh**

In `OverrideOverviewToolWindowFactory.kt`, replace the `ReadAction.compute` inside `refresh()` — `resolvingMissingPoms` takes the read lock itself, so wrapping it in one would deadlock the fetch outside it:

```kotlin
            val entries = try {
                resolvingMissingPoms(project) { onMissing -> scanner.scan(project, onMissing) }
            } catch (e: Throwable) {
```

Leave everything else in `refresh()` alone — the generation guard, the separately-scoped Maven-sync probe, and the `SwingUtilities.invokeLater` hop are all still correct.

- [ ] **Step 5: Wire "Show BOM Chain"**

In `BomChainReportFactory.kt`, add the parameter and thread it into both resolvers:

```kotlin
fun buildBomChainReportFor(
    project: Project,
    pomFile: VirtualFile,
    ga: Ga,
    declaredVersion: String,
    moduleLabel: String,
    onMissingPom: (Gav) -> Unit = {}
): BomChainReport? {
    val model = MavenDomUtil.getMavenDomProjectModel(project, pomFile) ?: return null
    val localRepositoryDir = MavenProjectsManager.getInstance(project).repositoryPath.toFile()
    val chain = BomChainResolver(localRepositoryDir, onMissingPom).resolveBomChain(model, project)

    return buildBomChainReport(
        ga = ga,
        declaredVersion = declaredVersion,
        moduleLabel = moduleLabel,
        bomChain = chain.imports,
        resolver = BomVersionResolver(localRepositoryDir, onMissingPom)
    )
}
```

Add the `cloud.schneidoa.resolver.Gav` import.

In `OverrideOverviewToolWindowFactory.showBomChain`, replace the `ReadAction.compute` block:

```kotlin
            val report = try {
                resolvingMissingPoms(project) { onMissing ->
                    buildBomChainReportFor(
                        project = project,
                        pomFile = entry.pomFile,
                        ga = entry.override.candidate.ga,
                        declaredVersion = entry.override.candidate.declaredVersion,
                        moduleLabel = entry.moduleLabel,
                        onMissingPom = onMissing
                    )
                }
            } catch (ex: Throwable) {
```

- [ ] **Step 6: Wire the Add dialog**

`AddOverrideDialog`'s `hintProvider` property is currently `(Project) -> ManagedVersionHint`. Change it to `(Project, (Gav) -> Unit) -> ManagedVersionHint`, defaulting to a private top-level function in the same file:

```kotlin
private fun hintFor(project: Project, onMissingPom: (Gav) -> Unit): ManagedVersionHint {
    val localRepositoryDir = MavenProjectsManager.getInstance(project).repositoryPath.toFile()
    return ManagedVersionHint(BomVersionResolver(localRepositoryDir, onMissingPom))
}
```

In `loadHint()`:

```kotlin
                when (val lookup = resolvingMissingPoms(project) { onMissing ->
                    hintProvider(project, onMissing).lookup(choice.model, project, ga)
                }) {
```

In `reloadCandidates()`:

```kotlin
            val loaded = try {
                resolvingMissingPoms(project) { onMissing ->
                    DependencyCandidates(hintProvider(project, onMissing).catalog(choice.model, project))
                }
            } catch (e: Throwable) {
                null
            }
```

Both already run on a pooled thread with a generation guard, which is what makes the now-slower load safe: a user who changes module mid-fetch gets the newer result, not the stale one.

- [ ] **Step 7: Wire the Edit dialog**

Same `hintProvider` signature change in `EditOverrideDialog.kt`, with its own copy of the `hintFor` helper (or a shared one in `ManagedVersionHint.kt` — if you extract it, replace `ManagedVersionHint.forProject` with an overload taking `onMissingPom` and delete the duplicate). In `loadHint()`:

```kotlin
                when (val lookup = resolvingMissingPoms(project) { onMissing ->
                    hintProvider(project, onMissing).lookup(model, project, candidate.ga)
                }) {
```

Note `EditOverrideDialog.loadHint` has no generation guard today — it loads once on open and the dialog is modal, so there is no stale-result race to introduce. Leave it as it is; do not add speculative guarding.

Once both dialogs use `hintFor`, `ManagedVersionHint.forProject` has no production caller left. Prefer replacing it outright with `forProject(project, onMissingPom: (Gav) -> Unit = {})` and having both dialogs call that, rather than leaving a dead companion function beside a duplicated private helper. Run `grep -rn "ManagedVersionHint.forProject\|hintFor" src/` afterwards and confirm every remaining reference is live.

- [ ] **Step 8: Confirm the inspection is untouched**

Run: `grep -n "resolvingMissingPoms\|RemotePomFetcher" src/main/kotlin/cloud/schneidoa/detection/OverrideInspection.kt`
Expected: no output. `OverrideInspection` must still construct its detector via `detectorFactory(project)` with the no-op default, and therefore never fetch.

- [ ] **Step 9: Run the full suite and the plugin verifier**

Run: `./gradlew check verifyPlugin`
Expected: BUILD SUCCESSFUL, no `INTERNAL_API_USAGES`.

- [ ] **Step 10: (deferred — repo owner)** End-to-end sandbox verification moved to the **Manual verification** section at the end of this plan. Skip it here and proceed to the commit.

- [ ] **Step 11: Commit**

```bash
git add src/main/kotlin src/test/kotlin
git commit -m "feat: fetch missing BOM POMs for the tool window, dialogs and BOM chain view"
```

---

### Task 6: Offline visibility and documentation

The spec's failure-semantics section says the reasons a BOM went unchecked should be distinguishable. Threading a reason through `DetectedOverride` would ripple into every consumer to say the same thing on every row, so it goes in the tool window as one status line instead — same information, stated once. **This is a deliberate refinement of the spec; update the spec's "Failure semantics" paragraph to match rather than leaving the two out of step.**

**Files:**
- Modify: `src/main/kotlin/cloud/schneidoa/ui/OverrideOverviewToolWindowFactory.kt`
- Modify: `docs/superpowers/specs/2026-08-12-phase9-remote-bom-resolution-design.md`
- Modify: `CLAUDE.md`
- Modify: `CHANGELOG.md`

- [ ] **Step 1: Show an offline notice in the tool window**

Add the label as a field next to `table`, and hang it off the existing `JPanel(BorderLayout())` at line 95 — `NORTH` and `CENTER` are already taken by the toolbar and the scroll pane, so it goes `SOUTH`:

```kotlin
    private val offlineNotice = JLabel().apply { isVisible = false }
```

```kotlin
    val component: JPanel = JPanel(BorderLayout()).apply {
        add(createToolbar().component, BorderLayout.NORTH)
        add(JBScrollPane(table), BorderLayout.CENTER)
        add(offlineNotice, BorderLayout.SOUTH)
    }
```

In `refresh()`, on the pooled thread, alongside the existing Maven-sync probe and scoped separately for the same reason — a throw here must not discard an otherwise-good scan:

```kotlin
            val offline = try {
                MavenProjectsManager.getInstance(project).generalSettings.isWorkOffline
            } catch (e: Throwable) {
                logger.warn("Failed to read Maven offline setting", e)
                false
            }
```

Pass it through the existing `SwingUtilities.invokeLater` hop: `populate(entries, syncedRows, offline)`. Note the error path earlier in `refresh()` calls `populate(emptyList(), emptyList())` — give it `false` too.

In `populate`, extend the signature and set the label:

```kotlin
    private fun populate(
        entries: List<ProjectOverrideEntry>,
        syncedRows: List<Boolean>,
        offline: Boolean
    ) {
        // One message for the whole table rather than a per-row explanation: the cause is
        // global, and repeating it on every Inconclusive row would bury it rather than
        // surface it. This is also why the reason is not threaded through DetectedOverride.
        val anyInconclusive = entries.any { verdictOf(it.override) == OverrideVerdict.INCONCLUSIVE }
        offlineNotice.isVisible = offline && anyInconclusive
        offlineNotice.text = if (offlineNotice.isVisible) {
            "Maven is in offline mode - BOMs missing from the local repository were not downloaded."
        } else {
            ""
        }
        ...
```

Add the imports: `javax.swing.JLabel`, and `cloud.schneidoa.detection.verdictOf` / `OverrideVerdict` if the file does not already have them (it renders verdicts in the Status column, so it likely does — check before adding).

- [ ] **Step 2: (deferred — repo owner)** Sandbox verification of the notice moved to the **Manual verification** section at the end of this plan. Confirm it compiles (`./gradlew check`) and proceed.

- [ ] **Step 3: Reconcile the spec**

In `docs/superpowers/specs/2026-08-12-phase9-remote-bom-resolution-design.md`, replace the sentence beginning "`OverrideFormatting` distinguishes the reasons in its explanation text" with a description of the tool-window status line, and record why: the reason is global rather than per-override, so stating it once is both cheaper and clearer than threading it through `DetectedOverride`.

- [ ] **Step 4: Record the new invariants in `CLAUDE.md`**

Add to the "Load-bearing invariants" list:

- **`OverrideInspection` never fetches, and that asymmetry is the design.** Everything else (tool window, both dialogs, the BOM chain view) resolves through `resolvingMissingPoms`; the inspection resolves with the no-op default. It runs synchronously per file on every edit, so a network round-trip there is editor latency and repository load. The tool window answering confidently where the editor still says `Inconclusive` is expected, not a bug.
- **Missing POMs are reported, never fetched in place.** `LocalRepositoryModelResolver.resolve` and `BomChainResolver.resolveParentViaLocalRepository` report the coordinate and still fail; fetching happens in `resolvingMissingPoms`, outside the read action. Two reasons, both load-bearing: a synchronous VFS refresh throws under the read lock, and `BomModelResult.Failure` names the BOM that failed to build rather than the POM that was missing — so an on-demand fetcher would re-request artifacts it already has and never converge.
- **`RemotePomFetcher` must not reimplement mirrors, proxies or credentials.** It hands the repository list to the IDE's Maven server, which applies `settings.xml` mirrors, proxies and authentication inside Maven's own `RepositorySystem`. Reimplementing any of that here — mirror selection, `settings-security.xml` decryption — is the class of correctness bug the design doc rules out by embedding Maven's own machinery. Offline mode is honored before any embedder is obtained: `fetch` returns an empty set immediately when `MavenGeneralSettings.isWorkOffline` is set, so no request is ever built.
- **`RemotePomFetcher` uses the `@ApiStatus.Obsolete` `MavenEmbeddersManager` on purpose, not out of neglect.** Its modern replacement, `MavenEmbedderWrappers`/`MavenEmbedderWrappersManager`, is class-level `@ApiStatus.Internal` on 2026.2 — `./gradlew verifyPlugin` fails on it with `INTERNAL_API_USAGES`, and so does the Marketplace review. Obsolete-but-allowed beats modern-but-rejected. Anyone "modernizing" this must re-run `verifyPlugin` and see it pass first; same constraint recorded for `AnalyzeDependencyAction`.
- **A coordinate still containing `${...}` is never fetched.** `Gav.isConcrete()` gates `resolvingMissingPoms`, because `MavenPropertyResolver.resolve` returns its input unchanged until Maven sync has run (see the invariant above). Before the first sync, a BOM or parent declared at a property version reaches the resolver as a placeholder; that is a coordinate we do not know yet, not a miss we can download. Dropping the filter would send guaranteed-404 requests built from placeholder text and, worse, make every round re-report the same unfetchable coordinate until the round budget ran out.
- **A truncated parent chain is `Inconclusive`, not a short chain.** `BomChain.truncatedAt` exists because a parent POM missing from the local repository used to end the walk silently, letting `OverrideDetector` report a confident "nothing manages this" for an artifact a BOM above the break manages. `OverrideDetectorTest`'s truncated-chain case guards it.

Also update the `resolver/` and `detection/` architecture paragraphs to mention `onMissingPom`, `BomChain`, `MissingPomResolution.kt` and `RemotePomFetcher`, and amend the "Detection is local-only" framing in the opening section: detection is still *correct* offline, but the tool window and dialogs now fetch missing POMs.

- [ ] **Step 5: Update `CHANGELOG.md`**

Under the `## Unreleased` heading (no brackets — that is how this file writes it), matching the `### Added` / `### Fixed` grouping the `0.0.1` section uses:

```markdown
### Added
- BOM and parent POMs missing from the local Maven repository are now downloaded from the
  remote repositories configured in `settings.xml` — mirrors, proxies and credentials
  included — so the Override Overview, the Add/Edit dialogs and the BOM chain view can give
  a verdict where they previously reported "inconclusive". The editor inspection stays
  offline and unchanged.
- Maven's "Work offline" setting is honored: nothing is downloaded, and the Override
  Overview says so when it has inconclusive rows.

### Fixed
- A parent POM missing from the local repository silently ended the parent-chain walk,
  which could drop an override from the report entirely or report it as unmanaged. Such a
  chain is now reported as inconclusive.
```

- [ ] **Step 6: Final verification**

Run: `./gradlew check verifyPlugin`
Expected: BUILD SUCCESSFUL, no `INTERNAL_API_USAGES`.

- [ ] **Step 7: Commit** (ask first)

```bash
git add src/main/kotlin/cloud/schneidoa/ui/OverrideOverviewToolWindowFactory.kt CLAUDE.md CHANGELOG.md docs/
git commit -m "docs: record remote BOM resolution invariants and offline notice"
```

---

## Manual verification (repo owner, after Task 6)

Everything below needs a human at a sandbox IDE and cannot be done by an implementer subagent or from a test suite. The repo owner chose to defer all of it to the end rather than gate Task 1 on it — the accepted risk being that if check 1 fails, the fix is confined to `RemotePomFetcher` (Task 1): Tasks 2 through 5 already tolerate a fetcher that fetches nothing, so none of them need rescoping.

Run `./gradlew runIde`, then:

1. **The no-sync assumption (the one that can invalidate the design).** Open a Maven project and decline the import prompt so Maven never syncs, having first deleted a literal-versioned BOM from `~/.m2/repository`. Open the Override Overview tool window and refresh.
   *Expected:* the POM is downloaded and the rows resolve. *If it fails* with an embedder or "no maven project" error, `MavenEmbeddersManager.getEmbedder(FOR_DEPENDENCIES_RESOLVE, basePath)` needs a synced project after all. There is no alternative route to fall back to: the newer `MavenEmbedderWrappers`/`MavenEmbedderWrappersManager` is `@ApiStatus.Internal` and fails `verifyPlugin`, which is exactly why Task 1 rejected it in the first place. The honest outcome in that case is that the feature narrows to already-synced projects and the fresh-checkout case is dropped.
   Note that BOMs imported at a `${property}` version stay `Inconclusive` here by design — `MavenPropertyResolver` cannot resolve them before sync, so `isConcrete()` filters them out. Use a literal-versioned BOM for this check.

2. **The normal case.** With a synced Spring Boot project using `spring-boot-starter-parent`, delete the `spring-boot-dependencies` POM directory from `~/.m2/repository` and refresh the tool window.
   *Expected:* rows resolve to real verdicts rather than `Inconclusive`, and the POM reappears on disk. The editor inspection may still show `Inconclusive` until its next pass — that divergence is intended.

3. **Offline mode.** Delete the POM again, enable **Settings | Build, Execution, Deployment | Build Tools | Maven | Work offline**, refresh.
   *Expected:* rows stay `Inconclusive`, no network access, and the offline notice from Task 6 appears below the table. Disabling offline and refreshing makes the notice disappear and the rows resolve.

4. **Unreachable coordinate degrades quietly.** Point a module's `dependencyManagement` at a BOM that exists nowhere (`com.example:definitely-not-real:9.9.9`) and refresh.
   *Expected:* `Inconclusive`, a warning in the IDE log, no exception dialog, no hang beyond the normal HTTP timeout.

5. **The Add dialog under partial data.** With a BOM POM absent and offline still on, open the Add dialog on that module.
   *Expected:* it loads without hanging; suggestions are incomplete and the dialog says so via the existing `unreadableBomCount` path.

## Notes for the executor

- **Task 1's runtime assumption is unverified until the end.** The repo owner deferred all sandbox verification to the **Manual verification** section rather than gating Task 1 on it. Build Tasks 2–6 as written; if check 1 there fails, the consequence is that the feature narrows to already-synced projects and the fresh-checkout case is dropped — not rework of the reporting callback (Task 2), the truncation fix (Task 3), or the retry loop (Task 4), none of which that narrowing touches.
- **Tasks 2 and 3 are independently valuable.** Task 3 in particular is a security-relevant correctness fix that stands alone; if the remote work is abandoned, Task 3 should still ship.
- **Do not add a project- or application-level cache** for fetched results or effective models. The existing `BomEffectiveModelResolver` memoization is deliberately bounded to one short-lived instance because a re-installed SNAPSHOT BOM answered from a stale model is exactly how this plugin would call an override "safe to remove" on evidence that no longer holds.
