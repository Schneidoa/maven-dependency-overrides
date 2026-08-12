# Maven Dependency Overrides — Phase 6: Panel Remove / Edit / Add Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the developer remove, edit, and add `dependencyManagement` overrides directly from the Override Overview tool window, without opening the corresponding pom.xml.

**Architecture:** New pure-Kotlin PSI-mutation functions (`OverrideMutations.kt`) and a thin BOM-lookup wrapper (`ManagedVersionHint.kt`) do the actual work and are independently unit-tested. Two new `DialogWrapper` classes (`EditOverrideDialog`, `AddOverrideDialog`) and a right-click context menu + toolbar button wired into the existing `OverrideOverviewToolWindowFactory` are thin, untested Swing glue — consistent with this project's established split between tested detection/mutation logic and manually-verified UI.

**Tech Stack:** Kotlin, IntelliJ Platform SDK (Maven DOM API, `DialogWrapper`, `WriteCommandAction`), JUnit via `BasePlatformTestCase`.

**Reference:** `docs/superpowers/specs/2026-08-11-maven-dependency-overrides-phase6-panel-crud-design.md`

---

### Task 1: Spike — verify DOM-API auto-creation of missing tags

**Files:**
- Create: `src/test/kotlin/cloud/schneidoa/detection/AddOverridePsiSpikeTest.kt`

This mirrors Phase 3's `PsiMutationSpikeTest` — a throwaway verification, not shipped
production code, confirming the Maven DOM API actually behaves as the design assumes
before `addOverride` (Task 3) is built on top of it.

- [ ] **Step 1: Write the spike test**

Create `src/test/kotlin/cloud/schneidoa/detection/AddOverridePsiSpikeTest.kt`:

```kotlin
package cloud.schneidoa.detection

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jetbrains.idea.maven.dom.MavenDomUtil

class AddOverridePsiSpikeTest : BasePlatformTestCase() {

    fun `test creates dependencyManagement and dependencies tags on write when neither exists yet`() {
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
        val model = MavenDomUtil.getMavenDomProjectModel(project, file.virtualFile)!!

        WriteCommandAction.runWriteCommandAction(project) {
            val dependency = model.dependencyManagement.dependencies.addDependency()
            dependency.groupId.stringValue = "com.example"
            dependency.artifactId.stringValue = "widget-core"
            dependency.version.stringValue = "1.0.0"
        }

        assertTrue(file.text.contains("<dependencyManagement>"))
        assertTrue(file.text.contains("<dependencies>"))
        assertTrue(file.text.contains("<groupId>com.example</groupId>"))
        assertTrue(file.text.contains("<artifactId>widget-core</artifactId>"))
        assertTrue(file.text.contains("<version>1.0.0</version>"))
    }

    fun `test adds a second dependency entry when dependencyManagement already exists`() {
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
                            <artifactId>widget-core</artifactId>
                            <version>1.0.0</version>
                        </dependency>
                    </dependencies>
                </dependencyManagement>
            </project>
            """.trimIndent()
        )
        val model = MavenDomUtil.getMavenDomProjectModel(project, file.virtualFile)!!

        WriteCommandAction.runWriteCommandAction(project) {
            val dependency = model.dependencyManagement.dependencies.addDependency()
            dependency.groupId.stringValue = "com.fasterxml.jackson.core"
            dependency.artifactId.stringValue = "jackson-databind"
            dependency.version.stringValue = "2.18.2"
        }

        assertEquals(2, model.dependencyManagement.dependencies.dependencies.size)
        assertTrue(file.text.contains("jackson-databind"))
        assertTrue(file.text.contains("widget-core"))
    }
}
```

- [ ] **Step 2: Run the spike**

Run: `./gradlew test --tests "cloud.schneidoa.detection.AddOverridePsiSpikeTest"`
Expected: `BUILD SUCCESSFUL`, 2 tests passed.

**If either test fails to compile or fails an assertion:** do not try alternative
approaches yourself — stop and report BLOCKED with the exact failure. This is the
load-bearing assumption for `addOverride` (Task 3); if it's wrong, that task needs a
different design, which is a plan-level decision, not something to improvise around.

- [ ] **Step 3: Commit**

```bash
git add src/test/kotlin/cloud/schneidoa/detection/AddOverridePsiSpikeTest.kt
git commit -m "Add spike verifying DOM-API auto-creation of missing dependencyManagement tags"
```

---

### Task 2: `OverrideMutations` — remove, set version, set reason

**Files:**
- Create: `src/main/kotlin/cloud/schneidoa/detection/OverrideMutations.kt`
- Create: `src/test/kotlin/cloud/schneidoa/detection/OverrideMutationsTest.kt`

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/cloud/schneidoa/detection/OverrideMutationsTest.kt`:

```kotlin
package cloud.schneidoa.detection

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jetbrains.idea.maven.dom.MavenDomUtil
import org.jetbrains.idea.maven.dom.model.MavenDomProjectModel

class OverrideMutationsTest : BasePlatformTestCase() {

    fun `test removeOverride deletes the dependency block and leaves the rest of the file intact`() {
        val model = configurePom(
            """
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
            """.trimIndent()
        )
        val candidate = DependencyManagementScanner.scan(model).single { it.ga.artifactId == "jackson-databind" }

        removeOverride(project, candidate)

        val resultText = myFixture.file.text
        assertFalse(resultText.contains("jackson-databind"))
        assertTrue(resultText.contains("acme-bom"))
    }

    fun `test setVersion changes only the version text`() {
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

        setVersion(project, candidate, "2.18.2")

        val resultText = myFixture.file.text
        assertTrue(resultText.contains("<version>2.18.2</version>"))
        assertFalse(resultText.contains("2.15.4"))
        assertTrue(resultText.contains("jackson-databind"))
    }

    fun `test setReason inserts a comment when there is none yet`() {
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

        setReason(project, candidate, "CVE-2024-12345")

        assertTrue(myFixture.file.text.contains("<!-- CVE-2024-12345 -->"))
    }

    fun `test setReason replaces an existing comment instead of stacking a second one`() {
        val model = configurePom(
            """
            <!-- old reason -->
            <dependency>
                <groupId>com.fasterxml.jackson.core</groupId>
                <artifactId>jackson-databind</artifactId>
                <version>2.15.4</version>
            </dependency>
            """.trimIndent()
        )
        val candidate = DependencyManagementScanner.scan(model).single()

        setReason(project, candidate, "new reason")

        val resultText = myFixture.file.text
        assertTrue(resultText.contains("<!-- new reason -->"))
        assertFalse(resultText.contains("old reason"))
    }

    fun `test setReason with null removes an existing comment`() {
        val model = configurePom(
            """
            <!-- old reason -->
            <dependency>
                <groupId>com.fasterxml.jackson.core</groupId>
                <artifactId>jackson-databind</artifactId>
                <version>2.15.4</version>
            </dependency>
            """.trimIndent()
        )
        val candidate = DependencyManagementScanner.scan(model).single()

        setReason(project, candidate, null)

        val resultText = myFixture.file.text
        assertFalse(resultText.contains("old reason"))
        assertTrue(resultText.contains("jackson-databind"))
    }

    private fun configurePom(dependencyManagementEntries: String): MavenDomProjectModel {
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

Run: `./gradlew test --tests "cloud.schneidoa.detection.OverrideMutationsTest"`
Expected: FAIL — compile error, `removeOverride`/`setVersion`/`setReason` unresolved.

- [ ] **Step 3: Create `OverrideMutations.kt`**

Create `src/main/kotlin/cloud/schneidoa/detection/OverrideMutations.kt`:

```kotlin
package cloud.schneidoa.detection

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project

/** Deletes the candidate's <dependency> block entirely. */
fun removeOverride(project: Project, candidate: OverrideCandidate) {
    WriteCommandAction.runWriteCommandAction(project) {
        candidate.xmlTag.delete()
    }
}

/** Changes the candidate's pinned version, leaving everything else untouched. */
fun setVersion(project: Project, candidate: OverrideCandidate, newVersion: String) {
    WriteCommandAction.runWriteCommandAction(project) {
        candidate.versionXmlTag.value.text = newVersion
    }
}

/**
 * Creates, replaces, or removes the candidate's preceding reason comment.
 * [newReason] blank or null removes any existing comment; otherwise it's
 * written as the comment text, replacing whatever was already there rather
 * than stacking a second comment above the entry.
 */
fun setReason(project: Project, candidate: OverrideCandidate, newReason: String?) {
    WriteCommandAction.runWriteCommandAction(project) {
        val existingComment = findPrecedingComment(candidate.xmlTag)
        when {
            newReason.isNullOrBlank() -> existingComment?.delete()
            existingComment != null -> existingComment.replace(createXmlComment(project, " $newReason "))
            else -> candidate.xmlTag.parent.addBefore(createXmlComment(project, " $newReason "), candidate.xmlTag)
        }
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests "cloud.schneidoa.detection.OverrideMutationsTest"`
Expected: PASS — all 5 tests green.

- [ ] **Step 5: Run the full suite**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL, no failures anywhere.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/cloud/schneidoa/detection/OverrideMutations.kt src/test/kotlin/cloud/schneidoa/detection/OverrideMutationsTest.kt
git commit -m "Add OverrideMutations: remove, set version, set reason"
```

---

### Task 3: `OverrideMutations` — add a new override

**Files:**
- Modify: `src/main/kotlin/cloud/schneidoa/detection/OverrideMutations.kt`
- Modify: `src/test/kotlin/cloud/schneidoa/detection/OverrideMutationsTest.kt`

- [ ] **Step 1: Add failing tests**

In `src/test/kotlin/cloud/schneidoa/detection/OverrideMutationsTest.kt`, add the import
`import cloud.schneidoa.resolver.Ga` to the top of the file (alongside the existing
imports), and add these two test methods (place them anywhere among the other test
methods, e.g. right after `` `test setReason with null removes an existing comment` ``
and before the `private fun configurePom` helper):

```kotlin
    fun `test addOverride creates dependencyManagement when it does not exist yet`() {
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
        val model = MavenDomUtil.getMavenDomProjectModel(project, file.virtualFile)!!

        addOverride(project, model, Ga("com.example", "widget-core"), "9.9.9", "CVE-2024-12345")

        val resultText = myFixture.file.text
        assertTrue(resultText.contains("<dependencyManagement>"))
        assertTrue(resultText.contains("<groupId>com.example</groupId>"))
        assertTrue(resultText.contains("<artifactId>widget-core</artifactId>"))
        assertTrue(resultText.contains("<version>9.9.9</version>"))
        assertTrue(resultText.contains("<!-- CVE-2024-12345 -->"))
    }

    fun `test addOverride appends to existing dependencyManagement without a reason comment when none given`() {
        val model = configurePom(
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

        addOverride(project, model, Ga("com.fasterxml.jackson.core", "jackson-databind"), "2.18.2", null)

        val candidates = DependencyManagementScanner.scan(model)
        assertEquals(1, candidates.size)
        val added = candidates.single()
        assertEquals("jackson-databind", added.ga.artifactId)
        assertEquals("2.18.2", added.declaredVersion)
        assertNull(added.reason)
    }
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests "cloud.schneidoa.detection.OverrideMutationsTest"`
Expected: FAIL — compile error, `addOverride` unresolved. The other 5 pre-existing
tests in this file were already passing after Task 2 and aren't expected to regress.

- [ ] **Step 3: Add `addOverride` to `OverrideMutations.kt`**

In `src/main/kotlin/cloud/schneidoa/detection/OverrideMutations.kt`, add these two
imports to the top of the file:

```kotlin
import cloud.schneidoa.resolver.Ga
import org.jetbrains.idea.maven.dom.model.MavenDomProjectModel
```

Then append this function at the end of the file:

```kotlin

/**
 * Creates a brand-new <dependency> entry inside [model]'s
 * <dependencyManagement>, creating that section (and <dependencies> inside
 * it) if it doesn't exist yet - verified to work via the Maven DOM API's
 * auto-vivification behavior in AddOverridePsiSpikeTest. [reason], if
 * non-blank, is written as a preceding comment the same way setReason
 * writes one for an existing entry.
 */
fun addOverride(project: Project, model: MavenDomProjectModel, ga: Ga, version: String, reason: String?) {
    WriteCommandAction.runWriteCommandAction(project) {
        val dependency = model.dependencyManagement.dependencies.addDependency()
        dependency.groupId.stringValue = ga.groupId
        dependency.artifactId.stringValue = ga.artifactId
        dependency.version.stringValue = version

        if (!reason.isNullOrBlank()) {
            val dependencyTag = dependency.xmlTag ?: return@runWriteCommandAction
            dependencyTag.parent.addBefore(createXmlComment(project, " $reason "), dependencyTag)
        }
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests "cloud.schneidoa.detection.OverrideMutationsTest"`
Expected: PASS — all 7 tests green (5 from Task 2 + 2 new).

- [ ] **Step 5: Run the full suite**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL, no failures anywhere.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/cloud/schneidoa/detection/OverrideMutations.kt src/test/kotlin/cloud/schneidoa/detection/OverrideMutationsTest.kt
git commit -m "Add addOverride to OverrideMutations"
```

---

### Task 4: `ManagedVersionHint`

**Files:**
- Create: `src/main/kotlin/cloud/schneidoa/detection/ManagedVersionHint.kt`
- Create: `src/test/kotlin/cloud/schneidoa/detection/ManagedVersionHintTest.kt`

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/cloud/schneidoa/detection/ManagedVersionHintTest.kt`:

```kotlin
package cloud.schneidoa.detection

import cloud.schneidoa.resolver.BomVersionResolver
import cloud.schneidoa.resolver.Ga
import cloud.schneidoa.resolver.ManagedVersionLookup
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jetbrains.idea.maven.dom.MavenDomUtil
import org.jetbrains.idea.maven.dom.model.MavenDomProjectModel
import java.io.File

class ManagedVersionHintTest : BasePlatformTestCase() {

    private fun localRepositoryDir(): File {
        val fixtureUrl = javaClass.classLoader.getResource("fixtures/local-repo")
            ?: error("Test fixture local-repo not found on classpath")
        return File(fixtureUrl.toURI())
    }

    private fun configureModuleImportingAcmeBom(): MavenDomProjectModel {
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
                    </dependencies>
                </dependencyManagement>
            </project>
            """.trimIndent()
        )
        return MavenDomUtil.getMavenDomProjectModel(project, file.virtualFile)!!
    }

    fun `test finds the version a BOM manages a GA at, even when it is not yet declared in the module`() {
        val model = configureModuleImportingAcmeBom()
        val hint = ManagedVersionHint(BomVersionResolver(localRepositoryDir()))

        val result = hint.lookup(model, project, Ga("com.fasterxml.jackson.core", "jackson-databind"))

        assertTrue(result is ManagedVersionLookup.Found)
        assertEquals("2.15.3", (result as ManagedVersionLookup.Found).version)
    }

    fun `test reports not found when no BOM in the chain manages the GA`() {
        val model = configureModuleImportingAcmeBom()
        val hint = ManagedVersionHint(BomVersionResolver(localRepositoryDir()))

        val result = hint.lookup(model, project, Ga("org.example", "totally-unmanaged"))

        assertTrue(result is ManagedVersionLookup.NotFound)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests "cloud.schneidoa.detection.ManagedVersionHintTest"`
Expected: FAIL — compile error, `ManagedVersionHint` unresolved.

- [ ] **Step 3: Create `ManagedVersionHint.kt`**

Create `src/main/kotlin/cloud/schneidoa/detection/ManagedVersionHint.kt`:

```kotlin
package cloud.schneidoa.detection

import cloud.schneidoa.resolver.BomVersionResolver
import cloud.schneidoa.resolver.Ga
import cloud.schneidoa.resolver.ManagedVersionLookup
import com.intellij.openapi.project.Project
import org.jetbrains.idea.maven.dom.model.MavenDomProjectModel
import org.jetbrains.idea.maven.project.MavenProjectsManager

/**
 * Looks up what a module's BOM chain currently manages a given GA at,
 * without requiring the GA to already be declared in that module's own
 * <dependencyManagement> - used by the Add/Edit dialogs to show a live hint
 * of what's already managed, informing what version to pin an override at.
 * Reuses exactly the same resolution BomChainResolver/BomVersionResolver
 * already perform for real detection; this is a thin wrapper, not new logic.
 */
class ManagedVersionHint(private val bomVersionResolver: BomVersionResolver) {

    private val bomChainResolver = BomChainResolver(bomVersionResolver.localRepositoryDir)

    fun lookup(model: MavenDomProjectModel, project: Project, ga: Ga): ManagedVersionLookup {
        val bomChain = bomChainResolver.resolveBomChain(model, project)
        return bomVersionResolver.resolveManagedVersion(bomChain.map { it.bom }, ga)
    }

    companion object {
        /** Convenience factory for real IDE usage, matching OverrideDetector.forProject. */
        fun forProject(project: Project): ManagedVersionHint {
            val localRepositoryDir = MavenProjectsManager.getInstance(project).repositoryPath.toFile()
            return ManagedVersionHint(BomVersionResolver(localRepositoryDir))
        }
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests "cloud.schneidoa.detection.ManagedVersionHintTest"`
Expected: PASS — both tests green.

- [ ] **Step 5: Run the full suite**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL, no failures anywhere.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/cloud/schneidoa/detection/ManagedVersionHint.kt src/test/kotlin/cloud/schneidoa/detection/ManagedVersionHintTest.kt
git commit -m "Add ManagedVersionHint for live BOM lookups in the Add/Edit dialogs"
```

---

### Task 5: `EditOverrideDialog`

**Files:**
- Create: `src/main/kotlin/cloud/schneidoa/ui/EditOverrideDialog.kt`

This class is thin Swing/platform glue with no automated test, consistent with how
this project already treats `OverrideOverviewToolWindowFactory` — it is manually
verified via `runIde` alongside the rest of this plan's final manual check.

- [ ] **Step 1: Create the dialog**

Create `src/main/kotlin/cloud/schneidoa/ui/EditOverrideDialog.kt`:

```kotlin
package cloud.schneidoa.ui

import cloud.schneidoa.detection.ManagedVersionHint
import cloud.schneidoa.detection.OverrideCandidate
import cloud.schneidoa.detection.setReason
import cloud.schneidoa.detection.setVersion
import cloud.schneidoa.resolver.ManagedVersionLookup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.util.ui.FormBuilder
import org.jetbrains.idea.maven.dom.model.MavenDomProjectModel
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JTextField
import javax.swing.SwingUtilities

class EditOverrideDialog(
    private val project: Project,
    private val model: MavenDomProjectModel,
    private val candidate: OverrideCandidate,
    private val hintProvider: (Project) -> ManagedVersionHint = ManagedVersionHint::forProject
) : DialogWrapper(project, true) {

    private val versionField = JTextField(candidate.declaredVersion)
    private val reasonField = JTextField(candidate.reason ?: "")
    private val hintLabel = JLabel("Checking BOM...")

    init {
        title = "Edit Dependency Override"
        init()
        updateOkEnabled()
        versionField.addKeyListener(object : KeyAdapter() {
            override fun keyReleased(e: KeyEvent) = updateOkEnabled()
        })
        loadHint()
    }

    override fun createCenterPanel(): JComponent =
        FormBuilder.createFormBuilder()
            .addLabeledComponent("Dependency", JLabel(candidate.ga.toString()))
            .addLabeledComponent("Version", versionField)
            .addLabeledComponent("Reason", reasonField)
            .addComponent(hintLabel)
            .panel

    override fun doOKAction() {
        setVersion(project, candidate, versionField.text.trim())
        setReason(project, candidate, reasonField.text.trim().ifBlank { null })
        super.doOKAction()
    }

    private fun updateOkEnabled() {
        isOKActionEnabled = versionField.text.isNotBlank()
    }

    private fun loadHint() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val text = try {
                when (val lookup = ReadAction.compute<ManagedVersionLookup, Throwable> {
                    hintProvider(project).lookup(model, project, candidate.ga)
                }) {
                    is ManagedVersionLookup.Found -> "Currently managed at ${lookup.version}"
                    is ManagedVersionLookup.NotFound -> "Not currently managed by any BOM in this module's chain"
                }
            } catch (e: Throwable) {
                "Could not check BOM"
            }
            SwingUtilities.invokeLater { hintLabel.text = text }
        }
    }
}
```

- [ ] **Step 2: Compile to catch platform-API mistakes**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL. If it fails, the most likely cause is a wrong platform API
signature (e.g. `FormBuilder.addComponent`, `DialogWrapper.isOKActionEnabled`,
`ReadAction.compute`). Look up the correct signature in the platform SDK sources
available on the classpath and fix accordingly, keeping behavior identical to what's
specified above. `OverrideOverviewToolWindowFactory.kt` (Phase 4) uses several of the
same platform idioms (`ReadAction.compute`, `ApplicationManager.executeOnPooledThread`,
`SwingUtilities.invokeLater`) and can serve as a reference.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/cloud/schneidoa/ui/EditOverrideDialog.kt
git commit -m "Add EditOverrideDialog"
```

---

### Task 6: `AddOverrideDialog`

**Files:**
- Create: `src/main/kotlin/cloud/schneidoa/ui/AddOverrideDialog.kt`

Also thin Swing glue, no automated test, same reasoning as Task 5.

- [ ] **Step 1: Create the dialog**

Create `src/main/kotlin/cloud/schneidoa/ui/AddOverrideDialog.kt`:

```kotlin
package cloud.schneidoa.ui

import cloud.schneidoa.detection.ManagedVersionHint
import cloud.schneidoa.detection.addOverride
import cloud.schneidoa.resolver.Ga
import cloud.schneidoa.resolver.ManagedVersionLookup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.ui.FormBuilder
import org.jetbrains.idea.maven.dom.MavenDomUtil
import org.jetbrains.idea.maven.dom.model.MavenDomProjectModel
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JTextField
import javax.swing.SwingUtilities
import javax.swing.Timer

private data class ModuleChoice(val label: String, val model: MavenDomProjectModel) {
    override fun toString(): String = label
}

class AddOverrideDialog(
    private val project: Project,
    private val hintProvider: (Project) -> ManagedVersionHint = ManagedVersionHint::forProject
) : DialogWrapper(project, true) {

    private val modules = findModules()
    private val moduleCombo = JComboBox(modules.toTypedArray())
    private val gaField = JTextField()
    private val versionField = JTextField()
    private val reasonField = JTextField()
    private val hintLabel = JLabel(" ")
    private val hintDebounce = Timer(300) { loadHint() }.apply { isRepeats = false }

    init {
        title = "Add Dependency Override"
        init()
        updateOkEnabled()
        val onChange = object : KeyAdapter() {
            override fun keyReleased(e: KeyEvent) {
                updateOkEnabled()
                if (e.source == gaField) hintDebounce.restart()
            }
        }
        gaField.addKeyListener(onChange)
        versionField.addKeyListener(onChange)
    }

    override fun createCenterPanel(): JComponent =
        FormBuilder.createFormBuilder()
            .addLabeledComponent("Module", moduleCombo)
            .addLabeledComponent("Group ID : Artifact ID", gaField)
            .addLabeledComponent("Version to pin", versionField)
            .addLabeledComponent("Reason (optional)", reasonField)
            .addComponent(hintLabel)
            .panel

    override fun doOKAction() {
        val ga = parseGa() ?: return
        val choice = moduleCombo.selectedItem as ModuleChoice
        addOverride(project, choice.model, ga, versionField.text.trim(), reasonField.text.trim().ifBlank { null })
        super.doOKAction()
    }

    private fun updateOkEnabled() {
        isOKActionEnabled = parseGa() != null && versionField.text.isNotBlank()
    }

    private fun parseGa(): Ga? {
        val parts = gaField.text.trim().split(":")
        if (parts.size != 2 || parts[0].isBlank() || parts[1].isBlank()) return null
        return Ga(parts[0], parts[1])
    }

    private fun loadHint() {
        val ga = parseGa() ?: return
        val choice = moduleCombo.selectedItem as? ModuleChoice ?: return
        hintLabel.text = "Checking BOM..."
        ApplicationManager.getApplication().executeOnPooledThread {
            val text = try {
                when (val lookup = ReadAction.compute<ManagedVersionLookup, Throwable> {
                    hintProvider(project).lookup(choice.model, project, ga)
                }) {
                    is ManagedVersionLookup.Found -> "Currently managed at ${lookup.version}"
                    is ManagedVersionLookup.NotFound -> "Not currently managed by any BOM in this module's chain"
                }
            } catch (e: Throwable) {
                "Could not check BOM"
            }
            SwingUtilities.invokeLater { hintLabel.text = text }
        }
    }

    private fun findModules(): List<ModuleChoice> {
        val pomFiles = FilenameIndex.getVirtualFilesByName("pom.xml", GlobalSearchScope.projectScope(project))
        return pomFiles.mapNotNull { pomFile ->
            val model = MavenDomUtil.getMavenDomProjectModel(project, pomFile) ?: return@mapNotNull null
            val artifactId = model.artifactId.rawText?.trim()
            val label = if (!artifactId.isNullOrEmpty()) artifactId else (pomFile.parent?.name ?: pomFile.name)
            ModuleChoice(label, model)
        }.sortedBy { it.label }
    }
}
```

- [ ] **Step 2: Compile to catch platform-API mistakes**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL. Same guidance as Task 5's Step 2 if it fails —
`ProjectOverrideScanner.kt` (Phase 4) uses the same `FilenameIndex`/`MavenDomUtil`
module-discovery idiom and can serve as a reference if `findModules()` needs fixing.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/cloud/schneidoa/ui/AddOverrideDialog.kt
git commit -m "Add AddOverrideDialog"
```

---

### Task 7: Wire the context menu and Add button into the tool window

**Files:**
- Modify: `src/main/kotlin/cloud/schneidoa/ui/OverrideOverviewToolWindowFactory.kt`

- [ ] **Step 1: Replace the file**

Replace the entire contents of
`src/main/kotlin/cloud/schneidoa/ui/OverrideOverviewToolWindowFactory.kt` with:

```kotlin
package cloud.schneidoa.ui

import cloud.schneidoa.detection.ProjectOverrideEntry
import cloud.schneidoa.detection.ProjectOverrideScanner
import cloud.schneidoa.detection.candidate
import cloud.schneidoa.detection.declaredToManaged
import cloud.schneidoa.detection.managedByChain
import cloud.schneidoa.detection.removeOverride
import cloud.schneidoa.detection.statusText
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.table.JBTable
import org.jetbrains.idea.maven.dom.MavenDomUtil
import org.jetbrains.idea.maven.dom.model.MavenDomProjectModel
import java.awt.BorderLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.SwingUtilities
import javax.swing.table.DefaultTableModel

class OverrideOverviewToolWindowFactory : ToolWindowFactory {
    override fun shouldBeAvailable(project: Project) = true

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = OverrideOverviewPanel(project)
        val content = ContentFactory.getInstance().createContent(panel.component, null, false)
        toolWindow.contentManager.addContent(content)
        panel.refresh()
    }
}

private val COLUMNS = arrayOf("Module", "Dependency", "Declared → Managed", "Managed By", "Status")

private class OverrideOverviewPanel(private val project: Project) {
    private val scanner = ProjectOverrideScanner()
    private var currentEntries: List<ProjectOverrideEntry> = emptyList()
    private val refreshGeneration = AtomicInteger(0)
    private val logger = Logger.getInstance(OverrideOverviewPanel::class.java)

    private val tableModel = object : DefaultTableModel(COLUMNS, 0) {
        override fun isCellEditable(row: Int, column: Int) = false
    }
    private val table = JBTable(tableModel).apply {
        setShowGrid(false)
        rowSelectionAllowed = true
        columnSelectionAllowed = false
    }

    val component: JPanel = JPanel(BorderLayout()).apply {
        add(createToolbar().component, BorderLayout.NORTH)
        add(JBScrollPane(table), BorderLayout.CENTER)
    }

    init {
        table.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount != 2) return
                val row = table.rowAtPoint(e.point)
                if (row < 0 || row >= currentEntries.size) return
                navigateTo(currentEntries[row])
            }

            override fun mousePressed(e: MouseEvent) = maybeShowContextMenu(e)
            override fun mouseReleased(e: MouseEvent) = maybeShowContextMenu(e)
        })
    }

    private fun maybeShowContextMenu(e: MouseEvent) {
        if (!e.isPopupTrigger) return
        val row = table.rowAtPoint(e.point)
        if (row < 0 || row >= currentEntries.size) return
        table.setRowSelectionInterval(row, row)
        showContextMenu(currentEntries[row], e)
    }

    private fun showContextMenu(entry: ProjectOverrideEntry, e: MouseEvent) {
        val menu = JPopupMenu()
        menu.add("Edit...").addActionListener { openEditDialog(entry) }
        menu.add("Remove").addActionListener { removeWithConfirmation(entry) }
        menu.show(e.component, e.x, e.y)
    }

    private fun createToolbar(): ActionToolbar {
        val refreshAction = object : AnAction(
            "Refresh",
            "Rescan the project for dependencyManagement overrides",
            AllIcons.Actions.Refresh
        ) {
            override fun actionPerformed(e: AnActionEvent) = refresh()
        }
        val addAction = object : AnAction(
            "Add",
            "Add a new dependencyManagement override",
            AllIcons.General.Add
        ) {
            override fun actionPerformed(e: AnActionEvent) = openAddDialog()
        }
        val group = DefaultActionGroup(refreshAction, addAction)
        return ActionManager.getInstance().createActionToolbar(ActionPlaces.TOOLBAR, group, true).apply {
            targetComponent = table
        }
    }

    fun refresh() {
        val generation = refreshGeneration.incrementAndGet()
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val entries = ReadAction.compute<List<ProjectOverrideEntry>, Throwable> { scanner.scan(project) }
                SwingUtilities.invokeLater {
                    if (generation == refreshGeneration.get()) populate(entries)
                }
            } catch (e: Throwable) {
                logger.warn("Failed to scan project for dependency overrides", e)
                SwingUtilities.invokeLater {
                    if (generation == refreshGeneration.get()) populate(emptyList())
                }
            }
        }
    }

    private fun populate(entries: List<ProjectOverrideEntry>) {
        currentEntries = entries
        tableModel.rowCount = 0
        for (entry in entries) {
            tableModel.addRow(
                arrayOf(
                    entry.moduleLabel,
                    entry.override.candidate.ga.toString(),
                    declaredToManaged(entry.override),
                    managedByChain(entry.override),
                    statusText(entry.override)
                )
            )
        }
    }

    private fun navigateTo(entry: ProjectOverrideEntry) {
        val offset = ReadAction.compute<Int?, Throwable> {
            val tag = entry.override.candidate.versionXmlTag
            if (tag.isValid) tag.textOffset else null
        } ?: return
        OpenFileDescriptor(project, entry.pomFile, offset).navigate(true)
    }

    private fun removeWithConfirmation(entry: ProjectOverrideEntry) {
        val answer = Messages.showYesNoDialog(
            project,
            "Remove the override for ${entry.override.candidate.ga}?",
            "Remove Dependency Override",
            Messages.getQuestionIcon()
        )
        if (answer != Messages.YES) return
        removeOverride(project, entry.override.candidate)
        refresh()
    }

    private fun openEditDialog(entry: ProjectOverrideEntry) {
        val model = ReadAction.compute<MavenDomProjectModel?, Throwable> {
            MavenDomUtil.getMavenDomProjectModel(project, entry.pomFile)
        } ?: return
        val dialog = EditOverrideDialog(project, model, entry.override.candidate)
        if (dialog.showAndGet()) {
            refresh()
        }
    }

    private fun openAddDialog() {
        val dialog = AddOverrideDialog(project)
        if (dialog.showAndGet()) {
            refresh()
        }
    }
}
```

- [ ] **Step 2: Compile to catch platform-API mistakes**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL. Same guidance as Task 5/6 if it fails — this file already
had working `ReadAction`/`ApplicationManager`/`SwingUtilities` usage before this task;
if something doesn't compile, compare carefully against what's changed (the context
menu, the Add toolbar action, `removeWithConfirmation`/`openEditDialog`/`openAddDialog`)
rather than the parts that were already there and already worked in Phase 4/5.

- [ ] **Step 3: Run the full suite**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL, no failures anywhere (this file has no automated tests
itself, but this confirms nothing else regressed).

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/cloud/schneidoa/ui/OverrideOverviewToolWindowFactory.kt
git commit -m "Wire Edit/Remove context menu and Add button into the Override Overview panel"
```

---

### Task 8: Full suite, changelog, manual verification note

**Files:**
- Modify: `CHANGELOG.md`

- [ ] **Step 1: Run the full suite one more time**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL, no failures.

- [ ] **Step 2: Add a changelog entry**

In `CHANGELOG.md`, under `## [Unreleased]`, add a new bullet to the existing `### Added` section:

```markdown
- Panel actions: the Override Overview tool window can now remove, edit, and add
  `dependencyManagement` overrides directly, without opening the pom.xml. Right-click a
  row for Edit (version and reason) or Remove (with confirmation); use the new "+ Add"
  toolbar button to pin a new override in any module, with a live hint showing what the
  BOM chain currently manages the entered dependency at.
```

- [ ] **Step 3: Commit**

```bash
git add CHANGELOG.md
git commit -m "Document panel remove/edit/add in the changelog"
```

- [ ] **Step 4: Manual verification note (do not attempt — for the human after this plan completes)**

Run `./gradlew runIde`, open a project with existing overrides (e.g. `camperchat/backend`
from earlier manual verification), and confirm:
- Right-clicking a row shows "Edit..." and "Remove".
- Edit opens a dialog pre-filled with the current version/reason, shows a "Currently
  managed at X" (or "Not currently managed...") hint, and Save actually updates the
  pom.xml (visible immediately after the panel refreshes).
- Remove shows a Yes/No confirmation before deleting the `<dependency>` block.
- The "+ Add" toolbar button opens a dialog with a module dropdown; typing a
  `groupId:artifactId` that's managed by a BOM in the selected module updates the hint
  live (with a short delay) to show what it's currently managed at; clicking Add
  creates a new entry in that module's pom.xml, including creating
  `<dependencyManagement>`/`<dependencies>` from scratch if the module didn't have them.
- The OK/Add button in both dialogs is disabled until the GA and version fields are
  validly filled in.
