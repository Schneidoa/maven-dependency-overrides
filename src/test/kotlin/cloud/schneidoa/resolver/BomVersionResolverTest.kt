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
        result as ManagedVersionLookup.Found
        assertEquals("4.2.0", result.version)
        assertEquals(listOf(missingBom), result.uncheckedBoms)
    }

    @Test
    fun `resolves a managed version through a BOM that itself imports another BOM`() {
        val composingBom = Gav("com.example", "composing-bom", "1.0.0")
        val result = resolver.resolveManagedVersion(
            listOf(composingBom),
            Ga("com.fasterxml.jackson.core", "jackson-databind")
        )

        assertTrue(result is ManagedVersionLookup.Found)
        result as ManagedVersionLookup.Found
        assertEquals("2.13.0", result.version)
        assertEquals(composingBom, result.declaredIn)
    }
}
