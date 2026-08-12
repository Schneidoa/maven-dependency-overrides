package cloud.schneidoa.detection

import cloud.schneidoa.resolver.BomVersionResolver
import cloud.schneidoa.resolver.Ga
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.idea.maven.dom.MavenDomUtil
import org.jetbrains.idea.maven.project.MavenProjectsManager

/**
 * Platform-touching assembly for [buildBomChainReport], kept in its own file so that
 * BomChainReport.kt - and BomChainReportTest, which exercises it as plain JUnit with no
 * platform fixture - stays free of IntelliJ Platform imports.
 *
 * This performs the same composition [OverrideDetector.forProject] already does for the
 * main detection path (repo-path lookup, then BomChainResolver + BomVersionResolver over
 * it), just for a single row re-resolved on demand rather than a whole-project scan. It
 * used to live inline in OverrideOverviewToolWindowFactory.showBomChain, duplicating that
 * composition in the one part of the codebase with no test coverage; it belongs in
 * detection/, which - unlike resolver/ - is allowed to depend on Platform APIs.
 */
fun buildBomChainReportFor(
    project: Project,
    pomFile: VirtualFile,
    ga: Ga,
    declaredVersion: String,
    moduleLabel: String
): BomChainReport? {
    val model = MavenDomUtil.getMavenDomProjectModel(project, pomFile) ?: return null
    val localRepositoryDir = MavenProjectsManager.getInstance(project).repositoryPath.toFile()
    val chain = BomChainResolver(localRepositoryDir).resolveBomChain(model, project)

    return buildBomChainReport(
        ga = ga,
        declaredVersion = declaredVersion,
        moduleLabel = moduleLabel,
        bomChain = chain,
        resolver = BomVersionResolver(localRepositoryDir)
    )
}
