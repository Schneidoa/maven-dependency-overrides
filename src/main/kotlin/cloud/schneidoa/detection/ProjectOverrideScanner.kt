package cloud.schneidoa.detection

import cloud.schneidoa.resolver.BomVersionResolver
import cloud.schneidoa.resolver.Gav
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.idea.maven.dom.MavenDomUtil
import org.jetbrains.idea.maven.dom.model.MavenDomProjectModel
import org.jetbrains.idea.maven.project.MavenProjectsManager

/**
 * One detected override, tagged with which module (pom.xml) it came from -
 * DetectedOverride itself has no notion of "which file", since OverrideDetector
 * only ever looks at a single module at a time.
 */
data class ProjectOverrideEntry(
    val moduleLabel: String,
    val pomFile: VirtualFile,
    val override: DetectedOverride
)

/** Default factory, extracted so the constructor default stays readable. */
private fun detectorFor(project: Project, onMissingPom: (Gav) -> Unit): OverrideDetector {
    val localRepositoryDir = MavenProjectsManager.getInstance(project).repositoryPath.toFile()
    return OverrideDetector(BomVersionResolver(localRepositoryDir, onMissingPom))
}

/**
 * Finds every pom.xml in the project via FilenameIndex rather than
 * MavenProjectsManager.projects - mirrors BomChainResolver's own choice to
 * avoid depending on IntelliJ's synced Maven project index, which doesn't
 * need a completed Maven import/sync to produce results.
 */
class ProjectOverrideScanner(
    private val detectorFactory: (Project, (Gav) -> Unit) -> OverrideDetector = ::detectorFor
) {
    fun scan(project: Project, onMissingPom: (Gav) -> Unit = {}): List<ProjectOverrideEntry> {
        val detector = detectorFactory(project, onMissingPom)
        val pomFiles = FilenameIndex.getVirtualFilesByName("pom.xml", GlobalSearchScope.projectScope(project))

        return pomFiles
            .flatMap { pomFile -> entriesFor(pomFile, detector, project) }
            .sortedWith(compareBy({ it.moduleLabel }, { it.override.candidate.ga.toString() }))
    }

    private fun entriesFor(pomFile: VirtualFile, detector: OverrideDetector, project: Project): List<ProjectOverrideEntry> {
        val model = MavenDomUtil.getMavenDomProjectModel(project, pomFile) ?: return emptyList()
        val moduleLabel = moduleLabelFor(model, pomFile)
        return detector.detect(model, project).map { ProjectOverrideEntry(moduleLabel, pomFile, it) }
    }

    private fun moduleLabelFor(model: MavenDomProjectModel, pomFile: VirtualFile): String {
        val artifactId = model.artifactId.rawText?.trim()
        return if (!artifactId.isNullOrEmpty()) artifactId else (pomFile.parent?.name ?: pomFile.name)
    }
}
