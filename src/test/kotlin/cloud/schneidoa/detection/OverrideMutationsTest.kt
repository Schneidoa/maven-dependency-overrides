package cloud.schneidoa.detection

import cloud.schneidoa.resolver.Ga
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jetbrains.idea.maven.dom.MavenDomUtil
import org.jetbrains.idea.maven.dom.model.MavenDomProjectModel

class OverrideMutationsTest : BasePlatformTestCase() {

    fun `test removeOverride deletes the dependency block and leaves the rest of the file intact`() {
        val model = configurePom(
            """
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
            """.trimIndent()
        )
        val candidate = DependencyManagementScanner.scan(model).single { it.ga.artifactId == "jackson-databind" }

        removeOverride(project, candidate)

        val resultText = myFixture.file.text
        assertFalse(resultText.contains("jackson-databind"))
        assertTrue(resultText.contains("acme-bom"))
    }

    fun `test removeOverride also deletes a preceding reason comment`() {
        val model = configurePom(
            """
            <!-- CVE-2024-12345 -->
            <dependency>
                <groupId>com.fasterxml.jackson.core</groupId>
                <artifactId>jackson-databind</artifactId>
                <version>2.15.4</version>
            </dependency>
            """.trimIndent()
        )
        val candidate = DependencyManagementScanner.scan(model).single()

        removeOverride(project, candidate)

        val resultText = myFixture.file.text
        assertFalse(resultText.contains("jackson-databind"))
        assertFalse(resultText.contains("CVE-2024-12345"))
    }

    fun `test setVersion changes only the version text`() {
        val model = configurePom(
            """
            <dependency>
                <groupId>com.fasterxml.jackson.core</groupId>
                <artifactId>jackson-databind</artifactId>
                <version>2.15.4</version>
            </dependency>
            """.trimIndent()
        )
        val candidate = DependencyManagementScanner.scan(model).single()

        setVersion(project, candidate, "2.18.2")

        val resultText = myFixture.file.text
        assertTrue(resultText.contains("<version>2.18.2</version>"))
        assertFalse(resultText.contains("2.15.4"))
        assertTrue(resultText.contains("jackson-databind"))
    }

    fun `test setReason inserts a comment when there is none yet`() {
        val model = configurePom(
            """
            <dependency>
                <groupId>com.fasterxml.jackson.core</groupId>
                <artifactId>jackson-databind</artifactId>
                <version>2.15.4</version>
            </dependency>
            """.trimIndent()
        )
        val candidate = DependencyManagementScanner.scan(model).single()

        setReason(project, candidate, "CVE-2024-12345")

        assertTrue(myFixture.file.text.contains("<!-- CVE-2024-12345 -->"))
    }

    fun `test setReason replaces an existing comment instead of stacking a second one`() {
        val model = configurePom(
            """
            <!-- old reason -->
            <dependency>
                <groupId>com.fasterxml.jackson.core</groupId>
                <artifactId>jackson-databind</artifactId>
                <version>2.15.4</version>
            </dependency>
            """.trimIndent()
        )
        val candidate = DependencyManagementScanner.scan(model).single()

        setReason(project, candidate, "new reason")

        val resultText = myFixture.file.text
        assertTrue(resultText.contains("<!-- new reason -->"))
        assertFalse(resultText.contains("old reason"))
    }

    fun `test setReason with null removes an existing comment`() {
        val model = configurePom(
            """
            <!-- old reason -->
            <dependency>
                <groupId>com.fasterxml.jackson.core</groupId>
                <artifactId>jackson-databind</artifactId>
                <version>2.15.4</version>
            </dependency>
            """.trimIndent()
        )
        val candidate = DependencyManagementScanner.scan(model).single()

        setReason(project, candidate, null)

        val resultText = myFixture.file.text
        assertFalse(resultText.contains("old reason"))
        assertTrue(resultText.contains("jackson-databind"))
    }

    fun `test setReason does not clobber a live suppress marker comment`() {
        val model = configurePom(
            """
            <!-- $SUPPRESS_MARKER -->
            <dependency>
                <groupId>com.fasterxml.jackson.core</groupId>
                <artifactId>jackson-databind</artifactId>
                <version>2.15.4</version>
            </dependency>
            """.trimIndent()
        )
        val candidate = DependencyManagementScanner.scan(model).single()
        assertTrue(candidate.suppressed)

        setReason(project, candidate, "a new reason")

        val resultText = myFixture.file.text
        assertTrue("Suppress marker must survive an attempted reason edit", resultText.contains(SUPPRESS_MARKER))
        assertFalse(resultText.contains("a new reason"))
    }

    fun `test addOverride creates dependencyManagement when it does not exist yet`() {
        val file = myFixture.configureByText(
            "pom.xml",
            """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.example</groupId>
                <artifactId>test-module</artifactId>
                <version>1.0.0</version>
            </project>
            """.trimIndent()
        )
        val model = MavenDomUtil.getMavenDomProjectModel(project, file.virtualFile)!!

        addOverride(project, model, Ga("com.example", "widget-core"), "9.9.9", "CVE-2024-12345")

        val resultText = myFixture.file.text
        assertTrue(resultText.contains("<dependencyManagement>"))
        assertTrue(resultText.contains("<groupId>com.example</groupId>"))
        assertTrue(resultText.contains("<artifactId>widget-core</artifactId>"))
        assertTrue(resultText.contains("<version>9.9.9</version>"))
        assertTrue(resultText.contains("<!-- CVE-2024-12345 -->"))
    }

    fun `test addOverride appends to existing dependencyManagement without a reason comment when none given`() {
        val model = configurePom(
            """
            <dependency>
                <groupId>com.example</groupId>
                <artifactId>acme-bom</artifactId>
                <version>1.0.0</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
            """.trimIndent()
        )

        addOverride(project, model, Ga("com.fasterxml.jackson.core", "jackson-databind"), "2.18.2", null)

        val candidates = DependencyManagementScanner.scan(model)
        assertEquals(1, candidates.size)
        val added = candidates.single()
        assertEquals("jackson-databind", added.ga.artifactId)
        assertEquals("2.18.2", added.declaredVersion)
        assertNull(added.reason)
    }

    fun `test addOverride treats a blank reason the same as no reason`() {
        val model = configurePom(
            """
            <dependency>
                <groupId>com.example</groupId>
                <artifactId>acme-bom</artifactId>
                <version>1.0.0</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
            """.trimIndent()
        )

        addOverride(project, model, Ga("com.fasterxml.jackson.core", "jackson-databind"), "2.18.2", "   ")

        val added = DependencyManagementScanner.scan(model).single()
        assertNull(added.reason)
    }

    private fun configurePom(dependencyManagementEntries: String): MavenDomProjectModel {
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
                        $dependencyManagementEntries
                    </dependencies>
                </dependencyManagement>
            </project>
            """.trimIndent()
        )
        return MavenDomUtil.getMavenDomProjectModel(project, file.virtualFile)!!
    }
}
