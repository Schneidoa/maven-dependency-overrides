package cloud.schneidoa.detection.quickfix

import cloud.schneidoa.detection.findPrecedingComment
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.project.Project
import com.intellij.psi.xml.XmlTag

class RemoveOverrideQuickFix : LocalQuickFix {
    override fun getFamilyName(): String = "Remove dependency override"
    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val versionTag = descriptor.psiElement as? XmlTag ?: return
        val dependencyTag = versionTag.parentTag ?: return
        findPrecedingComment(dependencyTag)?.delete()
        dependencyTag.delete()
    }
}
