package cloud.schneidoa.detection

import cloud.schneidoa.resolver.BomVersionResolver
import com.intellij.codeInspection.LocalInspectionEP
import com.intellij.codeInspection.ex.LocalInspectionToolWrapper
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

class OverrideInspectionTest : BasePlatformTestCase() {

    private fun testInspection(): OverrideInspection {
        val fixtureUrl = javaClass.classLoader.getResource("fixtures/local-repo")
            ?: error("Test fixture local-repo not found on classpath")
        val localRepositoryDir = File(fixtureUrl.toURI())
        return OverrideInspection { OverrideDetector(BomVersionResolver(localRepositoryDir)) }
    }

    fun `test registers a weak warning naming the managing BOM and its version`() {
        myFixture.enableInspections(testInspection())
        myFixture.configureByText(
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

        val highlights = myFixture.doHighlighting()
        val overrideHighlight = highlights.singleOrNull { it.description?.contains("acme-bom") == true }

        assertNotNull("Expected a highlight mentioning the BOM that manages this dependency", overrideHighlight)
        assertTrue(overrideHighlight!!.description.contains("2.15.3"))
    }

    fun `test confirmed override message includes the full parent chain to the managing BOM`() {
        myFixture.enableInspections(testInspection())
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
                            <version>2.15.3</version>
                        </dependency>
                    </dependencies>
                </dependencyManagement>
            </project>
            """.trimIndent()
        )
        myFixture.configureFromExistingVirtualFile(childFile.virtualFile)

        val highlights = myFixture.doHighlighting()
        val overrideHighlight = highlights.singleOrNull { it.description?.contains("acme-bom") == true }

        assertNotNull("Expected a highlight for the confirmed override", overrideHighlight)
        assertTrue(
            "Expected the message to include the full parent chain, was: ${overrideHighlight!!.description}",
            overrideHighlight.description.contains("parent → com.example:acme-bom:1.0.0")
        )
    }

    fun `test warns that an override matching the BOM version is redundant`() {
        myFixture.enableInspections(testInspection())
        myFixture.configureByText(
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

        val highlights = myFixture.doHighlighting()
        val overrideHighlight = highlights.singleOrNull { it.description?.contains("acme-bom") == true }

        assertNotNull("Expected a redundant-override highlight", overrideHighlight)
        assertTrue(overrideHighlight!!.description.contains("can be removed"))
    }

    fun `test does not warn when the override is above the BOM version`() {
        myFixture.enableInspections(testInspection())
        myFixture.configureByText(
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

        val highlights = myFixture.doHighlighting()

        assertTrue(highlights.none { it.description?.contains("acme-bom") == true })
    }

    fun `test does not warn when the override is not comparable to the BOM version`() {
        myFixture.enableInspections(testInspection())
        myFixture.configureByText(
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
                        <dependency>
                            <groupId>com.fasterxml.jackson.core</groupId>
                            <artifactId>jackson-databind</artifactId>
                            <version>${'$'}{no.such.property}</version>
                        </dependency>
                    </dependencies>
                </dependencyManagement>
            </project>
            """.trimIndent()
        )

        val highlights = myFixture.doHighlighting()

        assertTrue(highlights.none { it.description?.contains("acme-bom") == true })
    }

    /**
     * The counterpart to the tool window now listing these: UNMANAGED is visible in the
     * inventory and silent in the editor, the same asymmetry AHEAD_OF_BOM has. A warning on
     * every hand-pinned transitive version would be loudest in exactly the projects that have
     * the most of them - and the editor has no BOM-based claim to make about such a pin anyway.
     * Asserts on "BOM" rather than the BOM's name because the unmanaged explanation names no
     * single BOM; a regression here would surface its "None of the N BOMs" wording.
     */
    fun `test does not warn about an entry no BOM in the chain manages`() {
        myFixture.enableInspections(testInspection())
        myFixture.configureByText(
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
                        <dependency>
                            <groupId>org.example</groupId>
                            <artifactId>totally-unmanaged</artifactId>
                            <version>9.9.9</version>
                        </dependency>
                    </dependencies>
                </dependencyManagement>
            </project>
            """.trimIndent()
        )

        val highlights = myFixture.doHighlighting()

        assertTrue(highlights.none { it.description?.contains("BOM") == true })
        assertEmpty(myFixture.getAllQuickFixes())
    }

    fun `test an override below the BOM offers remove so the newer BOM version applies`() {
        myFixture.enableInspections(testInspection())
        myFixture.configureByText(
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
                        <dependency>
                            <groupId>com.fasterxml.jackson.core</groupId>
                            <artifactId>jackson-databind</artifactId>
                            <version>2.15.2</version>
                        </dependency>
                    </dependencies>
                </dependencyManagement>
            </project>
            """.trimIndent()
        )

        val highlights = myFixture.doHighlighting()
        val overrideHighlight = highlights.singleOrNull { it.description?.contains("acme-bom") == true }

        assertNotNull("Expected a behind-BOM highlight", overrideHighlight)
        assertTrue(overrideHighlight!!.description.contains("lets the newer BOM version apply"))
        assertEquals(
            setOf("Remove dependency override", "Suppress this override warning"),
            myFixture.getAllQuickFixes().map { it.familyName }.toSet()
        )
    }

    fun `test only offers suppress, not remove, when a higher precedence BOM is unresolvable`() {
        myFixture.enableInspections(testInspection())
        myFixture.configureByText(
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
                            <groupId>com.example</groupId>
                            <artifactId>totally-unmanaged-artifact</artifactId>
                            <version>9.9.9</version>
                        </dependency>
                    </dependencies>
                </dependencyManagement>
            </project>
            """.trimIndent()
        )

        val highlights = myFixture.doHighlighting()
        val overrideHighlight = highlights.singleOrNull { it.description?.contains("Cannot confirm") == true }

        assertNotNull("Expected an inconclusive-style highlight", overrideHighlight)
        val familyNames = myFixture.getAllQuickFixes().map { it.familyName }
        assertEquals(listOf("Suppress this override warning"), familyNames)
    }

    fun `test offers both remove and suppress together on a redundant override`() {
        myFixture.enableInspections(testInspection())
        myFixture.configureByText(
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

        myFixture.doHighlighting()
        val familyNames = myFixture.getAllQuickFixes().map { it.familyName }.toSet()

        assertEquals(setOf("Remove dependency override", "Suppress this override warning"), familyNames)
    }

    /**
     * The description lives at inspectionDescriptions/<shortName>.html and is found by
     * the shortName declared in plugin.xml, not by the class name - so renaming one
     * without the other loses the description silently, leaving the Settings | Editor |
     * Inspections page blank. Going through the registered extension point rather than
     * `OverrideInspection().loadDescription()` is the point of this test: a bare instance
     * derives its own short name from the class ("Override") and would look up the wrong
     * file entirely.
     */
    fun `test ships a description under the short name registered in plugin xml`() {
        val ep = LocalInspectionEP.LOCAL_INSPECTION.extensionList
            .single { it.implementationClass == OverrideInspection::class.java.name }

        val description = LocalInspectionToolWrapper(ep).loadDescription()

        assertNotNull("No inspectionDescriptions/${ep.getShortName()}.html on the classpath", description)
        assertTrue(description!!.isNotBlank())
    }

    fun `test does nothing on a non-Maven XML file`() {
        myFixture.enableInspections(testInspection())
        myFixture.configureByText("web.xml", "<web-app></web-app>")

        val highlights = myFixture.doHighlighting()

        assertTrue(highlights.none { it.description?.contains("manages this") == true || it.description?.contains("Cannot confirm") == true })
    }
}
