package cloud.schneidoa.detection

import cloud.schneidoa.resolver.BomVersionResolver
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

class ProjectOverrideScannerTest : BasePlatformTestCase() {

    private fun scanner(): ProjectOverrideScanner {
        val fixtureUrl = javaClass.classLoader.getResource("fixtures/local-repo")
            ?: error("Test fixture local-repo not found on classpath")
        val localRepositoryDir = File(fixtureUrl.toURI())
        return ProjectOverrideScanner { OverrideDetector(BomVersionResolver(localRepositoryDir)) }
    }

    fun `test finds overrides across multiple modules, tagged with their own module`() {
        myFixture.addFileToProject(
            "module-a/pom.xml",
            """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.example</groupId>
                <artifactId>module-a</artifactId>
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
        myFixture.addFileToProject(
            "module-b/pom.xml",
            """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.example</groupId>
                <artifactId>module-b</artifactId>
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
                        <dependency>
                            <groupId>com.example</groupId>
                            <artifactId>widget-core</artifactId>
                            <version>9.9.9</version>
                        </dependency>
                    </dependencies>
                </dependencyManagement>
            </project>
            """.trimIndent()
        )

        val entries = scanner().scan(project)

        assertEquals(2, entries.size)
        val moduleA = entries.single { it.moduleLabel == "module-a" }
        val moduleB = entries.single { it.moduleLabel == "module-b" }
        assertEquals("jackson-databind", moduleA.override.candidate.ga.artifactId)
        assertEquals("widget-core", moduleB.override.candidate.ga.artifactId)
    }

    fun `test a module without any override contributes nothing`() {
        myFixture.addFileToProject(
            "clean-module/pom.xml",
            """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.example</groupId>
                <artifactId>clean-module</artifactId>
                <version>1.0.0</version>
            </project>
            """.trimIndent()
        )

        assertTrue(scanner().scan(project).isEmpty())
    }

    fun `test excludes a suppressed override`() {
        myFixture.addFileToProject(
            "module-a/pom.xml",
            """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.example</groupId>
                <artifactId>module-a</artifactId>
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
                        <!-- maven-dependency-overrides: suppress -->
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

        assertTrue(scanner().scan(project).isEmpty())
    }

    fun `test skips a pom-xml-named file that is not a valid Maven model without failing the whole scan`() {
        myFixture.addFileToProject("not-really-a-module/pom.xml", "<foo></foo>")
        myFixture.addFileToProject(
            "module-a/pom.xml",
            """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.example</groupId>
                <artifactId>module-a</artifactId>
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

        val entries = scanner().scan(project)

        assertEquals(1, entries.size)
        assertEquals("module-a", entries.single().moduleLabel)
    }

    fun `test results are ordered by module then by dependency`() {
        myFixture.addFileToProject(
            "zeta/pom.xml",
            """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.example</groupId>
                <artifactId>zeta</artifactId>
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
        myFixture.addFileToProject(
            "alpha/pom.xml",
            """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.example</groupId>
                <artifactId>alpha</artifactId>
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
                        <dependency>
                            <groupId>com.example</groupId>
                            <artifactId>widget-core</artifactId>
                            <version>9.9.9</version>
                        </dependency>
                    </dependencies>
                </dependencyManagement>
            </project>
            """.trimIndent()
        )

        val entries = scanner().scan(project)

        assertEquals(listOf("alpha", "zeta"), entries.map { it.moduleLabel })
    }
}
