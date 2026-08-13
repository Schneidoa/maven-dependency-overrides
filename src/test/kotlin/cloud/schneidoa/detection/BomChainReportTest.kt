package cloud.schneidoa.detection

import cloud.schneidoa.resolver.BomVersionResolver
import cloud.schneidoa.resolver.Ga
import cloud.schneidoa.resolver.Gav
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BomChainReportTest {

    private fun localRepositoryDir(): File {
        val fixtureUrl = javaClass.classLoader.getResource("fixtures/local-repo")
            ?: error("Test fixture local-repo not found on classpath")
        return File(fixtureUrl.toURI())
    }

    private val jacksonDatabind = Ga("com.fasterxml.jackson.core", "jackson-databind")
    private val acmeBom = Gav("com.example", "acme-bom", "1.0.0")
    private val missingBom = Gav("com.example", "does-not-exist-bom", "9.9.9")

    @Test
    fun `a BOM that manages the artifact is reported with its version and marked as winning`() {
        val report = buildBomChainReport(
            ga = jacksonDatabind,
            declaredVersion = "2.15.4",
            moduleLabel = "test-module",
            bomChain = listOf(BomImport(acmeBom, emptyList())),
            resolver = BomVersionResolver(localRepositoryDir())
        )

        val entry = report.entries.single() as BomChainEntry.Manages
        assertEquals(acmeBom, entry.bom)
        assertEquals("2.15.3", entry.version)
        assertTrue(entry.wins)
    }

    @Test
    fun `a BOM that does not manage the artifact is distinguished from one that cannot be resolved`() {
        val report = buildBomChainReport(
            ga = Ga("org.example", "totally-unmanaged"),
            declaredVersion = "9.9.9",
            moduleLabel = "test-module",
            bomChain = listOf(BomImport(acmeBom, emptyList()), BomImport(missingBom, emptyList())),
            resolver = BomVersionResolver(localRepositoryDir())
        )

        assertTrue(report.entries[0] is BomChainEntry.DoesNotManage)
        assertTrue(report.entries[1] is BomChainEntry.Unresolvable)
    }

    @Test
    fun `only the first managing BOM in precedence order wins`() {
        val report = buildBomChainReport(
            ga = jacksonDatabind,
            declaredVersion = "2.15.4",
            moduleLabel = "test-module",
            bomChain = listOf(BomImport(acmeBom, emptyList()), BomImport(acmeBom, listOf("parent"))),
            resolver = BomVersionResolver(localRepositoryDir())
        )

        assertTrue((report.entries[0] as BomChainEntry.Manages).wins)
        assertTrue(!(report.entries[1] as BomChainEntry.Manages).wins)
    }

    @Test
    fun `a managing BOM preceded by an unresolvable BOM does not claim to win`() {
        val report = buildBomChainReport(
            ga = jacksonDatabind,
            declaredVersion = "2.15.4",
            moduleLabel = "test-module",
            bomChain = listOf(BomImport(missingBom, emptyList()), BomImport(acmeBom, emptyList())),
            resolver = BomVersionResolver(localRepositoryDir())
        )

        assertTrue(report.entries[0] is BomChainEntry.Unresolvable)
        val second = report.entries[1] as BomChainEntry.Manages
        assertTrue(
            "A Manages entry preceded by an Unresolvable one must not claim wins - " +
                "the unresolvable BOM has higher precedence and might manage the artifact differently",
            !second.wins
        )
        assertTrue(
            "The entry should record that it was blocked by an earlier unresolvable BOM, " +
                "not just silently lose",
            second.blockedByUnresolvable
        )
    }

    @Test
    fun `an empty BOM chain produces an empty entries list`() {
        val report = buildBomChainReport(
            ga = jacksonDatabind,
            declaredVersion = "2.15.4",
            moduleLabel = "test-module",
            bomChain = emptyList(),
            resolver = BomVersionResolver(localRepositoryDir())
        )

        assertTrue(report.entries.isEmpty())
    }

    @Test
    fun `a report over a fully walked chain records no truncation`() {
        val report = buildBomChainReport(
            ga = jacksonDatabind,
            declaredVersion = "2.15.4",
            moduleLabel = "test-module",
            bomChain = listOf(BomImport(acmeBom, emptyList())),
            resolver = BomVersionResolver(localRepositoryDir())
        )

        assertTrue(report.truncatedAt.isEmpty())
    }

    @Test
    fun `a parent the chain walk could not get past is carried onto the report`() {
        val unreadableParent = Gav("com.example", "absent-parent", "7.7.7")

        val report = buildBomChainReport(
            ga = jacksonDatabind,
            declaredVersion = "2.15.4",
            moduleLabel = "test-module",
            bomChain = listOf(BomImport(acmeBom, emptyList())),
            resolver = BomVersionResolver(localRepositoryDir()),
            truncatedAt = listOf(unreadableParent)
        )

        assertEquals(
            "A truncated parent walk must reach the dialog - otherwise it renders an incomplete " +
                "chain as a complete one, contradicting the Inconclusive row that opened it",
            listOf(unreadableParent),
            report.truncatedAt
        )
    }

    @Test
    fun `an empty chain still reports the truncation that made it empty`() {
        val unreadableParent = Gav("com.example", "absent-parent", "7.7.7")

        val report = buildBomChainReport(
            ga = jacksonDatabind,
            declaredVersion = "2.15.4",
            moduleLabel = "test-module",
            bomChain = emptyList(),
            resolver = BomVersionResolver(localRepositoryDir()),
            truncatedAt = listOf(unreadableParent)
        )

        assertTrue(report.entries.isEmpty())
        assertEquals(
            "This is the case the dialog otherwise renders as \"no BOMs are imported\", which is " +
                "actively misleading when the walk simply stopped before reaching any",
            listOf(unreadableParent),
            report.truncatedAt
        )
    }

    @Test
    fun `moduleLabel is carried through onto the report`() {
        val report = buildBomChainReport(
            ga = jacksonDatabind,
            declaredVersion = "2.15.4",
            moduleLabel = "camperchat-core",
            bomChain = emptyList(),
            resolver = BomVersionResolver(localRepositoryDir())
        )

        assertEquals("camperchat-core", report.moduleLabel)
    }

    @Test
    fun `the parent chain path each BOM was declared via is preserved`() {
        val report = buildBomChainReport(
            ga = jacksonDatabind,
            declaredVersion = "2.15.4",
            moduleLabel = "test-module",
            bomChain = listOf(BomImport(acmeBom, listOf("spring-boot-starter-parent"))),
            resolver = BomVersionResolver(localRepositoryDir())
        )

        assertEquals(listOf("spring-boot-starter-parent"), report.entries.single().declaredVia)
    }
}
