package cloud.schneidoa.resolver

import org.junit.Assert.assertEquals
import org.junit.Test

class VersionRelationTest {

    @Test
    fun `declared above managed is NEWER`() {
        assertEquals(VersionRelation.NEWER, compareDeclaredToManaged("2.15.4", "2.15.3"))
    }

    @Test
    fun `identical versions are SAME`() {
        assertEquals(VersionRelation.SAME, compareDeclaredToManaged("2.15.3", "2.15.3"))
    }

    @Test
    fun `declared below managed is OLDER`() {
        assertEquals(VersionRelation.OLDER, compareDeclaredToManaged("2.15.2", "2.15.3"))
    }

    // The case a naive string comparison gets backwards: "2.21.10" < "2.21.9" lexically.
    @Test
    fun `numeric segments compare numerically not lexically`() {
        assertEquals(VersionRelation.NEWER, compareDeclaredToManaged("2.21.10", "2.21.9"))
    }

    // Maven orders a qualifier below the bare release it qualifies.
    @Test
    fun `a prerelease qualifier sorts below the plain release`() {
        assertEquals(VersionRelation.OLDER, compareDeclaredToManaged("1.0-alpha", "1.0"))
    }

    @Test
    fun `an unresolved property on the declared side is INCOMPARABLE`() {
        assertEquals(VersionRelation.INCOMPARABLE, compareDeclaredToManaged("\${jackson.version}", "2.15.3"))
    }

    @Test
    fun `an unresolved property on the managed side is INCOMPARABLE`() {
        assertEquals(VersionRelation.INCOMPARABLE, compareDeclaredToManaged("2.15.3", "\${jackson.version}"))
    }

    @Test
    fun `a version range is INCOMPARABLE`() {
        assertEquals(VersionRelation.INCOMPARABLE, compareDeclaredToManaged("[1.0,2.0)", "2.15.3"))
    }

    @Test
    fun `a blank version is INCOMPARABLE`() {
        assertEquals(VersionRelation.INCOMPARABLE, compareDeclaredToManaged("  ", "2.15.3"))
    }

    @Test
    fun `surrounding whitespace does not affect the comparison`() {
        assertEquals(VersionRelation.SAME, compareDeclaredToManaged(" 2.15.3 ", "2.15.3"))
    }

    // ComparableVersion normalizes trailing zero segments away, so "1.0" and "1.0.0" order
    // equal - but they are different literal versions, i.e. different files in the local
    // repository. SAME must mean textual identity, not ordering equality.
    @Test
    fun `a trailing zero segment is INCOMPARABLE, not SAME, despite ordering equal`() {
        assertEquals(VersionRelation.INCOMPARABLE, compareDeclaredToManaged("1.0", "1.0.0"))
    }

    // The Spring-ecosystem ".RELEASE" qualifier convention normalizes away entirely under
    // ComparableVersion, but "2.3.4.RELEASE" and "2.3.4" resolve to different artifact files.
    @Test
    fun `a RELEASE qualifier suffix is INCOMPARABLE, not SAME, despite ordering equal`() {
        assertEquals(VersionRelation.INCOMPARABLE, compareDeclaredToManaged("2.3.4.RELEASE", "2.3.4"))
    }

    @Test
    fun `a final qualifier is INCOMPARABLE, not SAME, despite ordering equal`() {
        assertEquals(VersionRelation.INCOMPARABLE, compareDeclaredToManaged("1.0-final", "1.0"))
    }

    @Test
    fun `a ga qualifier is INCOMPARABLE, not SAME, despite ordering equal`() {
        assertEquals(VersionRelation.INCOMPARABLE, compareDeclaredToManaged("1.0-ga", "1.0"))
    }

    // LATEST and RELEASE are legal Maven metaversions, not points on the version line.
    // ComparableVersion orders both below any numeric release, which would otherwise produce
    // a false BEHIND_BOM verdict.
    @Test
    fun `LATEST is INCOMPARABLE`() {
        assertEquals(VersionRelation.INCOMPARABLE, compareDeclaredToManaged("LATEST", "2.15.3"))
    }

    @Test
    fun `RELEASE is INCOMPARABLE`() {
        assertEquals(VersionRelation.INCOMPARABLE, compareDeclaredToManaged("RELEASE", "2.15.3"))
    }

    @Test
    fun `a metaversion is matched case-insensitively`() {
        assertEquals(VersionRelation.INCOMPARABLE, compareDeclaredToManaged("latest", "2.15.3"))
    }
}
