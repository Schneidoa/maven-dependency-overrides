package cloud.schneidoa.detection

import cloud.schneidoa.resolver.Gav
import cloud.schneidoa.resolver.pomFileIn
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.idea.maven.dom.MavenDomUtil
import org.jetbrains.idea.maven.dom.MavenPropertyResolver
import org.jetbrains.idea.maven.dom.model.MavenDomDependency
import org.jetbrains.idea.maven.dom.model.MavenDomParent
import org.jetbrains.idea.maven.dom.model.MavenDomProjectModel
import java.io.File

/**
 * Bounds how far up the <parent> chain we'll climb. A real Maven precedence
 * walk would be indefinite, but Maven itself has practical limits on parent
 * depth too; this guards against a malformed/cyclic <parent> reference in a
 * broken POM turning into an infinite loop, without relying on
 * MavenDomProjectModel equality semantics (unverified) for cycle detection.
 */
private const val MAX_PARENT_CHAIN_DEPTH = 50
private const val DEFAULT_RELATIVE_PARENT_PATH = "../pom.xml"

/**
 * A BOM found somewhere in the parent chain, plus the path of artifactIds
 * climbed from the original module (exclusive) down to (and including) the
 * module whose own <dependencyManagement> declares it. Empty when the BOM
 * is declared directly in the original module itself.
 */
data class BomImport(val bom: Gav, val declaredVia: List<String>)

/**
 * The BOM imports found while climbing the parent chain, plus every parent the walk could
 * not get past - which matters because it means the walk stopped early and the imports
 * list is therefore incomplete rather than exhaustive. A caller that ignores [truncatedAt]
 * will read a short chain as a complete one and can report a confident "nothing manages
 * this" for an artifact a BOM above the break manages.
 *
 * [truncatedAt] covers all three ways a parent can stop the walk: its POM is not in the
 * local repository, its POM is there but yields no DOM model, and its <parent> element
 * names no resolvable coordinate at all. The last of those has no real GAV, so it is
 * recorded with "?" in place of whichever parts the POM did not declare.
 */
data class BomChain(val imports: List<BomImport>, val truncatedAt: List<Gav>)

class BomChainResolver(
    private val localRepositoryDir: File,
    private val onMissingPom: (Gav) -> Unit = {}
) {

    fun resolveBomChain(model: MavenDomProjectModel, project: Project): BomChain {
        val chain = mutableListOf<BomImport>()
        val truncatedAt = mutableListOf<Gav>()
        var current: MavenDomProjectModel? = model
        var path: List<String> = emptyList()
        var depth = 0

        // The depth cut-off is deliberately NOT recorded as a truncation. It only fires on a
        // cyclic or absurdly deep <parent> chain, which is a POM Maven itself cannot build; the
        // guard exists to stop, not to describe. Every truncation that can happen in a POM Maven
        // would accept is reported through onTruncated below.
        while (current != null && depth < MAX_PARENT_CHAIN_DEPTH) {
            val declaredHere = path
            chain += importedBomsOf(current).map { BomImport(it, declaredHere) }

            val parent = resolveParent(current, project) { truncatedAt += it }
            if (parent != null) {
                path = path + artifactIdOf(parent)
            }
            current = parent
            depth++
        }

        return BomChain(chain, truncatedAt.toList())
    }

    private fun importedBomsOf(model: MavenDomProjectModel): List<Gav> {
        val managedDependencies: List<MavenDomDependency> = model.dependencyManagement.dependencies.dependencies

        return managedDependencies
            // Deliberately requires both scope=import AND type=pom: an entry with
            // scope=import but a non-pom type is malformed and isn't treated as
            // either a BOM import (here) or an override candidate
            // (DependencyManagementScanner excludes anything with scope=import
            // regardless of type) - it's silently ignored by both, matching this
            // project's "miss rather than false-safe" principle.
            .filter { it.scope.rawText?.trim() == "import" && it.type.rawText?.trim() == "pom" }
            .mapNotNull { toGav(it, model) }
    }

    private fun toGav(dependency: MavenDomDependency, model: MavenDomProjectModel): Gav? {
        val groupId = dependency.groupId.rawText?.trim()
        val artifactId = dependency.artifactId.rawText?.trim()
        val rawVersion = dependency.version.rawText?.trim()
        if (groupId.isNullOrEmpty() || artifactId.isNullOrEmpty() || rawVersion.isNullOrEmpty()) return null
        // Hands back rawVersion unchanged while MavenProjectsManager is uninitialized
        // (IDEA 2026.2+), i.e. before the first Maven sync - the BOM then resolves to no
        // POM in the local repository and lands in uncheckedBoms, i.e. Inconclusive.
        return Gav(groupId, artifactId, MavenPropertyResolver.resolve(rawVersion, model))
    }

    private fun artifactIdOf(model: MavenDomProjectModel): String =
        model.artifactId.rawText?.trim().takeUnless { it.isNullOrEmpty() } ?: "?"

    /**
     * Resolves <parent>, preferring <relativePath> file navigation (not via
     * MavenDomProjectProcessorUtils.findParent, which only works against
     * MavenProjectsManager's already-synced project index) and falling back
     * to a local-repository GAV lookup whenever that doesn't produce a
     * result, mirroring real Maven's own two-stage parent resolution.
     */
    private fun resolveParent(
        model: MavenDomProjectModel,
        project: Project,
        onTruncated: (Gav) -> Unit
    ): MavenDomProjectModel? {
        val parent = model.mavenParent
        if (parent.xmlTag == null) return null

        return resolveParentViaRelativePath(model, parent, project, onTruncated)
            ?: resolveParentViaLocalRepository(parent, model, project, onTruncated)
    }

    /**
     * Falls back to Maven's own documented default of "../pom.xml" when
     * <relativePath> is absent (rawText == null). An explicit-but-empty
     * <relativePath/> means "don't do relative lookup at all" per Maven's
     * own semantics, which is different from "not written" — so that case
     * skips file lookup entirely (returns null, letting the caller fall
     * back to a repository lookup) rather than guessing the default and
     * possibly picking up an unrelated file that happens to sit there. If
     * the resolved path is a directory rather than a file (also valid per
     * Maven's own relativePath semantics), looks for pom.xml inside it.
     *
     * [onTruncated] fires only for the case that is otherwise silent: the relativePath
     * resolves to a real file, but that file does not yield a DOM model (malformed XML,
     * an in-progress edit, a merge conflict). Every other null return here (no
     * relativePath given and "../pom.xml" doesn't exist, an explicit-empty
     * <relativePath/>, no containing file to resolve against) is a normal, expected
     * reason to fall back to the repository lookup and is not reported - the repository
     * lookup has its own onTruncated for whatever it in turn cannot resolve. Without
     * this one case reported, a relativePath'd parent that is merely broken right now
     * would silently fall through to whatever unrelated, possibly stale copy of the same
     * GAV happens to sit in the local repository, and the walk would read as complete.
     */
    private fun resolveParentViaRelativePath(
        model: MavenDomProjectModel,
        parent: MavenDomParent,
        project: Project,
        onTruncated: (Gav) -> Unit
    ): MavenDomProjectModel? {
        val currentFile = model.xmlTag?.containingFile?.virtualFile ?: return null
        val baseDir = currentFile.parent ?: return null

        val rawRelativePath = parent.relativePath.rawText
        val relativePath = when {
            rawRelativePath == null -> DEFAULT_RELATIVE_PARENT_PATH
            rawRelativePath.isBlank() -> return null
            else -> rawRelativePath.trim()
        }

        val resolved = VfsUtilCore.findRelativeFile(relativePath, baseDir) ?: return null
        val parentFile: VirtualFile? = if (resolved.isDirectory) resolved.findChild("pom.xml") else resolved
        val parentModel = parentFile?.let { MavenDomUtil.getMavenDomProjectModel(project, it) }
        if (parentFile != null && parentModel == null) onTruncated(declaredParentGav(parent))
        return parentModel
    }

    /**
     * The `<parent>` element's own declared coordinate, with "?" standing in for
     * whichever parts are missing or blank - the same placeholder [artifactIdOf] uses,
     * so a rendered [BomChain.truncatedAt] entry says what is actually known about the
     * parent that broke the walk instead of inventing a value. Shared by both parent
     * resolution strategies so their placeholder GAVs are constructed identically.
     */
    private fun declaredParentGav(parent: MavenDomParent): Gav = Gav(
        parent.groupId.rawText?.trim()?.takeUnless { it.isEmpty() } ?: "?",
        parent.artifactId.rawText?.trim()?.takeUnless { it.isEmpty() } ?: "?",
        parent.version.rawText?.trim()?.takeUnless { it.isEmpty() } ?: "?"
    )

    /**
     * Looks the parent POM up by GAV coordinate in the local Maven
     * repository, the same way real Maven resolves a parent whose
     * <relativePath> is absent, wrong, or explicitly empty (the standard
     * shape Spring Initializr generates for non-multi-module projects).
     */
    private fun resolveParentViaLocalRepository(
        parent: MavenDomParent,
        model: MavenDomProjectModel,
        project: Project,
        onTruncated: (Gav) -> Unit
    ): MavenDomProjectModel? {
        val groupId = parent.groupId.rawText?.trim()
        val artifactId = parent.artifactId.rawText?.trim()
        val rawVersion = parent.version.rawText?.trim()
        if (groupId.isNullOrEmpty() || artifactId.isNullOrEmpty() || rawVersion.isNullOrEmpty()) {
            // A <parent> element is present but does not name a resolvable coordinate, and
            // relativePath resolution has already failed - so the walk ends here and the chain is
            // not exhaustive. Reported through the same channel as a missing POM rather than a new
            // one: truncatedAt is what makes detection go Inconclusive, and "we could not walk past
            // this point" is exactly the same fact whether or not the point had a name. The unknown
            // parts are rendered as "?" (the same placeholder artifactIdOf already uses) so the
            // displayed coordinate says what is actually known instead of inventing one. Note this
            // deliberately does NOT call onMissingPom: there is nothing concrete to download, and
            // handing a "?" coordinate to RemotePomFetcher would be a guaranteed-404 request.
            onTruncated(declaredParentGav(parent))
            return null
        }
        val version = MavenPropertyResolver.resolve(rawVersion, model)

        val gav = Gav(groupId, artifactId, version)
        val pomFile = gav.pomFileIn(localRepositoryDir)
        val virtualFile = LocalFileSystem.getInstance().findFileByIoFile(pomFile)
        if (virtualFile == null) {
            // Both callbacks fire and they are not the same thing: onMissingPom tells a fetch
            // loop what to download, onTruncated tells the caller this chain is incomplete and
            // must not be read as exhaustive. Fetching may fix the first without the second
            // ever becoming untrue for this pass.
            onMissingPom(gav)
            onTruncated(gav)
            return null
        }
        // The POM file exists but produced no DOM model - an unparseable, truncated or
        // error-bodied file. This branch got *more* likely once RemotePomFetcher started writing
        // POMs into the repository, and it is the worst kind of silent truncation: without
        // onTruncated the chain reads as fully walked and detection can report Confirmed - i.e.
        // "safe to remove" - on evidence it never actually gathered. Not routed through
        // onMissingPom: the file is on disk, so a re-fetch loop would never converge.
        val parentModel = MavenDomUtil.getMavenDomProjectModel(project, virtualFile)
        if (parentModel == null) onTruncated(gav)
        return parentModel
    }
}
