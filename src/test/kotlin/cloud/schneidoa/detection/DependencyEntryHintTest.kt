package cloud.schneidoa.detection

import cloud.schneidoa.resolver.Ga
import cloud.schneidoa.resolver.Gav
import cloud.schneidoa.resolver.ManagedVersionCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DependencyEntryHintTest {

    private val jacksonDatabind = Ga("com.fasterxml.jackson.core", "jackson-databind")
    private val widgetCore = Ga("com.example", "widget-core")
    private val unmanaged = Ga("org.nope", "nope")

    private fun candidatesWith(
        versions: Map<Ga, String> = mapOf(jacksonDatabind to "2.15.3", widgetCore to "4.2.0"),
        unchecked: List<Gav> = emptyList()
    ) = DependencyCandidates(ManagedVersionCatalog(versions, unchecked))

    @Test
    fun `prefills an empty field with the managed version`() {
        val result = dependencyEntryHint(candidatesWith(), jacksonDatabind, "", versionEditedByUser = false)

        assertEquals("2.15.3", result.prefillVersion)
    }

    // The exact shape of Critical 1: the field already holds a previous prefill (for
    // a different GA managed at a different version) and the user has now picked a
    // second GA. The old value-comparison guard re-entered `prefillVersion` from
    // inside a Swing document notification in exactly this situation, throwing and
    // leaving the field blank. This function has no state to re-enter - calling it
    // again, with the prior prefill as the "current" field text, is just another call.
    @Test
    fun `prefill replaces a previous prefill without special-casing the field's current value`() {
        val candidates = candidatesWith()

        val first = dependencyEntryHint(candidates, widgetCore, declaredVersion = "", versionEditedByUser = false)
        assertEquals("4.2.0", first.prefillVersion)

        val second = dependencyEntryHint(
            candidates,
            jacksonDatabind,
            declaredVersion = first.prefillVersion!!,
            versionEditedByUser = false
        )
        assertEquals("2.15.3", second.prefillVersion)
    }

    @Test
    fun `does not prefill once the user has edited the version field`() {
        val result = dependencyEntryHint(candidatesWith(), jacksonDatabind, "", versionEditedByUser = true)

        assertNull(result.prefillVersion)
    }

    @Test
    fun `does not prefill when the field already matches the managed version`() {
        val result = dependencyEntryHint(candidatesWith(), jacksonDatabind, "2.15.3", versionEditedByUser = false)

        assertNull(result.prefillVersion)
    }

    @Test
    fun `no GA entered yields a blank hint`() {
        val result = dependencyEntryHint(candidatesWith(), null, "", versionEditedByUser = false)

        assertEquals(" ", result.hintText)
    }

    @Test
    fun `GA not managed by the chain`() {
        val result = dependencyEntryHint(candidatesWith(), unmanaged, "1.0.0", versionEditedByUser = true)

        assertEquals("No BOM in this module's chain manages this", result.hintText)
    }

    @Test
    fun `verdict SAME`() {
        val result = dependencyEntryHint(candidatesWith(), jacksonDatabind, "2.15.3", versionEditedByUser = true)

        assertEquals("Same version the BOM already manages — this override would be redundant", result.hintText)
    }

    @Test
    fun `verdict NEWER`() {
        val result = dependencyEntryHint(candidatesWith(), jacksonDatabind, "2.16.0", versionEditedByUser = true)

        assertEquals("Raises this above the BOM's 2.15.3", result.hintText)
    }

    @Test
    fun `verdict OLDER`() {
        val result = dependencyEntryHint(candidatesWith(), jacksonDatabind, "2.10.0", versionEditedByUser = true)

        assertEquals("Holds this below the BOM's 2.15.3", result.hintText)
    }

    @Test
    fun `verdict INCOMPARABLE`() {
        val result = dependencyEntryHint(
            candidatesWith(),
            jacksonDatabind,
            "\${jackson.version}",
            versionEditedByUser = true
        )

        assertEquals("Can't be compared with the BOM's 2.15.3", result.hintText)
    }

    @Test
    fun `incompleteness suffix appended to a verdict, singular`() {
        val candidates = candidatesWith(unchecked = listOf(Gav("com.example", "missing-bom", "1.0.0")))

        val result = dependencyEntryHint(candidates, jacksonDatabind, "2.15.3", versionEditedByUser = true)

        assertEquals(
            "Same version the BOM already manages — this override would be redundant" +
                " — 1 BOM could not be read, so these suggestions may be incomplete",
            result.hintText
        )
    }

    @Test
    fun `incompleteness suffix stands alone when nothing has been typed, plural`() {
        val candidates = candidatesWith(
            unchecked = listOf(
                Gav("com.example", "missing-bom-1", "1.0.0"),
                Gav("com.example", "missing-bom-2", "1.0.0")
            )
        )

        val result = dependencyEntryHint(candidates, null, "", versionEditedByUser = false)

        assertEquals("2 BOMs could not be read, so these suggestions may be incomplete", result.hintText)
    }
}
