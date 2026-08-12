# Maven Dependency Overrides — Phase 4: Override Overview Panel Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a tool window that lists every detected `dependencyManagement` override across all `pom.xml` files in the project, replacing the leftover IntelliJ Platform Plugin Template demo tool window.

**Architecture:** A plain-Kotlin `ProjectOverrideScanner` (no UI dependency) discovers every `pom.xml` in the project via `FilenameIndex` and runs the existing `OverrideDetector` against each, flattening results into `ProjectOverrideEntry` values tagged with their module. A thin `OverrideOverviewToolWindowFactory` renders those in a `JBTable` with a manual Refresh action and click-to-navigate.

**Tech Stack:** Kotlin, IntelliJ Platform SDK (`com.intellij.psi.search.FilenameIndex`, `com.intellij.ui.table.JBTable`, `com.intellij.openapi.actionSystem`), existing `org.jetbrains.idea.maven` DOM APIs, JUnit via `BasePlatformTestCase`.

**Reference:** `docs/superpowers/specs/2026-08-11-maven-dependency-overrides-phase4-override-overview-panel-design.md`

---

### Task 1: `ProjectOverrideScanner`

**Files:**
- Modify: `src/main/kotlin/cloud/schneidoa/detection/OverrideDetector.kt`
- Create: `src/main/kotlin/cloud/schneidoa/detection/ProjectOverrideScanner.kt`
- Test: `src/test/kotlin/cloud/schneidoa/detection/ProjectOverrideScannerTest.kt`

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/cloud/schneidoa/detection/ProjectOverrideScannerTest.kt`:

```kotlin
package cloud.schneidoa.detection

import cloud.schneidoa.resolver.BomVersionResolver
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

class ProjectOverrideScannerTest : BasePlatformTestCase() {

    private fun scanner(): ProjectOverrideScanner {
        val fixtureUrl = javaClass.classLoader.getResource("fixtures/local-repo")
            ?: error("Test fixture local-repo not found on classpath")
        val localRepositoryDir = File(fixtureUrl.toURI())
        return ProjectOverrideScanner { OverrideDetector(BomVersionResolver(localRepositoryDir)) }
    }

    fun `test finds overrides across multiple modules, tagged with their own module`() {
        myFixture.addFileToProject(
            "module-a/pom.xml",
            """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.example</groupId>
                <artifactId>module-a</artifactId>
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
        myFixture.addFileToProject(
            "module-b/pom.xml",
            """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.example</groupId>
                <artifactId>module-b</artifactId>
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
                            <groupId>com.example</groupId>
                            <artifactId>widget-core</artifactId>
                            <version>9.9.9</version>
                        </dependency>
                    </dependencies>
                </dependencyManagement>
            </project>
            """.trimIndent()
        )

        val entries = scanner().scan(project)

        assertEquals(2, entries.size)
        val moduleA = entries.single { it.moduleLabel == "module-a" }
        val moduleB = entries.single { it.moduleLabel == "module-b" }
        assertEquals("jackson-databind", moduleA.override.candidate.ga.artifactId)
        assertEquals("widget-core", moduleB.override.candidate.ga.artifactId)
    }

    fun `test a module without any override contributes nothing`() {
        myFixture.addFileToProject(
            "clean-module/pom.xml",
            """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.example</groupId>
                <artifactId>clean-module</artifactId>
                <version>1.0.0</version>
            </project>
            """.trimIndent()
        )

        assertTrue(scanner().scan(project).isEmpty())
    }

    fun `test excludes a suppressed override`() {
        myFixture.addFileToProject(
            "module-a/pom.xml",
            """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.example</groupId>
                <artifactId>module-a</artifactId>
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
                        <!-- maven-dependency-overrides: suppress -->
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

        assertTrue(scanner().scan(project).isEmpty())
    }

    fun `test skips a pom-xml-named file that is not a valid Maven model without failing the whole scan`() {
        myFixture.addFileToProject("not-really-a-module/pom.xml", "<foo></foo>")
        myFixture.addFileToProject(
            "module-a/pom.xml",
            """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.example</groupId>
                <artifactId>module-a</artifactId>
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

        val entries = scanner().scan(project)

        assertEquals(1, entries.size)
        assertEquals("module-a", entries.single().moduleLabel)
    }

    fun `test results are ordered by module then by dependency`() {
        myFixture.addFileToProject(
            "zeta/pom.xml",
            """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.example</groupId>
                <artifactId>zeta</artifactId>
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
        myFixture.addFileToProject(
            "alpha/pom.xml",
            """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.example</groupId>
                <artifactId>alpha</artifactId>
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
                            <groupId>com.example</groupId>
                            <artifactId>widget-core</artifactId>
                            <version>9.9.9</version>
                        </dependency>
                    </dependencies>
                </dependencyManagement>
            </project>
            """.trimIndent()
        )

        val entries = scanner().scan(project)

        assertEquals(listOf("alpha", "zeta"), entries.map { it.moduleLabel })
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests "cloud.schneidoa.detection.ProjectOverrideScannerTest"`
Expected: FAIL — compile error, `ProjectOverrideScanner` (and `.candidate`) unresolved.

- [ ] **Step 3: Add the `candidate` extension property to `DetectedOverride`**

In `src/main/kotlin/cloud/schneidoa/detection/OverrideDetector.kt`, immediately after the closing brace of the `DetectedOverride` sealed class (after the `Inconclusive` data class and its closing `}`), add:

```kotlin

/** The candidate common to both outcomes - lets callers navigate/display without a `when`. */
val DetectedOverride.candidate: OverrideCandidate
    get() = when (this) {
        is DetectedOverride.Confirmed -> candidate
        is DetectedOverride.Inconclusive -> candidate
    }
```

- [ ] **Step 4: Create `ProjectOverrideScanner`**

Create `src/main/kotlin/cloud/schneidoa/detection/ProjectOverrideScanner.kt`:

```kotlin
package cloud.schneidoa.detection

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.idea.maven.dom.MavenDomUtil
import org.jetbrains.idea.maven.dom.model.MavenDomProjectModel

/**
 * One detected override, tagged with which module (pom.xml) it came from -
 * DetectedOverride itself has no notion of "which file", since OverrideDetector
 * only ever looks at a single module at a time.
 */
data class ProjectOverrideEntry(
    val moduleLabel: String,
    val pomFile: VirtualFile,
    val override: DetectedOverride
)

/**
 * Finds every pom.xml in the project via FilenameIndex rather than
 * MavenProjectsManager.projects - mirrors BomChainResolver's own choice to
 * avoid depending on IntelliJ's synced Maven project index, which doesn't
 * need a completed Maven import/sync to produce results.
 */
class ProjectOverrideScanner(
    private val detectorFactory: (Project) -> OverrideDetector = OverrideDetector::forProject
) {
    fun scan(project: Project): List<ProjectOverrideEntry> {
        val detector = detectorFactory(project)
        val pomFiles = FilenameIndex.getVirtualFilesByName("pom.xml", GlobalSearchScope.projectScope(project))

        return pomFiles
            .flatMap { pomFile -> entriesFor(pomFile, detector, project) }
            .sortedWith(compareBy({ it.moduleLabel }, { it.override.candidate.ga.toString() }))
    }

    private fun entriesFor(pomFile: VirtualFile, detector: OverrideDetector, project: Project): List<ProjectOverrideEntry> {
        val model = MavenDomUtil.getMavenDomProjectModel(project, pomFile) ?: return emptyList()
        val moduleLabel = moduleLabelFor(model, pomFile)
        return detector.detect(model, project).map { ProjectOverrideEntry(moduleLabel, pomFile, it) }
    }

    private fun moduleLabelFor(model: MavenDomProjectModel, pomFile: VirtualFile): String {
        val artifactId = model.artifactId.rawText?.trim()
        return if (!artifactId.isNullOrEmpty()) artifactId else (pomFile.parent?.name ?: pomFile.name)
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew test --tests "cloud.schneidoa.detection.ProjectOverrideScannerTest"`
Expected: PASS — all 5 tests green.

- [ ] **Step 6: Run the full suite to check for regressions**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL, no failures.

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/cloud/schneidoa/detection/OverrideDetector.kt src/main/kotlin/cloud/schneidoa/detection/ProjectOverrideScanner.kt src/test/kotlin/cloud/schneidoa/detection/ProjectOverrideScannerTest.kt
git commit -m "Add ProjectOverrideScanner to find overrides across all project modules"
```

---

### Task 2: Tool window UI

**Files:**
- Create: `src/main/kotlin/cloud/schneidoa/ui/OverrideOverviewToolWindowFactory.kt`

This class is thin Swing/platform glue with no automated test, consistent with how this project already treats other platform-UI adapter classes (see the design doc's Testing section) — it is manually verified via `runIde`.

- [ ] **Step 1: Create the tool window factory and panel**

Create `src/main/kotlin/cloud/schneidoa/ui/OverrideOverviewToolWindowFactory.kt`:

```kotlin
package cloud.schneidoa.ui

import cloud.schneidoa.detection.DetectedOverride
import cloud.schneidoa.detection.ProjectOverrideEntry
import cloud.schneidoa.detection.ProjectOverrideScanner
import cloud.schneidoa.detection.candidate
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.table.JBTable
import java.awt.BorderLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JPanel
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

private val COLUMNS = arrayOf("Module", "Dependency", "Declared → Managed", "Status")

private class OverrideOverviewPanel(private val project: Project) {
    private val scanner = ProjectOverrideScanner()
    private var currentEntries: List<ProjectOverrideEntry> = emptyList()

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
        })
    }

    private fun createToolbar(): ActionToolbar {
        val refreshAction = object : AnAction(
            "Refresh",
            "Rescan the project for dependencyManagement overrides",
            AllIcons.Actions.Refresh
        ) {
            override fun actionPerformed(e: AnActionEvent) = refresh()
        }
        val group = DefaultActionGroup(refreshAction)
        return ActionManager.getInstance().createActionToolbar(ActionPlaces.TOOLBAR, group, true).apply {
            targetComponent = table
        }
    }

    fun refresh() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val entries = ReadAction.compute<List<ProjectOverrideEntry>, Throwable> { scanner.scan(project) }
            SwingUtilities.invokeLater { populate(entries) }
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
                    statusText(entry.override)
                )
            )
        }
    }

    private fun navigateTo(entry: ProjectOverrideEntry) {
        val offset = ReadAction.compute<Int, Throwable> { entry.override.candidate.versionXmlTag.textOffset }
        OpenFileDescriptor(project, entry.pomFile, offset).navigate(true)
    }
}

private fun declaredToManaged(override: DetectedOverride): String = when (override) {
    is DetectedOverride.Confirmed -> "${override.candidate.declaredVersion} → ${override.bomVersion}"
    is DetectedOverride.Inconclusive -> "${override.candidate.declaredVersion} → ?"
}

private fun statusText(override: DetectedOverride): String = when (override) {
    is DetectedOverride.Confirmed -> "Confirmed"
    is DetectedOverride.Inconclusive -> {
        val count = override.uncheckedBoms.size
        "Inconclusive ($count BOM${if (count == 1) "" else "s"} unchecked)"
    }
}
```

- [ ] **Step 2: Compile to catch platform-API mistakes**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL. This file has no automated test — a clean compile is the first checkpoint; manual verification happens after Task 3 rewires `plugin.xml`.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/cloud/schneidoa/ui/OverrideOverviewToolWindowFactory.kt
git commit -m "Add OverrideOverviewToolWindowFactory: table view of all detected overrides"
```

---

### Task 3: Wire up plugin.xml, remove template boilerplate, full suite, changelog

**Files:**
- Modify: `src/main/resources/META-INF/plugin.xml`
- Modify: `src/main/resources/messages/MyMessageBundle.properties`
- Delete: `src/main/kotlin/MyToolWindowFactory.kt`
- Modify: `CHANGELOG.md`

- [ ] **Step 1: Point the toolWindow extension at the new factory**

In `src/main/resources/META-INF/plugin.xml`, replace:

```xml
        <toolWindow id="MyToolWindow" factoryClass="cloud.schneidoa.MyToolWindowFactory"
                    icon="AllIcons.Toolwindows.ToolWindowPalette"/>
```

with:

```xml
        <toolWindow id="MavenOverrides" factoryClass="cloud.schneidoa.ui.OverrideOverviewToolWindowFactory"
                    icon="AllIcons.Toolwindows.ToolWindowPalette"/>
```

- [ ] **Step 2: Replace the template's message bundle keys**

Replace the full contents of `src/main/resources/messages/MyMessageBundle.properties` (currently three template keys) with:

```properties
toolwindow.stripe.MavenOverrides=Maven Overrides
```

- [ ] **Step 3: Delete the template demo tool window**

```bash
rm src/main/kotlin/MyToolWindowFactory.kt
```

- [ ] **Step 4: Run the full suite**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL, no failures.

- [ ] **Step 5: Manually verify in the sandbox IDE**

Run: `./gradlew runIde`

In the sandbox IDE, open a Maven project with at least one detected override (e.g. re-add the earlier test override to a pom.xml, or open a project from Phase 3's manual testing). Confirm:
- A "Maven Overrides" tool window appears in the tool window stripe.
- Opening it shows a table with the override, correct module/dependency/version columns.
- The Refresh button re-scans (edit a version, save, click Refresh, see the row update).
- Double-clicking a row opens the corresponding `pom.xml` with the cursor on the `<version>` tag.

- [ ] **Step 6: Add a changelog entry**

In `CHANGELOG.md`, under `## [Unreleased]`, add a new `### Added` entry (after the existing `### Fixed` section, before or alongside the existing `### Added` section — merge into the existing `### Added` list if one is already present at the top):

```markdown
- Override Overview tool window: lists every detected `dependencyManagement`
  override across all `pom.xml` files in the project in one table (module,
  dependency, declared vs. managed version, status), with a manual refresh
  action and click-to-navigate to the exact `<version>` tag. Replaces the
  IntelliJ Platform Plugin Template's placeholder tool window.
```

- [ ] **Step 7: Commit**

```bash
git add src/main/resources/META-INF/plugin.xml src/main/resources/messages/MyMessageBundle.properties CHANGELOG.md
git rm src/main/kotlin/MyToolWindowFactory.kt
git commit -m "Wire up Override Overview tool window, remove template demo panel"
```
