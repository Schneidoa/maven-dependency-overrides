package cloud.schneidoa.detection

import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlTag
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class PomCommentsTest : BasePlatformTestCase() {

    fun `test finds a comment immediately preceding a tag`() {
        val file = myFixture.configureByText(
            "pom.xml",
            """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.example</groupId>
                <artifactId>test-module</artifactId>
                <version>1.0.0</version>
                <!-- a reason -->
                <description>x</description>
            </project>
            """.trimIndent()
        )
        val descriptionTag = PsiTreeUtil.findChildrenOfType(file, XmlTag::class.java)
            .first { it.name == "description" }

        val comment = findPrecedingComment(descriptionTag)

        assertNotNull(comment)
        assertEquals("a reason", comment!!.commentText.trim())
    }

    fun `test returns null when there is no preceding comment`() {
        val file = myFixture.configureByText(
            "pom.xml",
            """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.example</groupId>
                <artifactId>test-module</artifactId>
                <version>1.0.0</version>
                <description>x</description>
            </project>
            """.trimIndent()
        )
        val descriptionTag = PsiTreeUtil.findChildrenOfType(file, XmlTag::class.java)
            .first { it.name == "description" }

        assertNull(findPrecedingComment(descriptionTag))
    }

    fun `test creates a comment whose text round trips through commentText`() {
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

        val comment = createXmlComment(project, " $SUPPRESS_MARKER ")

        assertEquals(SUPPRESS_MARKER, comment.commentText.trim())
        // sanity: file itself is untouched, createXmlComment doesn't need a real anchor
        assertFalse(file.text.contains(SUPPRESS_MARKER))
    }

    fun `test creates a comment safely when the text contains a double dash`() {
        val comment = createXmlComment(project, " reason -- with double dash ")

        assertFalse(
            "XML comments cannot contain --, the created comment text must not either",
            comment.commentText.contains("--")
        )
    }

    fun `test creates a comment safely when the text ends with a dash`() {
        val comment = createXmlComment(project, " reason ending in -")

        // Not .trim(): the sanitizer's fix is a trailing protective space after the
        // dash, so it lives right at the string boundary. trim() would strip that
        // same space back off and reintroduce the "-" the test is trying to catch,
        // so the raw commentText (which mirrors the raw file text between
        // <!-- and -->) is what actually needs checking here.
        assertFalse("XML comments cannot end in -", comment.commentText.endsWith("-"))
    }
}
