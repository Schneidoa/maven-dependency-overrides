package cloud.schneidoa.detection

import cloud.schneidoa.resolver.Gav
import cloud.schneidoa.resolver.isConcrete
import cloud.schneidoa.resolver.pomFileIn
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProgressIndicatorProvider
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.runBlockingCancellable
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.util.concurrency.ThreadingAssertions
import kotlinx.coroutines.CancellationException
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
        // Both assertions are needed: background thread does not imply no read lock - a
        // caller inside ReadAction.nonBlocking is on a background thread but still holds
        // the read lock, and would otherwise pass the first check and fail later, obscurely,
        // inside the synchronous VFS refresh below.
        ThreadingAssertions.assertBackgroundThread()
        ThreadingAssertions.assertNoReadAccess()

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
        // The Maven Central fallback fires precisely in the pre-sync case this feature exists to
        // serve: before the first sync, remoteRepositories is empty, and with no repository list
        // there is nothing to ask at all. The trade-off is recorded rather than hidden. A
        // <mirrorOf>*</mirrorOf> in settings.xml still redirects this request - the IDE's Maven
        // server applies mirrors itself - so the common corporate setup stays correct. What is
        // not covered is a project with no mirror that declares its repositories only in its POM:
        // there we can pull a Central copy of a coordinate the real build resolves privately, and
        // cache it in ~/.m2. Accepted deliberately: the alternative is parsing repository
        // declarations out of unsynced POMs ourselves, which is the reimplement-Maven's-resolution
        // class of bug this class is written to avoid, and the failure mode here is a POM used for
        // a version lookup, not an artifact linked into a build.
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
            // The ProgressManager wrapper is NOT redundant - do not remove it. runBlockingCancellable
            // logs an IDE error when the calling thread has neither a ProgressIndicator nor a Job:
            // CoroutinesKt.runBlockingCancellable reaches
            // LOG.error(IllegalStateException("There is no ProgressIndicator or Job in this thread,
            // the current job is not cancellable.")). The fetch still completes, which is exactly
            // why it looks removable - but in a released IDE Logger.error raises the red "IDE
            // internal error - Report to JetBrains" balloon naming this plugin, on every use of the
            // headline feature, and in tests it fails the test outright (TestLoggerFactory turns
            // logged errors into failures, which this project deliberately keeps on).
            //
            // Verified against the pinned 2026.2.1 bytecode (lib/intellij.platform.core.jar):
            // ContextKt.prepareThreadContext calls ProgressManager.getGlobalProgressIndicator()
            // first and only falls through to the no-Job check when that is null, and
            // CoreProgressManager.runProcess -> executeProcessUnderProgress installs the indicator
            // on this thread for the duration of the call. ApplicationImpl.executeOnPooledThread
            // passes withContextJob = false and several call sites submit from plain AWT dispatch,
            // so without this there is neither an indicator nor a Job here.
            //
            // A fresh EmptyProgressIndicator per call, never a shared one: CoreProgressManager
            // .runProcess asserts no other thread is already running under the indicator it is
            // given. runBlockingMaybeCancellable would also silence the error but is @Deprecated as
            // platform-internal; indicatorRunBlockingCancellable is @Deprecated for the same reason.
            // Both ProgressManager.runProcess and EmptyProgressIndicator's constructor are only
            // @ApiStatus.Obsolete, which verifyPlugin accepts - see the class comment above for the
            // same trade-off around MavenEmbeddersManager.
            //
            // Skipped when an indicator is already installed, rather than wrapping unconditionally:
            // prepareThreadContext is already satisfied in that case, and installing a second
            // indicator would replace the caller's, so cancelling the outer progress would no
            // longer reach this fetch. (runProcess starts and stops the indicator it is given, so
            // re-installing the existing one is not an option either.)
            val resolve = Runnable {
                runBlockingCancellable {
                    embedder.resolveArtifacts(requests, null, MavenLogEventHandler)
                }
            }
            if (ProgressIndicatorProvider.getGlobalProgressIndicator() != null) {
                resolve.run()
            } else {
                ProgressManager.getInstance().runProcess(resolve, EmptyProgressIndicator())
            }
        } catch (e: CancellationException) {
            // Must come before the broad catch below: runBlockingCancellable rethrows
            // cancellation (project closing, progress indicator cancelled) as this exception,
            // and it is also an Exception - swallowing it here would break cooperative
            // cancellation instead of propagating it to the caller. This also covers
            // ProcessCanceledException, which extends java.util.concurrent.CancellationException
            // (verified against the pinned 2026.2.1 lib/util-8.jar) and is what
            // runBlockingCancellable actually throws, as CeProcessCanceledException.
            throw e
        } catch (e: Exception) {
            // Deliberately broad, matching BomEffectiveModelResolver's reflex for everything
            // that is not cancellation: one unreachable repository or one malformed coordinate
            // must degrade to "could not fetch" for the whole batch, never propagate into a
            // tool window refresh or a dialog load.
            logger.warn("Failed to fetch ${fetchable.size} POM(s) from remote repositories", e)
        } finally {
            embeddersManager.release(embedder)
        }

        // Success is decided by what is on disk, not by MavenArtifact.isResolved(): we asked for
        // packaging "pom", and isResolved()'s notion of resolved is about the primary artifact
        // file, which is not the question we need answered.
        val landed = fetchable.associateWith { it.pomFileIn(localRepositoryDir) }.filterValues { it.isFile }
        LocalFileSystem.getInstance().refreshIoFiles(landed.values)
        return landed.keys
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
