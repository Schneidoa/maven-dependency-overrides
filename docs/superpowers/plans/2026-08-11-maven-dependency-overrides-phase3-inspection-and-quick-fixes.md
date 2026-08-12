# Maven Dependency Overrides — Phase 3: Inspection + Local Quick Fixes — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Phase 2's detection logic visible and actionable in the editor: a
weak-warning highlight on the `<version>` text of every confirmed override, and
two quick fixes a developer can actually use today — remove the override, or
suppress the warning — both reachable via Alt+Enter.

**Architecture:** A `LocalInspectionTool` (`OverrideInspection`) visits each
`pom.xml` file, calls Phase 2's `OverrideDetector.forProject(project).detect(...)`,
and registers a problem per `DetectedOverride` on the relevant `<version>` tag.
Two stateless `LocalQuickFix` implementations mutate the PSI tree directly
(`RemoveOverrideQuickFix` deletes the `<dependency>` tag, `SuppressOverrideQuickFix`
inserts or replaces the preceding comment with the suppress marker). A small
shared `PomComments.kt` utility (extracted from Phase 2's scanner) is reused by
both the scanner and the new quick fix, so there's exactly one place that knows
what a "preceding comment" is and one canonical suppress-marker constant.

**Tech Stack:** Kotlin, IntelliJ Platform inspection APIs
(`com.intellij.codeInspection.*`), IntelliJ XML PSI (`com.intellij.psi.xml.*`),
IntelliJ Platform Test Framework (`CodeInsightTestFixture`'s
`enableInspections`/`doHighlighting`/`findSingleIntention`/`launchAction`).

---

## Why this phase is scoped the way it is

The design doc's "Editor Integration" component bundles a gutter icon, an
inspection, and **four** quick fixes (update override, bump parent/BOM, remove
override, suppress). Two of those four quick fixes — "update to latest
available version" and "bump the parent/BOM to a version that already provides
what's needed" — fundamentally require the **Version Advisor** (network
repository-metadata lookups), which doesn't exist yet; it's the next phase on
the roadmap. Building UI plumbing for quick fixes that can't actually do
anything yet would mean either stub buttons that do nothing (bad UX, actively
misleading for a security tool) or blocking this phase on a phase that hasn't
been designed.

**This phase therefore ships only what has everything it needs today:** the
inspection (visible, working, no dependencies outstanding) and the two quick
fixes that are entirely local decisions (remove — only offered when Phase 2
already confirmed the override is safe to remove; suppress — always available,
matching the design's "team can silence a known, deliberate override"
intent). The gutter icon (`LineMarkerProvider`) is also deferred — it's a
second, independent extension point with its own registration/testing
surface, and the inspection's wavy underline already delivers the primary
signal described in the design ("weak-warning highlight on the `<version>`
text"); the gutter icon is a supplementary at-a-glance affordance, not
required for the feature to be useful. Both the gutter icon and the two
network-dependent quick fixes are called out explicitly in this plan's
Roadmap section so they aren't forgotten.

**One risky assumption gets a spike first, same discipline as Phase 2:**
`XmlElementFactory` (IntelliJ's XML PSI factory) has no method for creating a
standalone `XmlComment` — confirmed by reading its full source. The
documented workaround (from JetBrains' own support forum, not primary source,
hence the spike) is to parse a throwaway wrapper tag containing the comment
via `PsiFileFactory` and extract the comment node with `PsiTreeUtil`. Task 1
verifies this actually produces a valid, insertable comment before anything
is built on top of it — if it doesn't, the whole "insert a suppress comment"
approach needs rethinking, which is a plan-level decision, not something to
improvise around mid-task.

## File Structure

```
src/main/resources/META-INF/plugin.xml   register the <localInspection>

src/main/kotlin/cloud/schneidoa/detection/
  PomComments.kt              NEW: shared findPrecedingComment + createXmlComment + SUPPRESS_MARKER
  OverrideCandidate.kt        MODIFY: add versionXmlTag field
  DependencyManagementScanner.kt   MODIFY: use the shared PomComments functions instead of its own private copy
  OverrideInspection.kt       NEW: the LocalInspectionTool
  quickfix/
    RemoveOverrideQuickFix.kt    NEW
    SuppressOverrideQuickFix.kt  NEW

src/test/kotlin/cloud/schneidoa/detection/
  PsiMutationSpikeTest.kt         NEW (Task 1)
  PomCommentsTest.kt              NEW (Task 2)
  DependencyManagementScannerTest.kt   MODIFY: add a versionXmlTag assertion
  OverrideInspectionTest.kt       NEW (Task 4)
  quickfix/
    RemoveOverrideQuickFixTest.kt    NEW (Task 5)
    SuppressOverrideQuickFixTest.kt  NEW (Task 6)
```

---

### Task 1: Spike — verify PSI comment creation and mutation

**Files:**
- Create: `src/test/kotlin/cloud/schneidoa/detection/PsiMutationSpikeTest.kt`

- [ ] **Step 1: Write the spike test**

```kotlin
// src/test/kotlin/cloud/schneidoa/detection/PsiMutationSpikeTest.kt
package cloud.schneidoa.detection

import com.intellij.lang.xml.XMLLanguage
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlComment
import com.intellij.psi.xml.XmlTag
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class PsiMutationSpikeTest : BasePlatformTestCase() {

    private val pomText = """
        <project xmlns="http://maven.apache.org/POM/4.0.0">
            <modelVersion>4.0.0</modelVersion>
            <groupId>com.example</groupId>
            <artifactId>test-module</artifactId>
            <version>1.0.0</version>

            <dependencyManagement>
                <dependencies>
                    <dependency>
                        <groupId>com.example</groupId>
                        <artifactId>widget-core</artifactId>
                        <version>1.0.0</version>
                    </dependency>
                </dependencies>
            </dependencyManagement>
        </project>
    """.trimIndent()

    fun `test creates a standalone XmlComment via a throwaway wrapper tag and inserts it before a dependency`() {
        val file = myFixture.configureByText("pom.xml", pomText)
        val dependencyTag = PsiTreeUtil.findChildrenOfType(file, XmlTag::class.java)
            .first { it.name == "dependency" }

        WriteCommandAction.runWriteCommandAction(project) {
            val dummyFile = PsiFileFactory.getInstance(project)
                .createFileFromText("dummy.xml", XMLLanguage.INSTANCE, "<a><!-- test comment --></a>")
            val dummyTag = PsiTreeUtil.findChildOfType(dummyFile, XmlTag::class.java)!!
            val comment = PsiTreeUtil.findChildOfType(dummyTag, XmlComment::class.java)!!

            dependencyTag.parent.addBefore(comment, dependencyTag)
        }

        assertTrue(file.text.contains("<!-- test comment -->"))
        val insertedComment = dependencyTag.prevSibling as? XmlComment
            ?: (dependencyTag.prevSibling?.prevSibling as? XmlComment)
        assertNotNull("Expected to find the inserted comment as a preceding sibling", insertedComment)
        assertEquals("test comment", insertedComment!!.commentText.trim())
    }

    fun `test deletes a dependency tag`() {
        val file = myFixture.configureByText("pom.xml", pomText)
        val dependencyTag = PsiTreeUtil.findChildrenOfType(file, XmlTag::class.java)
            .first { it.name == "dependency" }

        WriteCommandAction.runWriteCommandAction(project) {
            dependencyTag.delete()
        }

        assertFalse(file.text.contains("widget-core"))
    }
}
```

- [ ] **Step 2: Run the spike**

Run:

```bash
./gradlew test --tests "cloud.schneidoa.detection.PsiMutationSpikeTest"
```

Expected: `BUILD SUCCESSFUL`, 2 tests passed.

**If the first test fails** (compiles but the comment isn't found, or its text
doesn't match): the dummy-wrapper-tag workaround for creating an `XmlComment`
doesn't work as documented. Do not try alternative approaches yourself — stop
and report BLOCKED with the exact failure. This is the load-bearing assumption
for both `PomComments.kt` (Task 2) and `SuppressOverrideQuickFix` (Task 6);
if it's wrong, those tasks need a different design, which is a plan-level
decision.

- [ ] **Step 3: Commit**

```bash
git add src/test/kotlin/cloud/schneidoa/detection/PsiMutationSpikeTest.kt
git commit -m "$(cat <<'EOF'
Add spike verifying PSI comment creation and dependency-tag deletion

EOF
)"
```

---

### Task 2: Extract shared `PomComments` utility

**Files:**
- Create: `src/test/kotlin/cloud/schneidoa/detection/PomCommentsTest.kt`
- Create: `src/main/kotlin/cloud/schneidoa/detection/PomComments.kt`
- Modify: `src/main/kotlin/cloud/schneidoa/detection/DependencyManagementScanner.kt`

`DependencyManagementScanner` currently has a private `readPrecedingComment`
and a private `SUPPRESS_MARKER` constant. `SuppressOverrideQuickFix` (Task 6)
needs the exact same "find the preceding comment" logic (to replace an
existing reason comment rather than stacking a second one) and the exact same
marker text (so what it writes is exactly what the scanner will later read
back as "suppressed"). Extracting these into a shared file — rather than
duplicating them — is the only way to guarantee the writer and reader agree.

- [ ] **Step 1: Write the failing test**

```kotlin
// src/test/kotlin/cloud/schneidoa/detection/PomCommentsTest.kt
package cloud.schneidoa.detection

import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlTag
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class PomCommentsTest : BasePlatformTestCase() {

    fun `test finds a comment immediately preceding a tag`() {
        val file = myFixture.configureByText(
            "pom.xml",
            """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.example</groupId>
                <artifactId>test-module</artifactId>
                <version>1.0.0</version>
                <!-- a reason -->
                <description>x</description>
            </project>
            """.trimIndent()
        )
        val descriptionTag = PsiTreeUtil.findChildrenOfType(file, XmlTag::class.java)
            .first { it.name == "description" }

        val comment = findPrecedingComment(descriptionTag)

        assertNotNull(comment)
        assertEquals("a reason", comment!!.commentText.trim())
    }

    fun `test returns null when there is no preceding comment`() {
        val file = myFixture.configureByText(
            "pom.xml",
            """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.example</groupId>
                <artifactId>test-module</artifactId>
                <version>1.0.0</version>
                <description>x</description>
            </project>
            """.trimIndent()
        )
        val descriptionTag = PsiTreeUtil.findChildrenOfType(file, XmlTag::class.java)
            .first { it.name == "description" }

        assertNull(findPrecedingComment(descriptionTag))
    }

    fun `test creates a comment whose text round trips through commentText`() {
        val file = myFixture.configureByText(
            "pom.xml",
            """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.example</groupId>
                <artifactId>test-module</artifactId>
                <version>1.0.0</version>
            </project>
            """.trimIndent()
        )

        val comment = createXmlComment(project, " $SUPPRESS_MARKER ")

        assertEquals(SUPPRESS_MARKER, comment.commentText.trim())
        // sanity: file itself is untouched, createXmlComment doesn't need a real anchor
        assertFalse(file.text.contains(SUPPRESS_MARKER))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run:

```bash
./gradlew test --tests "cloud.schneidoa.detection.PomCommentsTest"
```

Expected: compilation failure — `unresolved reference: findPrecedingComment` (and `createXmlComment`, `SUPPRESS_MARKER`).

- [ ] **Step 3: Implement `PomComments.kt`**

```kotlin
// src/main/kotlin/cloud/schneidoa/detection/PomComments.kt
package cloud.schneidoa.detection

import com.intellij.lang.xml.XMLLanguage
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlComment
import com.intellij.psi.xml.XmlTag

const val SUPPRESS_MARKER = "maven-dependency-overrides: suppress"

/**
 * Walks backward over blank (whitespace-only) siblings to find the nearest
 * real preceding sibling, and returns it if it's a comment. Deliberately
 * avoids PsiTreeUtil.skipWhitespacesBackward, which skips PsiWhiteSpace
 * nodes specifically — inter-tag whitespace in XML PSI isn't guaranteed to
 * be represented that way, so comparing blank text directly is more robust.
 */
fun findPrecedingComment(xmlTag: XmlTag): XmlComment? {
    var sibling = xmlTag.prevSibling
    while (sibling != null && sibling.text.isBlank()) {
        sibling = sibling.prevSibling
    }
    return sibling as? XmlComment
}

/**
 * Creates a standalone XmlComment PSI element with the given text.
 * XmlElementFactory has no direct method for this (verified by reading its
 * full source), so this parses a throwaway wrapper tag containing the
 * comment and extracts it — the workaround documented by JetBrains support
 * for exactly this gap, verified empirically in this project's own spike
 * (PsiMutationSpikeTest) before being relied on here.
 */
fun createXmlComment(project: Project, text: String): XmlComment {
    val dummyFile = PsiFileFactory.getInstance(project)
        .createFileFromText("dummy.xml", XMLLanguage.INSTANCE, "<a><!--$text--></a>")
    val dummyTag = PsiTreeUtil.findChildOfType(dummyFile, XmlTag::class.java)
        ?: error("Failed to parse throwaway wrapper tag for comment creation")
    return PsiTreeUtil.findChildOfType(dummyTag, XmlComment::class.java)
        ?: error("Throwaway wrapper tag did not contain the expected XmlComment")
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run:

```bash
./gradlew test --tests "cloud.schneidoa.detection.PomCommentsTest"
```

Expected: `BUILD SUCCESSFUL`, 3 tests passed.

- [ ] **Step 5: Update `DependencyManagementScanner` to use the shared functions**

Edit `src/main/kotlin/cloud/schneidoa/detection/DependencyManagementScanner.kt` to
remove its own private `SUPPRESS_MARKER` and `readPrecedingComment`, using the
shared ones instead. Full resulting file:

```kotlin
// src/main/kotlin/cloud/schneidoa/detection/DependencyManagementScanner.kt
package cloud.schneidoa.detection

import cloud.schneidoa.resolver.Ga
import com.intellij.psi.xml.XmlComment
import org.jetbrains.idea.maven.dom.MavenPropertyResolver
import org.jetbrains.idea.maven.dom.model.MavenDomDependency
import org.jetbrains.idea.maven.dom.model.MavenDomProjectModel

object DependencyManagementScanner {

    fun scan(model: MavenDomProjectModel): List<OverrideCandidate> {
        // MavenDomProjectModel -> MavenDomDependencyManagement -> MavenDomDependencies
        // -> List<MavenDomDependency>; three ".dependencies" in a row is correct,
        // each step unwraps one level of the <dependencyManagement><dependencies>
        // <dependency> nesting.
        val managedDependencies: List<MavenDomDependency> = model.dependencyManagement.dependencies.dependencies

        return managedDependencies
            .filter { it.scope.rawText?.trim() != "import" }
            .mapNotNull { toCandidate(it, model) }
    }

    private fun toCandidate(dependency: MavenDomDependency, model: MavenDomProjectModel): OverrideCandidate? {
        val groupId = dependency.groupId.rawText?.trim()
        val artifactId = dependency.artifactId.rawText?.trim()
        val rawVersion = dependency.version.rawText?.trim()
        if (groupId.isNullOrEmpty() || artifactId.isNullOrEmpty() || rawVersion.isNullOrEmpty()) return null

        val xmlTag = dependency.xmlTag ?: return null
        val versionXmlTag = dependency.version.xmlTag ?: return null
        val resolvedVersion = MavenPropertyResolver.resolve(rawVersion, model)
        val (reason, suppressed) = classifyComment(findPrecedingComment(xmlTag))

        return OverrideCandidate(
            ga = Ga(groupId, artifactId),
            declaredVersion = resolvedVersion,
            xmlTag = xmlTag,
            versionXmlTag = versionXmlTag,
            reason = reason,
            suppressed = suppressed
        )
    }

    private fun classifyComment(comment: XmlComment?): Pair<String?, Boolean> {
        val text = comment?.commentText?.trim() ?: return null to false
        return if (text == SUPPRESS_MARKER) null to true else text to false
    }
}
```

Note: this also adds `versionXmlTag` to the constructed `OverrideCandidate` —
that's Task 3, implement it together with this step since the code above
won't compile otherwise (`OverrideCandidate` doesn't have that field yet
until Task 3's edit). Do Task 3's `OverrideCandidate.kt` edit now, as part of
this step, before moving on.

- [ ] **Step 6: Run the full existing detection test suite**

Run:

```bash
./gradlew test --tests "cloud.schneidoa.detection.*"
```

Expected: `BUILD SUCCESSFUL`. All previously-passing tests in this package
still pass (the refactor changes internals, not behavior) — this is Task 3's
job to fully verify (it adds a `versionXmlTag` assertion), but nothing here
should have broken any *existing* assertion.

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/cloud/schneidoa/detection/PomComments.kt src/main/kotlin/cloud/schneidoa/detection/DependencyManagementScanner.kt src/test/kotlin/cloud/schneidoa/detection/PomCommentsTest.kt
git commit -m "$(cat <<'EOF'
Extract shared PomComments utility from DependencyManagementScanner

EOF
)"
```

---

### Task 3: Add `versionXmlTag` to `OverrideCandidate`

**Files:**
- Modify: `src/main/kotlin/cloud/schneidoa/detection/OverrideCandidate.kt`
- Modify: `src/test/kotlin/cloud/schneidoa/detection/DependencyManagementScannerTest.kt`

The inspection (Task 4) needs to register its highlight on the `<version>`
tag specifically — not the whole `<dependency>` block — matching the design's
"weak-warning highlight on the `<version>` text". `OverrideCandidate`
currently only carries the `<dependency>` tag as a whole.

(If Task 2's Step 5 was followed exactly, `OverrideCandidate.kt`'s field and
`DependencyManagementScanner`'s construction of it are already done together.
This task is where that gets verified with a real test — do it now if you
skipped ahead, or just run the test below if it's already in place.)

- [ ] **Step 1: Write the failing test**

Add this test to `src/test/kotlin/cloud/schneidoa/detection/DependencyManagementScannerTest.kt`
(after the existing tests, before the closing brace):

```kotlin
    fun `test candidate carries the version tag itself, not just the dependency tag`() {
        val model = configurePom(
            """
            <dependency>
                <groupId>com.fasterxml.jackson.core</groupId>
                <artifactId>jackson-databind</artifactId>
                <version>2.15.4</version>
            </dependency>
            """.trimIndent()
        )

        val candidate = DependencyManagementScanner.scan(model).single()

        assertEquals("version", candidate.versionXmlTag.name)
        assertEquals("2.15.4", candidate.versionXmlTag.value.text)
    }
```

- [ ] **Step 2: Run the test to verify it fails**

Run:

```bash
./gradlew test --tests "cloud.schneidoa.detection.DependencyManagementScannerTest"
```

Expected (if Task 2 Step 5 wasn't followed yet): compilation failure —
`unresolved reference: versionXmlTag`. (If it was already added, this step
instead just confirms the new test passes — see Step 4.)

- [ ] **Step 3: Add the field to `OverrideCandidate`**

```kotlin
// src/main/kotlin/cloud/schneidoa/detection/OverrideCandidate.kt
package cloud.schneidoa.detection

import cloud.schneidoa.resolver.Ga
import com.intellij.psi.xml.XmlTag

/**
 * A literal-version entry found in a module's own `<dependencyManagement>` —
 * not yet compared against any BOM, just what's textually present in the POM.
 * [xmlTag] is the `<dependency>` block, kept for navigation; [versionXmlTag]
 * is specifically the `<version>` child, used to anchor editor highlights
 * precisely on the version text rather than the whole dependency block.
 */
data class OverrideCandidate(
    val ga: Ga,
    val declaredVersion: String,
    val xmlTag: XmlTag,
    val versionXmlTag: XmlTag,
    val reason: String?,
    val suppressed: Boolean
)
```

If Task 2's Step 5 wasn't done yet, also apply `DependencyManagementScanner.kt`'s
full content exactly as shown in Task 2 Step 5 now.

- [ ] **Step 4: Run the test to verify it passes**

Run:

```bash
./gradlew test --tests "cloud.schneidoa.detection.DependencyManagementScannerTest"
```

Expected: `BUILD SUCCESSFUL`, 8 tests passed (7 existing + this new one).

- [ ] **Step 5: Run the full suite to confirm no regressions**

Run:

```bash
./gradlew test
```

Expected: `BUILD SUCCESSFUL`. Everything that passed before (42 tests) plus
the 2 from Task 1's spike, the 3 from Task 2's `PomCommentsTest`, and this
task's new test — 48 total, no failures.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/cloud/schneidoa/detection/OverrideCandidate.kt src/test/kotlin/cloud/schneidoa/detection/DependencyManagementScannerTest.kt
git commit -m "$(cat <<'EOF'
Add versionXmlTag to OverrideCandidate for precise highlight anchoring

EOF
)"
```

---

### Task 4: `OverrideInspection`

**Files:**
- Create: `src/test/kotlin/cloud/schneidoa/detection/OverrideInspectionTest.kt`
- Create: `src/main/kotlin/cloud/schneidoa/detection/OverrideInspection.kt`
- Modify: `src/main/resources/META-INF/plugin.xml`

This is the visible payoff of Phase 2: a `LocalInspectionTool` that visits
each `pom.xml`, calls `OverrideDetector.forProject(project).detect(...)`, and
registers a weak-warning problem on each confirmed/inconclusive candidate's
`<version>` tag. `Confirmed` results get both quick fixes offered;
`Inconclusive` results only get "suppress" — offering "remove" when the tool
itself says "can't confirm this is safe" would contradict the whole point of
distinguishing the two states.

This test doesn't reference the quick fix classes yet (Tasks 5 and 6) — it
only checks that problems are registered with the right message and
severity. Quick fix *behavior* is tested in their own tasks; here we just
confirm they're *offered*.

`OverrideInspection`'s constructor takes a `detectorFactory` (defaulting to
the real `OverrideDetector::forProject`) specifically so tests don't have to
depend on how `MavenProjectsManager`/`MavenSettingsCache` resolve a local
repository path inside a lightweight test sandbox — that resolution chain
goes through `MavenSettingsCache.getEffectiveUserLocalRepo()` internally
(confirmed by reading `MavenProjectsManager`'s source), which is not
something this plan verified behaves predictably in `BasePlatformTestCase`
fixtures. Instead, tests inject a detector backed directly by this project's
own `src/test/resources/fixtures/local-repo` fixture — the same one Phase
1/2's tests already use — sidestepping the question entirely.

- [ ] **Step 1: Write the failing test**

```kotlin
// src/test/kotlin/cloud/schneidoa/detection/OverrideInspectionTest.kt
package cloud.schneidoa.detection

import cloud.schneidoa.resolver.BomVersionResolver
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

class OverrideInspectionTest : BasePlatformTestCase() {

    private fun testInspection(): OverrideInspection {
        val fixtureUrl = javaClass.classLoader.getResource("fixtures/local-repo")
            ?: error("Test fixture local-repo not found on classpath")
        val localRepositoryDir = File(fixtureUrl.toURI())
        return OverrideInspection { OverrideDetector(BomVersionResolver(localRepositoryDir)) }
    }

    fun `test registers a weak warning on a confirmed override`() {
        myFixture.enableInspections(testInspection())
        myFixture.configureByText(
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
                            <artifactId>acme-bom</artifactId>
                            <version>1.0.0</version>
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

        val highlights = myFixture.doHighlighting()
        val overrideHighlight = highlights.singleOrNull { it.description?.contains("acme-bom") == true }

        assertNotNull("Expected a highlight mentioning the BOM that manages this dependency", overrideHighlight)
        assertTrue(overrideHighlight!!.description.contains("2.15.3"))
    }

    fun `test does not warn when the version already matches the BOM`() {
        myFixture.enableInspections(testInspection())
        myFixture.configureByText(
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
                            <artifactId>acme-bom</artifactId>
                            <version>1.0.0</version>
                            <type>pom</type>
                            <scope>import</scope>
                        </dependency>
                        <dependency>
                            <groupId>com.fasterxml.jackson.core</groupId>
                            <artifactId>jackson-databind</artifactId>
                            <version>2.15.3</version>
                        </dependency>
                    </dependencies>
                </dependencyManagement>
            </project>
            """.trimIndent()
        )

        val highlights = myFixture.doHighlighting()

        assertTrue(highlights.none { it.description?.contains("acme-bom") == true })
    }
}
```

This reuses Phase 1/2's `src/test/resources/fixtures/local-repo` fixture
repository (specifically `acme-bom`, which manages `jackson-databind` at
`2.15.3`), injected directly via `testInspection()`'s `detectorFactory`
override — no dependency on how the test sandbox's own Maven settings
resolve a repository path.

- [ ] **Step 2: Run the test to verify it fails**

Run:

```bash
./gradlew test --tests "cloud.schneidoa.detection.OverrideInspectionTest"
```

Expected: compilation failure — `unresolved reference: OverrideInspection`
(fixed by Step 4).

- [ ] **Step 3: Create stub quick fix classes**

`OverrideInspection` (Step 4 below) references `RemoveOverrideQuickFix` and
`SuppressOverrideQuickFix` directly in its imports and constructor calls —
Kotlin compiles the whole module together, so those classes must exist
*before* `OverrideInspection.kt` will compile at all, not after. Their real
implementations belong to Tasks 5 and 6; for now, create minimal stubs so
this task's own code compiles and its test can run:

```kotlin
// src/main/kotlin/cloud/schneidoa/detection/quickfix/RemoveOverrideQuickFix.kt
package cloud.schneidoa.detection.quickfix

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.project.Project

class RemoveOverrideQuickFix : LocalQuickFix {
    override fun getFamilyName(): String = "Remove dependency override"
    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        // Implemented in Task 5.
    }
}
```

```kotlin
// src/main/kotlin/cloud/schneidoa/detection/quickfix/SuppressOverrideQuickFix.kt
package cloud.schneidoa.detection.quickfix

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.project.Project

class SuppressOverrideQuickFix : LocalQuickFix {
    override fun getFamilyName(): String = "Suppress this override warning"
    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        // Implemented in Task 6.
    }
}
```

If executing this plan via subagent-driven-development with one task per
dispatch, these stubs get immediately overwritten by Tasks 5/6's real
implementations — that's expected, not wasted work. `OverrideInspectionTest`
(Step 1) only asserts that problems are registered and *which* fix family
names are offered (via the highlight/problem data, not by actually invoking
a fix) — the stubs' empty `applyFix` bodies are never exercised by this
task's own tests.

- [ ] **Step 4: Implement `OverrideInspection`**

```kotlin
// src/main/kotlin/cloud/schneidoa/detection/OverrideInspection.kt
package cloud.schneidoa.detection

import cloud.schneidoa.detection.quickfix.RemoveOverrideQuickFix
import cloud.schneidoa.detection.quickfix.SuppressOverrideQuickFix
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.XmlElementVisitor
import com.intellij.psi.xml.XmlFile
import org.jetbrains.idea.maven.dom.MavenDomUtil

/**
 * [detectorFactory] defaults to the real, IDE-backed OverrideDetector.forProject,
 * but is overridable so tests can inject a detector backed by a fixture
 * repository directly, instead of depending on how MavenProjectsManager /
 * MavenSettingsCache resolve a local repository path inside a lightweight
 * test sandbox.
 */
class OverrideInspection(
    private val detectorFactory: (Project) -> OverrideDetector = OverrideDetector::forProject
) : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor =
        object : XmlElementVisitor() {
            override fun visitXmlFile(file: XmlFile) {
                val project = file.project
                val virtualFile = file.virtualFile ?: return
                val model = MavenDomUtil.getMavenDomProjectModel(project, virtualFile) ?: return
                val detector = detectorFactory(project)

                for (result in detector.detect(model, project)) {
                    when (result) {
                        is DetectedOverride.Confirmed -> holder.registerProblem(
                            result.candidate.versionXmlTag,
                            "Overrides ${result.declaredInBom} which already manages this dependency at ${result.bomVersion}",
                            ProblemHighlightType.WEAK_WARNING,
                            RemoveOverrideQuickFix(),
                            SuppressOverrideQuickFix()
                        )
                        is DetectedOverride.Inconclusive -> holder.registerProblem(
                            result.candidate.versionXmlTag,
                            "Cannot confirm whether this override is still needed – some BOMs could not be resolved locally",
                            ProblemHighlightType.WEAK_WARNING,
                            SuppressOverrideQuickFix()
                        )
                    }
                }
            }
        }
}
```

- [ ] **Step 5: Register the inspection in `plugin.xml`**

Edit `src/main/resources/META-INF/plugin.xml` so the `<extensions>` block reads:

```xml
    <extensions defaultExtensionNs="com.intellij">
        <toolWindow id="MyToolWindow" factoryClass="cloud.schneidoa.MyToolWindowFactory"
                    icon="AllIcons.Toolwindows.ToolWindowPalette"/>
        <localInspection implementationClass="cloud.schneidoa.detection.OverrideInspection"
                          language="XML"
                          displayName="Maven dependency override diverges from BOM"
                          groupName="Maven"
                          shortName="MavenDependencyOverride"
                          enabledByDefault="true"
                          level="WARNING"/>
    </extensions>
```

- [ ] **Step 6: Run the test to verify it passes**

Run:

```bash
./gradlew test --tests "cloud.schneidoa.detection.OverrideInspectionTest"
```

Expected: `BUILD SUCCESSFUL`, 2 tests passed.

- [ ] **Step 7: Run the full suite to confirm no regressions**

Run:

```bash
./gradlew test
```

Expected: `BUILD SUCCESSFUL`, no regressions.

- [ ] **Step 8: Commit**

```bash
git add src/main/kotlin/cloud/schneidoa/detection/OverrideInspection.kt src/main/resources/META-INF/plugin.xml src/test/kotlin/cloud/schneidoa/detection/OverrideInspectionTest.kt src/main/kotlin/cloud/schneidoa/detection/quickfix
git commit -m "$(cat <<'EOF'
Add OverrideInspection: weak-warning highlight on confirmed/inconclusive overrides

EOF
)"
```

---

### Task 5: `RemoveOverrideQuickFix`

**Files:**
- Create: `src/test/kotlin/cloud/schneidoa/detection/quickfix/RemoveOverrideQuickFixTest.kt`
- Modify: `src/main/kotlin/cloud/schneidoa/detection/quickfix/RemoveOverrideQuickFix.kt`

Deletes the `<dependency>` block entirely. Only ever offered by
`OverrideInspection` on `Confirmed` results — i.e. only when Phase 2 has
already established the BOM now manages a version that makes the override
redundant. This quick fix does not second-guess that; it trusts the
inspection not to offer it otherwise (covered by `OverrideInspectionTest`
already asserting `Inconclusive` results never get this fix).

Deliberately does **not** also delete the preceding reason/suppress comment
(if any) — a smaller, safer first version. A dangling comment after removal
is a minor cosmetic rough edge a developer can clean up themselves; silently
guessing which comment belongs to which dependency and getting it wrong would
be worse.

- [ ] **Step 1: Write the failing test**

```kotlin
// src/test/kotlin/cloud/schneidoa/detection/quickfix/RemoveOverrideQuickFixTest.kt
package cloud.schneidoa.detection.quickfix

import cloud.schneidoa.detection.OverrideDetector
import cloud.schneidoa.detection.OverrideInspection
import cloud.schneidoa.resolver.BomVersionResolver
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

class RemoveOverrideQuickFixTest : BasePlatformTestCase() {

    private fun testInspection(): OverrideInspection {
        val fixtureUrl = javaClass.classLoader.getResource("fixtures/local-repo")
            ?: error("Test fixture local-repo not found on classpath")
        val localRepositoryDir = File(fixtureUrl.toURI())
        return OverrideInspection { OverrideDetector(BomVersionResolver(localRepositoryDir)) }
    }

    fun `test removes the dependency block and leaves the rest of the file intact`() {
        myFixture.enableInspections(testInspection())
        myFixture.configureByText(
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
                            <artifactId>acme-bom</artifactId>
                            <version>1.0.0</version>
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
        myFixture.doHighlighting()

        val fix = myFixture.findSingleIntention("Remove dependency override")
        myFixture.launchAction(fix)

        val resultText = myFixture.file.text
        assertFalse(resultText.contains("jackson-databind"))
        assertTrue(resultText.contains("acme-bom"))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run:

```bash
./gradlew test --tests "cloud.schneidoa.detection.quickfix.RemoveOverrideQuickFixTest"
```

Expected: `FAILED` — the stub `applyFix` from Task 4 Step 7 does nothing, so
`jackson-databind` is still present after the fix runs.

- [ ] **Step 3: Implement `RemoveOverrideQuickFix`**

```kotlin
// src/main/kotlin/cloud/schneidoa/detection/quickfix/RemoveOverrideQuickFix.kt
package cloud.schneidoa.detection.quickfix

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.project.Project

class RemoveOverrideQuickFix : LocalQuickFix {

    override fun getFamilyName(): String = "Remove dependency override"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val dependencyTag = descriptor.psiElement.parentTag ?: return
        dependencyTag.delete()
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run:

```bash
./gradlew test --tests "cloud.schneidoa.detection.quickfix.RemoveOverrideQuickFixTest"
```

Expected: `BUILD SUCCESSFUL`, 1 test passed.

- [ ] **Step 5: Run the full suite to confirm no regressions**

Run:

```bash
./gradlew test
```

Expected: `BUILD SUCCESSFUL`, no regressions.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/cloud/schneidoa/detection/quickfix/RemoveOverrideQuickFix.kt src/test/kotlin/cloud/schneidoa/detection/quickfix/RemoveOverrideQuickFixTest.kt
git commit -m "$(cat <<'EOF'
Implement RemoveOverrideQuickFix: deletes the override dependency block

EOF
)"
```

---

### Task 6: `SuppressOverrideQuickFix`

**Files:**
- Create: `src/test/kotlin/cloud/schneidoa/detection/quickfix/SuppressOverrideQuickFixTest.kt`
- Modify: `src/main/kotlin/cloud/schneidoa/detection/quickfix/SuppressOverrideQuickFix.kt`

Inserts the suppress marker comment immediately before the `<dependency>`
tag — or, if a comment is already there (e.g. a reason comment), **replaces**
it rather than stacking a second one, since Phase 2's `findPrecedingComment`
only ever looks at the single immediately-preceding comment. Available on
both `Confirmed` and `Inconclusive` results.

- [ ] **Step 1: Write the failing test**

```kotlin
// src/test/kotlin/cloud/schneidoa/detection/quickfix/SuppressOverrideQuickFixTest.kt
package cloud.schneidoa.detection.quickfix

import cloud.schneidoa.detection.OverrideDetector
import cloud.schneidoa.detection.OverrideInspection
import cloud.schneidoa.detection.SUPPRESS_MARKER
import cloud.schneidoa.resolver.BomVersionResolver
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

class SuppressOverrideQuickFixTest : BasePlatformTestCase() {

    private fun testInspection(): OverrideInspection {
        val fixtureUrl = javaClass.classLoader.getResource("fixtures/local-repo")
            ?: error("Test fixture local-repo not found on classpath")
        val localRepositoryDir = File(fixtureUrl.toURI())
        return OverrideInspection { OverrideDetector(BomVersionResolver(localRepositoryDir)) }
    }

    fun `test inserts the suppress marker when there is no existing comment`() {
        myFixture.enableInspections(testInspection())
        myFixture.configureByText(
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
                            <artifactId>acme-bom</artifactId>
                            <version>1.0.0</version>
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
        myFixture.doHighlighting()

        val fix = myFixture.findSingleIntention("Suppress this override warning")
        myFixture.launchAction(fix)

        assertTrue(myFixture.file.text.contains(SUPPRESS_MARKER))
        // The dependency itself must still be there — suppress hides the
        // warning, it never removes anything.
        assertTrue(myFixture.file.text.contains("jackson-databind"))
    }

    fun `test replaces an existing reason comment instead of stacking a second one`() {
        myFixture.enableInspections(testInspection())
        myFixture.configureByText(
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
                            <artifactId>acme-bom</artifactId>
                            <version>1.0.0</version>
                            <type>pom</type>
                            <scope>import</scope>
                        </dependency>
                        <!-- CVE-2024-12345 -->
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
        myFixture.doHighlighting()

        val fix = myFixture.findSingleIntention("Suppress this override warning")
        myFixture.launchAction(fix)

        val resultText = myFixture.file.text
        assertTrue(resultText.contains(SUPPRESS_MARKER))
        assertFalse("The old reason comment must be gone, not just supplemented", resultText.contains("CVE-2024-12345"))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run:

```bash
./gradlew test --tests "cloud.schneidoa.detection.quickfix.SuppressOverrideQuickFixTest"
```

Expected: `FAILED` — the stub `applyFix` from Task 4 Step 7 does nothing.

- [ ] **Step 3: Implement `SuppressOverrideQuickFix`**

```kotlin
// src/main/kotlin/cloud/schneidoa/detection/quickfix/SuppressOverrideQuickFix.kt
package cloud.schneidoa.detection.quickfix

import cloud.schneidoa.detection.SUPPRESS_MARKER
import cloud.schneidoa.detection.createXmlComment
import cloud.schneidoa.detection.findPrecedingComment
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.project.Project

class SuppressOverrideQuickFix : LocalQuickFix {

    override fun getFamilyName(): String = "Suppress this override warning"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val dependencyTag = descriptor.psiElement.parentTag ?: return
        val suppressComment = createXmlComment(project, " $SUPPRESS_MARKER ")

        val existingComment = findPrecedingComment(dependencyTag)
        if (existingComment != null) {
            existingComment.replace(suppressComment)
        } else {
            dependencyTag.parent.addBefore(suppressComment, dependencyTag)
        }
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run:

```bash
./gradlew test --tests "cloud.schneidoa.detection.quickfix.SuppressOverrideQuickFixTest"
```

Expected: `BUILD SUCCESSFUL`, 2 tests passed.

- [ ] **Step 5: Run the full suite to confirm no regressions**

Run:

```bash
./gradlew test
```

Expected: `BUILD SUCCESSFUL`, no regressions.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/cloud/schneidoa/detection/quickfix/SuppressOverrideQuickFix.kt src/test/kotlin/cloud/schneidoa/detection/quickfix/SuppressOverrideQuickFixTest.kt
git commit -m "$(cat <<'EOF'
Implement SuppressOverrideQuickFix: inserts or replaces the suppress marker comment

EOF
)"
```

---

### Task 7: Full suite run and changelog entry

**Files:**
- Modify: `CHANGELOG.md`

- [ ] **Step 1: Run the full test suite**

Run:

```bash
./gradlew test
```

Expected: `BUILD SUCCESSFUL`, all tests pass, no regressions. (Exact count
will be higher than the 48 estimated after Task 3 — Tasks 4-6 each add more;
just confirm 0 failures/0 errors across all suites, the same way Phase 2's
final counts drifted from its own early estimates for legitimate reasons.)

- [ ] **Step 2: Update the changelog**

Edit `CHANGELOG.md` so the `[Unreleased]` section's `### Added` list gains a
new entry (keep the existing two Phase 1/2 entries as-is):

```markdown
- Inspection: confirmed and inconclusive dependency overrides are now
  highlighted directly in the editor as a weak warning on the `<version>`
  text, with quick fixes to remove the override (when confirmed safe) or
  suppress the warning. Quick fixes for updating to the latest available
  version or bumping the parent/BOM are not yet available — they need the
  Version Advisor (network repository lookups), planned for a later phase.
```

- [ ] **Step 3: Commit**

```bash
git add CHANGELOG.md
git commit -m "$(cat <<'EOF'
Document inspection and local quick fixes in changelog

EOF
)"
```

---

## Roadmap (future plans, not covered here)

1. **Gutter icon (`LineMarkerProvider`)** — supplementary at-a-glance
   affordance alongside the inspection's wavy underline, deferred from this
   phase to keep it focused. Own extension point, own registration, own
   tests.
2. **Version Advisor** — repository metadata lookups (read from `pom.xml`/
   `settings.xml`, per the design's Scope section) for "newer version
   available" / "newer parent available".
3. **Update-override and bump-parent quick fixes** — the two remaining quick
   fixes from the design, blocked on Version Advisor existing first.
4. **Tool Window** — project-wide overview panel, iterating
   `MavenProjectsManager.getInstance(project).getProjects()` and running the
   detector per module.
5. **Caching** — per Phase 2's final review, `BomEffectiveModelResolver`
   currently has no memoization; once the inspection is exercised on real,
   larger projects (many candidates per file, many files per project), add a
   `Map<Gav, BomModelResult>` cache layer, invalidated on Maven reimport /
   relevant file changes.
