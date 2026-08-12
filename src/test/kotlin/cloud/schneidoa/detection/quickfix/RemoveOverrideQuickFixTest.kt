package cloud.schneidoa.detection.quickfix

import cloud.schneidoa.detection.OverrideDetector
import cloud.schneidoa.detection.OverrideInspection
import cloud.schneidoa.resolver.BomVersionResolver
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

class RemoveOverrideQuickFixTest : BasePlatformTestCase() {

    private fun testInspection(): OverrideInspection {
        val fixtureUrl = javaClass.classLoader.getResource("fixtures/local-repo")
            ?: error("Test fixture local-repo not found on classpath")
        val localRepositoryDir = File(fixtureUrl.toURI())
        return OverrideInspection { OverrideDetector(BomVersionResolver(localRepositoryDir)) }
    }

    fun `test removes the dependency block and leaves the rest of the file intact`() {
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
                            <version><caret>2.15.3</version>
                        </dependency>
                    </dependencies>
                </dependencyManagement>
            </project>
            """.trimIndent()
        )
        myFixture.doHighlighting()

        val fix = myFixture.findSingleIntention("Remove dependency override")
        myFixture.launchAction(fix)

        val resultText = myFixture.file.text
        assertFalse(resultText.contains("jackson-databind"))
        assertTrue(resultText.contains("acme-bom"))
    }

    fun `test also removes a preceding reason comment`() {
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
                        <!-- CVE-2024-12345 -->
                        <dependency>
                            <groupId>com.fasterxml.jackson.core</groupId>
                            <artifactId>jackson-databind</artifactId>
                            <version><caret>2.15.3</version>
                        </dependency>
                    </dependencies>
                </dependencyManagement>
            </project>
            """.trimIndent()
        )
        myFixture.doHighlighting()

        val fix = myFixture.findSingleIntention("Remove dependency override")
        myFixture.launchAction(fix)

        val resultText = myFixture.file.text
        assertFalse(resultText.contains("jackson-databind"))
        assertFalse(resultText.contains("CVE-2024-12345"))
        assertTrue(resultText.contains("acme-bom"))
    }
}
