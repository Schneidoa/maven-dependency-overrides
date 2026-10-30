package cloud.schneidoa.detection

import cloud.schneidoa.resolver.BomVersionResolver
import cloud.schneidoa.resolver.Ga
import cloud.schneidoa.resolver.Gav
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import org.apache.maven.model.Model
import org.apache.maven.model.building.ModelSource2
import org.apache.maven.model.io.xpp3.MavenXpp3Reader
import org.jetbrains.idea.maven.project.MavenProjectsManager
import java.util.concurrent.CancellationException

/**
 * Finds a POM among the project's own files, the way a reactor build sees its modules before it
 * looks at the local repository. An internal BOM or parent is usually never installed, and when
 * it is, the installed copy is an older build of what is sitting right here.
 *
 * Comes from FilenameIndex rather than MavenProjectsManager for the reason ProjectOverrideScanner
 * does: it has to work before Maven sync. The index is built on first use, since a module with
 * no parent and no BOM never needs it, and lives as long as the [BomVersionResolver] holding it.
 *
 * Coordinates are read from the raw POMs, so a CI-friendly `${revision}` module has no literal
 * version to match. Such a module matches on groupId and artifactId alone, but only when it is
 * the only one in the project with those, since picking between two would be a guess. A literal
 * version that differs is treated as a different artifact: a project may deliberately import an
 * earlier release of its own BOM.
 */
class ReactorPoms(private val project: Project) : (Gav) -> ModelSource2? {

    private data class ReactorPom(val version: String, val file: VirtualFile)

    private val pomsByGa: Map<Ga, List<ReactorPom>> by lazy {
        // A dialog can open during indexing. Going without costs an Inconclusive at worst, the
        // same as this class not existing, where the exception would be mistaken for a bad POM.
        if (DumbService.isDumb(project)) return@lazy emptyMap()
        FilenameIndex.getVirtualFilesByName("pom.xml", GlobalSearchScope.projectScope(project))
            .mapNotNull { file -> coordinatesOf(file)?.let { (ga, version) -> ga to ReactorPom(version, file) } }
            .groupBy({ it.first }, { it.second })
    }

    override fun invoke(gav: Gav): ModelSource2? {
        val poms = pomsByGa[gav.toGa()] ?: return null
        val match = poms.firstOrNull { it.version == gav.version }
            ?: poms.singleOrNull()?.takeIf { "\${" in it.version }
        return match?.let { VfsModelSource(it.file) }
    }

    private fun coordinatesOf(file: VirtualFile): Pair<Ga, String>? {
        val model = readModel(file) ?: return null
        val groupId = model.groupId ?: model.parent?.groupId ?: return null
        val artifactId = model.artifactId ?: return null
        val version = model.version ?: model.parent?.version ?: return null
        return Ga(groupId, artifactId) to version
    }

    /** A pom.xml that doesn't parse can't be matched, the same as one that doesn't exist. */
    private fun readModel(file: VirtualFile): Model? = try {
        VfsModelSource(file).inputStream.use { MavenXpp3Reader().read(it, false) }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
}

/** The resolver every production entry point shares: local repository plus the project's own POMs. */
fun bomVersionResolverFor(project: Project, onMissingPom: (Gav) -> Unit = {}): BomVersionResolver =
    BomVersionResolver(
        MavenProjectsManager.getInstance(project).repositoryPath.toFile(),
        onMissingPom,
        ReactorPoms(project)
    )
