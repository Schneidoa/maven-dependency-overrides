package cloud.schneidoa.detection

import cloud.schneidoa.resolver.Gav
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * BasePlatformTestCase rather than plain JUnit despite the class under test touching no
 * PSI/DOM: resolvingMissingPoms takes the read lock, which needs a real application.
 */
class MissingPomResolutionTest : BasePlatformTestCase() {

    private val missingA = Gav("com.example", "a", "1.0.0")
    private val missingB = Gav("com.example", "b", "1.0.0")

    fun `test returns the first result when nothing is missing`() {
        var rounds = 0
        val fetched = mutableListOf<Set<Gav>>()

        val result = resolvingMissingPoms(project, fetcher = { fetched += it; it }) { _ ->
            rounds++
            "done"
        }

        assertEquals("done", result)
        assertEquals(1, rounds)
        assertTrue("fetcher must not run when nothing was reported missing", fetched.isEmpty())
    }

    fun `test refetches and reresolves until nothing is missing`() {
        var rounds = 0

        val result = resolvingMissingPoms(project, fetcher = { it }) { onMissing ->
            rounds++
            // First pass reports a miss; once "fetched", the second pass finds everything.
            if (rounds == 1) onMissing(missingA)
            rounds
        }

        assertEquals(2, result)
        assertEquals(2, rounds)
    }

    fun `test stops when the fetcher cannot deliver anything`() {
        var rounds = 0

        resolvingMissingPoms(project, fetcher = { emptySet() }) { onMissing ->
            rounds++
            onMissing(missingA)
        }

        // One resolve, one failed fetch, then stop - not maxRounds attempts.
        assertEquals(1, rounds)
    }

    fun `test does not retry a coordinate that already failed to fetch`() {
        val requested = mutableListOf<Set<Gav>>()

        resolvingMissingPoms(
            project,
            fetcher = { request ->
                requested += request
                // B is deliverable, A never is.
                request.filter { it == missingB }.toSet()
            }
        ) { onMissing ->
            // Reports both every round, so the only thing that can shrink the request
            // is the loop remembering that A is unfetchable.
            onMissing(missingA)
            onMissing(missingB)
        }

        assertEquals(2, requested.size)
        assertEquals(setOf(missingA, missingB), requested[0])
        assertEquals(setOf(missingB), requested[1])
    }

    fun `test never asks the fetcher for a coordinate that still contains a property`() {
        val requested = mutableListOf<Set<Gav>>()
        var rounds = 0
        val unresolved = Gav("com.example", "acme-bom", "\${acme.version}")

        resolvingMissingPoms(project, fetcher = { requested += it; it }) { onMissing ->
            rounds++
            onMissing(unresolved)
        }

        // Nothing fetchable was reported, so the loop must stop after one resolve rather than
        // re-reporting the same placeholder until the round budget runs out.
        assertTrue("placeholder coordinates must never be requested, got $requested", requested.isEmpty())
        assertEquals(1, rounds)
    }

    fun `test stops at the round bound even when fetches keep succeeding`() {
        var rounds = 0

        resolvingMissingPoms(project, fetcher = { it }, maxRounds = 3) { onMissing ->
            rounds++
            onMissing(Gav("com.example", "endless-$rounds", "1.0.0"))
        }

        assertEquals(3, rounds)
    }
}
