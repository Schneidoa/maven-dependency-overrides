# Maven Dependency Overrides — Phase 2: Override Detector — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Given an open `pom.xml`, detect which `<dependencyManagement>` entries are
overrides that diverge from what an imported BOM would manage — reusing Phase 1's
`BomVersionResolver` for the actual version comparison, adding the IntelliJ-specific
pieces: reading the module's own dependency-management PSI/DOM, discovering the
ordered chain of imported BOMs across the parent hierarchy, and parsing the
reason/suppress XML comments.

**Architecture:** Three focused, independently testable components on top of
IntelliJ's bundled Maven plugin's DOM API (`org.jetbrains.idea.maven.dom`):
`DependencyManagementScanner` (finds literal-version candidates in one module's own
POM, reads their reason/suppress comments), `BomChainResolver` (walks the `<parent>`
chain collecting `<scope>import</scope>` BOM coordinates in Maven's precedence
order), and `OverrideDetector` (orchestrates both against Phase 1's
`BomVersionResolver`, producing confirmed/inconclusive results — silently dropping
anything that turns out not to be a real, unsuppressed divergence). No editor UI
yet — that's Phase 3. This phase's output is a plain function you can call and
assert against in a test, nothing more.

**Tech Stack:** Kotlin, IntelliJ Platform Gradle Plugin (`bundledPlugin("org.jetbrains.idea.maven")`),
IntelliJ Platform Test Framework (`BasePlatformTestCase`, already available via the
existing `testFramework(TestFrameworkType.Platform)` declaration).

---

## Why this phase is scoped the way it is

Per the [design doc](../specs/2026-08-10-maven-dependency-overrides-design.md) and
the [Phase 1 plan's roadmap](2026-08-10-maven-dependency-overrides-phase1-bom-resolver.md#roadmap-future-plans-not-covered-here),
"Override Detector" is its own phase, separate from "Editor Integration": this
phase produces the *detection logic* (a function from "here's a pom.xml" to "here
are the overrides"), fully testable via IntelliJ's lightweight Platform test
fixtures, without touching line markers, inspections, or quick fixes — that's
Phase 3's job, once this phase's output type (`DetectedOverride`) exists for it to
render.

**One deliberate simplification vs. the design doc's literal wording:** the design
describes the detector as two stages — "fast synchronous pre-filter" then "async
precise check" — motivated by editor responsiveness (not blocking the UI thread
while Maven models get built). That's a *threading/scheduling* concern that
belongs to whatever calls this code from the editor (Phase 3), not to the
detection logic itself. This phase exposes a single `OverrideDetector.detect(...)`
function that does the full job; Phase 3 decides how to call it (background
thread, caching, progressive UI updates). The user-visible behavior described in
the design (confirmed overrides only, reason/suppress comments, silent drop of
non-issues) is unchanged — only the "how many internal passes" implementation
detail is simplified, the same way Phase 1 refined "build the model twice" into
"resolve the BOM's own model directly" without changing what the Resolver
promises callers.

**A second, more important scoping note — this phase carries real, only
partially verified platform-API risk.** Phase 1 embedded Apache Maven's own
`maven-model-builder`, a library whose exact API surface could be verified
against public source on GitHub with high confidence. This phase instead uses
IntelliJ's *bundled Maven plugin's* DOM API
(`org.jetbrains.idea.maven.dom.*`) — also verified against public
`intellij-community` source for the specific classes/methods used below.
**One assumption did turn out to be wrong, and the fix below reflects that
correction:** `MavenDomProjectProcessorUtils.findParent` resolves a `<parent>`
reference entirely by looking up its groupId:artifactId:version against
`MavenProjectsManager`'s already-synced project index — it does not use
`<relativePath>` or any file-based navigation at all (confirmed by reading its
source). That means it only works once a real Maven import/sync has populated
that index, which (a) doesn't happen in lightweight, in-memory Platform test
fixtures, and, more importantly, (b) wouldn't reflect a `<parent>` a developer
just edited until the next reimport completes — a worse fit for a detector
that's meant to react to freshly edited POMs. **`BomChainResolver` (Task 3)
therefore resolves the parent itself, purely via `<relativePath>` file
navigation** (`VfsUtilCore.findRelativeFile`, falling back to Maven's own
documented default of `../pom.xml` when `<relativePath>` is absent) —
independent of whether the project has been Maven-synced, and directly
testable with lightweight fixtures. Task 1's spike test verifies this
narrower, more mechanical assumption (relative file navigation resolves
correctly against an in-memory fixture) instead.

## File Structure

```
build.gradle.kts                                    add bundledPlugin("org.jetbrains.idea.maven")
src/main/resources/META-INF/plugin.xml               add <depends>org.jetbrains.idea.maven</depends>

src/main/kotlin/cloud/schneidoa/detection/
  OverrideCandidate.kt            Data class: one literal-version dependencyManagement entry
  DependencyManagementScanner.kt  Scans a module's own managed deps -> candidates, reads comments
  BomChainResolver.kt             Walks the parent chain, collects import-scope BOM Gavs in order
  OverrideDetector.kt             Orchestrates the above + Phase 1's BomVersionResolver

src/test/kotlin/cloud/schneidoa/detection/
  MavenDomInfrastructureSpikeTest.kt   Verifies DOM recognition + parent-chain resolution (Task 1)
  DependencyManagementScannerTest.kt   (Task 2)
  BomChainResolverTest.kt              (Task 3)
  OverrideDetectorTest.kt              (Task 4, reuses Phase 1's src/test/resources/fixtures/local-repo)
```

`Ga`/`Gav` from Phase 1's `cloud.schneidoa.resolver.MavenCoordinates.kt` and
`BomVersionResolver`/`ManagedVersionLookup` from
`cloud.schneidoa.resolver.BomVersionResolver.kt` are reused as-is, unmodified —
this phase only adds new files.

---

### Task 1: Maven plugin dependency + DOM infrastructure spike

**Files:**
- Modify: `build.gradle.kts`
- Modify: `src/main/resources/META-INF/plugin.xml`
- Create: `src/test/kotlin/cloud/schneidoa/detection/MavenDomInfrastructureSpikeTest.kt`

- [ ] **Step 1: Add the bundled Maven plugin dependency**

Edit `build.gradle.kts` so the `intellijPlatform` block reads:

```kotlin
    intellijPlatform {
        intellijIdea("2025.3.5")
        testFramework(TestFrameworkType.Platform)

        // Add plugin dependencies for compilation here:
        bundledPlugin("com.intellij.java")
        bundledPlugin("org.jetbrains.idea.maven")
    }
```

- [ ] **Step 2: Declare the runtime dependency in `plugin.xml`**

Edit `src/main/resources/META-INF/plugin.xml` so the `<depends>` line for Java is
followed by one for Maven:

```xml
    <depends>com.intellij.java</depends>
    <depends>org.jetbrains.idea.maven</depends>
```

> **Known risk:** the IntelliJ Platform Gradle Plugin has had issues where
> `bundledPlugin("org.jetbrains.idea.maven")` doesn't pull in every class you'd
> expect (e.g. [intellij-platform-gradle-plugin#2101](https://github.com/JetBrains/intellij-platform-gradle-plugin/issues/2101),
> where `org.jetbrains.idea.maven.model.MavenId` — in a separate `plugins/maven/model`
> submodule — failed to resolve on a newer/bleeding-edge platform version). The
> classes this plan uses (`org.jetbrains.idea.maven.dom.*`) are in the plugin's
> main module, not that submodule, and `2025.3.5` predates the specific restructuring
> in that issue, so this is not expected to bite here — but if Step 4 below fails
> with an unresolved-reference compile error for any of the `org.jetbrains.idea.maven.dom`
> imports, this is the first thing to check.

- [ ] **Step 3: Write the spike test**

```kotlin
// src/test/kotlin/cloud/schneidoa/detection/MavenDomInfrastructureSpikeTest.kt
package cloud.schneidoa.detection

import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jetbrains.idea.maven.dom.MavenDomUtil

class MavenDomInfrastructureSpikeTest : BasePlatformTestCase() {

    fun `test recognizes a pom xml file as a MavenDomProjectModel`() {
        val file = myFixture.configureByText(
            "pom.xml",
            """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.example</groupId>
                <artifactId>child</artifactId>
                <version>1.0.0</version>
            </project>
            """.trimIndent()
        )

        val model = MavenDomUtil.getMavenDomProjectModel(project, file.virtualFile)

        assertNotNull("Expected pom.xml to be recognized as a MavenDomProjectModel", model)
        assertEquals("child", model!!.artifactId.rawText?.trim())
    }

    fun `test resolves a relative path across two in-memory fixture files`() {
        myFixture.addFileToProject(
            "parent/pom.xml",
            """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.example</groupId>
                <artifactId>parent</artifactId>
                <version>1.0.0</version>
                <packaging>pom</packaging>
            </project>
            """.trimIndent()
        )
        val childFile = myFixture.addFileToProject(
            "child/pom.xml",
            """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <parent>
                    <groupId>com.example</groupId>
                    <artifactId>parent</artifactId>
                    <version>1.0.0</version>
                    <relativePath>../parent/pom.xml</relativePath>
                </parent>
                <artifactId>child</artifactId>
            </project>
            """.trimIndent()
        )

        val childDir = childFile.virtualFile.parent
        assertNotNull("Expected child/pom.xml to have a parent directory", childDir)

        val resolved = VfsUtilCore.findRelativeFile("../parent/pom.xml", childDir)

        assertNotNull(
            "Expected VfsUtilCore.findRelativeFile to resolve ../parent/pom.xml from an " +
                "in-memory fixture directory, without any Maven project sync",
            resolved
        )
        assertEquals("pom.xml", resolved!!.name)

        val parentModel = MavenDomUtil.getMavenDomProjectModel(project, resolved)
        assertNotNull(parentModel)
        assertEquals("parent", parentModel!!.artifactId.rawText?.trim())
    }
}
```

- [ ] **Step 4: Run the spike and confirm both assumptions hold**

Run:

```bash
./gradlew test --tests "cloud.schneidoa.detection.MavenDomInfrastructureSpikeTest"
```

Expected: `BUILD SUCCESSFUL`, 2 tests passed.

**If either test fails** (compiles but assertion fails, e.g. `resolved` is
`null`): this means `VfsUtilCore.findRelativeFile` doesn't resolve relative
paths against lightweight in-memory fixture directories either. Do not try to
work around this — stop and report BLOCKED with the exact failure. This would
mean Task 3's design needs a heavier Maven-aware test fixture strategy, which
is a plan-level decision, not an implementation workaround.

- [ ] **Step 5: Commit**

```bash
git add build.gradle.kts src/main/resources/META-INF/plugin.xml src/test/kotlin/cloud/schneidoa/detection/MavenDomInfrastructureSpikeTest.kt
git commit -m "$(cat <<'EOF'
Add Maven plugin dependency and verify DOM/parent-chain test infrastructure

EOF
)"
```

---

### Task 2: `OverrideCandidate` and `DependencyManagementScanner`

**Files:**
- Create: `src/test/kotlin/cloud/schneidoa/detection/DependencyManagementScannerTest.kt`
- Create: `src/main/kotlin/cloud/schneidoa/detection/OverrideCandidate.kt`
- Create: `src/main/kotlin/cloud/schneidoa/detection/DependencyManagementScanner.kt`

This scans one module's **own** `<dependencyManagement>` (not inherited entries —
each POM is scanned independently, matching the design doc's multi-module
handling) for literal-version entries (i.e. anything that isn't itself a
`<scope>import</scope>` BOM reference), resolving `${property}` references and
reading the XML comment immediately preceding each `<dependency>` as either a
free-text reason or, if it matches the exact suppress marker, a suppress flag.

- [ ] **Step 1: Write the failing test**

```kotlin
// src/test/kotlin/cloud/schneidoa/detection/DependencyManagementScannerTest.kt
package cloud.schneidoa.detection

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jetbrains.idea.maven.dom.MavenDomUtil
import org.jetbrains.idea.maven.dom.model.MavenDomProjectModel

class DependencyManagementScannerTest : BasePlatformTestCase() {

    fun `test finds a literal version override with a reason comment`() {
        val model = configurePom(
            """
            <!-- CVE-2024-12345, remove once Spring catches up -->
            <dependency>
                <groupId>com.fasterxml.jackson.core</groupId>
                <artifactId>jackson-databind</artifactId>
                <version>2.15.4</version>
            </dependency>
            """.trimIndent()
        )

        val candidates = DependencyManagementScanner.scan(model)

        assertEquals(1, candidates.size)
        val candidate = candidates.single()
        assertEquals("com.fasterxml.jackson.core", candidate.ga.groupId)
        assertEquals("jackson-databind", candidate.ga.artifactId)
        assertEquals("2.15.4", candidate.declaredVersion)
        assertEquals("CVE-2024-12345, remove once Spring catches up", candidate.reason)
        assertFalse(candidate.suppressed)
    }

    fun `test resolves a property based version`() {
        val model = configurePom(
            dependencyManagementEntries = """
            <dependency>
                <groupId>com.example</groupId>
                <artifactId>widget-core</artifactId>
                <version>${'$'}{widget.version}</version>
            </dependency>
            """.trimIndent(),
            extraProperties = "<widget.version>4.2.0</widget.version>"
        )

        val candidates = DependencyManagementScanner.scan(model)

        assertEquals("4.2.0", candidates.single().declaredVersion)
    }

    fun `test marks a candidate as suppressed and reports no reason`() {
        val model = configurePom(
            """
            <!-- maven-dependency-overrides: suppress -->
            <dependency>
                <groupId>com.example</groupId>
                <artifactId>widget-core</artifactId>
                <version>1.0.0</version>
            </dependency>
            """.trimIndent()
        )

        val candidate = DependencyManagementScanner.scan(model).single()

        assertTrue(candidate.suppressed)
        assertNull(candidate.reason)
    }

    fun `test reports no reason when there is no preceding comment`() {
        val model = configurePom(
            """
            <dependency>
                <groupId>com.example</groupId>
                <artifactId>widget-core</artifactId>
                <version>1.0.0</version>
            </dependency>
            """.trimIndent()
        )

        val candidate = DependencyManagementScanner.scan(model).single()

        assertFalse(candidate.suppressed)
        assertNull(candidate.reason)
    }

    fun `test excludes import scope entries since those are BOM imports, not overrides`() {
        val model = configurePom(
            """
            <dependency>
                <groupId>com.example</groupId>
                <artifactId>some-bom</artifactId>
                <version>1.0.0</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
            """.trimIndent()
        )

        assertTrue(DependencyManagementScanner.scan(model).isEmpty())
    }

    private fun configurePom(
        dependencyManagementEntries: String,
        extraProperties: String = ""
    ): MavenDomProjectModel {
        val file = myFixture.configureByText(
            "pom.xml",
            """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.example</groupId>
                <artifactId>test-module</artifactId>
                <version>1.0.0</version>

                <properties>
                    $extraProperties
                </properties>

                <dependencyManagement>
                    <dependencies>
                        $dependencyManagementEntries
                    </dependencies>
                </dependencyManagement>
            </project>
            """.trimIndent()
        )
        return MavenDomUtil.getMavenDomProjectModel(project, file.virtualFile)!!
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run:

```bash
./gradlew test --tests "cloud.schneidoa.detection.DependencyManagementScannerTest"
```

Expected: compilation failure — `unresolved reference: DependencyManagementScanner`
(and `OverrideCandidate`).

- [ ] **Step 3: Implement `OverrideCandidate`**

```kotlin
// src/main/kotlin/cloud/schneidoa/detection/OverrideCandidate.kt
package cloud.schneidoa.detection

import cloud.schneidoa.resolver.Ga
import com.intellij.psi.xml.XmlTag

/**
 * A literal-version entry found in a module's own `<dependencyManagement>` —
 * not yet compared against any BOM, just what's textually present in the POM.
 * [xmlTag] is kept so a later phase (Editor Integration) can navigate to /
 * anchor markers on the exact `<dependency>` element without re-scanning.
 */
data class OverrideCandidate(
    val ga: Ga,
    val declaredVersion: String,
    val xmlTag: XmlTag,
    val reason: String?,
    val suppressed: Boolean
)
```

- [ ] **Step 4: Implement `DependencyManagementScanner`**

```kotlin
// src/main/kotlin/cloud/schneidoa/detection/DependencyManagementScanner.kt
package cloud.schneidoa.detection

import cloud.schneidoa.resolver.Ga
import com.intellij.psi.xml.XmlComment
import com.intellij.psi.xml.XmlTag
import org.jetbrains.idea.maven.dom.MavenPropertyResolver
import org.jetbrains.idea.maven.dom.model.MavenDomDependency
import org.jetbrains.idea.maven.dom.model.MavenDomProjectModel

private const val SUPPRESS_MARKER = "maven-dependency-overrides: suppress"

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
        val resolvedVersion = MavenPropertyResolver.resolve(rawVersion, model)
        val (reason, suppressed) = readPrecedingComment(xmlTag)

        return OverrideCandidate(
            ga = Ga(groupId, artifactId),
            declaredVersion = resolvedVersion,
            xmlTag = xmlTag,
            reason = reason,
            suppressed = suppressed
        )
    }

    /**
     * Walks backward over blank (whitespace-only) siblings to find the nearest
     * real preceding sibling, and checks whether it's a comment. Deliberately
     * avoids PsiTreeUtil.skipWhitespacesBackward, which skips PsiWhiteSpace
     * nodes specifically — inter-tag whitespace in XML PSI isn't guaranteed to
     * be represented that way, so comparing blank text directly is more robust.
     */
    private fun readPrecedingComment(xmlTag: XmlTag): Pair<String?, Boolean> {
        var sibling = xmlTag.prevSibling
        while (sibling != null && sibling.text.isBlank()) {
            sibling = sibling.prevSibling
        }
        val comment = sibling as? XmlComment ?: return null to false
        val text = comment.commentText.trim()
        return if (text == SUPPRESS_MARKER) null to true else text to false
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run:

```bash
./gradlew test --tests "cloud.schneidoa.detection.DependencyManagementScannerTest"
```

Expected: `BUILD SUCCESSFUL`, 5 tests passed.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/cloud/schneidoa/detection/OverrideCandidate.kt src/main/kotlin/cloud/schneidoa/detection/DependencyManagementScanner.kt src/test/kotlin/cloud/schneidoa/detection/DependencyManagementScannerTest.kt
git commit -m "$(cat <<'EOF'
Add DependencyManagementScanner: literal-version candidates + comment parsing

EOF
)"
```

---

### Task 3: `BomChainResolver`

**Files:**
- Create: `src/test/kotlin/cloud/schneidoa/detection/BomChainResolverTest.kt`
- Create: `src/main/kotlin/cloud/schneidoa/detection/BomChainResolver.kt`

Walks a module's own `<dependencyManagement>` for `<scope>import</scope><type>pom</type>`
entries (the BOMs it imports), then climbs the `<parent>` chain and repeats —
building the ordered `List<Gav>` that Phase 1's `BomVersionResolver.resolveManagedVersion`
expects: nearest (child) BOM imports first, matching Maven's real precedence.

Parent resolution is done via `<relativePath>` file navigation
(`VfsUtilCore.findRelativeFile`), **not** `MavenDomProjectProcessorUtils.findParent`
— see "Why this phase is scoped the way it is" above for why: that utility only
resolves against `MavenProjectsManager`'s already-synced project index, which
doesn't work for freshly edited POMs or lightweight test fixtures. Task 1's
spike already confirmed `VfsUtilCore.findRelativeFile` works correctly against
in-memory fixtures.

- [ ] **Step 1: Write the failing test**

```kotlin
// src/test/kotlin/cloud/schneidoa/detection/BomChainResolverTest.kt
package cloud.schneidoa.detection

import cloud.schneidoa.resolver.Gav
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jetbrains.idea.maven.dom.MavenDomUtil
import org.jetbrains.idea.maven.dom.model.MavenDomProjectModel

class BomChainResolverTest : BasePlatformTestCase() {

    fun `test collects import scope entries declared directly in the module`() {
        val model = configureModule(
            """
            <dependency>
                <groupId>com.example</groupId>
                <artifactId>acme-bom</artifactId>
                <version>1.0.0</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
            """.trimIndent()
        )

        val chain = BomChainResolver.resolveBomChain(model, project)

        assertEquals(listOf(Gav("com.example", "acme-bom", "1.0.0")), chain)
    }

    fun `test ignores non import scope entries`() {
        val model = configureModule(
            """
            <dependency>
                <groupId>com.fasterxml.jackson.core</groupId>
                <artifactId>jackson-databind</artifactId>
                <version>2.15.4</version>
            </dependency>
            """.trimIndent()
        )

        assertTrue(BomChainResolver.resolveBomChain(model, project).isEmpty())
    }

    fun `test resolves a property based BOM version`() {
        val model = configureModule(
            dependencyManagementEntries = """
            <dependency>
                <groupId>com.example</groupId>
                <artifactId>acme-bom</artifactId>
                <version>${'$'}{acme.bom.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
            """.trimIndent(),
            extraProperties = "<acme.bom.version>1.0.0</acme.bom.version>"
        )

        val chain = BomChainResolver.resolveBomChain(model, project)

        assertEquals(listOf(Gav("com.example", "acme-bom", "1.0.0")), chain)
    }

    fun `test orders child imports before parent imports`() {
        myFixture.addFileToProject(
            "parent/pom.xml",
            """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.example</groupId>
                <artifactId>parent</artifactId>
                <version>1.0.0</version>
                <packaging>pom</packaging>

                <dependencyManagement>
                    <dependencies>
                        <dependency>
                            <groupId>com.example</groupId>
                            <artifactId>legacy-bom</artifactId>
                            <version>1.0.0</version>
                            <type>pom</type>
                            <scope>import</scope>
                        </dependency>
                    </dependencies>
                </dependencyManagement>
            </project>
            """.trimIndent()
        )
        val childFile = myFixture.addFileToProject(
            "child/pom.xml",
            """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <parent>
                    <groupId>com.example</groupId>
                    <artifactId>parent</artifactId>
                    <version>1.0.0</version>
                    <relativePath>../parent/pom.xml</relativePath>
                </parent>
                <artifactId>child</artifactId>

                <dependencyManagement>
                    <dependencies>
                        <dependency>
                            <groupId>com.example</groupId>
                            <artifactId>acme-bom</artifactId>
                            <version>1.0.0</version>
                            <type>pom</type>
                            <scope>import</scope>
                        </dependency>
                    </dependencies>
                </dependencyManagement>
            </project>
            """.trimIndent()
        )

        val childModel = MavenDomUtil.getMavenDomProjectModel(project, childFile.virtualFile)!!
        val chain = BomChainResolver.resolveBomChain(childModel, project)

        assertEquals(
            listOf(
                Gav("com.example", "acme-bom", "1.0.0"),
                Gav("com.example", "legacy-bom", "1.0.0")
            ),
            chain
        )
    }

    private fun configureModule(
        dependencyManagementEntries: String,
        extraProperties: String = ""
    ): MavenDomProjectModel {
        val file = myFixture.configureByText(
            "pom.xml",
            """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.example</groupId>
                <artifactId>test-module</artifactId>
                <version>1.0.0</version>

                <properties>
                    $extraProperties
                </properties>

                <dependencyManagement>
                    <dependencies>
                        $dependencyManagementEntries
                    </dependencies>
                </dependencyManagement>
            </project>
            """.trimIndent()
        )
        return MavenDomUtil.getMavenDomProjectModel(project, file.virtualFile)!!
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run:

```bash
./gradlew test --tests "cloud.schneidoa.detection.BomChainResolverTest"
```

Expected: compilation failure — `unresolved reference: BomChainResolver`.

- [ ] **Step 3: Implement `BomChainResolver`**

> **Note:** This snippet shows the version as originally planned. A later
> hardening round (see the `d7ac53d` commit) refined the empty-`<relativePath/>`
> handling to distinguish "absent" (defaults to `../pom.xml`) from
> "present but blank" (gives up, returns `null`) — see the actual shipped
> `BomChainResolver.kt` for the current behavior.

```kotlin
// src/main/kotlin/cloud/schneidoa/detection/BomChainResolver.kt
package cloud.schneidoa.detection

import cloud.schneidoa.resolver.Gav
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.idea.maven.dom.MavenDomUtil
import org.jetbrains.idea.maven.dom.MavenPropertyResolver
import org.jetbrains.idea.maven.dom.model.MavenDomDependency
import org.jetbrains.idea.maven.dom.model.MavenDomProjectModel

/**
 * Bounds how far up the <parent> chain we'll climb. A real Maven precedence
 * walk would be indefinite, but Maven itself has practical limits on parent
 * depth too; this guards against a malformed/cyclic <parent> reference in a
 * broken POM turning into an infinite loop, without relying on
 * MavenDomProjectModel equality semantics (unverified) for cycle detection.
 */
private const val MAX_PARENT_CHAIN_DEPTH = 50
private const val DEFAULT_RELATIVE_PARENT_PATH = "../pom.xml"

object BomChainResolver {

    fun resolveBomChain(model: MavenDomProjectModel, project: Project): List<Gav> {
        val chain = mutableListOf<Gav>()
        var current: MavenDomProjectModel? = model
        var depth = 0

        while (current != null && depth < MAX_PARENT_CHAIN_DEPTH) {
            chain += importedBomsOf(current)
            current = resolveParent(current, project)
            depth++
        }

        return chain
    }

    private fun importedBomsOf(model: MavenDomProjectModel): List<Gav> {
        val managedDependencies: List<MavenDomDependency> = model.dependencyManagement.dependencies.dependencies

        return managedDependencies
            .filter { it.scope.rawText?.trim() == "import" && it.type.rawText?.trim() == "pom" }
            .mapNotNull { toGav(it, model) }
    }

    private fun toGav(dependency: MavenDomDependency, model: MavenDomProjectModel): Gav? {
        val groupId = dependency.groupId.rawText?.trim()
        val artifactId = dependency.artifactId.rawText?.trim()
        val rawVersion = dependency.version.rawText?.trim()
        if (groupId.isNullOrEmpty() || artifactId.isNullOrEmpty() || rawVersion.isNullOrEmpty()) return null
        return Gav(groupId, artifactId, MavenPropertyResolver.resolve(rawVersion, model))
    }

    /**
     * Resolves <parent> purely via <relativePath> file navigation, not via
     * MavenDomProjectProcessorUtils.findParent (which only works against
     * MavenProjectsManager's already-synced project index — see "Why this
     * phase is scoped the way it is" at the top of this plan). Falls back to
     * Maven's own documented default of "../pom.xml" when <relativePath> is
     * absent. If the resolved path is a directory rather than a file (also
     * valid per Maven's own relativePath semantics), looks for pom.xml inside it.
     */
    private fun resolveParent(model: MavenDomProjectModel, project: Project): MavenDomProjectModel? {
        val parent = model.mavenParent
        if (parent.xmlTag == null) return null

        val currentFile = model.xmlTag?.containingFile?.virtualFile ?: return null
        val baseDir = currentFile.parent ?: return null
        val relativePath = parent.relativePath.rawText?.trim()?.takeIf { it.isNotEmpty() }
            ?: DEFAULT_RELATIVE_PARENT_PATH

        val resolved = VfsUtilCore.findRelativeFile(relativePath, baseDir) ?: return null
        val parentFile: VirtualFile? = if (resolved.isDirectory) resolved.findChild("pom.xml") else resolved
        return parentFile?.let { MavenDomUtil.getMavenDomProjectModel(project, it) }
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run:

```bash
./gradlew test --tests "cloud.schneidoa.detection.BomChainResolverTest"
```

Expected: `BUILD SUCCESSFUL`, 4 tests passed.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/cloud/schneidoa/detection/BomChainResolver.kt src/test/kotlin/cloud/schneidoa/detection/BomChainResolverTest.kt
git commit -m "$(cat <<'EOF'
Add BomChainResolver: ordered BOM discovery across the parent chain

EOF
)"
```

---

### Task 4: `DetectedOverride` and `OverrideDetector`

**Files:**
- Create: `src/test/kotlin/cloud/schneidoa/detection/OverrideDetectorTest.kt`
- Create: `src/main/kotlin/cloud/schneidoa/detection/OverrideDetector.kt`

This is the phase's public API: orchestrates `DependencyManagementScanner` +
`BomChainResolver` + Phase 1's `BomVersionResolver`, drops suppressed candidates
and non-issues (matching or unmanaged), and reports the rest as either
`Confirmed` (a real divergence) or `Inconclusive` (couldn't fully check — some
BOM in the chain wasn't locally resolvable, so "not found" isn't a confident
answer, per `ManagedVersionLookup.NotFound.uncheckedBoms`'s contract from Phase 1).

- [ ] **Step 1: Write the failing test**

```kotlin
// src/test/kotlin/cloud/schneidoa/detection/OverrideDetectorTest.kt
package cloud.schneidoa.detection

import cloud.schneidoa.resolver.BomVersionResolver
import cloud.schneidoa.resolver.Gav
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jetbrains.idea.maven.dom.MavenDomUtil
import org.jetbrains.idea.maven.dom.model.MavenDomProjectModel
import java.io.File

class OverrideDetectorTest : BasePlatformTestCase() {

    private fun localRepositoryDir(): File {
        val fixtureUrl = javaClass.classLoader.getResource("fixtures/local-repo")
            ?: error("Test fixture local-repo not found on classpath")
        return File(fixtureUrl.toURI())
    }

    fun `test confirms an override that diverges from the BOM managed version`() {
        val model = configureModuleImportingAcmeBom(
            """
            <!-- CVE-2024-12345 -->
            <dependency>
                <groupId>com.fasterxml.jackson.core</groupId>
                <artifactId>jackson-databind</artifactId>
                <version>2.15.4</version>
            </dependency>
            """.trimIndent()
        )
        val detector = OverrideDetector(BomVersionResolver(localRepositoryDir()))

        val results = detector.detect(model, project)

        assertEquals(1, results.size)
        val confirmed = results.single() as DetectedOverride.Confirmed
        assertEquals("jackson-databind", confirmed.candidate.ga.artifactId)
        assertEquals("2.15.3", confirmed.bomVersion)
        assertEquals(Gav("com.example", "acme-bom", "1.0.0"), confirmed.declaredInBom)
        assertEquals("CVE-2024-12345", confirmed.candidate.reason)
    }

    fun `test drops a candidate whose version already matches the BOM`() {
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

        assertTrue(detector.detect(model, project).isEmpty())
    }

    fun `test excludes a suppressed candidate even if it diverges from the BOM`() {
        val model = configureModuleImportingAcmeBom(
            """
            <!-- maven-dependency-overrides: suppress -->
            <dependency>
                <groupId>com.fasterxml.jackson.core</groupId>
                <artifactId>jackson-databind</artifactId>
                <version>2.15.4</version>
            </dependency>
            """.trimIndent()
        )
        val detector = OverrideDetector(BomVersionResolver(localRepositoryDir()))

        assertTrue(detector.detect(model, project).isEmpty())
    }

    fun `test drops a candidate that is not managed by any imported BOM`() {
        val model = configureModuleImportingAcmeBom(
            """
            <dependency>
                <groupId>org.example</groupId>
                <artifactId>totally-unmanaged</artifactId>
                <version>9.9.9</version>
            </dependency>
            """.trimIndent()
        )
        val detector = OverrideDetector(BomVersionResolver(localRepositoryDir()))

        assertTrue(detector.detect(model, project).isEmpty())
    }

    fun `test reports inconclusive when an imported BOM cannot be resolved locally`() {
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

        val results = detector.detect(model, project)

        assertEquals(1, results.size)
        val inconclusive = results.single() as DetectedOverride.Inconclusive
        assertEquals("jackson-databind", inconclusive.candidate.ga.artifactId)
        assertEquals(listOf(Gav("com.example", "does-not-exist-bom", "9.9.9")), inconclusive.uncheckedBoms)
    }

    private fun configureModuleImportingAcmeBom(dependencyManagementEntries: String): MavenDomProjectModel {
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
                            <artifactId>acme-bom</artifactId>
                            <version>1.0.0</version>
                            <type>pom</type>
                            <scope>import</scope>
                        </dependency>
                        $dependencyManagementEntries
                    </dependencies>
                </dependencyManagement>
            </project>
            """.trimIndent()
        )
        return MavenDomUtil.getMavenDomProjectModel(project, file.virtualFile)!!
    }
}
```

This reuses Phase 1's fixture local repository (`src/test/resources/fixtures/local-repo`,
specifically `acme-bom`, which manages `jackson-databind` at `2.15.3`) — no new
fixture POMs needed for this task.

- [ ] **Step 2: Run the test to verify it fails**

Run:

```bash
./gradlew test --tests "cloud.schneidoa.detection.OverrideDetectorTest"
```

Expected: compilation failure — `unresolved reference: OverrideDetector` (and
`DetectedOverride`).

- [ ] **Step 3: Implement `DetectedOverride` and `OverrideDetector`**

```kotlin
// src/main/kotlin/cloud/schneidoa/detection/OverrideDetector.kt
package cloud.schneidoa.detection

import cloud.schneidoa.resolver.BomVersionResolver
import cloud.schneidoa.resolver.Gav
import cloud.schneidoa.resolver.ManagedVersionLookup
import com.intellij.openapi.project.Project
import org.jetbrains.idea.maven.dom.model.MavenDomProjectModel
import org.jetbrains.idea.maven.project.MavenProjectsManager

sealed class DetectedOverride {
    /** A confirmed, unsuppressed override whose version differs from what the BOM chain manages. */
    data class Confirmed(
        val candidate: OverrideCandidate,
        val bomVersion: String,
        val declaredInBom: Gav
    ) : DetectedOverride()

    /**
     * Looks like it might be an override, but at least one BOM in the chain
     * couldn't be resolved locally — so "not managed anywhere" can't be
     * confidently ruled out. See ManagedVersionLookup.NotFound's contract.
     */
    data class Inconclusive(
        val candidate: OverrideCandidate,
        val uncheckedBoms: List<Gav>
    ) : DetectedOverride()
}

class OverrideDetector(private val bomVersionResolver: BomVersionResolver) {

    fun detect(model: MavenDomProjectModel, project: Project): List<DetectedOverride> {
        val bomChain = BomChainResolver.resolveBomChain(model, project)

        return DependencyManagementScanner.scan(model)
            .filterNot { it.suppressed }
            .mapNotNull { candidate -> evaluate(candidate, bomChain) }
    }

    private fun evaluate(candidate: OverrideCandidate, bomChain: List<Gav>): DetectedOverride? {
        return when (val lookup = bomVersionResolver.resolveManagedVersion(bomChain, candidate.ga)) {
            is ManagedVersionLookup.Found ->
                if (lookup.version != candidate.declaredVersion) {
                    DetectedOverride.Confirmed(candidate, lookup.version, lookup.declaredIn)
                } else {
                    null
                }
            is ManagedVersionLookup.NotFound ->
                if (lookup.uncheckedBoms.isNotEmpty()) {
                    DetectedOverride.Inconclusive(candidate, lookup.uncheckedBoms)
                } else {
                    null
                }
        }
    }

    companion object {
        /** Convenience factory for real IDE usage — reads the local repo path IntelliJ's Maven support already knows about. */
        fun forProject(project: Project): OverrideDetector {
            val localRepositoryDir = MavenProjectsManager.getInstance(project).repositoryPath.toFile()
            return OverrideDetector(BomVersionResolver(localRepositoryDir))
        }
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run:

```bash
./gradlew test --tests "cloud.schneidoa.detection.OverrideDetectorTest"
```

Expected: `BUILD SUCCESSFUL`, 5 tests passed.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/cloud/schneidoa/detection/OverrideDetector.kt src/test/kotlin/cloud/schneidoa/detection/OverrideDetectorTest.kt
git commit -m "$(cat <<'EOF'
Add OverrideDetector: orchestrates scanner + BOM chain + Phase 1 resolver

EOF
)"
```

---

### Task 5: Full suite run and changelog entry

**Files:**
- Modify: `CHANGELOG.md`

- [ ] **Step 1: Run the full test suite**

Run:

```bash
./gradlew test
```

Expected: `BUILD SUCCESSFUL`, all tests pass — the 18 from Phase 1 plus 2 + 5 + 4 + 5
= 16 from this phase, 34 total, no regressions.

- [ ] **Step 2: Update the changelog**

Edit `CHANGELOG.md` so the `[Unreleased]` section's `### Added` list gains a new
entry (keep the existing BOM Version Resolver entry from Phase 1 as-is):

```markdown
## [Unreleased]

### Added

- BOM Version Resolver: given a local Maven repository and an ordered list
  of BOM coordinates, resolves the version a dependency is managed at,
  independent of local overrides. Foundation for detecting stale
  dependencyManagement overrides in a later phase.
- Override Detector: scans a module's own `<dependencyManagement>` for
  version overrides that diverge from an imported BOM chain, resolving
  property-based versions and the ordered BOM chain across multi-module
  parent hierarchies. Reads a free-text reason comment and a suppress
  marker comment per entry. No editor UI yet — detection logic only.
```

- [ ] **Step 3: Commit**

```bash
git add CHANGELOG.md
git commit -m "$(cat <<'EOF'
Document Override Detector in changelog

EOF
)"
```

---

## Roadmap (future plans, not covered here)

1. **Editor Integration** — line marker (gutter icon) + local inspection
   (weak-warning highlight) on the `<version>` text + the four quick fixes
   (update override, bump parent/BOM, remove override, suppress). Calls
   `OverrideDetector.forProject(project).detect(...)` from a background thread
   per the design's Data Flow section, with caching keyed on the module + a
   reimport/file-change invalidation trigger. Renders `DetectedOverride.Confirmed`
   as a warning and `DetectedOverride.Inconclusive` as a distinct, less alarming
   state (per design: "can't tell yet", not "definitely fine").
   **Known follow-up from Phase 2's final review:** `BomEffectiveModelResolver
   .buildEffectiveModel` has zero memoization — a `detect()` call with N
   override candidates matched against the same BOM re-parses/re-builds that
   BOM's effective model N times. Not a correctness issue (confirmed by
   review), but worth a `Map<Gav, BomModelResult>` memoization layer
   (scoped at least per `detect()` call, ideally per-module with the same
   reimport-based invalidation this bullet already calls for) before this
   gets wired into live, keystroke-adjacent inspection passes.
2. **Version Advisor** — repository metadata lookups (read from `pom.xml`/
   `settings.xml`, per the design's Scope section) for "newer version available"
   / "newer parent available", feeding two of Editor Integration's quick fixes.
3. **Tool Window** — project-wide overview panel, iterating
   `MavenProjectsManager.getInstance(project).getProjects()` and running the
   detector per module.
