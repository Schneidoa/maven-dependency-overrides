package cloud.schneidoa.detection

import cloud.schneidoa.resolver.BomVersionResolver
import cloud.schneidoa.resolver.Ga
import cloud.schneidoa.resolver.Gav
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
    moduleLabel: String,
    onMissingPom: (Gav) -> Unit = {}
): BomChainReport? {
    val model = MavenDomUtil.getMavenDomProjectModel(project, pomFile) ?: return null
    val localRepositoryDir = MavenProjectsManager.getInstance(project).repositoryPath.toFile()
    val chain = BomChainResolver(localRepositoryDir, onMissingPom).resolveBomChain(model, project)

    return buildBomChainReport(
        ga = ga,
        declaredVersion = declaredVersion,
        moduleLabel = moduleLabel,
        bomChain = chain.imports,
        resolver = BomVersionResolver(localRepositoryDir, onMissingPom),
        // Passing chain.imports alone would hand the dialog a truncated walk with nothing marking
        // it as truncated - the dialog would then assert a complete chain to a user who opened it
        // precisely to find out why the row said Inconclusive.
        truncatedAt = chain.truncatedAt
    )
}
