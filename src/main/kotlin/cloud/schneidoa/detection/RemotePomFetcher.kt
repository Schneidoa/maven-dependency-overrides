package cloud.schneidoa.detection

import cloud.schneidoa.resolver.Gav
import cloud.schneidoa.resolver.isConcrete
import cloud.schneidoa.resolver.pomFileIn
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.runBlockingCancellable
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.util.concurrency.ThreadingAssertions
import org.jetbrains.idea.maven.buildtool.MavenLogEventHandler
import org.jetbrains.idea.maven.model.MavenArtifactInfo
import org.jetbrains.idea.maven.model.MavenRemoteRepository
import org.jetbrains.idea.maven.project.MavenEmbeddersManager
import org.jetbrains.idea.maven.project.MavenProjectsManager
import org.jetbrains.idea.maven.server.MavenArtifactResolutionRequest

private val logger = logger<RemotePomFetcher>()

/**
 * Downloads missing BOM/parent POMs from the remote repositories Maven itself would
 * use. This is the only class in the plugin that touches the network.
 *
 * Mirrors, proxies and authentication (including credentials encrypted in
 * settings-security.xml) are NOT handled here and must not be: the IDE's Maven server
 * applies them itself, inside Maven's own RepositorySystem, when it processes the
 * repository list we hand it. Reimplementing that here is precisely the class of
 * correctness bug the design doc rules out by embedding Maven's own machinery.
 *
 * Must run on a background thread and outside a read action - it does network I/O and
 * a synchronous VFS refresh, and the latter throws under the read lock.
 *
 * Uses the obsolete `MavenEmbeddersManager` route rather than the newer
 * `MavenEmbedderWrappers`/`MavenEmbedderWrappersManager`: verified by decompiling the
 * pinned 2026.2.1 jar (`plugins/maven-plugin/lib/intellij.maven.jar`) and confirmed by
 * `./gradlew verifyPlugin` failing with four INTERNAL_API_USAGES on an earlier attempt -
 * `MavenEmbedderWrappers` and `MavenEmbedderWrappersManager` are class-level
 * `@ApiStatus.Internal`, while `MavenEmbeddersManager` is only `@ApiStatus.Obsolete`,
 * which the verifier accepts. Obsolete-but-allowed beats modern-but-rejected: the
 * plugin cannot ship with an internal-API usage. Do not "modernize" this to
 * `MavenEmbedderWrappers` without re-running `verifyPlugin` and seeing it pass.
 */
class RemotePomFetcher(private val project: Project) {

    fun fetch(gavs: Set<Gav>): Set<Gav> {
        ThreadingAssertions.assertBackgroundThread()

        // Defence in depth - resolvingMissingPoms filters these out already. A coordinate
        // still carrying ${...} is what MavenPropertyResolver hands back before Maven sync
        // has run, and asking a repository for it would be a guaranteed-404 request built
        // from a placeholder.
        val fetchable = gavs.filter { it.isConcrete() }.toSet()
        if (fetchable.isEmpty()) return emptySet()

        val manager = MavenProjectsManager.getInstance(project)
        if (manager.generalSettings.isWorkOffline) {
            logger.debug("Maven is in offline mode; not fetching ${fetchable.size} missing POM(s)")
            return emptySet()
        }

        val basePath = project.basePath ?: return emptySet()
        val localRepositoryDir = manager.repositoryPath.toFile()
        val repositories = manager.remoteRepositories.toList().ifEmpty { listOf(MAVEN_CENTRAL) }
        val requests = fetchable.map { gav ->
            MavenArtifactResolutionRequest(
                MavenArtifactInfo(gav.groupId, gav.artifactId, gav.version, "pom", null),
                repositories
            )
        }

        // The (Key, workingDirectory) overload rather than (MavenProject, Key): it needs no
        // synced MavenProject, which is what makes this work on a fresh checkout - the case
        // this feature most needs to serve.
        val embeddersManager = manager.embeddersManager
        val embedder = embeddersManager.getEmbedder(MavenEmbeddersManager.FOR_DEPENDENCIES_RESOLVE, basePath)
        try {
            runBlockingCancellable {
                embedder.resolveArtifacts(requests, null, MavenLogEventHandler)
            }
        } catch (e: Exception) {
            // Deliberately broad, matching BomEffectiveModelResolver's reflex: one unreachable
            // repository or one malformed coordinate must degrade to "could not fetch" for the
            // whole batch, never propagate into a tool window refresh or a dialog load.
            logger.warn("Failed to fetch ${fetchable.size} POM(s) from remote repositories", e)
        } finally {
            embeddersManager.release(embedder)
        }

        // Success is decided by what is on disk, not by MavenArtifact.isResolved(): we asked for
        // packaging "pom", and isResolved()'s notion of resolved is about the primary artifact
        // file, which is not the question we need answered.
        val landed = fetchable.filter { it.pomFileIn(localRepositoryDir).isFile }.toSet()
        LocalFileSystem.getInstance().refreshIoFiles(landed.map { it.pomFileIn(localRepositoryDir) })
        return landed
    }

    private companion object {
        val MAVEN_CENTRAL = MavenRemoteRepository(
            "central",
            "Central Repository",
            "https://repo.maven.apache.org/maven2",
            "default",
            null,
            null
        )
    }
}
