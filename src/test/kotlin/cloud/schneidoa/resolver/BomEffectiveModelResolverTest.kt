package cloud.schneidoa.resolver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
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

    @Test
    fun `fails gracefully when the BOM's own parent cannot be resolved`() {
        val brokenBom = Gav("com.example", "broken-parent-bom", "1.0.0")
        val result = resolver.buildEffectiveModel(brokenBom)

        assertTrue(result is BomModelResult.Failure)
        result as BomModelResult.Failure
        assertEquals(brokenBom, result.gav)
        assertTrue(result.reason.isNotBlank())
    }

    @Test
    fun `builds the effective model when the BOM's parent has a JDK-version-activated profile`() {
        val result = resolver.buildEffectiveModel(Gav("com.example", "jdk-profile-bom", "1.0.0"))

        assertTrue(result is BomModelResult.Success)
        result as BomModelResult.Success
        val jacksonVersion = result.effectiveModel.dependencyManagement.dependencies
            .first { it.groupId == "com.fasterxml.jackson.core" && it.artifactId == "jackson-databind" }
            .version
        assertEquals("2.21.2", jacksonVersion)
    }

    /**
     * Detection asks the same resolver for the same BOM once per override in a POM, so
     * without memoization a POM with ten overrides rebuilds its whole BOM chain's effective
     * models ten times - and those builds parse every parent POM off disk. Identity, not
     * equality, is what proves the second call didn't redo the work: `Model` has no
     * meaningful equals().
     */
    @Test
    fun `reuses the effective model it already built for the same BOM`() {
        val bom = Gav("com.example", "acme-bom", "1.0.0")

        val first = resolver.buildEffectiveModel(bom)
        val second = resolver.buildEffectiveModel(bom)

        assertTrue(first is BomModelResult.Success)
        assertTrue(second is BomModelResult.Success)
        assertSame(
            (first as BomModelResult.Success).effectiveModel,
            (second as BomModelResult.Success).effectiveModel
        )
    }

    @Test
    fun `reuses a failure verdict instead of retrying a BOM that is not in the repository`() {
        val missing = Gav("com.example", "not-in-repo-bom", "9.9.9")

        val first = resolver.buildEffectiveModel(missing)
        val second = resolver.buildEffectiveModel(missing)

        assertTrue(first is BomModelResult.Failure)
        assertSame(first, second)
    }

    @Test
    fun `merges a nested BOM pulled in via dependencyManagement import scope`() {
        val result = resolver.buildEffectiveModel(Gav("com.example", "composing-bom", "1.0.0"))

        assertTrue(result is BomModelResult.Success)
        result as BomModelResult.Success
        val jacksonVersion = result.effectiveModel.dependencyManagement.dependencies
            .first { it.groupId == "com.fasterxml.jackson.core" && it.artifactId == "jackson-databind" }
            .version
        assertEquals("2.13.0", jacksonVersion)
    }
}
