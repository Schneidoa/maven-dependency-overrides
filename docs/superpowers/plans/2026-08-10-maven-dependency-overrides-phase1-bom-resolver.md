# Maven Dependency Overrides — Phase 1: BOM Version Resolver — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build and unit-test the BOM Version Resolver — a pure, IDE-independent
Kotlin library that, given a local Maven repository directory and an
ordered list of BOM (`groupId:artifactId:version`) coordinates, tells you the
version a target `groupId:artifactId` would be managed at if it were *not*
locally overridden.

**Architecture:** Embeds Maven's own `maven-model-builder` library so version
resolution (property interpolation, BOM's own parent chain) is delegated to
real Maven semantics rather than hand-rolled. A small `ModelResolver`
implementation resolves parent/import POM references purely against a local
repository directory (standard Maven layout, no network). Three focused
classes: `LocalRepositoryModelResolver` (filesystem lookup),
`BomEffectiveModelResolver` (builds one BOM's effective model), and
`BomVersionResolver` (searches an ordered BOM list for a managed version).

**Tech Stack:** Kotlin, Gradle (IntelliJ Platform Gradle Plugin scaffold
already in place), JUnit 4, `org.apache.maven:maven-model-builder:3.9.9`.

---

## Why this phase is scoped the way it is

The full design (see
[`docs/superpowers/specs/2026-08-10-maven-dependency-overrides-design.md`](../specs/2026-08-10-maven-dependency-overrides-design.md))
has four components: BOM Version Resolver, Override Detector (PSI-based),
Version Advisor (network), and Editor Integration/Tool Window (IntelliJ
Platform UI). These are not independent subsystems — Detector depends on the
Resolver, the editor/tool-window UI depends on the Detector — but they *can*
be delivered incrementally, each producing working, independently testable
software. This plan covers only the Resolver: it has zero IntelliJ Platform
dependency, is fully unit-testable without an IDE sandbox, and is the piece
where correctness matters most (it's what a later "is it safe to remove this
override" quick fix will rely on). Subsequent phases (Override Detector,
Editor Integration, Version Advisor, Tool Window) will each get their own
plan document once this one is implemented and reviewed.

## File Structure

New code goes under a `resolver` sub-package, breaking from the existing flat
`src/main/kotlin/*.kt` demo layout — this project is about to grow well
beyond the two demo files, so directory-per-responsibility starts now:

```
src/main/kotlin/cloud/schneidoa/resolver/
  MavenCoordinates.kt          Ga, Gav value types + local-repo path helper
  LocalRepositoryModelResolver.kt   ModelResolver backed by a local repo dir
  BomEffectiveModelResolver.kt      Builds one BOM's effective Maven model
  BomVersionResolver.kt             Precedence-ordered managed-version lookup
src/test/kotlin/cloud/schneidoa/resolver/
  LocalRepositoryModelResolverTest.kt
  BomEffectiveModelResolverTest.kt
  BomVersionResolverTest.kt
src/test/resources/fixtures/local-repo/
  com/example/acme-bom-parent/1.0.0/acme-bom-parent-1.0.0.pom
  com/example/acme-bom/1.0.0/acme-bom-1.0.0.pom
  com/example/legacy-bom/1.0.0/legacy-bom-1.0.0.pom
```

---

### Task 1: Add the `maven-model-builder` dependency to the build

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `build.gradle.kts`

- [ ] **Step 1: Add the version catalog entry**

Edit `gradle/libs.versions.toml` to:

```toml
[versions]
junit = "4.13.2"
mavenModelBuilder = "3.9.9"

[libraries]
junit = { module = "junit:junit", version.ref = "junit" }
maven-model-builder = { module = "org.apache.maven:maven-model-builder", version.ref = "mavenModelBuilder" }
```

- [ ] **Step 2: Add the dependency to `build.gradle.kts`**

Edit `build.gradle.kts` so the `dependencies` block reads:

```kotlin
dependencies {
    implementation(libs.maven.model.builder)
    testImplementation(libs.junit)

    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        intellijIdea("2025.3.5")
        testFramework(TestFrameworkType.Platform)

        // Add plugin dependencies for compilation here:
        bundledPlugin("com.intellij.java")
    }
}
```

- [ ] **Step 3: Verify the dependency resolves**

Run:

```bash
./gradlew dependencies --configuration compileClasspath | grep -A2 "maven-model-builder"
```

Expected: output showing `org.apache.maven:maven-model-builder:3.9.9` resolved
(not `FAILED`), pulling in `org.apache.maven:maven-model` and
`org.codehaus.plexus:plexus-utils` transitively.

> **Known follow-up risk (not fixed in this phase):** `maven-model-builder`'s
> transitive dependencies (`plexus-utils`, `plexus-interpolation`) may later
> collide with classes IntelliJ's own bundled Maven plugin ships, once a
> later phase adds `bundledPlugin("org.jetbrains.idea.maven")` and this code
> runs inside the platform. If `runIde`/`verifyPlugin` in a later phase
> surfaces `NoSuchMethodError`/`ClassNotFoundException` around these
> packages, shade-and-relocate `maven-model-builder`'s transitive deps via
> the Gradle Shadow plugin. Not addressed now because this phase never runs
> inside an IntelliJ platform classloader — it's plain JVM unit tests.

- [ ] **Step 4: Commit**

```bash
git add gradle/libs.versions.toml build.gradle.kts
git commit -m "$(cat <<'EOF'
Add maven-model-builder dependency for BOM version resolution

EOF
)"
```

---

### Task 2: Maven coordinate types and BOM fixture repository

**Files:**
- Create: `src/main/kotlin/cloud/schneidoa/resolver/MavenCoordinates.kt`
- Create: `src/test/resources/fixtures/local-repo/com/example/acme-bom-parent/1.0.0/acme-bom-parent-1.0.0.pom`
- Create: `src/test/resources/fixtures/local-repo/com/example/acme-bom/1.0.0/acme-bom-1.0.0.pom`
- Create: `src/test/resources/fixtures/local-repo/com/example/legacy-bom/1.0.0/legacy-bom-1.0.0.pom`

These fixture POMs stand in for a real Spring-Boot-style BOM chain and are
reused by every test in this plan:

- `acme-bom-parent` — declares `jackson.version=2.15.3` as a property (models
  a BOM inheriting a version from its own parent, e.g. how
  `spring-boot-dependencies` inherits from `spring-boot-parent`).
- `acme-bom` — inherits from `acme-bom-parent`, manages
  `jackson-databind` via the inherited property, and manages
  `widget-core` via its own property `widget.version=4.2.0`.
- `legacy-bom` — an unrelated, independent BOM that also manages
  `jackson-databind`, but at an older literal version — used to prove
  precedence ordering (first BOM in a caller-supplied list wins).

There's no fixture for "missing BOM" — tests reference a coordinate that
simply isn't on disk to exercise that path.

- [ ] **Step 1: Create the value types**

```kotlin
// src/main/kotlin/cloud/schneidoa/resolver/MavenCoordinates.kt
package cloud.schneidoa.resolver

import java.io.File

/** A Maven `groupId:artifactId` pair, without a version. */
data class Ga(val groupId: String, val artifactId: String) {
    override fun toString(): String = "$groupId:$artifactId"
}

/** A fully qualified Maven `groupId:artifactId:version`. */
data class Gav(val groupId: String, val artifactId: String, val version: String) {
    fun toGa(): Ga = Ga(groupId, artifactId)
    override fun toString(): String = "$groupId:$artifactId:$version"
}

/**
 * The file a Maven local repository would store this artifact's POM at,
 * following the standard `groupId/artifactId/version/artifactId-version.pom`
 * layout.
 */
fun Gav.pomFileIn(localRepositoryDir: File): File = File(
    localRepositoryDir,
    "${groupId.replace('.', '/')}/$artifactId/$version/$artifactId-$version.pom"
)
```

- [ ] **Step 2: Create the `acme-bom-parent` fixture**

```xml
<!-- src/test/resources/fixtures/local-repo/com/example/acme-bom-parent/1.0.0/acme-bom-parent-1.0.0.pom -->
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <modelVersion>4.0.0</modelVersion>
    <groupId>com.example</groupId>
    <artifactId>acme-bom-parent</artifactId>
    <version>1.0.0</version>
    <packaging>pom</packaging>

    <properties>
        <jackson.version>2.15.3</jackson.version>
    </properties>
</project>
```

- [ ] **Step 3: Create the `acme-bom` fixture**

```xml
<!-- src/test/resources/fixtures/local-repo/com/example/acme-bom/1.0.0/acme-bom-1.0.0.pom -->
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>com.example</groupId>
        <artifactId>acme-bom-parent</artifactId>
        <version>1.0.0</version>
    </parent>
    <artifactId>acme-bom</artifactId>
    <packaging>pom</packaging>

    <properties>
        <widget.version>4.2.0</widget.version>
    </properties>

    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>com.fasterxml.jackson.core</groupId>
                <artifactId>jackson-databind</artifactId>
                <version>${jackson.version}</version>
            </dependency>
            <dependency>
                <groupId>com.example</groupId>
                <artifactId>widget-core</artifactId>
                <version>${widget.version}</version>
            </dependency>
        </dependencies>
    </dependencyManagement>
</project>
```

- [ ] **Step 4: Create the `legacy-bom` fixture**

```xml
<!-- src/test/resources/fixtures/local-repo/com/example/legacy-bom/1.0.0/legacy-bom-1.0.0.pom -->
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <modelVersion>4.0.0</modelVersion>
    <groupId>com.example</groupId>
    <artifactId>legacy-bom</artifactId>
    <version>1.0.0</version>
    <packaging>pom</packaging>

    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>com.fasterxml.jackson.core</groupId>
                <artifactId>jackson-databind</artifactId>
                <version>2.13.0</version>
            </dependency>
        </dependencies>
    </dependencyManagement>
</project>
```

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/cloud/schneidoa/resolver/MavenCoordinates.kt src/test/resources/fixtures
git commit -m "$(cat <<'EOF'
Add Maven coordinate types and BOM fixture repository for resolver tests

EOF
)"
```

---

### Task 3: `LocalRepositoryModelResolver`

**Files:**
- Create: `src/test/kotlin/cloud/schneidoa/resolver/LocalRepositoryModelResolverTest.kt`
- Create: `src/main/kotlin/cloud/schneidoa/resolver/LocalRepositoryModelResolver.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
// src/test/kotlin/cloud/schneidoa/resolver/LocalRepositoryModelResolverTest.kt
package cloud.schneidoa.resolver

import org.apache.maven.model.Repository
import org.apache.maven.model.building.FileModelSource
import org.apache.maven.model.resolution.UnresolvableModelException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File

class LocalRepositoryModelResolverTest {

    private lateinit var resolver: LocalRepositoryModelResolver

    @Before
    fun setUp() {
        val fixtureUrl = javaClass.classLoader.getResource("fixtures/local-repo")
            ?: error("Test fixture local-repo not found on classpath")
        resolver = LocalRepositoryModelResolver(File(fixtureUrl.toURI()))
    }

    @Test
    fun `resolves an existing artifact to its pom file contents`() {
        val source = resolver.resolveModel("com.example", "acme-bom-parent", "1.0.0") as FileModelSource
        val content = source.inputStream.bufferedReader().use { it.readText() }
        assertTrue(content.contains("<artifactId>acme-bom-parent</artifactId>"))
    }

    @Test
    fun `resolves a parent reference the same way as a plain coordinate`() {
        val parent = org.apache.maven.model.Parent().apply {
            groupId = "com.example"
            artifactId = "acme-bom-parent"
            version = "1.0.0"
        }
        val source = resolver.resolveModel(parent) as FileModelSource
        assertTrue(source.inputStream.bufferedReader().use { it.readText() }.contains("acme-bom-parent"))
    }

    @Test
    fun `resolves a dependency reference the same way as a plain coordinate`() {
        val dependency = org.apache.maven.model.Dependency().apply {
            groupId = "com.example"
            artifactId = "acme-bom-parent"
            version = "1.0.0"
        }
        val source = resolver.resolveModel(dependency) as FileModelSource
        assertTrue(source.inputStream.bufferedReader().use { it.readText() }.contains("acme-bom-parent"))
    }

    @Test
    fun `throws UnresolvableModelException with the requested coordinates for a missing artifact`() {
        try {
            resolver.resolveModel("com.example", "does-not-exist-bom", "9.9.9")
            fail("Expected UnresolvableModelException")
        } catch (e: UnresolvableModelException) {
            assertEquals("com.example", e.groupId)
            assertEquals("does-not-exist-bom", e.artifactId)
            assertEquals("9.9.9", e.version)
        }
    }

    @Test
    fun `addRepository is a no-op and never throws`() {
        resolver.addRepository(Repository())
        resolver.addRepository(Repository(), true)
    }

    @Test
    fun `newCopy resolves against the same local repository`() {
        val copy = resolver.newCopy()
        val source = copy.resolveModel("com.example", "acme-bom-parent", "1.0.0") as FileModelSource
        assertTrue(source.inputStream.bufferedReader().use { it.readText() }.contains("acme-bom-parent"))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run:

```bash
./gradlew test --tests "cloud.schneidoa.resolver.LocalRepositoryModelResolverTest"
```

Expected: compilation failure — `unresolved reference: LocalRepositoryModelResolver`.

- [ ] **Step 3: Implement `LocalRepositoryModelResolver`**

```kotlin
// src/main/kotlin/cloud/schneidoa/resolver/LocalRepositoryModelResolver.kt
package cloud.schneidoa.resolver

import org.apache.maven.model.Dependency
import org.apache.maven.model.Parent
import org.apache.maven.model.Repository
import org.apache.maven.model.building.FileModelSource
import org.apache.maven.model.building.ModelSource
import org.apache.maven.model.resolution.ModelResolver
import org.apache.maven.model.resolution.UnresolvableModelException
import java.io.File

/**
 * Resolves parent/import POM references purely against a local Maven
 * repository directory, following the standard repository layout. Never
 * touches the network — remote repositories declared in POMs are ignored,
 * since resolution here only concerns artifacts already present locally
 * (see design doc: core override detection must work offline).
 */
class LocalRepositoryModelResolver(private val localRepositoryDir: File) : ModelResolver {

    override fun resolveModel(groupId: String, artifactId: String, version: String): ModelSource =
        resolve(Gav(groupId, artifactId, version))

    override fun resolveModel(parent: Parent): ModelSource =
        resolve(Gav(parent.groupId, parent.artifactId, parent.version))

    override fun resolveModel(dependency: Dependency): ModelSource =
        resolve(Gav(dependency.groupId, dependency.artifactId, dependency.version))

    override fun addRepository(repository: Repository) {
        // Intentionally a no-op, see class doc.
    }

    override fun addRepository(repository: Repository, replace: Boolean) {
        // Intentionally a no-op, see class doc.
    }

    override fun newCopy(): ModelResolver = LocalRepositoryModelResolver(localRepositoryDir)

    private fun resolve(gav: Gav): ModelSource {
        val pomFile = gav.pomFileIn(localRepositoryDir)
        if (!pomFile.isFile) {
            throw UnresolvableModelException(
                "Could not find ${pomFile.name} in local repository $localRepositoryDir",
                gav.groupId,
                gav.artifactId,
                gav.version
            )
        }
        return FileModelSource(pomFile)
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run:

```bash
./gradlew test --tests "cloud.schneidoa.resolver.LocalRepositoryModelResolverTest"
```

Expected: `BUILD SUCCESSFUL`, 6 tests passed.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/cloud/schneidoa/resolver/LocalRepositoryModelResolver.kt src/test/kotlin/cloud/schneidoa/resolver/LocalRepositoryModelResolverTest.kt
git commit -m "$(cat <<'EOF'
Add LocalRepositoryModelResolver for offline parent/import POM lookup

EOF
)"
```

---

### Task 4: `BomEffectiveModelResolver`

**Files:**
- Create: `src/test/kotlin/cloud/schneidoa/resolver/BomEffectiveModelResolverTest.kt`
- Create: `src/main/kotlin/cloud/schneidoa/resolver/BomEffectiveModelResolver.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
// src/test/kotlin/cloud/schneidoa/resolver/BomEffectiveModelResolverTest.kt
package cloud.schneidoa.resolver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class BomEffectiveModelResolverTest {

    private lateinit var resolver: BomEffectiveModelResolver

    @Before
    fun setUp() {
        val fixtureUrl = javaClass.classLoader.getResource("fixtures/local-repo")
            ?: error("Test fixture local-repo not found on classpath")
        resolver = BomEffectiveModelResolver(File(fixtureUrl.toURI()))
    }

    @Test
    fun `builds the effective model including managed dependencies with a literal version`() {
        val result = resolver.buildEffectiveModel(Gav("com.example", "acme-bom", "1.0.0"))

        assertTrue(result is BomModelResult.Success)
        result as BomModelResult.Success
        val widgetVersion = result.effectiveModel.dependencyManagement.dependencies
            .first { it.groupId == "com.example" && it.artifactId == "widget-core" }
            .version
        assertEquals("4.2.0", widgetVersion)
    }

    @Test
    fun `interpolates a property inherited from the BOM's own parent`() {
        val result = resolver.buildEffectiveModel(Gav("com.example", "acme-bom", "1.0.0"))

        assertTrue(result is BomModelResult.Success)
        result as BomModelResult.Success
        val jacksonVersion = result.effectiveModel.dependencyManagement.dependencies
            .first { it.groupId == "com.fasterxml.jackson.core" && it.artifactId == "jackson-databind" }
            .version
        assertEquals("2.15.3", jacksonVersion)
    }

    @Test
    fun `fails gracefully when the BOM POM is not in the local repository`() {
        val missing = Gav("com.example", "does-not-exist-bom", "9.9.9")
        val result = resolver.buildEffectiveModel(missing)

        assertTrue(result is BomModelResult.Failure)
        result as BomModelResult.Failure
        assertEquals(missing, result.gav)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run:

```bash
./gradlew test --tests "cloud.schneidoa.resolver.BomEffectiveModelResolverTest"
```

Expected: compilation failure — `unresolved reference: BomEffectiveModelResolver`.

- [ ] **Step 3: Implement `BomEffectiveModelResolver`**

```kotlin
// src/main/kotlin/cloud/schneidoa/resolver/BomEffectiveModelResolver.kt
package cloud.schneidoa.resolver

import org.apache.maven.model.Model
import org.apache.maven.model.building.DefaultModelBuilderFactory
import org.apache.maven.model.building.DefaultModelBuildingRequest
import org.apache.maven.model.building.ModelBuildingException
import org.apache.maven.model.building.ModelBuildingRequest
import java.io.File

sealed class BomModelResult {
    data class Success(val effectiveModel: Model) : BomModelResult()
    data class Failure(val gav: Gav, val reason: String) : BomModelResult()
}

/**
 * Builds the fully effective Maven model (parent chain resolved, properties
 * interpolated, nested BOM imports merged) of a single BOM artifact, in
 * isolation from whatever project might be importing it. This is what makes
 * it possible to ask "what would this BOM manage this artifact at" without
 * caring whether some consumer POM has locally overridden it.
 */
class BomEffectiveModelResolver(private val localRepositoryDir: File) {

    fun buildEffectiveModel(bom: Gav): BomModelResult {
        val pomFile = bom.pomFileIn(localRepositoryDir)
        if (!pomFile.isFile) {
            return BomModelResult.Failure(bom, "BOM POM not found in local repository: $pomFile")
        }

        val request = DefaultModelBuildingRequest()
        request.setPomFile(pomFile)
        request.setModelResolver(LocalRepositoryModelResolver(localRepositoryDir))
        request.setValidationLevel(ModelBuildingRequest.VALIDATION_LEVEL_MINIMAL)
        request.setProcessPlugins(false)

        return try {
            val result = DefaultModelBuilderFactory().newInstance().build(request)
            BomModelResult.Success(result.effectiveModel)
        } catch (e: ModelBuildingException) {
            BomModelResult.Failure(bom, e.message ?: "Failed to build effective model for $bom")
        }
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run:

```bash
./gradlew test --tests "cloud.schneidoa.resolver.BomEffectiveModelResolverTest"
```

Expected: `BUILD SUCCESSFUL`, 3 tests passed.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/cloud/schneidoa/resolver/BomEffectiveModelResolver.kt src/test/kotlin/cloud/schneidoa/resolver/BomEffectiveModelResolverTest.kt
git commit -m "$(cat <<'EOF'
Add BomEffectiveModelResolver, wrapping Maven's model builder per BOM

EOF
)"
```

---

### Task 5: `BomVersionResolver` (public API of this phase)

**Files:**
- Create: `src/test/kotlin/cloud/schneidoa/resolver/BomVersionResolverTest.kt`
- Create: `src/main/kotlin/cloud/schneidoa/resolver/BomVersionResolver.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
// src/test/kotlin/cloud/schneidoa/resolver/BomVersionResolverTest.kt
package cloud.schneidoa.resolver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class BomVersionResolverTest {

    private lateinit var resolver: BomVersionResolver

    private val acmeBom = Gav("com.example", "acme-bom", "1.0.0")
    private val legacyBom = Gav("com.example", "legacy-bom", "1.0.0")
    private val missingBom = Gav("com.example", "does-not-exist-bom", "9.9.9")

    @Before
    fun setUp() {
        val fixtureUrl = javaClass.classLoader.getResource("fixtures/local-repo")
            ?: error("Test fixture local-repo not found on classpath")
        resolver = BomVersionResolver(File(fixtureUrl.toURI()))
    }

    @Test
    fun `resolves a literal version declared directly in the BOM`() {
        val result = resolver.resolveManagedVersion(listOf(acmeBom), Ga("com.example", "widget-core"))

        assertTrue(result is ManagedVersionLookup.Found)
        result as ManagedVersionLookup.Found
        assertEquals("4.2.0", result.version)
        assertEquals(acmeBom, result.declaredIn)
    }

    @Test
    fun `first BOM in precedence order wins when both manage the same artifact`() {
        val result = resolver.resolveManagedVersion(
            listOf(acmeBom, legacyBom),
            Ga("com.fasterxml.jackson.core", "jackson-databind")
        )

        assertTrue(result is ManagedVersionLookup.Found)
        result as ManagedVersionLookup.Found
        assertEquals("2.15.3", result.version)
        assertEquals(acmeBom, result.declaredIn)
    }

    @Test
    fun `falls through to the next BOM when the artifact isn't managed by the first`() {
        val result = resolver.resolveManagedVersion(
            listOf(legacyBom, acmeBom),
            Ga("com.example", "widget-core")
        )

        assertTrue(result is ManagedVersionLookup.Found)
        result as ManagedVersionLookup.Found
        assertEquals("4.2.0", result.version)
        assertEquals(acmeBom, result.declaredIn)
    }

    @Test
    fun `reports not found with no unchecked BOMs when the artifact is managed nowhere`() {
        val result = resolver.resolveManagedVersion(
            listOf(acmeBom, legacyBom),
            Ga("org.example", "totally-unmanaged")
        )

        assertTrue(result is ManagedVersionLookup.NotFound)
        assertTrue((result as ManagedVersionLookup.NotFound).uncheckedBoms.isEmpty())
    }

    @Test
    fun `reports the BOM as unchecked when its POM cannot be found locally`() {
        val result = resolver.resolveManagedVersion(
            listOf(missingBom),
            Ga("com.fasterxml.jackson.core", "jackson-databind")
        )

        assertTrue(result is ManagedVersionLookup.NotFound)
        assertEquals(listOf(missingBom), (result as ManagedVersionLookup.NotFound).uncheckedBoms)
    }

    @Test
    fun `still finds a match in a later BOM even when an earlier one is unresolvable`() {
        val result = resolver.resolveManagedVersion(
            listOf(missingBom, acmeBom),
            Ga("com.example", "widget-core")
        )

        assertTrue(result is ManagedVersionLookup.Found)
        assertEquals("4.2.0", (result as ManagedVersionLookup.Found).version)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run:

```bash
./gradlew test --tests "cloud.schneidoa.resolver.BomVersionResolverTest"
```

Expected: compilation failure — `unresolved reference: BomVersionResolver`.

- [ ] **Step 3: Implement `BomVersionResolver`**

```kotlin
// src/main/kotlin/cloud/schneidoa/resolver/BomVersionResolver.kt
package cloud.schneidoa.resolver

import java.io.File

sealed class ManagedVersionLookup {
    data class Found(val version: String, val declaredIn: Gav) : ManagedVersionLookup()

    /**
     * No BOM in the supplied precedence list manages this artifact.
     * [uncheckedBoms] lists any BOMs that could NOT be resolved locally
     * (POM missing from the repository) — when non-empty, "not found" is
     * not a confident answer, since one of the unchecked BOMs might have
     * managed it. Callers must treat that case as "can't tell yet", not as
     * proof the override is safe to remove.
     */
    data class NotFound(val uncheckedBoms: List<Gav>) : ManagedVersionLookup()
}

/**
 * Searches an ordered list of BOM coordinates (nearest/highest-precedence
 * first) for the version a given `groupId:artifactId` is managed at,
 * independent of any local override in the consuming project's own POM.
 */
class BomVersionResolver(localRepositoryDir: File) {

    private val modelResolver = BomEffectiveModelResolver(localRepositoryDir)

    fun resolveManagedVersion(bomsInPrecedenceOrder: List<Gav>, target: Ga): ManagedVersionLookup {
        val unchecked = mutableListOf<Gav>()

        for (bom in bomsInPrecedenceOrder) {
            when (val result = modelResolver.buildEffectiveModel(bom)) {
                is BomModelResult.Failure -> unchecked += bom
                is BomModelResult.Success -> {
                    val managedVersion = result.effectiveModel.dependencyManagement
                        ?.dependencies
                        ?.firstOrNull { it.groupId == target.groupId && it.artifactId == target.artifactId }
                        ?.version
                    if (managedVersion != null) {
                        return ManagedVersionLookup.Found(managedVersion, bom)
                    }
                }
            }
        }

        return ManagedVersionLookup.NotFound(unchecked)
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run:

```bash
./gradlew test --tests "cloud.schneidoa.resolver.BomVersionResolverTest"
```

Expected: `BUILD SUCCESSFUL`, 6 tests passed.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/cloud/schneidoa/resolver/BomVersionResolver.kt src/test/kotlin/cloud/schneidoa/resolver/BomVersionResolverTest.kt
git commit -m "$(cat <<'EOF'
Add BomVersionResolver: precedence-ordered managed-version lookup

EOF
)"
```

---

### Task 6: Full suite run and changelog entry

**Files:**
- Modify: `CHANGELOG.md`

- [ ] **Step 1: Run the full test suite**

Run:

```bash
./gradlew test
```

Expected: `BUILD SUCCESSFUL`, all 15 tests across the three resolver test
classes pass, no other test regressions.

- [ ] **Step 2: Update the changelog**

Edit `CHANGELOG.md` so the `[Unreleased]` section reads:

```markdown
<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Maven-dependency-overrides Changelog

## [Unreleased]

### Added

- BOM Version Resolver: given a local Maven repository and an ordered list
  of BOM coordinates, resolves the version a dependency is managed at,
  independent of local overrides. Foundation for detecting stale
  dependencyManagement overrides in a later phase.
```

- [ ] **Step 3: Commit**

```bash
git add CHANGELOG.md
git commit -m "$(cat <<'EOF'
Document BOM Version Resolver in changelog

EOF
)"
```

---

## Roadmap (future plans, not covered here)

Once this phase is implemented and reviewed, subsequent phases each get
their own plan document:

1. **Override Detector** — PSI/DOM scanning of `pom.xml` for override
   candidates, comment/suppress-marker parsing, multi-module BOM-list
   discovery (feeds `BomVersionResolver.resolveManagedVersion`).
2. **Editor Integration** — line marker + inspection + the "update
   override" / "remove override" quick fixes (the ones that don't need
   network data).
3. **Version Advisor** — repository metadata lookups for "newer version
   available" / "newer parent available" quick fixes.
4. **Tool Window** — project-wide overview panel.
