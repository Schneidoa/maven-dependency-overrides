# Phase 8: Guided Dependency Entry Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Split the Add dialog's combined `Group ID : Artifact ID` field into two, and make both offer what the selected module's BOM chain actually manages — via typing completion and a browsable dropdown — without ever restricting entry to the offered set.

**Architecture:** A new catalog projection in `resolver/` enumerates every version an ordered BOM list manages, reusing the effective models the resolver already builds. A pure `DependencyCandidates` class in `detection/` holds every rule about how the two fields relate. `AddOverrideDialog` is left with widget wiring: two `TextFieldWithAutoCompletion`s in `ComponentWithBrowseButton`s, a `JBPopupFactory` chooser behind each arrow, and a live verdict computed with Phase 7's `compareDeclaredToManaged`.

**Tech Stack:** Kotlin, IntelliJ Platform 2025.3.5, Apache `maven-model-builder` / `maven-artifact`, plain JUnit 4 for pure tests, `BasePlatformTestCase` for PSI-facing ones.

## Global Constraints

- **Commit each task on `main`, locally.** Work directly on `main` — no branches, no worktrees. Commit when your task's verification passes; the per-task review gate reads `git diff` between commits. **Never push.**
- Commit messages end with these two trailers:
  ```
  Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01DCj8mPK6ZJWjMk83YqZWuF
  ```
- **`resolver/` must contain no IntelliJ Platform APIs.** It stays unit-testable against fixture POMs.
- **"Miss rather than false-safe."** A wrong "safe to remove" can silently reintroduce a patched CVE. In this phase the rule shows up twice: an unresolvable BOM must leave the catalog explicitly *partial* rather than silently short, and an ambiguous artifactId must never auto-fill a guessed groupId.
- **Suggest, never restrict.** Every field stays free-text. The dropdown is an offer, not a whitelist.
- **No network, no Maven repository index.** Suggestions come only from the BOM chain the plugin already resolves locally.
- **`ReadAction.compute` does not get you off the EDT** — it takes the read lock and runs on the calling thread. Anything touching the local repository, PSI, or the projects tree goes through `executeOnPooledThread { ReadAction.compute { … } }` with a `SwingUtilities.invokeLater` hop back for UI. See the invariant in `CLAUDE.md`; this has been got wrong twice already.
- Target platform pinned to `intellijIdea("2025.3.5")`, `sinceBuild`/`untilBuild` = `253.*`. Do not widen.
- **Do not run `./gradlew runIde`** — it launches a blocking interactive IDE. Visual checks are the controller's.
- Full suite: `./gradlew check` (currently green at 125 tests). Single class: `./gradlew test --tests "cloud.schneidoa.resolver.ManagedVersionCatalogTest"`.
- `TestLoggerFactory` promotes logged errors into test failures. That is deliberate — never weaken it to make a test pass.

### Fixture reference (exact contents, verified)

| BOM | manages |
|---|---|
| `com.example:acme-bom:1.0.0` | `com.fasterxml.jackson.core:jackson-databind` **2.15.3** (via `${jackson.version}` from its parent), `com.example:widget-core` **4.2.0** (via a local property) |
| `com.example:legacy-bom:1.0.0` | `com.fasterxml.jackson.core:jackson-databind` **2.13.0** |
| `com.example:composing-bom:1.0.0` | nothing directly — imports `legacy-bom`, so its effective model manages jackson-databind **2.13.0** |
| `com.example:jdk-profile-bom:1.0.0` | `com.fasterxml.jackson.core:jackson-databind` **2.21.2** |
| `com.example:broken-parent-bom:1.0.0` | nothing — its `<parent>` does not exist, so the model build **fails** and it lands in `uncheckedBoms` |

Reuse these. Do not add fixtures unless a task says to.

---

### Task 1: Managed-version catalog

**Files:**
- Modify: `src/main/kotlin/cloud/schneidoa/resolver/BomVersionResolver.kt`
- Create: `src/test/kotlin/cloud/schneidoa/resolver/ManagedVersionCatalogTest.kt`

**Interfaces:**
- Consumes: `BomEffectiveModelResolver.buildEffectiveModel`, `BomModelResult`, `Ga`, `Gav` — all existing.
- Produces, in package `cloud.schneidoa.resolver`:
  - `data class ManagedVersionCatalog(val versions: Map<Ga, String>, val uncheckedBoms: List<Gav>)`
  - `BomVersionResolver.collectManagedVersions(bomsInPrecedenceOrder: List<Gav>): ManagedVersionCatalog`

`resolveManagedVersion` must be left exactly as it is. Detection keeps using the cheap early-return path; only the Add dialog pays for the full walk.

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/cloud/schneidoa/resolver/ManagedVersionCatalogTest.kt`:

```kotlin
package cloud.schneidoa.resolver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ManagedVersionCatalogTest {

    private fun resolver(): BomVersionResolver {
        val fixtureUrl = javaClass.classLoader.getResource("fixtures/local-repo")
            ?: error("Test fixture local-repo not found on classpath")
        return BomVersionResolver(File(fixtureUrl.toURI()))
    }

    private val acmeBom = Gav("com.example", "acme-bom", "1.0.0")
    private val legacyBom = Gav("com.example", "legacy-bom", "1.0.0")
    private val composingBom = Gav("com.example", "composing-bom", "1.0.0")
    private val brokenBom = Gav("com.example", "broken-parent-bom", "1.0.0")

    private val jacksonDatabind = Ga("com.fasterxml.jackson.core", "jackson-databind")
    private val widgetCore = Ga("com.example", "widget-core")

    @Test
    fun `collects every managed entry with its interpolated version`() {
        val catalog = resolver().collectManagedVersions(listOf(acmeBom))

        assertEquals("2.15.3", catalog.versions[jacksonDatabind])
        assertEquals("4.2.0", catalog.versions[widgetCore])
        assertEquals(2, catalog.versions.size)
        assertTrue(catalog.uncheckedBoms.isEmpty())
    }

    // Same precedence rule resolveManagedVersion applies by returning early.
    @Test
    fun `the first BOM in precedence order wins a contested artifact`() {
        val catalog = resolver().collectManagedVersions(listOf(acmeBom, legacyBom))

        assertEquals("2.15.3", catalog.versions[jacksonDatabind])
    }

    @Test
    fun `a later BOM still contributes artifacts the earlier one does not manage`() {
        val catalog = resolver().collectManagedVersions(listOf(legacyBom, acmeBom))

        assertEquals("2.13.0", catalog.versions[jacksonDatabind])
        assertEquals("4.2.0", catalog.versions[widgetCore])
    }

    @Test
    fun `a nested BOM import is flattened into the catalog`() {
        val catalog = resolver().collectManagedVersions(listOf(composingBom))

        assertEquals("2.13.0", catalog.versions[jacksonDatabind])
    }

    // The catalog must stay usable AND admit that it is partial - an absent Ga here
    // means "we could not check", not "not managed".
    @Test
    fun `an unresolvable BOM is reported without discarding the readable ones`() {
        val catalog = resolver().collectManagedVersions(listOf(brokenBom, acmeBom))

        assertEquals(listOf(brokenBom), catalog.uncheckedBoms)
        assertEquals("2.15.3", catalog.versions[jacksonDatabind])
    }

    @Test
    fun `an empty BOM list yields an empty catalog`() {
        val catalog = resolver().collectManagedVersions(emptyList())

        assertTrue(catalog.versions.isEmpty())
        assertTrue(catalog.uncheckedBoms.isEmpty())
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests "cloud.schneidoa.resolver.ManagedVersionCatalogTest"`
Expected: FAIL — compilation error, `collectManagedVersions` and `ManagedVersionCatalog` unresolved.

- [ ] **Step 3: Write the implementation**

In `src/main/kotlin/cloud/schneidoa/resolver/BomVersionResolver.kt`, add above the class:

```kotlin
/**
 * Every version an ordered BOM list manages, rather than the answer to one lookup.
 *
 * [uncheckedBoms] carries the same contract as [ManagedVersionLookup]'s: when it is
 * not empty the catalog is *partial*, and a Ga that is absent from [versions] means
 * "we could not check" rather than "nothing manages this". Callers that show these
 * entries to a user must say so rather than presenting the list as exhaustive.
 */
data class ManagedVersionCatalog(
    val versions: Map<Ga, String>,
    val uncheckedBoms: List<Gav>
)
```

And inside the class, below `resolveManagedVersion`:

```kotlin
    /**
     * Projects the whole `dependencyManagement` of an ordered BOM list into one map,
     * for callers that need to offer choices rather than answer a single lookup.
     *
     * Unlike [resolveManagedVersion] this cannot stop at the first match - it has to
     * build every BOM's effective model - so detection deliberately keeps using the
     * cheaper single-target path and only the Add dialog pays for this walk.
     */
    fun collectManagedVersions(bomsInPrecedenceOrder: List<Gav>): ManagedVersionCatalog {
        val versions = LinkedHashMap<Ga, String>()
        val unchecked = mutableListOf<Gav>()

        for (bom in bomsInPrecedenceOrder) {
            when (val result = modelResolver.buildEffectiveModel(bom)) {
                is BomModelResult.Failure -> unchecked += bom
                is BomModelResult.Success ->
                    result.effectiveModel.dependencyManagement?.dependencies?.forEach { dependency ->
                        val groupId = dependency.groupId ?: return@forEach
                        val artifactId = dependency.artifactId ?: return@forEach
                        val version = dependency.version ?: return@forEach
                        // putIfAbsent, not put: the first BOM in precedence order wins,
                        // mirroring resolveManagedVersion's early return.
                        versions.putIfAbsent(Ga(groupId, artifactId), version)
                    }
            }
        }

        return ManagedVersionCatalog(versions, unchecked.toList())
    }
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests "cloud.schneidoa.resolver.ManagedVersionCatalogTest"`
Expected: PASS, 6 tests.

- [ ] **Step 5: Verify and commit**

Run: `./gradlew check` — expected BUILD SUCCESSFUL, 131 tests. Then commit.

---

### Task 2: Candidate rules

**Files:**
- Create: `src/main/kotlin/cloud/schneidoa/detection/DependencyCandidates.kt`
- Create: `src/test/kotlin/cloud/schneidoa/detection/DependencyCandidatesTest.kt`
- Modify: `src/main/kotlin/cloud/schneidoa/detection/ManagedVersionHint.kt`
- Modify: `src/test/kotlin/cloud/schneidoa/detection/ManagedVersionHintTest.kt`

**Interfaces:**
- Consumes: `ManagedVersionCatalog`, `collectManagedVersions` from Task 1.
- Produces:
  - `class DependencyCandidates(catalog: ManagedVersionCatalog)` with `isComplete: Boolean`, `unreadableBomCount: Int`, `groups(): List<String>`, `artifactsIn(groupId: String): List<String>`, `allCoordinates(): List<Ga>`, `allArtifactIds(): List<String>`, `uniqueGroupFor(artifactId: String): String?`, `managedVersionOf(ga: Ga): String?`
  - `ManagedVersionHint.catalog(model: MavenDomProjectModel, project: Project): ManagedVersionCatalog`

`DependencyCandidates.kt` must import nothing from `com.intellij.*` or `org.jetbrains.idea.maven.*` — its test is plain JUnit over a hand-built catalog, no fixture repository and no sandbox.

- [ ] **Step 1: Write the failing test for the rules**

Create `src/test/kotlin/cloud/schneidoa/detection/DependencyCandidatesTest.kt`:

```kotlin
package cloud.schneidoa.detection

import cloud.schneidoa.resolver.Ga
import cloud.schneidoa.resolver.Gav
import cloud.schneidoa.resolver.ManagedVersionCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DependencyCandidatesTest {

    private val jacksonDatabind = Ga("com.fasterxml.jackson.core", "jackson-databind")
    private val jacksonCore = Ga("com.fasterxml.jackson.core", "jackson-core")
    private val widgetCore = Ga("com.example", "widget-core")
    // Same artifactId as jacksonCore but a different group - the ambiguous case.
    private val otherCore = Ga("com.other", "jackson-core")

    private fun candidates(
        versions: Map<Ga, String> = mapOf(
            jacksonDatabind to "2.15.3",
            jacksonCore to "2.15.3",
            widgetCore to "4.2.0"
        ),
        unchecked: List<Gav> = emptyList()
    ) = DependencyCandidates(ManagedVersionCatalog(versions, unchecked))

    @Test
    fun `groups are distinct and sorted`() {
        assertEquals(listOf("com.example", "com.fasterxml.jackson.core"), candidates().groups())
    }

    @Test
    fun `artifactsIn narrows to one group and sorts`() {
        assertEquals(
            listOf("jackson-core", "jackson-databind"),
            candidates().artifactsIn("com.fasterxml.jackson.core")
        )
    }

    @Test
    fun `artifactsIn returns nothing for an unknown group`() {
        assertTrue(candidates().artifactsIn("org.nope").isEmpty())
    }

    @Test
    fun `uniqueGroupFor resolves an artifactId owned by exactly one group`() {
        assertEquals("com.fasterxml.jackson.core", candidates().uniqueGroupFor("jackson-databind"))
    }

    // Deliberately null rather than a pick: auto-filling a guessed group would be
    // worse than leaving the field for the user, who still has the full-coordinate
    // dropdown to disambiguate with.
    @Test
    fun `uniqueGroupFor refuses an artifactId owned by several groups`() {
        val ambiguous = candidates(
            versions = mapOf(jacksonCore to "2.15.3", otherCore to "1.0.0")
        )

        assertNull(ambiguous.uniqueGroupFor("jackson-core"))
    }

    @Test
    fun `uniqueGroupFor returns null for an unknown artifactId`() {
        assertNull(candidates().uniqueGroupFor("nope"))
    }

    @Test
    fun `allArtifactIds is distinct and sorted across groups`() {
        val withDuplicate = candidates(
            versions = mapOf(jacksonCore to "2.15.3", otherCore to "1.0.0", widgetCore to "4.2.0")
        )

        assertEquals(listOf("jackson-core", "widget-core"), withDuplicate.allArtifactIds())
    }

    @Test
    fun `allCoordinates is sorted by group then artifact`() {
        assertEquals(
            listOf(widgetCore, jacksonCore, jacksonDatabind),
            candidates().allCoordinates()
        )
    }

    @Test
    fun `managedVersionOf returns the catalog version`() {
        assertEquals("2.15.3", candidates().managedVersionOf(jacksonDatabind))
        assertNull(candidates().managedVersionOf(Ga("org.nope", "nope")))
    }

    @Test
    fun `a catalog with unreadable BOMs is not complete`() {
        val partial = candidates(unchecked = listOf(Gav("com.example", "missing-bom", "1.0.0")))

        assertFalse(partial.isComplete)
        assertEquals(1, partial.unreadableBomCount)
        assertTrue(candidates().isComplete)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests "cloud.schneidoa.detection.DependencyCandidatesTest"`
Expected: FAIL — `DependencyCandidates` unresolved.

- [ ] **Step 3: Write the implementation**

Create `src/main/kotlin/cloud/schneidoa/detection/DependencyCandidates.kt`:

```kotlin
package cloud.schneidoa.detection

import cloud.schneidoa.resolver.Ga
import cloud.schneidoa.resolver.ManagedVersionCatalog

/**
 * The rules relating the Add dialog's Group ID and Artifact ID fields.
 *
 * These live here rather than in the dialog because this project leaves its Swing
 * classes hand-verified rather than unit-tested, which is only affordable while they
 * hold nothing worth testing. Field coupling is worth testing, so it does not go
 * there. Nothing in this file touches the IntelliJ Platform.
 */
class DependencyCandidates(private val catalog: ManagedVersionCatalog) {

    /** False when a BOM in the chain could not be read, so these suggestions omit unknown entries. */
    val isComplete: Boolean = catalog.uncheckedBoms.isEmpty()

    val unreadableBomCount: Int = catalog.uncheckedBoms.size

    fun groups(): List<String> = catalog.versions.keys.map { it.groupId }.distinct().sorted()

    fun artifactsIn(groupId: String): List<String> =
        catalog.versions.keys.filter { it.groupId == groupId }.map { it.artifactId }.distinct().sorted()

    fun allArtifactIds(): List<String> = catalog.versions.keys.map { it.artifactId }.distinct().sorted()

    fun allCoordinates(): List<Ga> =
        catalog.versions.keys.sortedWith(compareBy({ it.groupId }, { it.artifactId }))

    /**
     * The group owning [artifactId] - but only when exactly one does. Null both for an
     * unknown artifactId and, deliberately, for an ambiguous one: filling the group
     * field from a guess would be worse than leaving it, and the caller still has the
     * full-coordinate list to disambiguate with.
     */
    fun uniqueGroupFor(artifactId: String): String? =
        catalog.versions.keys
            .filter { it.artifactId == artifactId }
            .map { it.groupId }
            .distinct()
            .singleOrNull()

    fun managedVersionOf(ga: Ga): String? = catalog.versions[ga]
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests "cloud.schneidoa.detection.DependencyCandidatesTest"`
Expected: PASS, 10 tests.

- [ ] **Step 5: Add the catalog lookup for a module**

In `src/main/kotlin/cloud/schneidoa/detection/ManagedVersionHint.kt`, add the import
`import cloud.schneidoa.resolver.ManagedVersionCatalog` and, below `lookup`:

```kotlin
    /**
     * Everything the module's BOM chain manages, for the Add dialog's suggestions.
     * Same chain resolution as [lookup], different projection - and a heavier one,
     * since it cannot stop at a first match. Call it once per module selection, not
     * per keystroke.
     */
    fun catalog(model: MavenDomProjectModel, project: Project): ManagedVersionCatalog {
        val bomChain = bomChainResolver.resolveBomChain(model, project)
        return bomVersionResolver.collectManagedVersions(bomChain.map { it.bom })
    }
```

- [ ] **Step 6: Test it against the fixture repository**

`ManagedVersionHintTest` already has a no-arg `configureModuleImportingAcmeBom()` helper. Add this test to that class, using it:

```kotlin
    fun `test catalog returns everything the module's BOM chain manages`() {
        val model = configureModuleImportingAcmeBom()
        val hint = ManagedVersionHint(BomVersionResolver(localRepositoryDir()))

        val catalog = hint.catalog(model, project)

        assertEquals("2.15.3", catalog.versions[Ga("com.fasterxml.jackson.core", "jackson-databind")])
        assertEquals("4.2.0", catalog.versions[Ga("com.example", "widget-core")])
        assertTrue(catalog.uncheckedBoms.isEmpty())
    }
```

- [ ] **Step 7: Verify and commit**

Run: `./gradlew check` — expected BUILD SUCCESSFUL, 142 tests. Then commit.

---

### Task 3: Split the coordinate into two fields

**Files:**
- Modify: `src/main/kotlin/cloud/schneidoa/ui/AddOverrideDialog.kt`

**Interfaces:**
- Consumes: nothing new.
- Produces: no new public API. `parseGa()` changes its source from one field to two.

This task deliberately does **only** the split, with plain `JTextField`s. Completion arrives in Task 4. Keeping them separate means a reviewer can reject the widget work without rejecting the split.

- [ ] **Step 1: Replace the field**

In `AddOverrideDialog`, replace `private val gaField = JTextField()` with:

```kotlin
    private val groupField = JTextField()
    private val artifactField = JTextField()
```

- [ ] **Step 2: Rewrite `parseGa`**

```kotlin
    private fun parseGa(): Ga? {
        val groupId = groupField.text.trim()
        val artifactId = artifactField.text.trim()
        if (groupId.isBlank() || artifactId.isBlank()) return null
        return Ga(groupId, artifactId)
    }
```

The `split(":")` parsing goes away entirely — with two fields there is no separator to get wrong.

- [ ] **Step 3: Update the form and the listeners**

In `createCenterPanel`, replace the single `addLabeledComponent("Group ID : Artifact ID", gaField)` row with:

```kotlin
            .addLabeledComponent("Group ID", groupField)
            .addLabeledComponent("Artifact ID", artifactField)
```

In `init`, replace the single `gaField.document.addDocumentListener(...)` registration with the same listener attached to both fields:

```kotlin
        val coordinateChanged = SimpleDocumentListener {
            updateOkEnabled()
            hintDebounce.restart()
        }
        groupField.document.addDocumentListener(coordinateChanged)
        artifactField.document.addDocumentListener(coordinateChanged)
```

- [ ] **Step 4: Verify and commit**

Run: `./gradlew check` — expected BUILD SUCCESSFUL, 142 tests, no change in count (this task adds no tests; the dialog is hand-verified Swing glue per the Phase 4 precedent, and the coupling logic that *is* worth testing arrives in Task 4 already tested by `DependencyCandidatesTest`). Then commit.

---

### Task 4: Completion, dropdown, and field coupling

**Files:**
- Modify: `src/main/kotlin/cloud/schneidoa/ui/AddOverrideDialog.kt`

**Interfaces:**
- Consumes: `DependencyCandidates` and `ManagedVersionHint.catalog` from Task 2.
- Produces: no new public API.

**Two platform hazards this task must respect.** Both will compile fine and fail at runtime:

1. `TextFieldWithAutoCompletion` is an `EditorTextField`, **not** a `JTextField`. Its listener is `com.intellij.openapi.editor.event.DocumentListener` (verified in `lib/util-8.jar`, with `documentChanged(DocumentEvent)` as a default method) — the existing Swing `SimpleDocumentListener` will not attach to it. Text access is `getText()` / `setText(String)` via `TextAccessor`.
2. **Do not write to an editor document from inside a document event.** Auto-filling the group field runs from the artifact field's `documentChanged`; setting `groupField.text` there modifies a different editor document mid-event. Defer it with `ApplicationManager.getApplication().invokeLater { … }`.

- [ ] **Step 1: Swap in the completion fields and their dropdowns**

Replace the two `JTextField`s from Task 3 with:

```kotlin
    private val groupField = TextFieldWithAutoCompletion.create(project, emptyList(), false, "")
    private val artifactField = TextFieldWithAutoCompletion.create(project, emptyList(), false, "")

    private val groupPicker = ComponentWithBrowseButton(groupField) { chooseGroup() }.apply {
        setButtonIcon(AllIcons.General.ArrowDown)
    }
    private val artifactPicker = ComponentWithBrowseButton(artifactField) { chooseArtifact() }.apply {
        setButtonIcon(AllIcons.General.ArrowDown)
    }
```

Imports to add:

```kotlin
import cloud.schneidoa.detection.DependencyCandidates
import com.intellij.icons.AllIcons
import com.intellij.openapi.ui.ComponentWithBrowseButton
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.TextFieldWithAutoCompletion
import com.intellij.openapi.editor.event.DocumentEvent as EditorDocumentEvent
import com.intellij.openapi.editor.event.DocumentListener as EditorDocumentListener
```

> The third argument of `TextFieldWithAutoCompletion.create(project, variants, forbidWordCompletion, text)` is the platform's word-completion flag; `false` keeps ordinary behavior. If it turns out to interfere with typing, report it rather than working around it.

`ComponentWithBrowseButton` implements `Disposable`, and each wraps an `EditorTextField` that owns a real editor. Register both with the dialog so the editors are released when it closes — otherwise they leak for the lifetime of the project:

```kotlin
        Disposer.register(disposable, groupPicker)
        Disposer.register(disposable, artifactPicker)
```

(`disposable` is `DialogWrapper`'s own; import `com.intellij.openapi.util.Disposer`.) Put this in `init`.

In `createCenterPanel`, use the pickers rather than the raw fields:

```kotlin
            .addLabeledComponent("Group ID", groupPicker)
            .addLabeledComponent("Artifact ID", artifactPicker)
```

- [ ] **Step 2: Attach the editor listeners**

Replace the Swing listener registrations from Task 3 Step 3 with:

```kotlin
        groupField.addDocumentListener(object : EditorDocumentListener {
            override fun documentChanged(event: EditorDocumentEvent) {
                refreshArtifactVariants()
                updateOkEnabled()
                hintDebounce.restart()
            }
        })
        artifactField.addDocumentListener(object : EditorDocumentListener {
            override fun documentChanged(event: EditorDocumentEvent) {
                maybeFillGroupFromArtifact()
                updateOkEnabled()
                hintDebounce.restart()
            }
        })
```

- [ ] **Step 3: Load the catalog per module, off the EDT**

Add the state and the loader:

```kotlin
    private var candidates: DependencyCandidates? = null
    private val catalogGeneration = AtomicInteger(0)

    /**
     * Reloaded whenever the module changes, because the suggestions are exactly what
     * *that* module's BOM chain manages. Off the EDT: building every BOM's effective
     * model reads POMs from the local repository.
     */
    private fun reloadCandidates() {
        val choice = moduleCombo.selectedItem as? ModuleChoice ?: return
        val generation = catalogGeneration.incrementAndGet()
        candidates = null
        setPickersEnabled(false)

        ApplicationManager.getApplication().executeOnPooledThread {
            val loaded = try {
                ReadAction.compute<DependencyCandidates?, Throwable> {
                    DependencyCandidates(hintProvider(project).catalog(choice.model, project))
                }
            } catch (e: Throwable) {
                null
            }

            SwingUtilities.invokeLater {
                if (generation != catalogGeneration.get()) return@invokeLater
                candidates = loaded
                groupField.setVariants(loaded?.groups() ?: emptyList())
                refreshArtifactVariants()
                setPickersEnabled(loaded != null)
                updateHint()
            }
        }
    }

    private fun setPickersEnabled(enabled: Boolean) {
        groupPicker.setButtonEnabled(enabled)
        artifactPicker.setButtonEnabled(enabled)
    }
```

Call `reloadCandidates()` at the end of `init`, and from the module combo's existing action listener alongside the hint restart.

- [ ] **Step 4: Implement the coupling**

```kotlin
    /** Group chosen -> only that group's artifacts; otherwise every artifactId in the chain. */
    private fun refreshArtifactVariants() {
        val available = candidates ?: return
        val group = groupField.text.trim()
        val artifacts = if (group.isNotBlank() && group in available.groups()) {
            available.artifactsIn(group)
        } else {
            available.allArtifactIds()
        }
        artifactField.setVariants(artifacts)
    }

    /**
     * Fills the group in once the typed artifactId belongs to exactly one - the common
     * case, since developers remember artifactIds and not groupIds. Deferred via
     * invokeLater because this runs inside the artifact field's document event, and
     * writing to another editor's document mid-event is illegal.
     */
    private fun maybeFillGroupFromArtifact() {
        val available = candidates ?: return
        if (groupField.text.isNotBlank()) return
        val group = available.uniqueGroupFor(artifactField.text.trim()) ?: return
        ApplicationManager.getApplication().invokeLater {
            if (groupField.text.isBlank()) groupField.text = group
        }
    }
```

- [ ] **Step 5: Implement the dropdowns**

```kotlin
    private fun chooseGroup() {
        val available = candidates ?: return
        showChooser("Group ID", available.groups(), groupPicker) { groupField.text = it }
    }

    /**
     * With no group chosen the list shows full coordinates, so picking one fills both
     * fields - which is also how an ambiguous artifactId gets disambiguated, since
     * uniqueGroupFor deliberately refuses to guess in that case.
     */
    private fun chooseArtifact() {
        val available = candidates ?: return
        val group = groupField.text.trim()

        if (group.isNotBlank() && group in available.groups()) {
            showChooser("Artifact ID", available.artifactsIn(group), artifactPicker) {
                artifactField.text = it
            }
            return
        }

        val coordinates = available.allCoordinates()
        showChooser("Dependency", coordinates.map { it.toString() }, artifactPicker) { chosen ->
            val ga = coordinates.firstOrNull { it.toString() == chosen } ?: return@showChooser
            groupField.text = ga.groupId
            artifactField.text = ga.artifactId
        }
    }

    private fun showChooser(
        title: String,
        items: List<String>,
        anchor: JComponent,
        onChosen: (String) -> Unit
    ) {
        if (items.isEmpty()) return
        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(items)
            .setTitle(title)
            .setNamerForFiltering { it }
            // Always visible: a BOM chain can manage well over a thousand artifacts,
            // and scrolling that is not browsing.
            .setFilterAlwaysVisible(true)
            .setItemChosenCallback { onChosen(it) }
            .createPopup()
            .showUnderneathOf(anchor)
    }
```

- [ ] **Step 6: Verify and commit**

Run: `./gradlew check` — expected BUILD SUCCESSFUL, 142 tests. Then commit.

---

### Task 5: Version prefill and the live verdict

**Files:**
- Modify: `src/main/kotlin/cloud/schneidoa/ui/AddOverrideDialog.kt`

**Interfaces:**
- Consumes: `DependencyCandidates.managedVersionOf` / `isComplete` / `unreadableBomCount` from Task 2; `compareDeclaredToManaged` and `VersionRelation` from Phase 7.
- Produces: no new public API.

**The trap this task exists to defuse:** prefilling the version with what the BOM already manages leads the user straight into creating a *redundant* pin — the exact thing this plugin flags and offers to remove. Filling a field with a value and then complaining about that value is not a feature. So the prefill ships together with a hint that says what the entered version means.

The dialog must **not** block OK on a redundant pin. Restating a BOM's version is legitimate — pinning against a future BOM upgrade, for one — and this plugin's job is to say what a thing means, not to refuse it.

- [ ] **Step 1: Add the prefill, with a guard against fighting the user**

```kotlin
    private var versionEditedByUser = false
    private var applyingPrefill = false

    /**
     * Programmatic writes must not look like user edits, or the first prefill would
     * immediately disable every later one. Hence the flag rather than comparing values:
     * deliberately typing the BOM's own version stays a user edit and is respected.
     */
    private fun prefillVersion(version: String) {
        applyingPrefill = true
        try {
            versionField.text = version
        } finally {
            applyingPrefill = false
        }
    }
```

Change the existing `versionField` listener registration to:

```kotlin
        versionField.document.addDocumentListener(SimpleDocumentListener {
            if (!applyingPrefill) versionEditedByUser = true
            updateOkEnabled()
            updateHint()
        })
```

- [ ] **Step 2: Prefill when the coordinate resolves**

Call this from **exactly one place** — the first line of `updateHint()` in Step 3. `updateHint` already runs on every coordinate and version change, so a second trigger site would only create two paths that can disagree:

```kotlin
    private fun maybePrefillVersion() {
        if (versionEditedByUser) return
        val ga = parseGa() ?: return
        val managed = candidates?.managedVersionOf(ga) ?: return
        // The inequality check is load-bearing, not an optimisation: prefillVersion writes
        // the field, whose listener calls updateHint, which calls back into here. Without
        // it that is an infinite loop; with it the second pass finds the field already
        // equal and stops.
        if (versionField.text.trim() != managed) prefillVersion(managed)
    }
```

- [ ] **Step 3: Replace the hint with the live verdict**

```kotlin
    /**
     * Reads the verdict straight out of the catalog rather than re-resolving the chain,
     * so it updates as fast as typing. The async ManagedVersionHint lookup stays as the
     * fallback for when the catalog could not be loaded at all.
     */
    private fun updateHint() {
        val available = candidates
        if (available == null) {
            loadHint()
            return
        }

        val ga = parseGa()
        val managed = ga?.let { available.managedVersionOf(it) }
        val declared = versionField.text.trim()

        val verdict = when {
            ga == null -> ""
            managed == null -> "No BOM in this module's chain manages this"
            declared.isBlank() -> "The BOM manages this at $managed"
            else -> when (compareDeclaredToManaged(declared, managed)) {
                VersionRelation.SAME -> "Same version the BOM already manages - this override would be redundant"
                VersionRelation.NEWER -> "Raises this above the BOM's $managed"
                VersionRelation.OLDER -> "Holds this below the BOM's $managed"
                VersionRelation.INCOMPARABLE -> "Can't be compared with the BOM's $managed"
            }
        }

        // Stated, not implied: a dropdown that silently omits entries reads as "this
        // artifact isn't managed", which is the false negative this project's
        // miss-rather-than-false-safe rule exists to prevent, inverted into the UI.
        val incomplete = if (available.isComplete) {
            ""
        } else {
            val n = available.unreadableBomCount
            "${if (verdict.isEmpty()) "" else " — "}$n BOM${if (n == 1) "" else "s"} could not be read, so these suggestions may be incomplete"
        }

        hintLabel.text = (verdict + incomplete).ifBlank { " " }
    }
```

Route the existing debounce timer at `hintDebounce` to `updateHint()` instead of `loadHint()`, and have `updateHint` call `maybePrefillVersion()` before computing the text. Leave `loadHint()` in place as the catalog-unavailable fallback.

- [ ] **Step 4: Verify and commit**

Run: `./gradlew check` — expected BUILD SUCCESSFUL, 142 tests. Then commit.

---

### Task 6: Documentation

**Files:**
- Modify: `CHANGELOG.md`
- Modify: `CLAUDE.md`
- Modify: `README.md`
- Modify: `src/main/resources/META-INF/plugin.xml`

Read the actual committed code before describing it; do not paraphrase this plan.

- [ ] **Step 1: Changelog**

Under `## [Unreleased]`, describe the user-visible change: the Add dialog now has separate Group ID and Artifact ID fields, both offering what the module's BOM chain manages via typing completion and a browsable dropdown; the version prefills from the BOM with a live note saying what the entered version would mean; and the suggestions say so when a BOM in the chain could not be read. Match the voice of the existing `[0.1.0]` entries — consequence and why, not a symbol list.

- [ ] **Step 2: Architecture notes**

In `CLAUDE.md`: note `collectManagedVersions`/`ManagedVersionCatalog` under `resolver/` and why it is separate from `resolveManagedVersion` (cannot stop early; detection keeps the cheap path). Note `DependencyCandidates` under `detection/` and, importantly, *why it exists* — the Swing layer is hand-verified only, so anything worth testing must not live there.

Add a load-bearing invariant: **an ambiguous artifactId never auto-fills a group.** `uniqueGroupFor` returns null for both unknown and ambiguous input, and that is the same refuse-to-guess reflex as `Inconclusive` — a future reader who "improves" it into picking the first match should read this first.

- [ ] **Step 3: User-facing copy**

Update `README.md`'s feature list and the `plugin.xml` description to mention guided entry in the Add dialog. Keep `shortName="MavenDependencyOverride"` untouched.

- [ ] **Step 4: Final verification**

Run: `./gradlew check` — expected BUILD SUCCESSFUL, 142 tests. Run `git status --short` — expected clean. Report the commit list; nothing is ever pushed.
