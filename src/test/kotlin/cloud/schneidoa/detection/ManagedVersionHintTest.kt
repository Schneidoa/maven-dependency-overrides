package cloud.schneidoa.detection

import cloud.schneidoa.resolver.BomVersionResolver
import cloud.schneidoa.resolver.Ga
import cloud.schneidoa.resolver.ManagedVersionLookup
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jetbrains.idea.maven.dom.MavenDomUtil
import org.jetbrains.idea.maven.dom.model.MavenDomProjectModel
import java.io.File

class ManagedVersionHintTest : BasePlatformTestCase() {

    private fun localRepositoryDir(): File {
        val fixtureUrl = javaClass.classLoader.getResource("fixtures/local-repo")
            ?: error("Test fixture local-repo not found on classpath")
        return File(fixtureUrl.toURI())
    }

    private fun configureModuleImportingAcmeBom(): MavenDomProjectModel {
        val file = myFixture.configureByText(
            "pom.xml",
            """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.example</groupId>
                <artifactId>test-module</artifactId>
                <version>1.0.0</version>

                <dependencyManagement>
                    <dependencies>
                        <dependency>
                            <groupId>com.example</groupId>
                            <artifactId>acme-bom</artifactId>
                            <version>1.0.0</version>
                            <type>pom</type>
                            <scope>import</scope>
                        </dependency>
                    </dependencies>
                </dependencyManagement>
            </project>
            """.trimIndent()
        )
        return MavenDomUtil.getMavenDomProjectModel(project, file.virtualFile)!!
    }

    fun `test finds the version a BOM manages a GA at, even when it is not yet declared in the module`() {
        val model = configureModuleImportingAcmeBom()
        val hint = ManagedVersionHint(BomVersionResolver(localRepositoryDir()))

        val result = hint.lookup(model, project, Ga("com.fasterxml.jackson.core", "jackson-databind"))

        assertTrue(result is ManagedVersionLookup.Found)
        assertEquals("2.15.3", (result as ManagedVersionLookup.Found).version)
    }

    fun `test reports not found when no BOM in the chain manages the GA`() {
        val model = configureModuleImportingAcmeBom()
        val hint = ManagedVersionHint(BomVersionResolver(localRepositoryDir()))

        val result = hint.lookup(model, project, Ga("org.example", "totally-unmanaged"))

        assertTrue(result is ManagedVersionLookup.NotFound)
    }

    fun `test catalog returns everything the module's BOM chain manages`() {
        val model = configureModuleImportingAcmeBom()
        val hint = ManagedVersionHint(BomVersionResolver(localRepositoryDir()))

        val catalog = hint.catalog(model, project)

        assertEquals("2.15.3", catalog.versions[Ga("com.fasterxml.jackson.core", "jackson-databind")])
        assertEquals("4.2.0", catalog.versions[Ga("com.example", "widget-core")])
        assertTrue(catalog.uncheckedBoms.isEmpty())
    }
}
