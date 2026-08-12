package cloud.schneidoa.detection

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jetbrains.idea.maven.dom.MavenDomUtil

class AddOverridePsiSpikeTest : BasePlatformTestCase() {

    fun `test creates dependencyManagement and dependencies tags on write when neither exists yet`() {
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

        WriteCommandAction.runWriteCommandAction(project) {
            val dependency = model.dependencyManagement.dependencies.addDependency()
            dependency.groupId.stringValue = "com.example"
            dependency.artifactId.stringValue = "widget-core"
            dependency.version.stringValue = "1.0.0"
        }

        assertTrue(file.text.contains("<dependencyManagement>"))
        assertTrue(file.text.contains("<dependencies>"))
        assertTrue(file.text.contains("<groupId>com.example</groupId>"))
        assertTrue(file.text.contains("<artifactId>widget-core</artifactId>"))
        assertTrue(file.text.contains("<version>1.0.0</version>"))
    }

    fun `test adds a second dependency entry when dependencyManagement already exists`() {
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
                            <artifactId>widget-core</artifactId>
                            <version>1.0.0</version>
                        </dependency>
                    </dependencies>
                </dependencyManagement>
            </project>
            """.trimIndent()
        )
        val model = MavenDomUtil.getMavenDomProjectModel(project, file.virtualFile)!!

        WriteCommandAction.runWriteCommandAction(project) {
            val dependency = model.dependencyManagement.dependencies.addDependency()
            dependency.groupId.stringValue = "com.fasterxml.jackson.core"
            dependency.artifactId.stringValue = "jackson-databind"
            dependency.version.stringValue = "2.18.2"
        }

        assertEquals(2, model.dependencyManagement.dependencies.dependencies.size)
        assertTrue(file.text.contains("jackson-databind"))
        assertTrue(file.text.contains("widget-core"))
    }
}
