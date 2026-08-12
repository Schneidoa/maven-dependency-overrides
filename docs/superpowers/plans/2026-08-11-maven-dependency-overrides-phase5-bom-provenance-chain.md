# Maven Dependency Overrides — Phase 5: BOM Provenance Chain Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show the full parent-chain path from a module's own POM down to whichever ancestor POM actually declares the BOM that manages a confirmed override — e.g. `spring-boot-starter-parent → spring-boot-dependencies → com.fasterxml.jackson:jackson-bom:2.21.2` — in both the inline editor warning and the Override Overview tool window.

**Architecture:** `BomChainResolver` starts tracking, per BOM it finds, the artifactId path of parent-chain hops climbed to reach it (`BomImport(bom, declaredVia)` instead of a bare `Gav`). `OverrideDetector` looks up the matching path for whichever BOM ends up managing a confirmed override and attaches it to `DetectedOverride.Confirmed`. A new `managedByChain(override)` formatting function joins the path into a single display string, used by both `OverrideInspection`'s warning message and a new "Managed By" column in the Override Overview tool window.

**Tech Stack:** Kotlin, IntelliJ Platform SDK, existing `org.jetbrains.idea.maven` DOM APIs, JUnit via `BasePlatformTestCase`.

**Reference:** `docs/superpowers/specs/2026-08-11-maven-dependency-overrides-phase5-bom-provenance-chain-design.md`

---

### Task 1: `BomChainResolver` tracks provenance, `OverrideDetector` threads it through

**Files:**
- Modify: `src/main/kotlin/cloud/schneidoa/detection/BomChainResolver.kt`
- Modify: `src/test/kotlin/cloud/schneidoa/detection/BomChainResolverTest.kt`
- Modify: `src/main/kotlin/cloud/schneidoa/detection/OverrideDetector.kt`
- Modify: `src/test/kotlin/cloud/schneidoa/detection/OverrideDetectorTest.kt`

- [ ] **Step 1: Replace `BomChainResolverTest.kt` with the updated test file**

Replace the entire contents of `src/test/kotlin/cloud/schneidoa/detection/BomChainResolverTest.kt` with:

```kotlin
package cloud.schneidoa.detection

import cloud.schneidoa.resolver.Gav
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jetbrains.idea.maven.dom.MavenDomUtil
import org.jetbrains.idea.maven.dom.model.MavenDomProjectModel
import java.io.File

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

        val chain = resolver().resolveBomChain(model, project)

        assertEquals(
            listOf(BomImport(Gav("com.example", "acme-bom", "1.0.0"), emptyList())),
            chain
        )
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

        assertTrue(resolver().resolveBomChain(model, project).isEmpty())
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

        val chain = resolver().resolveBomChain(model, project)

        assertEquals(
            listOf(BomImport(Gav("com.example", "acme-bom", "1.0.0"), emptyList())),
            chain
        )
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
        val chain = resolver().resolveBomChain(childModel, project)

        assertEquals(
            listOf(
                BomImport(Gav("com.example", "acme-bom", "1.0.0"), emptyList()),
                BomImport(Gav("com.example", "legacy-bom", "1.0.0"), listOf("parent"))
            ),
            chain
        )
    }

    fun `test child BOM version wins over parent's same BOM at a different version`() {
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
                            <artifactId>shared-bom</artifactId>
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
                            <artifactId>shared-bom</artifactId>
                            <version>2.0.0</version>
                            <type>pom</type>
                            <scope>import</scope>
                        </dependency>
                    </dependencies>
                </dependencyManagement>
            </project>
            """.trimIndent()
        )

        val childModel = MavenDomUtil.getMavenDomProjectModel(project, childFile.virtualFile)!!
        val chain = resolver().resolveBomChain(childModel, project)

        assertEquals(
            listOf(
                BomImport(Gav("com.example", "shared-bom", "2.0.0"), emptyList()),
                BomImport(Gav("com.example", "shared-bom", "1.0.0"), listOf("parent"))
            ),
            chain
        )
    }

    fun `test terminates instead of hanging when the parent chain is cyclic`() {
        val fileA = myFixture.addFileToProject(
            "a/pom.xml",
            """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <parent>
                    <groupId>com.example</groupId>
                    <artifactId>module-b</artifactId>
                    <version>1.0.0</version>
                    <relativePath>../b/pom.xml</relativePath>
                </parent>
                <groupId>com.example</groupId>
                <artifactId>module-a</artifactId>
                <version>1.0.0</version>
            </project>
            """.trimIndent()
        )
        myFixture.addFileToProject(
            "b/pom.xml",
            """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <parent>
                    <groupId>com.example</groupId>
                    <artifactId>module-a</artifactId>
                    <version>1.0.0</version>
                    <relativePath>../a/pom.xml</relativePath>
                </parent>
                <groupId>com.example</groupId>
                <artifactId>module-b</artifactId>
                <version>1.0.0</version>
            </project>
            """.trimIndent()
        )

        val modelA = MavenDomUtil.getMavenDomProjectModel(project, fileA.virtualFile)!!
        val chain = resolver().resolveBomChain(modelA, project)

        assertTrue(chain.isEmpty())
    }

    fun `test skips file-based parent lookup when relativePath is present but empty, and falls back to repository`() {
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
                    <relativePath></relativePath>
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
        val chain = resolver().resolveBomChain(childModel, project)

        // The sibling "parent/pom.xml" is deliberately NOT picked up: an
        // explicit-but-empty <relativePath/> means "don't do relative file
        // lookup at all" per Maven's own semantics, so guessing "../pom.xml"
        // would risk grabbing an unrelated file that happens to sit there.
        // com.example:parent:1.0.0 also doesn't exist in the local repo
        // fixture, so the repository fallback finds nothing either - only
        // the child's own import shows up.
        assertEquals(
            listOf(BomImport(Gav("com.example", "acme-bom", "1.0.0"), emptyList())),
            chain
        )
    }

    fun `test falls back to local repository lookup when relativePath is empty and the parent is only resolvable there`() {
        val childFile = myFixture.configureByText(
            "pom.xml",
            """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <parent>
                    <groupId>com.example</groupId>
                    <artifactId>composing-bom</artifactId>
                    <version>1.0.0</version>
                    <relativePath/>
                </parent>
                <artifactId>child</artifactId>
            </project>
            """.trimIndent()
        )

        val childModel = MavenDomUtil.getMavenDomProjectModel(project, childFile.virtualFile)!!
        val chain = resolver().resolveBomChain(childModel, project)

        // composing-bom exists only in the local-repo fixture (no sibling
        // file anywhere in the test's VFS tree) and itself imports
        // legacy-bom - this is the real-world "Spring Initializr" shape:
        // <parent><relativePath/></parent> pointing at a BOM the IDE only
        // knows about via the local Maven repository. legacy-bom's
        // declaredVia names "composing-bom" - the parent-chain hop that
        // actually declares the import - even though composing-bom itself
        // was reached via repository lookup, not a sibling file.
        assertEquals(
            listOf(BomImport(Gav("com.example", "legacy-bom", "1.0.0"), listOf("composing-bom"))),
            chain
        )
    }

    fun `test multi-level parent chain records every hop in declaredVia`() {
        myFixture.addFileToProject(
            "grandparent/pom.xml",
            """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.example</groupId>
                <artifactId>grandparent</artifactId>
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
        myFixture.addFileToProject(
            "parent/pom.xml",
            """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <parent>
                    <groupId>com.example</groupId>
                    <artifactId>grandparent</artifactId>
                    <version>1.0.0</version>
                    <relativePath>../grandparent/pom.xml</relativePath>
                </parent>
                <artifactId>parent</artifactId>
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

        val childModel = MavenDomUtil.getMavenDomProjectModel(project, childFile.virtualFile)!!
        val chain = resolver().resolveBomChain(childModel, project)

        assertEquals(
            listOf(BomImport(Gav("com.example", "legacy-bom", "1.0.0"), listOf("parent", "grandparent"))),
            chain
        )
    }

    private fun resolver(): BomChainResolver {
        val fixtureUrl = javaClass.classLoader.getResource("fixtures/local-repo")
            ?: error("Test fixture local-repo not found on classpath")
        return BomChainResolver(File(fixtureUrl.toURI()))
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

Run: `./gradlew test --tests "cloud.schneidoa.detection.BomChainResolverTest"`
Expected: FAIL — compile error, `BomImport` unresolved (it doesn't exist yet) and/or type mismatches (`resolveBomChain` still returns `List<Gav>`).

- [ ] **Step 3: Update `BomChainResolver.kt`**

Replace the entire contents of `src/main/kotlin/cloud/schneidoa/detection/BomChainResolver.kt` with:

```kotlin
package cloud.schneidoa.detection

import cloud.schneidoa.resolver.Gav
import cloud.schneidoa.resolver.pomFileIn
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.idea.maven.dom.MavenDomUtil
import org.jetbrains.idea.maven.dom.MavenPropertyResolver
import org.jetbrains.idea.maven.dom.model.MavenDomDependency
import org.jetbrains.idea.maven.dom.model.MavenDomParent
import org.jetbrains.idea.maven.dom.model.MavenDomProjectModel
import java.io.File

/**
 * Bounds how far up the <parent> chain we'll climb. A real Maven precedence
 * walk would be indefinite, but Maven itself has practical limits on parent
 * depth too; this guards against a malformed/cyclic <parent> reference in a
 * broken POM turning into an infinite loop, without relying on
 * MavenDomProjectModel equality semantics (unverified) for cycle detection.
 */
private const val MAX_PARENT_CHAIN_DEPTH = 50
private const val DEFAULT_RELATIVE_PARENT_PATH = "../pom.xml"

/**
 * A BOM found somewhere in the parent chain, plus the path of artifactIds
 * climbed from the original module (exclusive) down to (and including) the
 * module whose own <dependencyManagement> declares it. Empty when the BOM
 * is declared directly in the original module itself.
 */
data class BomImport(val bom: Gav, val declaredVia: List<String>)

class BomChainResolver(private val localRepositoryDir: File) {

    fun resolveBomChain(model: MavenDomProjectModel, project: Project): List<BomImport> {
        val chain = mutableListOf<BomImport>()
        var current: MavenDomProjectModel? = model
        var path: List<String> = emptyList()
        var depth = 0

        while (current != null && depth < MAX_PARENT_CHAIN_DEPTH) {
            val declaredHere = path
            chain += importedBomsOf(current).map { BomImport(it, declaredHere) }

            val parent = resolveParent(current, project)
            if (parent != null) {
                path = path + artifactIdOf(parent)
            }
            current = parent
            depth++
        }

        return chain
    }

    private fun importedBomsOf(model: MavenDomProjectModel): List<Gav> {
        val managedDependencies: List<MavenDomDependency> = model.dependencyManagement.dependencies.dependencies

        return managedDependencies
            // Deliberately requires both scope=import AND type=pom: an entry with
            // scope=import but a non-pom type is malformed and isn't treated as
            // either a BOM import (here) or an override candidate
            // (DependencyManagementScanner excludes anything with scope=import
            // regardless of type) - it's silently ignored by both, matching this
            // project's "miss rather than false-safe" principle.
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

    private fun artifactIdOf(model: MavenDomProjectModel): String =
        model.artifactId.rawText?.trim().takeUnless { it.isNullOrEmpty() } ?: "?"

    /**
     * Resolves <parent>, preferring <relativePath> file navigation (not via
     * MavenDomProjectProcessorUtils.findParent, which only works against
     * MavenProjectsManager's already-synced project index) and falling back
     * to a local-repository GAV lookup whenever that doesn't produce a
     * result, mirroring real Maven's own two-stage parent resolution.
     */
    private fun resolveParent(model: MavenDomProjectModel, project: Project): MavenDomProjectModel? {
        val parent = model.mavenParent
        if (parent.xmlTag == null) return null

        return resolveParentViaRelativePath(model, parent, project)
            ?: resolveParentViaLocalRepository(parent, model, project)
    }

    /**
     * Falls back to Maven's own documented default of "../pom.xml" when
     * <relativePath> is absent (rawText == null). An explicit-but-empty
     * <relativePath/> means "don't do relative lookup at all" per Maven's
     * own semantics, which is different from "not written" — so that case
     * skips file lookup entirely (returns null, letting the caller fall
     * back to a repository lookup) rather than guessing the default and
     * possibly picking up an unrelated file that happens to sit there. If
     * the resolved path is a directory rather than a file (also valid per
     * Maven's own relativePath semantics), looks for pom.xml inside it.
     */
    private fun resolveParentViaRelativePath(
        model: MavenDomProjectModel,
        parent: MavenDomParent,
        project: Project
    ): MavenDomProjectModel? {
        val currentFile = model.xmlTag?.containingFile?.virtualFile ?: return null
        val baseDir = currentFile.parent ?: return null

        val rawRelativePath = parent.relativePath.rawText
        val relativePath = when {
            rawRelativePath == null -> DEFAULT_RELATIVE_PARENT_PATH
            rawRelativePath.isBlank() -> return null
            else -> rawRelativePath.trim()
        }

        val resolved = VfsUtilCore.findRelativeFile(relativePath, baseDir) ?: return null
        val parentFile: VirtualFile? = if (resolved.isDirectory) resolved.findChild("pom.xml") else resolved
        return parentFile?.let { MavenDomUtil.getMavenDomProjectModel(project, it) }
    }

    /**
     * Looks the parent POM up by GAV coordinate in the local Maven
     * repository, the same way real Maven resolves a parent whose
     * <relativePath> is absent, wrong, or explicitly empty (the standard
     * shape Spring Initializr generates for non-multi-module projects).
     */
    private fun resolveParentViaLocalRepository(
        parent: MavenDomParent,
        model: MavenDomProjectModel,
        project: Project
    ): MavenDomProjectModel? {
        val groupId = parent.groupId.rawText?.trim()
        val artifactId = parent.artifactId.rawText?.trim()
        val rawVersion = parent.version.rawText?.trim()
        if (groupId.isNullOrEmpty() || artifactId.isNullOrEmpty() || rawVersion.isNullOrEmpty()) return null
        val version = MavenPropertyResolver.resolve(rawVersion, model)

        val pomFile = Gav(groupId, artifactId, version).pomFileIn(localRepositoryDir)
        val virtualFile = LocalFileSystem.getInstance().findFileByIoFile(pomFile) ?: return null
        return MavenDomUtil.getMavenDomProjectModel(project, virtualFile)
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests "cloud.schneidoa.detection.BomChainResolverTest"`
Expected: FAIL — this time with compile errors in `OverrideDetector.kt`, since it still calls `bomVersionResolver.resolveManagedVersion(bomChain, ...)` with `bomChain: List<BomImport>` where a `List<Gav>` is expected. This is expected; proceed to the next step to fix it.

- [ ] **Step 5: Update `OverrideDetector.kt`**

Replace the entire contents of `src/main/kotlin/cloud/schneidoa/detection/OverrideDetector.kt` with:

```kotlin
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
        val declaredInBom: Gav,
        val managedByChain: List<String>
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

/** The candidate common to both outcomes - lets callers navigate/display without a `when`. */
val DetectedOverride.candidate: OverrideCandidate
    get() = when (this) {
        is DetectedOverride.Confirmed -> candidate
        is DetectedOverride.Inconclusive -> candidate
    }

class OverrideDetector(private val bomVersionResolver: BomVersionResolver) {

    private val bomChainResolver = BomChainResolver(bomVersionResolver.localRepositoryDir)

    fun detect(model: MavenDomProjectModel, project: Project): List<DetectedOverride> {
        val bomChain = bomChainResolver.resolveBomChain(model, project)

        return DependencyManagementScanner.scan(model)
            .filterNot { it.suppressed }
            .mapNotNull { candidate -> evaluate(candidate, bomChain) }
    }

    private fun evaluate(candidate: OverrideCandidate, bomChain: List<BomImport>): DetectedOverride? {
        return when (val lookup = bomVersionResolver.resolveManagedVersion(bomChain.map { it.bom }, candidate.ga)) {
            is ManagedVersionLookup.Found ->
                if (lookup.uncheckedBoms.isNotEmpty()) {
                    DetectedOverride.Inconclusive(candidate, lookup.uncheckedBoms)
                } else if (lookup.version != candidate.declaredVersion) {
                    val declaredVia = bomChain.firstOrNull { it.bom == lookup.declaredIn }?.declaredVia ?: emptyList()
                    DetectedOverride.Confirmed(candidate, lookup.version, lookup.declaredIn, declaredVia)
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

- [ ] **Step 6: Run `BomChainResolverTest` again to verify it passes**

Run: `./gradlew test --tests "cloud.schneidoa.detection.BomChainResolverTest"`
Expected: PASS — all 9 tests green.

- [ ] **Step 7: Update `OverrideDetectorTest.kt`'s two `Confirmed` assertions**

In `src/test/kotlin/cloud/schneidoa/detection/OverrideDetectorTest.kt`, find the test `` `test confirms an override that diverges from the BOM managed version` `` (around line 18-39) and add one line right after the existing `assertEquals("CVE-2024-12345", confirmed.candidate.reason)` line, so the end of that test reads:

```kotlin
        assertEquals("jackson-databind", confirmed.candidate.ga.artifactId)
        assertEquals("2.15.3", confirmed.bomVersion)
        assertEquals(Gav("com.example", "acme-bom", "1.0.0"), confirmed.declaredInBom)
        assertEquals("CVE-2024-12345", confirmed.candidate.reason)
        assertEquals(emptyList<String>(), confirmed.managedByChain)
    }
```

Then find the test `` `test confirms an override in a child module against a BOM imported by its parent` `` (around line 200-263) and add one line right after its existing `assertEquals("CVE-2024-99999", confirmed.candidate.reason)` line, so the end of that test reads:

```kotlin
        assertEquals("jackson-databind", confirmed.candidate.ga.artifactId)
        assertEquals("2.15.3", confirmed.bomVersion)
        assertEquals(Gav("com.example", "acme-bom", "1.0.0"), confirmed.declaredInBom)
        assertEquals("CVE-2024-99999", confirmed.candidate.reason)
        assertEquals(listOf("parent"), confirmed.managedByChain)
    }
```

(The BOM is declared in `parent/pom.xml`'s own `<dependencyManagement>`, one hop up from `child/pom.xml` — so `managedByChain` for this one should be `["parent"]`, matching the artifactId of that intermediate POM.)

- [ ] **Step 8: Run the full `OverrideDetectorTest` class**

Run: `./gradlew test --tests "cloud.schneidoa.detection.OverrideDetectorTest"`
Expected: PASS — all tests green, including the two updated ones.

- [ ] **Step 9: Run the full suite to check for regressions**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL, no failures anywhere. `DetectedOverride.Confirmed` gained a new constructor parameter, but the only place that *constructs* a `Confirmed` is `OverrideDetector.kt` (already updated in Step 5) — everywhere else (`OverrideFormatting.kt`, `OverrideInspection.kt`, `OverrideOverviewToolWindowFactory.kt`, and their tests) only *reads* fields off an already-built `Confirmed` obtained via `detector.detect(...)`, so they're unaffected by the new field and should already be green. If anything outside `BomChainResolverTest`/`OverrideDetectorTest` fails, treat it as a real regression to investigate now, not something deferred to Task 2.

- [ ] **Step 10: Commit**

```bash
git add src/main/kotlin/cloud/schneidoa/detection/BomChainResolver.kt src/main/kotlin/cloud/schneidoa/detection/OverrideDetector.kt src/test/kotlin/cloud/schneidoa/detection/BomChainResolverTest.kt src/test/kotlin/cloud/schneidoa/detection/OverrideDetectorTest.kt
git commit -m "Track BOM import provenance through the parent chain"
```

---

### Task 2: Surface the chain in the inspection message and the tool window

**Files:**
- Modify: `src/main/kotlin/cloud/schneidoa/detection/OverrideFormatting.kt`
- Modify: `src/test/kotlin/cloud/schneidoa/detection/OverrideFormattingTest.kt`
- Modify: `src/main/kotlin/cloud/schneidoa/detection/OverrideInspection.kt`
- Modify: `src/test/kotlin/cloud/schneidoa/detection/OverrideInspectionTest.kt`
- Modify: `src/main/kotlin/cloud/schneidoa/ui/OverrideOverviewToolWindowFactory.kt`

- [ ] **Step 1: Add failing tests for `managedByChain` to `OverrideFormattingTest.kt`**

In `src/test/kotlin/cloud/schneidoa/detection/OverrideFormattingTest.kt`, add these three test methods (place them anywhere among the other test methods, e.g. right after the last `statusText` test and before the `private fun configureModuleImportingAcmeBom` helper at the bottom):

```kotlin
    fun `test managedByChain shows only the BOM when declared directly in the module`() {
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

        assertEquals("com.example:acme-bom:1.0.0", managedByChain(confirmed))
    }

    fun `test managedByChain joins parent-chain hops before the BOM`() {
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
                            <groupId>com.fasterxml.jackson.core</groupId>
                            <artifactId>jackson-databind</artifactId>
                            <version>2.15.4</version>
                        </dependency>
                    </dependencies>
                </dependencyManagement>
            </project>
            """.trimIndent()
        )
        val childModel = MavenDomUtil.getMavenDomProjectModel(project, childFile.virtualFile)!!
        val detector = OverrideDetector(BomVersionResolver(localRepositoryDir()))

        val confirmed = detector.detect(childModel, project).single() as DetectedOverride.Confirmed

        assertEquals("parent → com.example:acme-bom:1.0.0", managedByChain(confirmed))
    }

    fun `test managedByChain is empty for an inconclusive override`() {
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

        val inconclusive = detector.detect(model, project).single() as DetectedOverride.Inconclusive

        assertEquals("", managedByChain(inconclusive))
    }
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests "cloud.schneidoa.detection.OverrideFormattingTest"`
Expected: FAIL — compile error, `managedByChain` unresolved (the 3 new tests reference a function that doesn't exist yet). The other 5 pre-existing tests in this file were already passing after Task 1 and aren't expected to regress.

- [ ] **Step 3: Add `managedByChain` to `OverrideFormatting.kt`**

Replace the entire contents of `src/main/kotlin/cloud/schneidoa/detection/OverrideFormatting.kt` with:

```kotlin
package cloud.schneidoa.detection

/** Formats "declared version → what the BOM chain manages it at" for display; "?" when the managed version can't be confirmed. */
fun declaredToManaged(override: DetectedOverride): String = when (override) {
    is DetectedOverride.Confirmed -> "${override.candidate.declaredVersion} → ${override.bomVersion}"
    is DetectedOverride.Inconclusive -> "${override.candidate.declaredVersion} → ?"
}

/** Human-readable status label, including how many BOMs were unchecked when inconclusive. */
fun statusText(override: DetectedOverride): String = when (override) {
    is DetectedOverride.Confirmed -> "Confirmed"
    is DetectedOverride.Inconclusive -> {
        val count = override.uncheckedBoms.size
        "Inconclusive ($count BOM${if (count == 1) "" else "s"} unchecked)"
    }
}

/**
 * The full parent-chain path from the module down to the BOM that actually
 * manages a confirmed override's version - e.g. "spring-boot-starter-parent
 * → spring-boot-dependencies → com.fasterxml.jackson:jackson-bom:2.21.2".
 * Degenerates to just the BOM itself when declared directly in the module.
 * Empty for Inconclusive results, which have no single managing BOM to name.
 */
fun managedByChain(override: DetectedOverride): String = when (override) {
    is DetectedOverride.Confirmed -> (override.managedByChain + override.declaredInBom.toString()).joinToString(" → ")
    is DetectedOverride.Inconclusive -> ""
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests "cloud.schneidoa.detection.OverrideFormattingTest"`
Expected: PASS — all 8 tests green (5 pre-existing + 3 new).

- [ ] **Step 5: Update the inspection message in `OverrideInspection.kt`**

In `src/main/kotlin/cloud/schneidoa/detection/OverrideInspection.kt`, replace this line:

```kotlin
                            "Overrides ${result.declaredInBom} which already manages this dependency at ${result.bomVersion}",
```

with:

```kotlin
                            "Overrides ${managedByChain(result)} which already manages this dependency at ${result.bomVersion}",
```

- [ ] **Step 6: Add a failing test to `OverrideInspectionTest.kt` for the full chain in the message**

In `src/test/kotlin/cloud/schneidoa/detection/OverrideInspectionTest.kt`, add this test method (place it right after the existing `` `test registers a weak warning on a confirmed override` `` test):

```kotlin
    fun `test confirmed override message includes the full parent chain to the managing BOM`() {
        myFixture.enableInspections(testInspection())
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
                            <groupId>com.fasterxml.jackson.core</groupId>
                            <artifactId>jackson-databind</artifactId>
                            <version>2.15.4</version>
                        </dependency>
                    </dependencies>
                </dependencyManagement>
            </project>
            """.trimIndent()
        )
        myFixture.configureFromExistingVirtualFile(childFile.virtualFile)

        val highlights = myFixture.doHighlighting()
        val overrideHighlight = highlights.singleOrNull { it.description?.contains("acme-bom") == true }

        assertNotNull("Expected a highlight for the confirmed override", overrideHighlight)
        assertTrue(
            "Expected the message to include the full parent chain, was: ${overrideHighlight!!.description}",
            overrideHighlight.description.contains("parent → com.example:acme-bom:1.0.0")
        )
    }
```

- [ ] **Step 7: Run `OverrideInspectionTest` to verify it passes**

Run: `./gradlew test --tests "cloud.schneidoa.detection.OverrideInspectionTest"`
Expected: PASS — all tests green, including the new one from Step 6. (If you want to see the new test genuinely fail first, temporarily revert Step 5's one-line message change, run this command, confirm the new test fails because the message lacks the `"parent → "` prefix, then reapply Step 5 and re-run to confirm it passes — but since Step 5 already happened by this point in the task, running it now should simply pass.) The pre-existing `` `test registers a weak warning on a confirmed override` `` test also still passes, since `"acme-bom"` remains a substring of the new, longer message.

- [ ] **Step 8: Add the "Managed By" column to `OverrideOverviewToolWindowFactory.kt`**

In `src/main/kotlin/cloud/schneidoa/ui/OverrideOverviewToolWindowFactory.kt`:

Add this import alongside the other `cloud.schneidoa.detection.*` imports:

```kotlin
import cloud.schneidoa.detection.managedByChain
```

Replace this line:

```kotlin
private val COLUMNS = arrayOf("Module", "Dependency", "Declared → Managed", "Status")
```

with:

```kotlin
private val COLUMNS = arrayOf("Module", "Dependency", "Declared → Managed", "Managed By", "Status")
```

And replace the `tableModel.addRow(...)` call inside `populate(...)`:

```kotlin
            tableModel.addRow(
                arrayOf(
                    entry.moduleLabel,
                    entry.override.candidate.ga.toString(),
                    declaredToManaged(entry.override),
                    statusText(entry.override)
                )
            )
```

with:

```kotlin
            tableModel.addRow(
                arrayOf(
                    entry.moduleLabel,
                    entry.override.candidate.ga.toString(),
                    declaredToManaged(entry.override),
                    managedByChain(entry.override),
                    statusText(entry.override)
                )
            )
```

- [ ] **Step 9: Compile to catch mistakes**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL. This file has no automated test (consistent with Phase 4's precedent for this class) — a clean compile is the checkpoint; manual verification happens after this task, alongside the rest of the plan's final manual check.

- [ ] **Step 10: Run the full suite**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL, no failures anywhere.

- [ ] **Step 11: Commit**

```bash
git add src/main/kotlin/cloud/schneidoa/detection/OverrideFormatting.kt src/main/kotlin/cloud/schneidoa/detection/OverrideInspection.kt src/main/kotlin/cloud/schneidoa/ui/OverrideOverviewToolWindowFactory.kt src/test/kotlin/cloud/schneidoa/detection/OverrideFormattingTest.kt src/test/kotlin/cloud/schneidoa/detection/OverrideInspectionTest.kt
git commit -m "Show the BOM provenance chain in the inspection message and tool window"
```

---

### Task 3: Full suite, changelog, manual verification note

**Files:**
- Modify: `CHANGELOG.md`

- [ ] **Step 1: Run the full suite one more time**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL, no failures.

- [ ] **Step 2: Add a changelog entry**

In `CHANGELOG.md`, under `## [Unreleased]`, add a new bullet to the existing `### Added` section:

```markdown
- BOM provenance chain: confirmed overrides now show the full parent-chain
  path from the module down to the BOM that actually manages the version
  (e.g. `spring-boot-starter-parent → spring-boot-dependencies →
  com.fasterxml.jackson:jackson-bom:2.21.2`), in both the inline editor
  warning and a new "Managed By" column in the Override Overview tool
  window — not just the bare BOM name, which was often something the
  developer never wrote themselves.
```

- [ ] **Step 3: Commit**

```bash
git add CHANGELOG.md
git commit -m "Document the BOM provenance chain in the changelog"
```

- [ ] **Step 4: Manual verification note (do not attempt — for the human after this plan completes)**

Run `./gradlew runIde`, open a project with a confirmed override reached through a multi-level parent chain (e.g. `camperchat/backend` from earlier manual verification), and confirm:
- The inline editor warning names the full chain, not just the BOM.
- The Override Overview tool window's new "Managed By" column shows the same chain.
- Inconclusive rows show an empty "Managed By" cell (not an error, not a stray arrow).
