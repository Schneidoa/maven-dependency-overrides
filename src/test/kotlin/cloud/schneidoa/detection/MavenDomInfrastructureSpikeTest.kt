package cloud.schneidoa.detection

import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jetbrains.idea.maven.dom.MavenDomUtil

class MavenDomInfrastructureSpikeTest : BasePlatformTestCase() {

    fun `test recognizes a pom xml file as a MavenDomProjectModel`() {
        val file = myFixture.configureByText(
            "pom.xml",
            """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.example</groupId>
                <artifactId>child</artifactId>
                <version>1.0.0</version>
            </project>
            """.trimIndent()
        )

        val model = MavenDomUtil.getMavenDomProjectModel(project, file.virtualFile)

        assertNotNull("Expected pom.xml to be recognized as a MavenDomProjectModel", model)
        assertEquals("child", model!!.artifactId.rawText?.trim())
    }

    fun `test resolves a relative path across two in-memory fixture files`() {
        myFixture.addFileToProject(
            "parent/pom.xml",
            """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.example</groupId>
                <artifactId>parent</artifactId>
                <version>1.0.0</version>
                <packaging>pom</packaging>
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
            </project>
            """.trimIndent()
        )

        val childDir = childFile.virtualFile.parent
        assertNotNull("Expected child/pom.xml to have a parent directory", childDir)

        val resolved = VfsUtilCore.findRelativeFile("../parent/pom.xml", childDir)

        assertNotNull(
            "Expected VfsUtilCore.findRelativeFile to resolve ../parent/pom.xml from an " +
                "in-memory fixture directory, without any Maven project sync",
            resolved
        )
        assertEquals("pom.xml", resolved!!.name)

        val parentModel = MavenDomUtil.getMavenDomProjectModel(project, resolved)
        assertNotNull(parentModel)
        assertEquals("parent", parentModel!!.artifactId.rawText?.trim())
    }
}
