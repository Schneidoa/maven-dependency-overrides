package cloud.schneidoa.detection.quickfix

import cloud.schneidoa.detection.OverrideDetector
import cloud.schneidoa.detection.OverrideInspection
import cloud.schneidoa.detection.SUPPRESS_MARKER
import cloud.schneidoa.resolver.BomVersionResolver
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

class SuppressOverrideQuickFixTest : BasePlatformTestCase() {

    private fun testInspection(): OverrideInspection {
        val fixtureUrl = javaClass.classLoader.getResource("fixtures/local-repo")
            ?: error("Test fixture local-repo not found on classpath")
        val localRepositoryDir = File(fixtureUrl.toURI())
        return OverrideInspection { OverrideDetector(BomVersionResolver(localRepositoryDir)) }
    }

    fun `test inserts the suppress marker when there is no existing comment`() {
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

        val fix = myFixture.findSingleIntention("Suppress this override warning")
        myFixture.launchAction(fix)

        assertTrue(myFixture.file.text.contains(SUPPRESS_MARKER))
        // The dependency itself must still be there — suppress hides the
        // warning, it never removes anything.
        assertTrue(myFixture.file.text.contains("jackson-databind"))
    }

    fun `test replaces an existing reason comment instead of stacking a second one`() {
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

        val fix = myFixture.findSingleIntention("Suppress this override warning")
        myFixture.launchAction(fix)

        val resultText = myFixture.file.text
        assertTrue(resultText.contains(SUPPRESS_MARKER))
        assertFalse("The old reason comment must be gone, not just supplemented", resultText.contains("CVE-2024-12345"))
    }
}
