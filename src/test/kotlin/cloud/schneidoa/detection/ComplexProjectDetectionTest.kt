package cloud.schneidoa.detection

import cloud.schneidoa.resolver.BomVersionResolver
import cloud.schneidoa.resolver.Gav
import cloud.schneidoa.resolver.VersionRelation
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jetbrains.idea.maven.dom.MavenDomUtil
import java.io.File

/**
 * The shapes real multi-module builds take that a hand-written parent walk gets wrong. Every
 * case here is something Maven itself resolves differently from reading each POM in isolation.
 */
class ComplexProjectDetectionTest : BasePlatformTestCase() {

    /**
     * The everyday Spring Boot layout: spring-boot-starter-parent as <parent> means most of what
     * is managed sits directly in spring-boot-dependencies' own <dependencyManagement>, 606 of
     * its 652 entries in 4.1.1. Only 46 are imports.
     */
    fun `test confirms an override against a version a parent manages directly`() {
        addPom("parent/pom.xml", PARENT_HEADER, dependencyManagement(MANAGED_DIRECTLY_AT_1))
        val child = addChildPom(dependencyManagement(dependency("org.example", "managed-directly", "2.0.0")))

        val confirmed = detect(child).single() as DetectedOverride.Confirmed

        assertEquals("1.0.0", confirmed.bomVersion)
        assertEquals(VersionRelation.NEWER, confirmed.relation)
        assertEquals(Gav("com.example", "parent", "1.0.0"), confirmed.declaredInBom)
        assertEquals(emptyList<String>(), confirmed.managedByChain)
    }

    fun `test names the whole path to a directly managing grandparent`() {
        addPom("grandparent/pom.xml", header("grandparent"), dependencyManagement(MANAGED_DIRECTLY_AT_1))
        addPom(
            "parent/pom.xml",
            parentRef("grandparent", "../grandparent/pom.xml") + "<artifactId>parent</artifactId>"
        )
        val child = addChildPom(dependencyManagement(dependency("org.example", "managed-directly", "1.0.0")))

        val confirmed = detect(child).single() as DetectedOverride.Confirmed

        assertEquals(VersionRelation.SAME, confirmed.relation)
        assertEquals(Gav("com.example", "grandparent", "1.0.0"), confirmed.declaredInBom)
        assertEquals(listOf("parent"), confirmed.managedByChain)
    }

    /** Maven lets a direct entry beat any import, even one declared lower in the hierarchy. */
    fun `test a parent's direct entry beats a BOM the child imports`() {
        addPom(
            "parent/pom.xml",
            PARENT_HEADER,
            dependencyManagement(dependency("com.fasterxml.jackson.core", "jackson-databind", "9.0.0"))
        )
        val child = addChildPom(
            dependencyManagement(
                ACME_BOM_IMPORT + dependency("com.fasterxml.jackson.core", "jackson-databind", "9.0.0")
            )
        )

        val confirmed = detect(child).single() as DetectedOverride.Confirmed

        assertEquals("9.0.0", confirmed.bomVersion)
        assertEquals(VersionRelation.SAME, confirmed.relation)
    }

    /** No BOM at all, but the parent does manage the artifact, so this still overrides something. */
    fun `test reports an override of a parent entry in a chain without any BOM`() {
        addPom("parent/pom.xml", PARENT_HEADER, dependencyManagement(MANAGED_DIRECTLY_AT_1))
        val child = addChildPom(dependencyManagement(dependency("org.example", "managed-directly", "1.0.0")))

        assertTrue(detect(child).single() is DetectedOverride.Confirmed)
    }

    /**
     * A property declared in the parent, imported in the parent, redefined in the child. Maven
     * interpolates inherited imports in the child's context, so the child gets 2.0.0 of this BOM.
     */
    fun `test a child property redefines the version of a BOM its parent imports`() {
        addPom(
            "parent/pom.xml",
            PARENT_HEADER,
            "<properties><shared.bom.version>1.0.0</shared.bom.version></properties>",
            dependencyManagement(bomImport("legacy-bom", "\${shared.bom.version}"))
        )
        val child = addChildPom(
            "<properties><shared.bom.version>9.9.9</shared.bom.version></properties>",
            dependencyManagement(dependency("org.example", "whatever", "1.0.0"))
        )

        val chain = resolver().resolveBomChain(model(child), project)

        assertEquals(listOf(Gav("com.example", "legacy-bom", "9.9.9")), chain.imports.map { it.bom })
    }

    /** Works without a Maven sync, which is the state a large project sits in for minutes. */
    fun `test resolves a BOM version from a parent property before Maven sync`() {
        addPom(
            "parent/pom.xml",
            PARENT_HEADER,
            "<properties><acme.version>1.0.0</acme.version></properties>"
        )
        val child = addChildPom(
            dependencyManagement(
                bomImport("acme-bom", "\${acme.version}") +
                    dependency("com.fasterxml.jackson.core", "jackson-databind", "2.15.4")
            )
        )

        val confirmed = detect(child).single() as DetectedOverride.Confirmed

        assertEquals("2.15.3", confirmed.bomVersion)
    }

    fun `test resolves a declared version from a parent property before Maven sync`() {
        addPom(
            "parent/pom.xml",
            PARENT_HEADER,
            "<properties><jackson.pin>2.15.4</jackson.pin></properties>"
        )
        val child = addChildPom(
            dependencyManagement(
                ACME_BOM_IMPORT + dependency("com.fasterxml.jackson.core", "jackson-databind", "\${jackson.pin}")
            )
        )

        val confirmed = detect(child).single() as DetectedOverride.Confirmed

        assertEquals("2.15.4", confirmed.candidate.declaredVersion)
        assertEquals(VersionRelation.NEWER, confirmed.relation)
    }

    /**
     * An internal BOM that is a module of the same build, imported at the project's own version.
     * It was never installed, so the local repository cannot answer and the old lookup left every
     * override in every consuming module Inconclusive.
     */
    fun `test resolves a BOM that is a module of the same build`() {
        addPom(
            "platform-bom/pom.xml",
            header("platform-bom"),
            dependencyManagement(dependency("org.example", "internal-lib", "3.0.0"))
        )
        val consumer = addPom(
            "app/pom.xml",
            header("app"),
            dependencyManagement(
                bomImport("platform-bom", "1.0.0") + dependency("org.example", "internal-lib", "3.0.1")
            )
        )
        val reactorResolver = BomVersionResolver(localRepositoryDir(), reactorPoms = ReactorPoms(project))

        val confirmed = OverrideDetector(reactorResolver).detect(model(consumer), project).single()
            as DetectedOverride.Confirmed

        assertEquals("3.0.0", confirmed.bomVersion)
        assertEquals(Gav("com.example", "platform-bom", "1.0.0"), confirmed.declaredInBom)
    }

    /** The CI-friendly shape: the module's coordinates only exist once ${revision} is known. */
    fun `test resolves a same-build BOM versioned through revision`() {
        addPom(
            "pom.xml",
            """
            <groupId>com.example</groupId>
            <artifactId>root</artifactId>
            <version>${'$'}{revision}</version>
            <packaging>pom</packaging>
            <properties><revision>4.2.0</revision></properties>
            """.trimIndent()
        )
        val parentAtRevision = """
            <parent>
                <groupId>com.example</groupId>
                <artifactId>root</artifactId>
                <version>${'$'}{revision}</version>
                <relativePath>../pom.xml</relativePath>
            </parent>
        """.trimIndent()
        addPom(
            "platform-bom/pom.xml",
            parentAtRevision + "<artifactId>platform-bom</artifactId><packaging>pom</packaging>",
            dependencyManagement(dependency("org.example", "internal-lib", "3.0.0"))
        )
        val consumer = addPom(
            "app/pom.xml",
            parentAtRevision + "<artifactId>app</artifactId>",
            dependencyManagement(
                bomImport("platform-bom", "\${project.version}") + dependency("org.example", "internal-lib", "3.0.1")
            )
        )
        val reactorResolver = BomVersionResolver(localRepositoryDir(), reactorPoms = ReactorPoms(project))

        val confirmed = OverrideDetector(reactorResolver).detect(model(consumer), project).single()
            as DetectedOverride.Confirmed

        assertEquals("3.0.0", confirmed.bomVersion)
    }

    fun `test finds an override declared inside a profile`() {
        val pom = addPom(
            "pom.xml",
            header("with-profile"),
            dependencyManagement(ACME_BOM_IMPORT),
            """
            <profiles>
                <profile>
                    <id>hardened</id>
                    ${dependencyManagement(dependency("com.fasterxml.jackson.core", "jackson-databind", "2.15.4"))}
                </profile>
            </profiles>
            """.trimIndent()
        )

        val confirmed = detect(pom).single() as DetectedOverride.Confirmed

        assertEquals("2.15.3", confirmed.bomVersion)
    }

    fun `test a BOM imported by an active profile is part of the chain`() {
        val pom = addPom(
            "pom.xml",
            header("with-profile"),
            dependencyManagement(dependency("com.fasterxml.jackson.core", "jackson-databind", "2.15.4")),
            """
            <profiles>
                <profile>
                    <id>default-bom</id>
                    <activation><activeByDefault>true</activeByDefault></activation>
                    ${dependencyManagement(ACME_BOM_IMPORT)}
                </profile>
            </profiles>
            """.trimIndent()
        )

        assertTrue(detect(pom).single() is DetectedOverride.Confirmed)
    }

    /** Files keep arriving and being edited; the verdict must follow the editor, not the disk. */
    fun `test sees a BOM import that is only in the unsaved document`() {
        val pom = addPom(
            "pom.xml",
            header("edited"),
            dependencyManagement(dependency("com.fasterxml.jackson.core", "jackson-databind", "2.15.4"))
        )
        myFixture.openFileInEditor(pom)
        val editor = myFixture.editor.document
        val anchor = editor.text.indexOf("<dependency>")
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
            editor.insertString(anchor, ACME_BOM_IMPORT)
        }
        com.intellij.psi.PsiDocumentManager.getInstance(project).commitAllDocuments()

        assertTrue(detect(pom).single() is DetectedOverride.Confirmed)
    }

    private fun detect(pom: com.intellij.openapi.vfs.VirtualFile) =
        OverrideDetector(BomVersionResolver(localRepositoryDir())).detect(model(pom), project)

    private fun resolver() = BomChainResolver(localRepositoryDir())

    private fun model(pom: com.intellij.openapi.vfs.VirtualFile) = MavenDomUtil.getMavenDomProjectModel(project, pom)!!

    private fun addChildPom(vararg sections: String) = addPom(
        "child/pom.xml",
        parentRef("parent", "../parent/pom.xml") + "<artifactId>child</artifactId>",
        *sections
    )

    private fun addPom(path: String, vararg sections: String) = myFixture.addFileToProject(
        path,
        """
        <project xmlns="http://maven.apache.org/POM/4.0.0">
            <modelVersion>4.0.0</modelVersion>
            ${sections.joinToString("\n")}
        </project>
        """.trimIndent()
    ).virtualFile

    private fun localRepositoryDir(): File {
        val fixtureUrl = javaClass.classLoader.getResource("fixtures/local-repo")
            ?: error("Test fixture local-repo not found on classpath")
        return File(fixtureUrl.toURI())
    }

    private companion object {
        val MANAGED_DIRECTLY_AT_1 = dependency("org.example", "managed-directly", "1.0.0")
        val ACME_BOM_IMPORT = bomImport("acme-bom", "1.0.0")
        val PARENT_HEADER = header("parent")

        fun header(artifactId: String) = """
            <groupId>com.example</groupId>
            <artifactId>$artifactId</artifactId>
            <version>1.0.0</version>
            <packaging>pom</packaging>
        """.trimIndent()

        fun parentRef(artifactId: String, relativePath: String) = """
            <parent>
                <groupId>com.example</groupId>
                <artifactId>$artifactId</artifactId>
                <version>1.0.0</version>
                <relativePath>$relativePath</relativePath>
            </parent>
        """.trimIndent()

        fun dependencyManagement(entries: String) =
            "<dependencyManagement><dependencies>$entries</dependencies></dependencyManagement>"

        fun dependency(groupId: String, artifactId: String, version: String) =
            "<dependency><groupId>$groupId</groupId><artifactId>$artifactId</artifactId>" +
                "<version>$version</version></dependency>"

        fun bomImport(artifactId: String, version: String) =
            "<dependency><groupId>com.example</groupId><artifactId>$artifactId</artifactId>" +
                "<version>$version</version><type>pom</type><scope>import</scope></dependency>"
    }
}
