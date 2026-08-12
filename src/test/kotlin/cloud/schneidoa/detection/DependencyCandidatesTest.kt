package cloud.schneidoa.detection

import cloud.schneidoa.resolver.Ga
import cloud.schneidoa.resolver.Gav
import cloud.schneidoa.resolver.ManagedVersionCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DependencyCandidatesTest {

    private val jacksonDatabind = Ga("com.fasterxml.jackson.core", "jackson-databind")
    private val jacksonCore = Ga("com.fasterxml.jackson.core", "jackson-core")
    private val widgetCore = Ga("com.example", "widget-core")
    // Same artifactId as jacksonCore but a different group - the ambiguous case.
    private val otherCore = Ga("com.other", "jackson-core")

    private fun candidates(
        versions: Map<Ga, String> = mapOf(
            jacksonDatabind to "2.15.3",
            jacksonCore to "2.15.3",
            widgetCore to "4.2.0"
        ),
        unchecked: List<Gav> = emptyList()
    ) = DependencyCandidates(ManagedVersionCatalog(versions, unchecked))

    @Test
    fun `groups are distinct and sorted`() {
        assertEquals(listOf("com.example", "com.fasterxml.jackson.core"), candidates().groups())
    }

    @Test
    fun `artifactsIn narrows to one group and sorts`() {
        assertEquals(
            listOf("jackson-core", "jackson-databind"),
            candidates().artifactsIn("com.fasterxml.jackson.core")
        )
    }

    @Test
    fun `artifactsIn returns nothing for an unknown group`() {
        assertTrue(candidates().artifactsIn("org.nope").isEmpty())
    }

    @Test
    fun `uniqueGroupFor resolves an artifactId owned by exactly one group`() {
        assertEquals("com.fasterxml.jackson.core", candidates().uniqueGroupFor("jackson-databind"))
    }

    // Deliberately null rather than a pick: auto-filling a guessed group would be
    // worse than leaving the field for the user, who still has the full-coordinate
    // dropdown to disambiguate with.
    @Test
    fun `uniqueGroupFor refuses an artifactId owned by several groups`() {
        val ambiguous = candidates(
            versions = mapOf(jacksonCore to "2.15.3", otherCore to "1.0.0")
        )

        assertNull(ambiguous.uniqueGroupFor("jackson-core"))
    }

    @Test
    fun `uniqueGroupFor returns null for an unknown artifactId`() {
        assertNull(candidates().uniqueGroupFor("nope"))
    }

    @Test
    fun `allArtifactIds is distinct and sorted across groups`() {
        val withDuplicate = candidates(
            versions = mapOf(jacksonCore to "2.15.3", otherCore to "1.0.0", widgetCore to "4.2.0")
        )

        assertEquals(listOf("jackson-core", "widget-core"), withDuplicate.allArtifactIds())
    }

    @Test
    fun `allCoordinates is sorted by group then artifact`() {
        assertEquals(
            listOf(widgetCore, jacksonCore, jacksonDatabind),
            candidates().allCoordinates()
        )
    }

    @Test
    fun `managedVersionOf returns the catalog version`() {
        assertEquals("2.15.3", candidates().managedVersionOf(jacksonDatabind))
        assertNull(candidates().managedVersionOf(Ga("org.nope", "nope")))
    }

    @Test
    fun `a catalog with unreadable BOMs is not complete`() {
        val partial = candidates(unchecked = listOf(Gav("com.example", "missing-bom", "1.0.0")))

        assertFalse(partial.isComplete)
        assertEquals(1, partial.unreadableBomCount)
        assertTrue(candidates().isComplete)
    }
}
