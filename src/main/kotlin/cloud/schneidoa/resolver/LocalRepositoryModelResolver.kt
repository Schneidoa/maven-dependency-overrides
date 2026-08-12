package cloud.schneidoa.resolver

import org.apache.maven.model.Dependency
import org.apache.maven.model.Parent
import org.apache.maven.model.Repository
import org.apache.maven.model.building.FileModelSource
import org.apache.maven.model.building.ModelSource
import org.apache.maven.model.resolution.ModelResolver
import org.apache.maven.model.resolution.UnresolvableModelException
import java.io.File

/**
 * Resolves parent/import POM references purely against a local Maven
 * repository directory, following the standard repository layout. Never
 * touches the network — remote repositories declared in POMs are ignored,
 * since resolution here only concerns artifacts already present locally
 * (see design doc: core override detection must work offline).
 */
class LocalRepositoryModelResolver(private val localRepositoryDir: File) : ModelResolver {

    override fun resolveModel(groupId: String, artifactId: String, version: String): ModelSource =
        resolve(Gav(groupId, artifactId, version))

    override fun resolveModel(parent: Parent): ModelSource =
        resolve(Gav(parent.groupId, parent.artifactId, parent.version))

    override fun resolveModel(dependency: Dependency): ModelSource =
        resolve(Gav(dependency.groupId, dependency.artifactId, dependency.version))

    override fun addRepository(repository: Repository) {
        // Intentionally a no-op, see class doc.
    }

    override fun addRepository(repository: Repository, replace: Boolean) {
        // Intentionally a no-op, see class doc.
    }

    override fun newCopy(): ModelResolver = LocalRepositoryModelResolver(localRepositoryDir)

    private fun resolve(gav: Gav): ModelSource {
        val pomFile = gav.pomFileIn(localRepositoryDir)
        if (!pomFile.isFile) {
            throw UnresolvableModelException(
                "Could not find ${pomFile.name} in local repository $localRepositoryDir",
                gav.groupId,
                gav.artifactId,
                gav.version
            )
        }
        return FileModelSource(pomFile)
    }
}
