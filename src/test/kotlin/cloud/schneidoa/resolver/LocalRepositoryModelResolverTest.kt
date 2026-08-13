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
}
