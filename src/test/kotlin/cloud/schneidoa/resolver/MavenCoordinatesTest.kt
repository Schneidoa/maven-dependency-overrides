package cloud.schneidoa.resolver

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MavenCoordinatesTest {

    @Test
    fun `a fully literal coordinate is concrete`() {
        assertTrue(Gav("com.example", "acme-bom", "1.0.0").isConcrete())
    }

    @Test
    fun `an unresolved property in the version makes a coordinate unfetchable`() {
        assertFalse(Gav("com.example", "acme-bom", "\${acme.version}").isConcrete())
    }

    @Test
    fun `an unresolved property anywhere in the coordinate counts`() {
        assertFalse(Gav("\${acme.group}", "acme-bom", "1.0.0").isConcrete())
        assertFalse(Gav("com.example", "\${acme.artifact}", "1.0.0").isConcrete())
    }

    @Test
    fun `a blank segment is not concrete`() {
        assertFalse(Gav("com.example", "acme-bom", "").isConcrete())
    }
}
