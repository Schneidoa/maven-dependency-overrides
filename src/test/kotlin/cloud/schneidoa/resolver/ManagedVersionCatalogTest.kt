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
