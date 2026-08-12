package cloud.schneidoa.detection.quickfix

import cloud.schneidoa.detection.SUPPRESS_MARKER
import cloud.schneidoa.detection.createXmlComment
import cloud.schneidoa.detection.findPrecedingComment
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.project.Project
import com.intellij.psi.xml.XmlTag

class SuppressOverrideQuickFix : LocalQuickFix {

    override fun getFamilyName(): String = "Suppress this override warning"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val versionTag = descriptor.psiElement as? XmlTag ?: return
        val dependencyTag = versionTag.parentTag ?: return
        val suppressComment = createXmlComment(project, " $SUPPRESS_MARKER ")

        val existingComment = findPrecedingComment(dependencyTag)
        if (existingComment != null) {
            existingComment.replace(suppressComment)
        } else {
            dependencyTag.parent.addBefore(suppressComment, dependencyTag)
        }
    }
}
