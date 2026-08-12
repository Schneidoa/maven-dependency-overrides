package cloud.schneidoa.detection

import com.intellij.lang.xml.XMLLanguage
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlComment
import com.intellij.psi.xml.XmlTag

const val SUPPRESS_MARKER = "maven-dependency-overrides: suppress"

/**
 * XML comments cannot legally contain "--" or end in "-". Rather than reject
 * such text outright (validation belongs at the UI layer for a good error
 * message), this makes createXmlComment always produce well-formed XML no
 * matter what free text a caller passes through it - inserting a space
 * between any "--" pair, and after a trailing "-", is a minimal, readable
 * transformation that preserves the text's meaning.
 */
private fun sanitizeCommentText(text: String): String {
    var sanitized = text
    while (sanitized.contains("--")) {
        sanitized = sanitized.replace("--", "- -")
    }
    return if (sanitized.endsWith("-")) "$sanitized " else sanitized
}

/**
 * Walks backward over blank (whitespace-only) siblings to find the nearest
 * real preceding sibling, and returns it if it's a comment. Deliberately
 * avoids PsiTreeUtil.skipWhitespacesBackward, which skips PsiWhiteSpace
 * nodes specifically — inter-tag whitespace in XML PSI isn't guaranteed to
 * be represented that way, so comparing blank text directly is more robust.
 */
fun findPrecedingComment(xmlTag: XmlTag): XmlComment? {
    var sibling = xmlTag.prevSibling
    while (sibling != null && sibling.text.isBlank()) {
        sibling = sibling.prevSibling
    }
    return sibling as? XmlComment
}

/**
 * Creates a standalone XmlComment PSI element with the given text.
 * XmlElementFactory has no direct method for this (verified by reading its
 * full source), so this parses a throwaway wrapper tag containing the
 * comment and extracts it — the workaround documented by JetBrains support
 * for exactly this gap, verified empirically in this project's own spike
 * (PsiMutationSpikeTest) before being relied on here.
 */
fun createXmlComment(project: Project, text: String): XmlComment {
    val safeText = sanitizeCommentText(text)
    val dummyFile = PsiFileFactory.getInstance(project)
        .createFileFromText("dummy.xml", XMLLanguage.INSTANCE, "<a><!--$safeText--></a>")
    val dummyTag = PsiTreeUtil.findChildOfType(dummyFile, XmlTag::class.java)
        ?: error("Failed to parse throwaway wrapper tag for comment creation")
    return PsiTreeUtil.findChildOfType(dummyTag, XmlComment::class.java)
        ?: error("Throwaway wrapper tag did not contain the expected XmlComment")
}
