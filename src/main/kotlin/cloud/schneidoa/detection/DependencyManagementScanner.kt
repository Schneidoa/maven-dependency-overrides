package cloud.schneidoa.detection

import cloud.schneidoa.resolver.Ga
import com.intellij.psi.xml.XmlComment
import org.jetbrains.idea.maven.dom.MavenPropertyResolver
import org.jetbrains.idea.maven.dom.model.MavenDomDependency
import org.jetbrains.idea.maven.dom.model.MavenDomProjectModel

object DependencyManagementScanner {

    fun scan(model: MavenDomProjectModel): List<OverrideCandidate> {
        // MavenDomProjectModel -> MavenDomDependencyManagement -> MavenDomDependencies
        // -> List<MavenDomDependency>; three ".dependencies" in a row is correct,
        // each step unwraps one level of the <dependencyManagement><dependencies>
        // <dependency> nesting.
        val managedDependencies: List<MavenDomDependency> = model.dependencyManagement.dependencies.dependencies

        return managedDependencies
            .filter { it.scope.rawText?.trim() != "import" }
            .mapNotNull { toCandidate(it, model) }
    }

    private fun toCandidate(dependency: MavenDomDependency, model: MavenDomProjectModel): OverrideCandidate? {
        val groupId = dependency.groupId.rawText?.trim()
        val artifactId = dependency.artifactId.rawText?.trim()
        val rawVersion = dependency.version.rawText?.trim()
        if (groupId.isNullOrEmpty() || artifactId.isNullOrEmpty() || rawVersion.isNullOrEmpty()) return null

        val xmlTag = dependency.xmlTag ?: return null
        val versionXmlTag = dependency.version.xmlTag ?: return null
        // Hands back rawVersion unchanged while MavenProjectsManager is uninitialized
        // (IDEA 2026.2+), i.e. before the first Maven sync - a ${...} version then flows
        // through as literal text and settles on NOT_COMPARABLE rather than a guess.
        val resolvedVersion = MavenPropertyResolver.resolve(rawVersion, model)
        val (reason, suppressed) = classifyComment(findPrecedingComment(xmlTag))

        return OverrideCandidate(
            ga = Ga(groupId, artifactId),
            declaredVersion = resolvedVersion,
            xmlTag = xmlTag,
            versionXmlTag = versionXmlTag,
            reason = reason,
            suppressed = suppressed
        )
    }

    private fun classifyComment(comment: XmlComment?): Pair<String?, Boolean> {
        val text = comment?.commentText?.trim() ?: return null to false
        return if (text == SUPPRESS_MARKER) null to true else text to false
    }
}
