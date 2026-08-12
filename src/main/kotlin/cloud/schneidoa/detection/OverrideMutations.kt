package cloud.schneidoa.detection

import cloud.schneidoa.resolver.Ga
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import org.jetbrains.idea.maven.dom.model.MavenDomProjectModel

/** Deletes the candidate's <dependency> block entirely. */
fun removeOverride(project: Project, candidate: OverrideCandidate) {
    WriteCommandAction.runWriteCommandAction(project) {
        findPrecedingComment(candidate.xmlTag)?.delete()
        candidate.xmlTag.delete()
    }
}

/** Changes the candidate's pinned version, leaving everything else untouched. */
fun setVersion(project: Project, candidate: OverrideCandidate, newVersion: String) {
    WriteCommandAction.runWriteCommandAction(project) {
        candidate.versionXmlTag.value.text = newVersion
    }
}

/**
 * Creates, replaces, or removes the candidate's preceding reason comment.
 * [newReason] blank or null removes any existing comment; otherwise it's
 * written as the comment text, replacing whatever was already there rather
 * than stacking a second comment above the entry.
 *
 * If the *live* preceding comment turns out to be the suppress marker, this
 * is a no-op instead: [candidate] is a snapshot from whenever the panel last
 * scanned, and the entry may have been suppressed via the editor's own
 * suppress quick fix since then. Silently un-suppressing it as a side effect
 * of an unrelated "edit reason" action would be a worse outcome than just
 * not applying the edit.
 */
fun setReason(project: Project, candidate: OverrideCandidate, newReason: String?) {
    WriteCommandAction.runWriteCommandAction(project) {
        val existingComment = findPrecedingComment(candidate.xmlTag)
        if (existingComment?.commentText?.trim() == SUPPRESS_MARKER) return@runWriteCommandAction
        when {
            newReason.isNullOrBlank() -> existingComment?.delete()
            existingComment != null -> existingComment.replace(createXmlComment(project, " $newReason "))
            else -> candidate.xmlTag.parent.addBefore(createXmlComment(project, " $newReason "), candidate.xmlTag)
        }
    }
}

/**
 * Creates a brand-new <dependency> entry inside [model]'s
 * <dependencyManagement>, creating that section (and <dependencies> inside
 * it) if it doesn't exist yet - verified to work via the Maven DOM API's
 * auto-vivification behavior in AddOverridePsiSpikeTest. [reason], if
 * non-blank, is written as a preceding comment the same way setReason
 * writes one for an existing entry.
 */
fun addOverride(project: Project, model: MavenDomProjectModel, ga: Ga, version: String, reason: String?) {
    WriteCommandAction.runWriteCommandAction(project) {
        val dependency = model.dependencyManagement.dependencies.addDependency()
        dependency.groupId.stringValue = ga.groupId
        dependency.artifactId.stringValue = ga.artifactId
        dependency.version.stringValue = version

        if (!reason.isNullOrBlank()) {
            val dependencyTag = dependency.xmlTag ?: error("Newly created dependency tag is unexpectedly null")
            dependencyTag.parent.addBefore(createXmlComment(project, " $reason "), dependencyTag)
        }
    }
}
