package cloud.schneidoa.detection

import cloud.schneidoa.resolver.BomVersionResolver
import cloud.schneidoa.resolver.Gav
import cloud.schneidoa.resolver.VersionRelation
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jetbrains.idea.maven.dom.MavenDomUtil
import org.jetbrains.idea.maven.dom.model.MavenDomProjectModel
import java.io.File

class OverrideDetectorTest : BasePlatformTestCase() {

    private fun localRepositoryDir(): File {
        val fixtureUrl = javaClass.classLoader.getResource("fixtures/local-repo")
            ?: error("Test fixture local-repo not found on classpath")
        return File(fixtureUrl.toURI())
    }

    fun `test confirms an override that diverges from the BOM managed version`() {
        val model = configureModuleImportingAcmeBom(
            """
            <!-- CVE-2024-12345 -->
            <dependency>
                <groupId>com.fasterxml.jackson.core</groupId>
                <artifactId>jackson-databind</artifactId>
                <version>2.15.4</version>
            </dependency>
            """.trimIndent()
        )
        val detector = OverrideDetector(BomVersionResolver(localRepositoryDir()))

        val results = detector.detect(model, project)

        assertEquals(1, results.size)
        val confirmed = results.single() as DetectedOverride.Confirmed
        assertEquals("jackson-databind", confirmed.candidate.ga.artifactId)
        assertEquals("2.15.3", confirmed.bomVersion)
        assertEquals(Gav("com.example", "acme-bom", "1.0.0"), confirmed.declaredInBom)
        assertEquals("CVE-2024-12345", confirmed.candidate.reason)
        assertEquals(emptyList<String>(), confirmed.managedByChain)
    }

    fun `test reports a redundant candidate whose version already matches the BOM`() {
        val model = configureModuleImportingAcmeBom(
            """
            <dependency>
                <groupId>com.fasterxml.jackson.core</groupId>
                <artifactId>jackson-databind</artifactId>
                <version>2.15.3</version>
            </dependency>
            """.trimIndent()
        )
        val detector = OverrideDetector(BomVersionResolver(localRepositoryDir()))

        val confirmed = detector.detect(model, project).single() as DetectedOverride.Confirmed

        assertEquals(VersionRelation.SAME, confirmed.relation)
        assertEquals("2.15.3", confirmed.bomVersion)
    }

    fun `test a candidate above the BOM version is reported as NEWER`() {
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

        assertEquals(VersionRelation.NEWER, confirmed.relation)
    }

    fun `test a candidate below the BOM version is reported as OLDER`() {
        val model = configureModuleImportingAcmeBom(
            """
            <dependency>
                <groupId>com.fasterxml.jackson.core</groupId>
                <artifactId>jackson-databind</artifactId>
                <version>2.15.2</version>
            </dependency>
            """.trimIndent()
        )
        val detector = OverrideDetector(BomVersionResolver(localRepositoryDir()))

        val confirmed = detector.detect(model, project).single() as DetectedOverride.Confirmed

        assertEquals(VersionRelation.OLDER, confirmed.relation)
    }

    fun `test a candidate pinned to an unresolvable property is reported as INCOMPARABLE`() {
        val model = configureModuleImportingAcmeBom(
            """
            <dependency>
                <groupId>com.fasterxml.jackson.core</groupId>
                <artifactId>jackson-databind</artifactId>
                <version>${'$'}{no.such.property}</version>
            </dependency>
            """.trimIndent()
        )
        val detector = OverrideDetector(BomVersionResolver(localRepositoryDir()))

        val confirmed = detector.detect(model, project).single() as DetectedOverride.Confirmed

        assertEquals(VersionRelation.INCOMPARABLE, confirmed.relation)
    }

    fun `test excludes a suppressed candidate even if it diverges from the BOM`() {
        val model = configureModuleImportingAcmeBom(
            """
            <!-- maven-dependency-overrides: suppress -->
            <dependency>
                <groupId>com.fasterxml.jackson.core</groupId>
                <artifactId>jackson-databind</artifactId>
                <version>2.15.4</version>
            </dependency>
            """.trimIndent()
        )
        val detector = OverrideDetector(BomVersionResolver(localRepositoryDir()))

        assertTrue(detector.detect(model, project).isEmpty())
    }

    fun `test drops a candidate that is not managed by any imported BOM`() {
        val model = configureModuleImportingAcmeBom(
            """
            <dependency>
                <groupId>org.example</groupId>
                <artifactId>totally-unmanaged</artifactId>
                <version>9.9.9</version>
            </dependency>
            """.trimIndent()
        )
        val detector = OverrideDetector(BomVersionResolver(localRepositoryDir()))

        assertTrue(detector.detect(model, project).isEmpty())
    }

    fun `test reports inconclusive when an imported BOM cannot be resolved locally`() {
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

        val results = detector.detect(model, project)

        assertEquals(1, results.size)
        val inconclusive = results.single() as DetectedOverride.Inconclusive
        assertEquals("jackson-databind", inconclusive.candidate.ga.artifactId)
        assertEquals(listOf(Gav("com.example", "does-not-exist-bom", "9.9.9")), inconclusive.uncheckedBoms)
    }

    fun `test reports inconclusive rather than a confident match when a higher-precedence BOM was unresolvable`() {
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
                            <groupId>com.example</groupId>
                            <artifactId>acme-bom</artifactId>
                            <version>1.0.0</version>
                            <type>pom</type>
                            <scope>import</scope>
                        </dependency>
                        <dependency>
                            <groupId>com.fasterxml.jackson.core</groupId>
                            <artifactId>jackson-databind</artifactId>
                            <version>2.15.3</version>
                        </dependency>
                    </dependencies>
                </dependencyManagement>
            </project>
            """.trimIndent()
        )
        val model = MavenDomUtil.getMavenDomProjectModel(project, file.virtualFile)!!
        val detector = OverrideDetector(BomVersionResolver(localRepositoryDir()))

        val results = detector.detect(model, project)

        assertEquals(1, results.size)
        val inconclusive = results.single() as DetectedOverride.Inconclusive
        assertEquals("jackson-databind", inconclusive.candidate.ga.artifactId)
        assertEquals(listOf(Gav("com.example", "does-not-exist-bom", "9.9.9")), inconclusive.uncheckedBoms)
    }

    fun `test reports only the confirmed override when a suppressed divergence is present for another artifact`() {
        val model = configureModuleImportingAcmeBom(
            """
            <!-- maven-dependency-overrides: suppress -->
            <dependency>
                <groupId>com.example</groupId>
                <artifactId>widget-core</artifactId>
                <version>9.9.9</version>
            </dependency>
            <!-- CVE-2024-99999 -->
            <dependency>
                <groupId>com.fasterxml.jackson.core</groupId>
                <artifactId>jackson-databind</artifactId>
                <version>2.15.4</version>
            </dependency>
            """.trimIndent()
        )
        val detector = OverrideDetector(BomVersionResolver(localRepositoryDir()))

        val results = detector.detect(model, project)

        assertEquals(1, results.size)
        val confirmed = results.single() as DetectedOverride.Confirmed
        assertEquals("jackson-databind", confirmed.candidate.ga.artifactId)
    }

    fun `test confirms an override in a child module against a BOM imported by its parent`() {
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
                        <!-- CVE-2024-99999 -->
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

        val results = detector.detect(childModel, project)

        assertEquals(1, results.size)
        val confirmed = results.single() as DetectedOverride.Confirmed
        assertEquals("jackson-databind", confirmed.candidate.ga.artifactId)
        assertEquals("2.15.3", confirmed.bomVersion)
        assertEquals(Gav("com.example", "acme-bom", "1.0.0"), confirmed.declaredInBom)
        assertEquals("CVE-2024-99999", confirmed.candidate.reason)
        assertEquals(listOf("parent"), confirmed.managedByChain)
    }

    fun `test reports inconclusive when the parent chain could not be fully walked`() {
        val file = myFixture.configureByText(
            "pom.xml",
            """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <parent>
                    <groupId>com.example</groupId>
                    <artifactId>absent-parent</artifactId>
                    <version>7.7.7</version>
                    <relativePath/>
                </parent>
                <artifactId>consumer</artifactId>
                <dependencyManagement>
                    <dependencies>
                        <dependency>
                            <groupId>com.fasterxml.jackson.core</groupId>
                            <artifactId>jackson-databind</artifactId>
                            <version>2.15.0</version>
                        </dependency>
                    </dependencies>
                </dependencyManagement>
            </project>
            """.trimIndent()
        )
        val model = MavenDomUtil.getMavenDomProjectModel(project, file.virtualFile)!!

        val detected = OverrideDetector(BomVersionResolver(localRepositoryDir())).detect(model, project)

        assertEquals(1, detected.size)
        val result = detected.single()
        assertTrue("expected Inconclusive, got $result", result is DetectedOverride.Inconclusive)
        assertEquals(
            listOf(Gav("com.example", "absent-parent", "7.7.7")),
            (result as DetectedOverride.Inconclusive).uncheckedBoms
        )
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
