package cloud.schneidoa.detection

import com.intellij.lang.xml.XMLLanguage
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlComment
import com.intellij.psi.xml.XmlTag
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class PsiMutationSpikeTest : BasePlatformTestCase() {

    private val pomText = """
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

    fun `test creates a standalone XmlComment via a throwaway wrapper tag and inserts it before a dependency`() {
        val file = myFixture.configureByText("pom.xml", pomText)
        val dependencyTag = PsiTreeUtil.findChildrenOfType(file, XmlTag::class.java)
            .first { it.name == "dependency" }

        WriteCommandAction.runWriteCommandAction(project) {
            val dummyFile = PsiFileFactory.getInstance(project)
                .createFileFromText("dummy.xml", XMLLanguage.INSTANCE, "<a><!-- test comment --></a>")
            val dummyTag = PsiTreeUtil.findChildOfType(dummyFile, XmlTag::class.java)!!
            val comment = PsiTreeUtil.findChildOfType(dummyTag, XmlComment::class.java)!!

            dependencyTag.parent.addBefore(comment, dependencyTag)
        }

        assertTrue(file.text.contains("<!-- test comment -->"))
        val insertedComment = dependencyTag.prevSibling as? XmlComment
            ?: (dependencyTag.prevSibling?.prevSibling as? XmlComment)
        assertNotNull("Expected to find the inserted comment as a preceding sibling", insertedComment)
        assertEquals("test comment", insertedComment!!.commentText.trim())
    }

    fun `test deletes a dependency tag`() {
        val file = myFixture.configureByText("pom.xml", pomText)
        val dependencyTag = PsiTreeUtil.findChildrenOfType(file, XmlTag::class.java)
            .first { it.name == "dependency" }

        WriteCommandAction.runWriteCommandAction(project) {
            dependencyTag.delete()
        }

        assertFalse(file.text.contains("widget-core"))
    }
}
