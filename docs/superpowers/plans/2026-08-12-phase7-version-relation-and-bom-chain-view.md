# Phase 7: Version Relation & BOM Chain View Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the uninformative `Confirmed`/`Inconclusive` status with a verdict that says whether an override is redundant, ahead of, or behind its BOM — and give each panel row a way to inspect where its version comes from.

**Architecture:** A pure-JVM version comparison in `resolver/` feeds a new `relation` field on `DetectedOverride.Confirmed`. `OverrideFormatting` turns that into a semantic verdict enum plus display strings; the panel maps the enum to icons and the inspection maps it to which problems (if any) to register. A new pure `BomChainReport` builder probes each BOM in the chain individually and backs a tree dialog.

**Tech Stack:** Kotlin, IntelliJ Platform 2025.3.5, Apache `maven-artifact` (`ComparableVersion`), JUnit 3-style `BasePlatformTestCase` for platform tests, plain JUnit 4 for pure tests.

## Global Constraints

- **Commit each task on `main`, locally.** Work directly on `main` — do not create branches or worktrees. Commit when your task's verification passes; the per-task review gate reads `git diff` between commits, so an uncommitted task cannot be reviewed. **Never push.** The user reviews the local history afterwards and decides what happens to it.
- Commit messages end with these two trailers:
  ```
  Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01DCj8mPK6ZJWjMk83YqZWuF
  ```
- **`resolver/` must contain no IntelliJ Platform APIs.** It stays unit-testable against fixture POMs. Anything touching PSI/DOM belongs in `detection/` or `ui/`.
- **"Miss rather than false-safe."** Never produce an actionable "safe to remove" verdict from data that wasn't verified. A wrong removal recommendation can silently reintroduce a patched CVE.
- **Target platform is pinned** to `intellijIdea("2025.3.5")`, `sinceBuild`/`untilBuild` = `253.*`. Do not widen.
- Run the full suite with `./gradlew check`. A single class: `./gradlew test --tests "cloud.schneidoa.resolver.VersionRelationTest"`. Backticked test names become globs: `./gradlew test --tests "cloud.schneidoa.detection.OverrideDetectorTest.test*redundant*"`.
- Existing fixture `com.example:acme-bom:1.0.0` manages `com.fasterxml.jackson.core:jackson-databind` at **2.15.3** and `com.example:widget-core` at **4.2.0**. Reuse it; do not add fixtures unless a task says to.
- `TestLoggerFactory` promotes logged errors into test failures. That is deliberate — do not weaken it to make a test pass.

---

### Task 1: Maven version comparison

**Files:**
- Create: `src/main/kotlin/cloud/schneidoa/resolver/VersionRelation.kt`
- Create: `src/test/kotlin/cloud/schneidoa/resolver/VersionRelationTest.kt`
- Modify: `gradle/libs.versions.toml`
- Modify: `build.gradle.kts` (the `dependencies` block)

**Interfaces:**
- Consumes: nothing (first task).
- Produces: `enum class VersionRelation { SAME, NEWER, OLDER, INCOMPARABLE }` and
  `fun compareDeclaredToManaged(declared: String, managed: String): VersionRelation`,
  both in package `cloud.schneidoa.resolver`.

- [ ] **Step 1: Declare `maven-artifact` explicitly**

It is currently only present transitively via `maven-model-builder`. This task imports from it directly, so it gets its own entry rather than relying on a transitive dependency staying put. Both artifacts ship from the same Maven release, so they share the version ref.

In `gradle/libs.versions.toml`, under `[libraries]`, add:

```toml
maven-artifact = { module = "org.apache.maven:maven-artifact", version.ref = "mavenModelBuilder" }
```

In `build.gradle.kts`, in the `dependencies` block, directly under the existing `implementation(libs.maven.model.builder)` line, add:

```kotlin
    implementation(libs.maven.artifact)
```

- [ ] **Step 2: Write the failing test**

Create `src/test/kotlin/cloud/schneidoa/resolver/VersionRelationTest.kt`:

```kotlin
package cloud.schneidoa.resolver

import org.junit.Assert.assertEquals
import org.junit.Test

class VersionRelationTest {

    @Test
    fun `declared above managed is NEWER`() {
        assertEquals(VersionRelation.NEWER, compareDeclaredToManaged("2.15.4", "2.15.3"))
    }

    @Test
    fun `identical versions are SAME`() {
        assertEquals(VersionRelation.SAME, compareDeclaredToManaged("2.15.3", "2.15.3"))
    }

    @Test
    fun `declared below managed is OLDER`() {
        assertEquals(VersionRelation.OLDER, compareDeclaredToManaged("2.15.2", "2.15.3"))
    }

    // The case a naive string comparison gets backwards: "2.21.10" < "2.21.9" lexically.
    @Test
    fun `numeric segments compare numerically not lexically`() {
        assertEquals(VersionRelation.NEWER, compareDeclaredToManaged("2.21.10", "2.21.9"))
    }

    // Maven orders a qualifier below the bare release it qualifies.
    @Test
    fun `a prerelease qualifier sorts below the plain release`() {
        assertEquals(VersionRelation.OLDER, compareDeclaredToManaged("1.0-alpha", "1.0"))
    }

    @Test
    fun `an unresolved property on the declared side is INCOMPARABLE`() {
        assertEquals(VersionRelation.INCOMPARABLE, compareDeclaredToManaged("\${jackson.version}", "2.15.3"))
    }

    @Test
    fun `an unresolved property on the managed side is INCOMPARABLE`() {
        assertEquals(VersionRelation.INCOMPARABLE, compareDeclaredToManaged("2.15.3", "\${jackson.version}"))
    }

    @Test
    fun `a version range is INCOMPARABLE`() {
        assertEquals(VersionRelation.INCOMPARABLE, compareDeclaredToManaged("[1.0,2.0)", "2.15.3"))
    }

    @Test
    fun `a blank version is INCOMPARABLE`() {
        assertEquals(VersionRelation.INCOMPARABLE, compareDeclaredToManaged("  ", "2.15.3"))
    }

    @Test
    fun `surrounding whitespace does not affect the comparison`() {
        assertEquals(VersionRelation.SAME, compareDeclaredToManaged(" 2.15.3 ", "2.15.3"))
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `./gradlew test --tests "cloud.schneidoa.resolver.VersionRelationTest"`
Expected: FAIL — compilation error, `compareDeclaredToManaged` and `VersionRelation` are unresolved.

- [ ] **Step 4: Write the implementation**

Create `src/main/kotlin/cloud/schneidoa/resolver/VersionRelation.kt`:

```kotlin
package cloud.schneidoa.resolver

import org.apache.maven.artifact.versioning.ComparableVersion

/** How a declared override version relates to what a BOM chain manages the same artifact at. */
enum class VersionRelation { SAME, NEWER, OLDER, INCOMPARABLE }

/**
 * Characters that only appear in Maven version *ranges* ("[1.0,2.0)"), which
 * name an interval rather than a point and so have nothing to compare against
 * a single managed version.
 */
private val RANGE_MARKERS = listOf('[', ']', '(', ')', ',')

/**
 * Orders [declared] against [managed] using Maven's own version semantics, so
 * "2.21.10" ranks above "2.21.9" and "1.0-alpha" below "1.0" — both of which a
 * lexical comparison gets wrong.
 *
 * Returns [VersionRelation.INCOMPARABLE] rather than guessing whenever an input
 * isn't a single concrete version. ComparableVersion will happily order any
 * string, so without this guard an unresolved "${'$'}{jackson.version}" (which is what
 * MavenPropertyResolver hands back when the property is undefined) or a version
 * range would produce a confident-looking verdict derived from nonsense. This
 * plugin declines to answer instead - a wrong "safe to remove" can silently
 * reintroduce a patched CVE.
 */
fun compareDeclaredToManaged(declared: String, managed: String): VersionRelation {
    if (!isSingleConcreteVersion(declared) || !isSingleConcreteVersion(managed)) {
        return VersionRelation.INCOMPARABLE
    }

    val comparison = ComparableVersion(declared.trim()).compareTo(ComparableVersion(managed.trim()))
    return when {
        comparison == 0 -> VersionRelation.SAME
        comparison > 0 -> VersionRelation.NEWER
        else -> VersionRelation.OLDER
    }
}

private fun isSingleConcreteVersion(version: String): Boolean {
    val trimmed = version.trim()
    return trimmed.isNotEmpty() &&
        !trimmed.contains("\${") &&
        RANGE_MARKERS.none { trimmed.contains(it) }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew test --tests "cloud.schneidoa.resolver.VersionRelationTest"`
Expected: PASS, 10 tests.

- [ ] **Step 6: Verify nothing else broke**

Run: `./gradlew check`
Expected: BUILD SUCCESSFUL. Then commit this task (see Global Constraints for the required trailers).

---

### Task 2: Carry the relation through detection, and stop dropping redundant pins

**Files:**
- Modify: `src/main/kotlin/cloud/schneidoa/detection/OverrideDetector.kt`
- Modify: `src/test/kotlin/cloud/schneidoa/detection/OverrideDetectorTest.kt`

**Interfaces:**
- Consumes: `VersionRelation`, `compareDeclaredToManaged` from Task 1.
- Produces: `DetectedOverride.Confirmed` gains a fifth constructor parameter
  `relation: VersionRelation`, positioned last. Later tasks read
  `(override as DetectedOverride.Confirmed).relation`.

**Behavioral change to be aware of:** `evaluate` currently returns `null` when the declared version equals the managed one, so redundant pins are invisible everywhere. After this task they are reported as `Confirmed(relation = SAME)`. The existing test `test drops a candidate whose version already matches the BOM` (`OverrideDetectorTest.kt:42`) asserts the old behavior and is **rewritten**, not deleted — the scenario still matters, only the expectation flips.

- [ ] **Step 1: Rewrite the test that asserts the old drop-on-equal behavior**

In `src/test/kotlin/cloud/schneidoa/detection/OverrideDetectorTest.kt`, replace the whole `test drops a candidate whose version already matches the BOM` function with:

```kotlin
    fun `test reports a redundant candidate whose version already matches the BOM`() {
        val model = configureModuleImportingAcmeBom(
            """
            <dependency>
                <groupId>com.fasterxml.jackson.core</groupId>
                <artifactId>jackson-databind</artifactId>
                <version>2.15.3</version>
            </dependency>
            """.trimIndent()
        )
        val detector = OverrideDetector(BomVersionResolver(localRepositoryDir()))

        val confirmed = detector.detect(model, project).single() as DetectedOverride.Confirmed

        assertEquals(VersionRelation.SAME, confirmed.relation)
        assertEquals("2.15.3", confirmed.bomVersion)
    }
```

- [ ] **Step 2: Add tests for the three remaining relations**

Immediately after the function added in Step 1, insert:

```kotlin
    fun `test a candidate above the BOM version is reported as NEWER`() {
        val model = configureModuleImportingAcmeBom(
            """
            <dependency>
                <groupId>com.fasterxml.jackson.core</groupId>
                <artifactId>jackson-databind</artifactId>
                <version>2.15.4</version>
            </dependency>
            """.trimIndent()
        )
        val detector = OverrideDetector(BomVersionResolver(localRepositoryDir()))

        val confirmed = detector.detect(model, project).single() as DetectedOverride.Confirmed

        assertEquals(VersionRelation.NEWER, confirmed.relation)
    }

    fun `test a candidate below the BOM version is reported as OLDER`() {
        val model = configureModuleImportingAcmeBom(
            """
            <dependency>
                <groupId>com.fasterxml.jackson.core</groupId>
                <artifactId>jackson-databind</artifactId>
                <version>2.15.2</version>
            </dependency>
            """.trimIndent()
        )
        val detector = OverrideDetector(BomVersionResolver(localRepositoryDir()))

        val confirmed = detector.detect(model, project).single() as DetectedOverride.Confirmed

        assertEquals(VersionRelation.OLDER, confirmed.relation)
    }

    fun `test a candidate pinned to an unresolvable property is reported as INCOMPARABLE`() {
        val model = configureModuleImportingAcmeBom(
            """
            <dependency>
                <groupId>com.fasterxml.jackson.core</groupId>
                <artifactId>jackson-databind</artifactId>
                <version>${'$'}{no.such.property}</version>
            </dependency>
            """.trimIndent()
        )
        val detector = OverrideDetector(BomVersionResolver(localRepositoryDir()))

        val confirmed = detector.detect(model, project).single() as DetectedOverride.Confirmed

        assertEquals(VersionRelation.INCOMPARABLE, confirmed.relation)
    }
```

Add `import cloud.schneidoa.resolver.VersionRelation` to the file's imports.

- [ ] **Step 3: Run the tests to verify they fail**

Run: `./gradlew test --tests "cloud.schneidoa.detection.OverrideDetectorTest"`
Expected: FAIL — compilation error, `Confirmed` has no `relation` property.

- [ ] **Step 4: Add the field and stop discarding equal versions**

In `src/main/kotlin/cloud/schneidoa/detection/OverrideDetector.kt`, add these imports:

```kotlin
import cloud.schneidoa.resolver.VersionRelation
import cloud.schneidoa.resolver.compareDeclaredToManaged
```

Change the `Confirmed` declaration to:

```kotlin
    /**
     * An unsuppressed override whose managing BOM chain resolved cleanly, so we
     * know exactly what the BOM manages it at. [relation] says how the declared
     * version compares to that — which is what decides whether the override is
     * redundant, still doing useful work, or holding the version back.
     */
    data class Confirmed(
        val candidate: OverrideCandidate,
        val bomVersion: String,
        val declaredInBom: Gav,
        val managedByChain: List<String>,
        val relation: VersionRelation
    ) : DetectedOverride()
```

Replace the `is ManagedVersionLookup.Found ->` branch of `evaluate` with:

```kotlin
            is ManagedVersionLookup.Found ->
                if (lookup.uncheckedBoms.isNotEmpty()) {
                    DetectedOverride.Inconclusive(candidate, lookup.uncheckedBoms)
                } else {
                    // Equal versions are reported too, not filtered out: a pin the BOM has
                    // exactly caught up to is the redundant one this plugin exists to find.
                    val declaredVia = bomChain.firstOrNull { it.bom == lookup.declaredIn }?.declaredVia ?: emptyList()
                    DetectedOverride.Confirmed(
                        candidate,
                        lookup.version,
                        lookup.declaredIn,
                        declaredVia,
                        compareDeclaredToManaged(candidate.declaredVersion, lookup.version)
                    )
                }
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew test --tests "cloud.schneidoa.detection.OverrideDetectorTest"`
Expected: PASS.

- [ ] **Step 6: Run the full suite and expect known failures**

Run: `./gradlew check`
Expected: FAIL. `OverrideFormattingTest`, `OverrideInspectionTest` and `ProjectOverrideScannerTest` may fail because redundant pins now appear where those tests expected nothing, and because `statusText` still says `"Confirmed"`. **Do not fix them here** — Tasks 3 and 4 own those files. Record which tests failed so the later tasks can confirm they went green. Then commit this task (see Global Constraints for the required trailers).

---

### Task 3: Verdict formatting

**Files:**
- Modify: `src/main/kotlin/cloud/schneidoa/detection/OverrideFormatting.kt`
- Modify: `src/test/kotlin/cloud/schneidoa/detection/OverrideFormattingTest.kt`

**Interfaces:**
- Consumes: `DetectedOverride.Confirmed.relation` from Task 2.
- Produces, all in package `cloud.schneidoa.detection`:
  - `enum class OverrideVerdict { REDUNDANT, AHEAD_OF_BOM, BEHIND_BOM, NOT_COMPARABLE, INCONCLUSIVE }`
  - `fun verdictOf(override: DetectedOverride): OverrideVerdict`
  - `fun verdictLabel(override: DetectedOverride): String`
  - `fun verdictExplanation(override: DetectedOverride): String`
  - `statusText` is **removed**; Task 5 replaces its only production call site.

The verdict deliberately carries no `javax.swing.Icon`. Icon choice is a presentation concern and lives in `ui/` (Task 5), which keeps these three functions testable as plain string logic.

- [ ] **Step 1: Replace the statusText tests**

In `src/test/kotlin/cloud/schneidoa/detection/OverrideFormattingTest.kt`, delete every test whose name mentions `statusText` and add these in their place. Keep the existing `declaredToManaged` and `managedByChain` tests untouched.

```kotlin
    fun `test verdict of an override matching the BOM is REDUNDANT`() {
        val confirmed = detectSingleAgainstAcmeBom("2.15.3")

        assertEquals(OverrideVerdict.REDUNDANT, verdictOf(confirmed))
        assertEquals("Redundant", verdictLabel(confirmed))
        assertTrue(verdictExplanation(confirmed).contains("can be removed"))
    }

    fun `test verdict of an override above the BOM is AHEAD_OF_BOM`() {
        val confirmed = detectSingleAgainstAcmeBom("2.15.4")

        assertEquals(OverrideVerdict.AHEAD_OF_BOM, verdictOf(confirmed))
        assertEquals("Ahead of BOM", verdictLabel(confirmed))
    }

    fun `test verdict of an override below the BOM is BEHIND_BOM`() {
        val confirmed = detectSingleAgainstAcmeBom("2.15.2")

        assertEquals(OverrideVerdict.BEHIND_BOM, verdictOf(confirmed))
        assertEquals("Behind BOM", verdictLabel(confirmed))
        assertTrue(verdictExplanation(confirmed).contains("below"))
    }

    fun `test verdict of an unresolvable property version is NOT_COMPARABLE`() {
        val confirmed = detectSingleAgainstAcmeBom("${'$'}{no.such.property}")

        assertEquals(OverrideVerdict.NOT_COMPARABLE, verdictOf(confirmed))
        assertEquals("Not comparable", verdictLabel(confirmed))
    }

    fun `test verdict label for an inconclusive override names the unchecked BOM count`() {
        val file = myFixture.configureByText(
            "pom.xml",
            """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.example</groupId>
                <artifactId>test-module</artifactId>
                <version>1.0.0</version>

                <dependencyManagement>
                    <dependencies>
                        <dependency>
                            <groupId>com.example</groupId>
                            <artifactId>does-not-exist-bom</artifactId>
                            <version>9.9.9</version>
                            <type>pom</type>
                            <scope>import</scope>
                        </dependency>
                        <dependency>
                            <groupId>com.fasterxml.jackson.core</groupId>
                            <artifactId>jackson-databind</artifactId>
                            <version>2.15.4</version>
                        </dependency>
                    </dependencies>
                </dependencyManagement>
            </project>
            """.trimIndent()
        )
        val model = MavenDomUtil.getMavenDomProjectModel(project, file.virtualFile)!!
        val detector = OverrideDetector(BomVersionResolver(localRepositoryDir()))

        val inconclusive = detector.detect(model, project).single()

        assertEquals(OverrideVerdict.INCONCLUSIVE, verdictOf(inconclusive))
        assertEquals("Inconclusive (1 BOM unchecked)", verdictLabel(inconclusive))
    }

    private fun detectSingleAgainstAcmeBom(declaredVersion: String): DetectedOverride {
        val model = configureModuleImportingAcmeBom(
            """
            <dependency>
                <groupId>com.fasterxml.jackson.core</groupId>
                <artifactId>jackson-databind</artifactId>
                <version>$declaredVersion</version>
            </dependency>
            """.trimIndent()
        )
        return OverrideDetector(BomVersionResolver(localRepositoryDir())).detect(model, project).single()
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests "cloud.schneidoa.detection.OverrideFormattingTest"`
Expected: FAIL — compilation error, `OverrideVerdict` / `verdictOf` unresolved.

- [ ] **Step 3: Replace statusText with the verdict functions**

In `src/main/kotlin/cloud/schneidoa/detection/OverrideFormatting.kt`, add the import:

```kotlin
import cloud.schneidoa.resolver.VersionRelation
```

Delete the entire `statusText` function and add:

```kotlin
/**
 * What the developer should conclude about an override. Deliberately semantic
 * rather than presentational - the panel maps this to an icon and the inspection
 * maps it to whether to register a problem at all, and those two answers differ.
 */
enum class OverrideVerdict { REDUNDANT, AHEAD_OF_BOM, BEHIND_BOM, NOT_COMPARABLE, INCONCLUSIVE }

fun verdictOf(override: DetectedOverride): OverrideVerdict = when (override) {
    is DetectedOverride.Inconclusive -> OverrideVerdict.INCONCLUSIVE
    is DetectedOverride.Confirmed -> when (override.relation) {
        VersionRelation.SAME -> OverrideVerdict.REDUNDANT
        VersionRelation.NEWER -> OverrideVerdict.AHEAD_OF_BOM
        VersionRelation.OLDER -> OverrideVerdict.BEHIND_BOM
        VersionRelation.INCOMPARABLE -> OverrideVerdict.NOT_COMPARABLE
    }
}

/** Short label for the panel's Status column. */
fun verdictLabel(override: DetectedOverride): String = when (verdictOf(override)) {
    OverrideVerdict.REDUNDANT -> "Redundant"
    OverrideVerdict.AHEAD_OF_BOM -> "Ahead of BOM"
    OverrideVerdict.BEHIND_BOM -> "Behind BOM"
    OverrideVerdict.NOT_COMPARABLE -> "Not comparable"
    OverrideVerdict.INCONCLUSIVE -> {
        val count = (override as DetectedOverride.Inconclusive).uncheckedBoms.size
        "Inconclusive ($count BOM${if (count == 1) "" else "s"} unchecked)"
    }
}

/** Full sentence, used as the column tooltip and as the inspection's problem description. */
fun verdictExplanation(override: DetectedOverride): String = when (override) {
    is DetectedOverride.Inconclusive ->
        "Cannot confirm whether this override is still needed - some BOMs could not be resolved locally, so removal isn't offered here"
    is DetectedOverride.Confirmed -> when (override.relation) {
        VersionRelation.SAME ->
            "${managedByChain(override)} already manages this at ${override.bomVersion} - this override can be removed"
        VersionRelation.NEWER ->
            "This override raises the version above the ${override.bomVersion} managed by ${managedByChain(override)}, so it is still taking effect"
        VersionRelation.OLDER ->
            "This override holds the version below the ${override.bomVersion} managed by ${managedByChain(override)} - removing it lets the newer BOM version apply"
        VersionRelation.INCOMPARABLE ->
            "The declared version cannot be ordered against the ${override.bomVersion} managed by ${managedByChain(override)}"
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew test --tests "cloud.schneidoa.detection.OverrideFormattingTest"`
Expected: PASS.

- [ ] **Step 5: Fix the now-broken production call site**

`OverrideOverviewToolWindowFactory.kt` still imports and calls `statusText`, so the module will not compile. As a temporary bridge until Task 5 builds the real renderer, change its import of `statusText` to `verdictLabel` and the call in `populate` from `statusText(entry.override)` to `verdictLabel(entry.override)`.

Run: `./gradlew check`
Expected: `OverrideFormattingTest` passes. `OverrideInspectionTest` and possibly `ProjectOverrideScannerTest` may still fail — Task 4 owns the first, and the second is addressed in Task 4 Step 5. Then commit this task (see Global Constraints for the required trailers).

---

### Task 4: Inspection reports the distinction, and stops nagging about effective pins

**Files:**
- Modify: `src/main/kotlin/cloud/schneidoa/detection/OverrideInspection.kt`
- Modify: `src/test/kotlin/cloud/schneidoa/detection/OverrideInspectionTest.kt`
- Modify: `src/test/kotlin/cloud/schneidoa/detection/ProjectOverrideScannerTest.kt` (only if Task 2 Step 6 recorded it as failing)

**Interfaces:**
- Consumes: `OverrideVerdict`, `verdictOf`, `verdictExplanation` from Task 3.
- Produces: no new public API.

**The decision this task encodes:** an override *above* its BOM version is not stale — it is doing exactly the job it was written for. Warning about it is the noise that motivated this whole phase, so it produces no editor problem at all while remaining visible in the panel. `NOT_COMPARABLE` is silent for a different reason: no verdict was reached, so there is nothing to assert.

- [ ] **Step 1: Repoint the existing tests that pin 2.15.4**

`OverrideInspectionTest` has **no** `configureModuleImportingAcmeBom` helper — every test inlines its own `myFixture.configureByText` POM and gets the inspection from `testInspection()`. Follow that existing shape; do not introduce a second setup mechanism.

Three existing tests pin `<version>2.15.4</version>`, which is *above* the fixture BOM's 2.15.3 and therefore becomes silent under this task. All three are about "a confirmed override warns and offers fixes", which is still true — of a **redundant** one. So change `2.15.4` to `2.15.3` in the `jackson-databind` block of each:

- `test registers a weak warning on a confirmed override` (line ~39)
- `test confirmed override message includes the full parent chain to the managing BOM` (line ~98)
- `test offers both remove and suppress together on a confirmed override` (line ~222)

Their assertions all keep holding with the new message: `contains("acme-bom")` and `contains("2.15.3")` are both satisfied by `"com.example:acme-bom:1.0.0 already manages this at 2.15.3 - this override can be removed"`, and the parent-chain test's `contains("parent → com.example:acme-bom:1.0.0")` is unaffected because `managedByChain` is unchanged.

The two tests covering the unresolvable-BOM case and the non-Maven file are unaffected — leave them alone.

- [ ] **Step 2: Invert the test asserting the old drop-on-equal behavior**

`test does not warn when the version already matches the BOM` (line ~117) pins `2.15.3` and asserts **no** highlight. That is precisely the behavior this phase reverses. Rename it and flip the assertion, keeping its inline POM exactly as it is apart from the version:

```kotlin
    fun `test warns that an override matching the BOM version is redundant`() {
        // ... existing setup, POM unchanged, still pinning 2.15.3 ...

        val highlights = myFixture.doHighlighting()
        val overrideHighlight = highlights.singleOrNull { it.description?.contains("acme-bom") == true }

        assertNotNull("Expected a redundant-override highlight", overrideHighlight)
        assertTrue(overrideHighlight!!.description.contains("can be removed"))
    }
```

Then add a replacement for the "does not warn" scenario it used to cover, this time for the case that genuinely should stay silent — copy that test's POM verbatim and change only the version to `2.15.4`:

```kotlin
    fun `test does not warn when the override is above the BOM version`() {
        // ... same inline POM as the test above, but <version>2.15.4</version> ...

        val highlights = myFixture.doHighlighting()

        assertTrue(highlights.none { it.description?.contains("acme-bom") == true })
    }
```

This assertion is the one guarding this task's central decision — an override still doing its job produces no editor noise. It asserts an absence, exactly the kind of thing that silently regresses in a later refactor, so it is stated explicitly rather than left implied.

- [ ] **Step 3: Add the below-BOM test**

Copy the same inline POM once more, with `<version>2.15.2</version>`, and assert that it warns and offers both fixes — removal is how the developer takes the BOM's newer version. Note the existing idiom is `familyName`, not `text`:

```kotlin
    fun `test an override below the BOM offers remove so the newer BOM version applies`() {
        // ... same inline POM, but <version>2.15.2</version> ...

        val highlights = myFixture.doHighlighting()
        val overrideHighlight = highlights.singleOrNull { it.description?.contains("acme-bom") == true }

        assertNotNull("Expected a behind-BOM highlight", overrideHighlight)
        assertTrue(overrideHighlight!!.description.contains("lets the newer BOM version apply"))
        assertEquals(
            setOf("Remove dependency override", "Suppress this override warning"),
            myFixture.getAllQuickFixes().map { it.familyName }.toSet()
        )
    }
```

- [ ] **Step 4: Fix the now-toothless guard in the non-Maven test**

`test does nothing on a non-Maven XML file` filters on `it.description?.contains("Overrides")`. No message produced by this plugin contains the word "Overrides" any more, so that half of the predicate can never match and the test has quietly lost half its teeth. Change the predicate to:

```kotlin
        assertTrue(highlights.none { it.description?.contains("manages this") == true || it.description?.contains("Cannot confirm") == true })
```

- [ ] **Step 5: Run the tests to verify they fail**

Run: `./gradlew test --tests "cloud.schneidoa.detection.OverrideInspectionTest"`
Expected: FAIL — the redundant case registers no problem yet, and `2.15.4` still does.

- [ ] **Step 6: Rewrite the inspection's reporting**

In `src/main/kotlin/cloud/schneidoa/detection/OverrideInspection.kt`, replace the whole `for (result in detector.detect(model, project))` loop with:

```kotlin
                for (result in detector.detect(model, project)) {
                    when (verdictOf(result)) {
                        // Redundant: the BOM caught up, so removal is the whole point.
                        OverrideVerdict.REDUNDANT -> holder.registerProblem(
                            result.candidate.versionXmlTag,
                            verdictExplanation(result),
                            ProblemHighlightType.WEAK_WARNING,
                            RemoveOverrideQuickFix(),
                            SuppressOverrideQuickFix()
                        )
                        // Behind the BOM: removing the pin raises the version to the BOM's,
                        // which is the desired outcome - such a pin is nearly always one set
                        // once and never revisited, now holding the dependency below what the
                        // BOM already ships. The message says so explicitly, so the fix isn't
                        // a silent build-affecting change.
                        OverrideVerdict.BEHIND_BOM -> holder.registerProblem(
                            result.candidate.versionXmlTag,
                            verdictExplanation(result),
                            ProblemHighlightType.WEAK_WARNING,
                            RemoveOverrideQuickFix(),
                            SuppressOverrideQuickFix()
                        )
                        OverrideVerdict.INCONCLUSIVE -> holder.registerProblem(
                            result.candidate.versionXmlTag,
                            verdictExplanation(result),
                            ProblemHighlightType.WEAK_WARNING,
                            SuppressOverrideQuickFix()
                        )
                        // Silent on purpose. AHEAD_OF_BOM is a pin that is still doing its
                        // job - warning about it is noise. NOT_COMPARABLE reached no verdict,
                        // so there is nothing to say. Both stay visible in the tool window.
                        OverrideVerdict.AHEAD_OF_BOM, OverrideVerdict.NOT_COMPARABLE -> Unit
                    }
                }
```

Remove the now-unused `DetectedOverride` import only if the compiler flags it; `result.candidate` still resolves via the extension property.

- [ ] **Step 7: Run the tests to verify they pass**

Run: `./gradlew test --tests "cloud.schneidoa.detection.OverrideInspectionTest"`
Expected: PASS.

- [ ] **Step 8: Reconcile the project scanner tests**

Run: `./gradlew test --tests "cloud.schneidoa.detection.ProjectOverrideScannerTest"`

If any test fails because a POM in its fixture now yields an extra row that used to be dropped as an equal-version match, update that test's expectation to include the new row. Do **not** re-add a filter to make it pass — surfacing redundant pins is this phase's point.

- [ ] **Step 9: Verify the whole suite**

Run: `./gradlew check`
Expected: BUILD SUCCESSFUL. Then commit this task (see Global Constraints for the required trailers).

---

### Task 5: Status column shows icon, label and tooltip

**Files:**
- Modify: `src/main/kotlin/cloud/schneidoa/ui/OverrideOverviewToolWindowFactory.kt`

**Interfaces:**
- Consumes: `OverrideVerdict`, `verdictOf`, `verdictLabel`, `verdictExplanation` from Task 3.
- Produces: no new public API. The Status column's model value becomes the `ProjectOverrideEntry` itself rather than a `String`, so the renderer can reach both the label and the explanation.

This task has no automated test. It follows the precedent set in Phase 4 and 6 that the Swing glue in `OverrideOverviewToolWindowFactory` is verified by hand in `./gradlew runIde`, with all the interesting logic pushed into tested functions elsewhere — which is exactly what Task 3 did.

- [ ] **Step 1: Add the imports**

```kotlin
import cloud.schneidoa.detection.OverrideVerdict
import cloud.schneidoa.detection.verdictExplanation
import cloud.schneidoa.detection.verdictLabel
import cloud.schneidoa.detection.verdictOf
import java.awt.Component
import javax.swing.JTable
import javax.swing.table.DefaultTableCellRenderer
```

Remove the `import cloud.schneidoa.detection.statusText` line if it is still present (Task 3 Step 5 changed it to `verdictLabel`; that import goes away too, since `verdictLabel` is now only called from the renderer).

- [ ] **Step 2: Add the renderer**

At the bottom of the file, outside the panel class:

```kotlin
private fun iconFor(verdict: OverrideVerdict) = when (verdict) {
    OverrideVerdict.REDUNDANT -> AllIcons.General.GreenCheckmark
    OverrideVerdict.AHEAD_OF_BOM -> AllIcons.General.ArrowUp
    OverrideVerdict.BEHIND_BOM -> AllIcons.General.Warning
    OverrideVerdict.NOT_COMPARABLE -> AllIcons.General.Information
    OverrideVerdict.INCONCLUSIVE -> AllIcons.General.ShowWarning
}

/**
 * Renders the Status column from the entry itself rather than a pre-formatted
 * string, so the icon, the short label and the full explanation all come from
 * one verdict instead of three parallel columns of derived text.
 */
private class VerdictCellRenderer : DefaultTableCellRenderer() {
    override fun getTableCellRendererComponent(
        table: JTable,
        value: Any?,
        isSelected: Boolean,
        hasFocus: Boolean,
        row: Int,
        column: Int
    ): Component {
        val entry = value as? ProjectOverrideEntry
        super.getTableCellRendererComponent(table, entry?.let { verdictLabel(it.override) } ?: "", isSelected, hasFocus, row, column)
        icon = entry?.let { iconFor(verdictOf(it.override)) }
        toolTipText = entry?.let { verdictExplanation(it.override) }
        return this
    }
}
```

- [ ] **Step 3: Feed the entry into the Status column and install the renderer**

In `populate`, change the last element of the row array from `verdictLabel(entry.override)` to `entry`.

In the `table` initializer's `apply { ... }` block, after `columnSelectionAllowed = false`, add:

```kotlin
        columnModel.getColumn(COLUMNS.lastIndex).cellRenderer = VerdictCellRenderer()
```

`COLUMNS.lastIndex` rather than a literal `4` so the renderer follows the column if one is ever inserted before it.

- [ ] **Step 4: Verify it compiles and the suite is green**

Run: `./gradlew check`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Verify by hand**

Run: `./gradlew runIde`

In the sandbox IDE, open a Maven project that imports a BOM, open the **Maven Overrides** tool window, and confirm: each row shows an icon plus a short label, hovering a Status cell shows the full sentence, and a pin matching its BOM reads "Redundant". Then commit this task (see Global Constraints for the required trailers).

---

### Task 6: BOM chain report model

**Files:**
- Create: `src/main/kotlin/cloud/schneidoa/detection/BomChainReport.kt`
- Create: `src/test/kotlin/cloud/schneidoa/detection/BomChainReportTest.kt`

**Interfaces:**
- Consumes: `BomImport` (from `BomChainResolver.kt`), `BomVersionResolver`, `ManagedVersionLookup`, `Ga`, `Gav`.
- Produces, in package `cloud.schneidoa.detection`:
  - `sealed class BomChainEntry` with `Manages(bom, declaredVia, version, wins)`, `DoesNotManage(bom, declaredVia)`, `Unresolvable(bom, declaredVia)`
  - `data class BomChainReport(ga, declaredVersion, moduleLabel, entries)`
  - `fun buildBomChainReport(ga, declaredVersion, moduleLabel, bomChain, resolver): BomChainReport`

Although this file lives in `detection/`, it touches no PSI and no Platform API, so its test is plain JUnit against the fixture repository — no `BasePlatformTestCase`, no sandbox.

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/cloud/schneidoa/detection/BomChainReportTest.kt`:

```kotlin
package cloud.schneidoa.detection

import cloud.schneidoa.resolver.BomVersionResolver
import cloud.schneidoa.resolver.Ga
import cloud.schneidoa.resolver.Gav
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BomChainReportTest {

    private fun localRepositoryDir(): File {
        val fixtureUrl = javaClass.classLoader.getResource("fixtures/local-repo")
            ?: error("Test fixture local-repo not found on classpath")
        return File(fixtureUrl.toURI())
    }

    private val jacksonDatabind = Ga("com.fasterxml.jackson.core", "jackson-databind")
    private val acmeBom = Gav("com.example", "acme-bom", "1.0.0")
    private val missingBom = Gav("com.example", "does-not-exist-bom", "9.9.9")

    @Test
    fun `a BOM that manages the artifact is reported with its version and marked as winning`() {
        val report = buildBomChainReport(
            ga = jacksonDatabind,
            declaredVersion = "2.15.4",
            moduleLabel = "test-module",
            bomChain = listOf(BomImport(acmeBom, emptyList())),
            resolver = BomVersionResolver(localRepositoryDir())
        )

        val entry = report.entries.single() as BomChainEntry.Manages
        assertEquals(acmeBom, entry.bom)
        assertEquals("2.15.3", entry.version)
        assertTrue(entry.wins)
    }

    @Test
    fun `a BOM that does not manage the artifact is distinguished from one that cannot be resolved`() {
        val report = buildBomChainReport(
            ga = Ga("org.example", "totally-unmanaged"),
            declaredVersion = "9.9.9",
            moduleLabel = "test-module",
            bomChain = listOf(BomImport(acmeBom, emptyList()), BomImport(missingBom, emptyList())),
            resolver = BomVersionResolver(localRepositoryDir())
        )

        assertTrue(report.entries[0] is BomChainEntry.DoesNotManage)
        assertTrue(report.entries[1] is BomChainEntry.Unresolvable)
    }

    @Test
    fun `only the first managing BOM in precedence order wins`() {
        val report = buildBomChainReport(
            ga = jacksonDatabind,
            declaredVersion = "2.15.4",
            moduleLabel = "test-module",
            bomChain = listOf(BomImport(acmeBom, emptyList()), BomImport(acmeBom, listOf("parent"))),
            resolver = BomVersionResolver(localRepositoryDir())
        )

        assertTrue((report.entries[0] as BomChainEntry.Manages).wins)
        assertTrue(!(report.entries[1] as BomChainEntry.Manages).wins)
    }

    @Test
    fun `the parent chain path each BOM was declared via is preserved`() {
        val report = buildBomChainReport(
            ga = jacksonDatabind,
            declaredVersion = "2.15.4",
            moduleLabel = "test-module",
            bomChain = listOf(BomImport(acmeBom, listOf("spring-boot-starter-parent"))),
            resolver = BomVersionResolver(localRepositoryDir())
        )

        assertEquals(listOf("spring-boot-starter-parent"), report.entries.single().declaredVia)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests "cloud.schneidoa.detection.BomChainReportTest"`
Expected: FAIL — compilation error, `buildBomChainReport` unresolved.

- [ ] **Step 3: Write the implementation**

Create `src/main/kotlin/cloud/schneidoa/detection/BomChainReport.kt`:

```kotlin
package cloud.schneidoa.detection

import cloud.schneidoa.resolver.BomVersionResolver
import cloud.schneidoa.resolver.Ga
import cloud.schneidoa.resolver.Gav
import cloud.schneidoa.resolver.ManagedVersionLookup

/** One BOM in a module's chain, and what it had to say about a particular artifact. */
sealed class BomChainEntry {
    abstract val bom: Gav
    abstract val declaredVia: List<String>

    /** [wins] marks the first BOM in precedence order that manages the artifact - the one Maven would actually use. */
    data class Manages(
        override val bom: Gav,
        override val declaredVia: List<String>,
        val version: String,
        val wins: Boolean
    ) : BomChainEntry()

    data class DoesNotManage(
        override val bom: Gav,
        override val declaredVia: List<String>
    ) : BomChainEntry()

    /** The BOM's POM wasn't in the local repository, so it could not be consulted at all. */
    data class Unresolvable(
        override val bom: Gav,
        override val declaredVia: List<String>
    ) : BomChainEntry()
}

data class BomChainReport(
    val ga: Ga,
    val declaredVersion: String,
    val moduleLabel: String,
    val entries: List<BomChainEntry>
)

/**
 * Probes every BOM in [bomChain] individually, rather than asking the resolver
 * for one answer over the whole list. The normal detection path stops at the
 * first BOM that manages the artifact, which is the right answer but a poor
 * explanation - this builds the full picture the "Show BOM Chain" dialog needs,
 * including the BOMs that lost and the ones that could not be read.
 *
 * Passing a single-element list per BOM lets an unresolvable BOM be told apart
 * from a merely silent one: the resolver reports the former as NotFound with
 * that BOM listed in uncheckedBoms.
 */
fun buildBomChainReport(
    ga: Ga,
    declaredVersion: String,
    moduleLabel: String,
    bomChain: List<BomImport>,
    resolver: BomVersionResolver
): BomChainReport {
    var winnerAlreadySeen = false

    val entries = bomChain.map { import ->
        when (val lookup = resolver.resolveManagedVersion(listOf(import.bom), ga)) {
            is ManagedVersionLookup.Found -> {
                val wins = !winnerAlreadySeen
                winnerAlreadySeen = true
                BomChainEntry.Manages(import.bom, import.declaredVia, lookup.version, wins)
            }
            is ManagedVersionLookup.NotFound ->
                if (lookup.uncheckedBoms.isEmpty()) {
                    BomChainEntry.DoesNotManage(import.bom, import.declaredVia)
                } else {
                    BomChainEntry.Unresolvable(import.bom, import.declaredVia)
                }
        }
    }

    return BomChainReport(ga, declaredVersion, moduleLabel, entries)
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests "cloud.schneidoa.detection.BomChainReportTest"`
Expected: PASS, 4 tests.

- [ ] **Step 5: Verify the whole suite**

Run: `./gradlew check`
Expected: BUILD SUCCESSFUL. Then commit this task (see Global Constraints for the required trailers).

---

### Task 7: Show BOM Chain dialog

**Files:**
- Create: `src/main/kotlin/cloud/schneidoa/ui/BomChainDialog.kt`
- Modify: `src/main/kotlin/cloud/schneidoa/ui/OverrideOverviewToolWindowFactory.kt`

**Interfaces:**
- Consumes: `BomChainReport`, `BomChainEntry`, `buildBomChainReport` from Task 6; `BomChainResolver`, `OverrideDetector.forProject`.
- Produces: `class BomChainDialog(project: Project, report: BomChainReport) : DialogWrapper`.

Like Task 5, this is Swing glue verified by hand; the model it renders is already covered by `BomChainReportTest`.

- [ ] **Step 1: Write the dialog**

Create `src/main/kotlin/cloud/schneidoa/ui/BomChainDialog.kt`:

```kotlin
package cloud.schneidoa.ui

import cloud.schneidoa.detection.BomChainEntry
import cloud.schneidoa.detection.BomChainReport
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreeSelectionModel

/**
 * Read-only view of every BOM that was consulted for one override, in the
 * precedence order Maven itself would apply. Exists because the panel's
 * "Managed By" column can only show the winner, truncated - which is no help
 * when the question is why a given version won, or why no answer was reached.
 */
class BomChainDialog(project: Project, private val report: BomChainReport) : DialogWrapper(project) {

    init {
        title = "BOM Chain for ${report.ga}"
        init()
    }

    override fun createCenterPanel(): JComponent {
        val root = DefaultMutableTreeNode("${report.ga} — declared ${report.declaredVersion} in ${report.moduleLabel}")

        if (report.entries.isEmpty()) {
            root.add(DefaultMutableTreeNode("No BOMs are imported by this module or its parents"))
        }
        for (entry in report.entries) {
            root.add(nodeFor(entry))
        }

        val tree = Tree(DefaultTreeModel(root)).apply {
            selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
            isRootVisible = true
            showsRootHandles = true
        }
        for (row in 0 until tree.rowCount) tree.expandRow(row)

        return JBScrollPane(tree).apply { preferredSize = Dimension(620, 320) }
    }

    private fun nodeFor(entry: BomChainEntry): DefaultMutableTreeNode {
        val via = if (entry.declaredVia.isEmpty()) {
            "declared in this module"
        } else {
            "via ${entry.declaredVia.joinToString(" → ")}"
        }
        val node = DefaultMutableTreeNode("${entry.bom} ($via)")

        val detail = when (entry) {
            is BomChainEntry.Manages ->
                if (entry.wins) "manages this at ${entry.version} — this is the version that applies"
                else "manages this at ${entry.version}, but an earlier BOM takes precedence"
            is BomChainEntry.DoesNotManage -> "does not manage this dependency"
            is BomChainEntry.Unresolvable -> "could not be resolved from the local Maven repository"
        }
        node.add(DefaultMutableTreeNode(detail))
        return node
    }

    override fun createActions() = arrayOf(okAction)
}
```

- [ ] **Step 2: Wire it into the context menu**

In `OverrideOverviewToolWindowFactory.kt` add these imports:

```kotlin
import cloud.schneidoa.detection.BomChainResolver
import cloud.schneidoa.detection.OverrideDetector
import cloud.schneidoa.detection.buildBomChainReport
import cloud.schneidoa.resolver.BomVersionResolver
import org.jetbrains.idea.maven.project.MavenProjectsManager
```

Replace `showContextMenu` with:

```kotlin
    private fun showContextMenu(entry: ProjectOverrideEntry, e: MouseEvent) {
        val menu = JPopupMenu()
        menu.add("Edit...").addActionListener { openEditDialog(entry) }
        menu.add("Remove").addActionListener { removeWithConfirmation(entry) }
        menu.addSeparator()
        menu.add("Show BOM Chain").addActionListener { showBomChain(entry) }
        menu.show(e.component, e.x, e.y)
    }
```

And add:

```kotlin
    /**
     * Re-resolves the chain for just this row rather than carrying it in every
     * scan result - the dialog is opened rarely, and widening
     * DetectedOverride to hold the whole per-entry chain would make every
     * project scan heavier to serve it.
     */
    private fun showBomChain(entry: ProjectOverrideEntry) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val report = try {
                ReadAction.compute<cloud.schneidoa.detection.BomChainReport?, Throwable> {
                    val model = MavenDomUtil.getMavenDomProjectModel(project, entry.pomFile)
                        ?: return@compute null
                    val localRepositoryDir = MavenProjectsManager.getInstance(project).repositoryPath.toFile()
                    val chain = BomChainResolver(localRepositoryDir).resolveBomChain(model, project)
                    buildBomChainReport(
                        ga = entry.override.candidate.ga,
                        declaredVersion = entry.override.candidate.declaredVersion,
                        moduleLabel = entry.moduleLabel,
                        bomChain = chain,
                        resolver = BomVersionResolver(localRepositoryDir)
                    )
                }
            } catch (ex: Throwable) {
                logger.warn("Failed to build BOM chain report for ${entry.override.candidate.ga}", ex)
                null
            }

            SwingUtilities.invokeLater {
                if (report == null) {
                    Messages.showErrorDialog(
                        project,
                        "Could not resolve the BOM chain for this entry. Try refreshing and retrying.",
                        "Show BOM Chain Failed"
                    )
                } else {
                    BomChainDialog(project, report).show()
                }
            }
        }
    }
```

`OverrideDetector` is imported for the `candidate` extension property that `entry.override.candidate` relies on; if the compiler reports it unused, remove that single import and keep the rest.

- [ ] **Step 3: Verify it compiles and the suite is green**

Run: `./gradlew check`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Verify by hand**

Run: `./gradlew runIde`

Right-click a row → **Show BOM Chain**. Confirm the tree lists every imported BOM in order, marks exactly one as the version that applies, and — on a project with a BOM missing from the local repository — shows that BOM as unresolvable. Then commit this task (see Global Constraints for the required trailers).

---

### Task 8: Analyze Dependencies context menu entry

**Files:**
- Modify: `src/main/kotlin/cloud/schneidoa/ui/OverrideOverviewToolWindowFactory.kt`

**Interfaces:**
- Consumes: `ProjectOverrideEntry` from `detection/`.
- Produces: no new public API.

**The constraint this task works around:** IntelliJ's Dependency Analyzer reads `MavenProjectsManager`'s *resolved* model, so unlike every other feature in this plugin it needs a completed Maven sync. When there isn't one, the menu entry is shown but disabled with an explanatory tooltip. Opening an empty analyzer would look like the plugin is broken; hiding the entry would leave the developer wondering where it went.

All four APIs below were verified present on the pinned platform (2025.3.5):
`DependencyAnalyzerManager.getInstance(Project).getOrCreate(ProjectSystemId)`,
`DependencyAnalyzerView.setSelectedDependency(Module, DependencyAnalyzerDependency.Data)`,
`DAArtifact(String, String, String)`, `MavenUtil.SYSTEM_ID`.

- [ ] **Step 1: Add the imports**

```kotlin
import com.intellij.openapi.externalSystem.dependency.analyzer.DAArtifact
import com.intellij.openapi.externalSystem.dependency.analyzer.DependencyAnalyzerManager
import com.intellij.openapi.module.ModuleUtilCore
import org.jetbrains.idea.maven.utils.MavenUtil
```

- [ ] **Step 2: Add the menu entry**

In `showContextMenu`, after the `"Show BOM Chain"` line, add:

```kotlin
        val analyze = menu.add("Analyze Dependencies")
        val mavenProject = MavenProjectsManager.getInstance(project).findProject(entry.pomFile)
        if (mavenProject == null) {
            analyze.isEnabled = false
            analyze.toolTipText = "Requires a completed Maven sync"
        } else {
            analyze.addActionListener { analyzeDependencies(entry) }
        }
```

- [ ] **Step 3: Add the action**

```kotlin
    /**
     * Hands the artifact coordinate to IntelliJ's own Maven Dependency Analyzer
     * rather than computing a transitive tree here - that view already exists in
     * the Maven support this plugin depends on, and it answers a different
     * question from the BOM chain dialog: who pulls this in, not who manages its
     * version.
     */
    private fun analyzeDependencies(entry: ProjectOverrideEntry) {
        val module = ReadAction.compute<com.intellij.openapi.module.Module?, Throwable> {
            ModuleUtilCore.findModuleForFile(entry.pomFile, project)
        }
        if (module == null) {
            Messages.showErrorDialog(
                project,
                "Could not determine which module this pom.xml belongs to.",
                "Analyze Dependencies Failed"
            )
            return
        }

        val ga = entry.override.candidate.ga
        DependencyAnalyzerManager.getInstance(project)
            .getOrCreate(MavenUtil.SYSTEM_ID)
            .setSelectedDependency(
                module,
                DAArtifact(ga.groupId, ga.artifactId, entry.override.candidate.declaredVersion)
            )
    }
```

- [ ] **Step 4: Verify it compiles and the suite is green**

Run: `./gradlew check`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Verify by hand, both states**

Run: `./gradlew runIde`

- On a project **before** Maven sync finishes: the entry is present but greyed out, tooltip reads "Requires a completed Maven sync".
- **After** sync: the entry opens the Dependency Analyzer tool window with that artifact selected.

Then commit this task (see Global Constraints for the required trailers).

---

### Task 9: Documentation

**Files:**
- Modify: `CHANGELOG.md`
- Modify: `CLAUDE.md`
- Modify: `README.md`

- [ ] **Step 1: Add the changelog entries**

Under `## [Unreleased]` in `CHANGELOG.md`, add an `### Added` and a `### Changed` section covering: the version-relation verdict replacing the Confirmed/Inconclusive status; redundant overrides now being detected at all where they were previously discarded; the inspection no longer warning about overrides above their BOM version; and the two new context menu entries.

- [ ] **Step 2: Update the architecture notes**

In `CLAUDE.md`, under the `detection/` bullet, note that `Confirmed` carries a `VersionRelation` and that equal-version overrides are reported rather than dropped. Under `resolver/`, note `VersionRelation.kt` and that `INCOMPARABLE` is the property/range safety valve. Add a "Load-bearing invariants" entry stating that an override above its BOM version is deliberately silent in the editor and visible only in the tool window — that is the kind of intentional absence that gets "fixed" by someone who assumes it is a bug.

- [ ] **Step 3: Update the feature list**

In `README.md`, under "What it does", update the editor and tool-window bullets to describe the verdict rather than a bare warning, and mention the BOM chain view.

- [ ] **Step 4: Final verification**

Run: `./gradlew check`
Expected: BUILD SUCCESSFUL.

Run: `git status --short`
Expected: a clean tree — every task committed. Report the commit list to the user; nothing is ever pushed.
