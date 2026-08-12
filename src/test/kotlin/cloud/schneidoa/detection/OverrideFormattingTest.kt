package cloud.schneidoa.detection

import cloud.schneidoa.resolver.BomVersionResolver
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jetbrains.idea.maven.dom.MavenDomUtil
import org.jetbrains.idea.maven.dom.model.MavenDomProjectModel
import java.io.File

class OverrideFormattingTest : BasePlatformTestCase() {

    private fun localRepositoryDir(): File {
        val fixtureUrl = javaClass.classLoader.getResource("fixtures/local-repo")
            ?: error("Test fixture local-repo not found on classpath")
        return File(fixtureUrl.toURI())
    }

    fun `test declaredToManaged formats a confirmed override as declared arrow managed`() {
        val model = configureModuleImportingAcmeBom(
            """
            <dependency>
                <groupId>com.fasterxml.jackson.core</groupId>
                <artifactId>jackson-databind</artifactId>
                <version>2.15.4</version>
            </dependency>
            """.trimIndent()
        )
        val detector = OverrideDetector(BomVersionResolver(localRepositoryDir()))

        val confirmed = detector.detect(model, project).single() as DetectedOverride.Confirmed

        assertEquals("2.15.4 → 2.15.3", declaredToManaged(confirmed))
    }

    fun `test declaredToManaged formats an inconclusive override with a question mark`() {
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
                            <artifactId>does-not-exist-bom</artifactId>
                            <version>9.9.9</version>
                            <type>pom</type>
                            <scope>import</scope>
                        </dependency>
                        <dependency>
                            <groupId>com.fasterxml.jackson.core</groupId>
                            <artifactId>jackson-databind</artifactId>
                            <version>2.15.4</version>
                        </dependency>
                    </dependencies>
                </dependencyManagement>
            </project>
            """.trimIndent()
        )
        val model = MavenDomUtil.getMavenDomProjectModel(project, file.virtualFile)!!
        val detector = OverrideDetector(BomVersionResolver(localRepositoryDir()))

        val inconclusive = detector.detect(model, project).single() as DetectedOverride.Inconclusive

        assertEquals("2.15.4 → ?", declaredToManaged(inconclusive))
    }

    fun `test verdict of an override matching the BOM is REDUNDANT`() {
        val confirmed = detectSingleAgainstAcmeBom("2.15.3")

        assertEquals(OverrideVerdict.REDUNDANT, verdictOf(confirmed))
        assertEquals("Redundant", verdictLabel(confirmed))
        assertTrue(verdictExplanation(confirmed).contains("can be removed"))
    }

    fun `test verdict of an override above the BOM is AHEAD_OF_BOM`() {
        val confirmed = detectSingleAgainstAcmeBom("2.15.4")

        assertEquals(OverrideVerdict.AHEAD_OF_BOM, verdictOf(confirmed))
        assertEquals("Ahead of BOM", verdictLabel(confirmed))
        assertTrue(verdictExplanation(confirmed).contains("still taking effect"))
    }

    fun `test verdict of an override below the BOM is BEHIND_BOM`() {
        val confirmed = detectSingleAgainstAcmeBom("2.15.2")

        assertEquals(OverrideVerdict.BEHIND_BOM, verdictOf(confirmed))
        assertEquals("Behind BOM", verdictLabel(confirmed))
        assertTrue(verdictExplanation(confirmed).contains("below"))
    }

    fun `test verdict of an unresolvable property version is NOT_COMPARABLE`() {
        val confirmed = detectSingleAgainstAcmeBom("${'$'}{no.such.property}")

        assertEquals(OverrideVerdict.NOT_COMPARABLE, verdictOf(confirmed))
        assertEquals("Not comparable", verdictLabel(confirmed))
    }

    fun `test verdict explanation for a not-comparable override does not suggest removal`() {
        val confirmed = detectSingleAgainstAcmeBom("${'$'}{no.such.property}")

        val explanation = verdictExplanation(confirmed)

        assertFalse(explanation.contains("can be removed"))
        assertFalse(explanation.contains("remove"))
    }

    fun `test verdict label for an inconclusive override names the unchecked BOM count`() {
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
                            <artifactId>does-not-exist-bom</artifactId>
                            <version>9.9.9</version>
                            <type>pom</type>
                            <scope>import</scope>
                        </dependency>
                        <dependency>
                            <groupId>com.fasterxml.jackson.core</groupId>
                            <artifactId>jackson-databind</artifactId>
                            <version>2.15.4</version>
                        </dependency>
                    </dependencies>
                </dependencyManagement>
            </project>
            """.trimIndent()
        )
        val model = MavenDomUtil.getMavenDomProjectModel(project, file.virtualFile)!!
        val detector = OverrideDetector(BomVersionResolver(localRepositoryDir()))

        val inconclusive = detector.detect(model, project).single()

        assertEquals(OverrideVerdict.INCONCLUSIVE, verdictOf(inconclusive))
        assertEquals("Inconclusive (1 BOM unchecked)", verdictLabel(inconclusive))
    }

    fun `test verdict label for an inconclusive override with two unchecked BOMs uses plural wording`() {
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
                            <artifactId>does-not-exist-bom-one</artifactId>
                            <version>9.9.9</version>
                            <type>pom</type>
                            <scope>import</scope>
                        </dependency>
                        <dependency>
                            <groupId>com.example</groupId>
                            <artifactId>does-not-exist-bom-two</artifactId>
                            <version>9.9.9</version>
                            <type>pom</type>
                            <scope>import</scope>
                        </dependency>
                        <dependency>
                            <groupId>com.fasterxml.jackson.core</groupId>
                            <artifactId>jackson-databind</artifactId>
                            <version>2.15.4</version>
                        </dependency>
                    </dependencies>
                </dependencyManagement>
            </project>
            """.trimIndent()
        )
        val model = MavenDomUtil.getMavenDomProjectModel(project, file.virtualFile)!!
        val detector = OverrideDetector(BomVersionResolver(localRepositoryDir()))

        val inconclusive = detector.detect(model, project).single() as DetectedOverride.Inconclusive

        assertEquals(2, inconclusive.uncheckedBoms.size)
        assertEquals("Inconclusive (2 BOMs unchecked)", verdictLabel(inconclusive))
    }

    fun `test verdict explanation for an inconclusive override does not suggest removal`() {
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
                            <artifactId>does-not-exist-bom</artifactId>
                            <version>9.9.9</version>
                            <type>pom</type>
                            <scope>import</scope>
                        </dependency>
                        <dependency>
                            <groupId>com.fasterxml.jackson.core</groupId>
                            <artifactId>jackson-databind</artifactId>
                            <version>2.15.4</version>
                        </dependency>
                    </dependencies>
                </dependencyManagement>
            </project>
            """.trimIndent()
        )
        val model = MavenDomUtil.getMavenDomProjectModel(project, file.virtualFile)!!
        val detector = OverrideDetector(BomVersionResolver(localRepositoryDir()))

        val inconclusive = detector.detect(model, project).single()

        val explanation = verdictExplanation(inconclusive)

        assertFalse(explanation.contains("can be removed"))
        assertFalse(explanation.contains("remove"))
    }

    private fun detectSingleAgainstAcmeBom(declaredVersion: String): DetectedOverride {
        val model = configureModuleImportingAcmeBom(
            """
            <dependency>
                <groupId>com.fasterxml.jackson.core</groupId>
                <artifactId>jackson-databind</artifactId>
                <version>$declaredVersion</version>
            </dependency>
            """.trimIndent()
        )
        return OverrideDetector(BomVersionResolver(localRepositoryDir())).detect(model, project).single()
    }

    fun `test managedByChain shows only the BOM when declared directly in the module`() {
        val model = configureModuleImportingAcmeBom(
            """
            <dependency>
                <groupId>com.fasterxml.jackson.core</groupId>
                <artifactId>jackson-databind</artifactId>
                <version>2.15.4</version>
            </dependency>
            """.trimIndent()
        )
        val detector = OverrideDetector(BomVersionResolver(localRepositoryDir()))

        val confirmed = detector.detect(model, project).single() as DetectedOverride.Confirmed

        assertEquals("com.example:acme-bom:1.0.0", managedByChain(confirmed))
    }

    fun `test managedByChain joins parent-chain hops before the BOM`() {
        myFixture.addFileToProject(
            "parent/pom.xml",
            """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.example</groupId>
                <artifactId>parent</artifactId>
                <version>1.0.0</version>
                <packaging>pom</packaging>

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
        val childFile = myFixture.addFileToProject(
            "child/pom.xml",
            """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <parent>
                    <groupId>com.example</groupId>
                    <artifactId>parent</artifactId>
                    <version>1.0.0</version>
                    <relativePath>../parent/pom.xml</relativePath>
                </parent>
                <artifactId>child</artifactId>

                <dependencyManagement>
                    <dependencies>
                        <dependency>
                            <groupId>com.fasterxml.jackson.core</groupId>
                            <artifactId>jackson-databind</artifactId>
                            <version>2.15.4</version>
                        </dependency>
                    </dependencies>
                </dependencyManagement>
            </project>
            """.trimIndent()
        )
        val childModel = MavenDomUtil.getMavenDomProjectModel(project, childFile.virtualFile)!!
        val detector = OverrideDetector(BomVersionResolver(localRepositoryDir()))

        val confirmed = detector.detect(childModel, project).single() as DetectedOverride.Confirmed

        assertEquals("parent → com.example:acme-bom:1.0.0", managedByChain(confirmed))
    }

    fun `test managedByChain is empty for an inconclusive override`() {
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
                            <artifactId>does-not-exist-bom</artifactId>
                            <version>9.9.9</version>
                            <type>pom</type>
                            <scope>import</scope>
                        </dependency>
                        <dependency>
                            <groupId>com.fasterxml.jackson.core</groupId>
                            <artifactId>jackson-databind</artifactId>
                            <version>2.15.4</version>
                        </dependency>
                    </dependencies>
                </dependencyManagement>
            </project>
            """.trimIndent()
        )
        val model = MavenDomUtil.getMavenDomProjectModel(project, file.virtualFile)!!
        val detector = OverrideDetector(BomVersionResolver(localRepositoryDir()))

        val inconclusive = detector.detect(model, project).single() as DetectedOverride.Inconclusive

        assertEquals("", managedByChain(inconclusive))
    }

    private fun configureModuleImportingAcmeBom(dependencyManagementEntries: String): MavenDomProjectModel {
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
                        $dependencyManagementEntries
                    </dependencies>
                </dependencyManagement>
            </project>
            """.trimIndent()
        )
        return MavenDomUtil.getMavenDomProjectModel(project, file.virtualFile)!!
    }
}
