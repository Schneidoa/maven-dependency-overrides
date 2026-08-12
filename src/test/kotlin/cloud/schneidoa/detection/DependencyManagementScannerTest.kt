package cloud.schneidoa.detection

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jetbrains.idea.maven.dom.MavenDomUtil
import org.jetbrains.idea.maven.dom.model.MavenDomProjectModel

class DependencyManagementScannerTest : BasePlatformTestCase() {

    fun `test finds a literal version override with a reason comment`() {
        val model = configurePom(
            """
            <!-- CVE-2024-12345, remove once Spring catches up -->
            <dependency>
                <groupId>com.fasterxml.jackson.core</groupId>
                <artifactId>jackson-databind</artifactId>
                <version>2.15.4</version>
            </dependency>
            """.trimIndent()
        )

        val candidates = DependencyManagementScanner.scan(model)

        assertEquals(1, candidates.size)
        val candidate = candidates.single()
        assertEquals("com.fasterxml.jackson.core", candidate.ga.groupId)
        assertEquals("jackson-databind", candidate.ga.artifactId)
        assertEquals("2.15.4", candidate.declaredVersion)
        assertEquals("CVE-2024-12345, remove once Spring catches up", candidate.reason)
        assertFalse(candidate.suppressed)
    }

    fun `test resolves a property based version`() {
        val model = configurePom(
            dependencyManagementEntries = """
            <dependency>
                <groupId>com.example</groupId>
                <artifactId>widget-core</artifactId>
                <version>${'$'}{widget.version}</version>
            </dependency>
            """.trimIndent(),
            extraProperties = "<widget.version>4.2.0</widget.version>"
        )

        val candidates = DependencyManagementScanner.scan(model)

        assertEquals("4.2.0", candidates.single().declaredVersion)
    }

    fun `test marks a candidate as suppressed and reports no reason`() {
        val model = configurePom(
            """
            <!-- maven-dependency-overrides: suppress -->
            <dependency>
                <groupId>com.example</groupId>
                <artifactId>widget-core</artifactId>
                <version>1.0.0</version>
            </dependency>
            """.trimIndent()
        )

        val candidate = DependencyManagementScanner.scan(model).single()

        assertTrue(candidate.suppressed)
        assertNull(candidate.reason)
    }

    fun `test reports no reason when there is no preceding comment`() {
        val model = configurePom(
            """
            <dependency>
                <groupId>com.example</groupId>
                <artifactId>widget-core</artifactId>
                <version>1.0.0</version>
            </dependency>
            """.trimIndent()
        )

        val candidate = DependencyManagementScanner.scan(model).single()

        assertFalse(candidate.suppressed)
        assertNull(candidate.reason)
    }

    fun `test excludes import scope entries since those are BOM imports, not overrides`() {
        val model = configurePom(
            """
            <dependency>
                <groupId>com.example</groupId>
                <artifactId>some-bom</artifactId>
                <version>1.0.0</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
            """.trimIndent()
        )

        assertTrue(DependencyManagementScanner.scan(model).isEmpty())
    }

    fun `test attributes each entry's own preceding comment independently when there are multiple entries`() {
        val model = configurePom(
            """
            <!-- CVE-2024-11111, jackson override -->
            <dependency>
                <groupId>com.fasterxml.jackson.core</groupId>
                <artifactId>jackson-databind</artifactId>
                <version>2.15.4</version>
            </dependency>
            <dependency>
                <groupId>com.example</groupId>
                <artifactId>widget-core</artifactId>
                <version>1.0.0</version>
            </dependency>
            """.trimIndent()
        )

        val candidates = DependencyManagementScanner.scan(model)

        assertEquals(2, candidates.size)
        val jackson = candidates.first { it.ga.artifactId == "jackson-databind" }
        val widget = candidates.first { it.ga.artifactId == "widget-core" }
        assertEquals("CVE-2024-11111, jackson override", jackson.reason)
        assertNull(widget.reason)
        assertFalse(widget.suppressed)
    }

    fun `test does not suppress when the comment only contains the suppress marker text as a substring`() {
        val model = configurePom(
            """
            <!-- see maven-dependency-overrides: suppress-list.md for policy -->
            <dependency>
                <groupId>com.example</groupId>
                <artifactId>widget-core</artifactId>
                <version>1.0.0</version>
            </dependency>
            """.trimIndent()
        )

        val candidate = DependencyManagementScanner.scan(model).single()

        assertFalse(candidate.suppressed)
        assertEquals("see maven-dependency-overrides: suppress-list.md for policy", candidate.reason)
    }

    fun `test candidate carries the version tag itself, not just the dependency tag`() {
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

        assertEquals("version", candidate.versionXmlTag.name)
        assertEquals("2.15.4", candidate.versionXmlTag.value.text)
    }

    private fun configurePom(
        dependencyManagementEntries: String,
        extraProperties: String = ""
    ): MavenDomProjectModel {
        val file = myFixture.configureByText(
            "pom.xml",
            """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.example</groupId>
                <artifactId>test-module</artifactId>
                <version>1.0.0</version>

                <properties>
                    $extraProperties
                </properties>

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
