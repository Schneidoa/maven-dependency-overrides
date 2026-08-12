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

class BomChainResolver(private val localRepositoryDir: File) {

    fun resolveBomChain(model: MavenDomProjectModel, project: Project): List<BomImport> {
        val chain = mutableListOf<BomImport>()
        var current: MavenDomProjectModel? = model
        var path: List<String> = emptyList()
        var depth = 0

        while (current != null && depth < MAX_PARENT_CHAIN_DEPTH) {
            val declaredHere = path
            chain += importedBomsOf(current).map { BomImport(it, declaredHere) }

            val parent = resolveParent(current, project)
            if (parent != null) {
                path = path + artifactIdOf(parent)
            }
            current = parent
            depth++
        }

        return chain
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
    private fun resolveParent(model: MavenDomProjectModel, project: Project): MavenDomProjectModel? {
        val parent = model.mavenParent
        if (parent.xmlTag == null) return null

        return resolveParentViaRelativePath(model, parent, project)
            ?: resolveParentViaLocalRepository(parent, model, project)
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
     */
    private fun resolveParentViaRelativePath(
        model: MavenDomProjectModel,
        parent: MavenDomParent,
        project: Project
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
        return parentFile?.let { MavenDomUtil.getMavenDomProjectModel(project, it) }
    }

    /**
     * Looks the parent POM up by GAV coordinate in the local Maven
     * repository, the same way real Maven resolves a parent whose
     * <relativePath> is absent, wrong, or explicitly empty (the standard
     * shape Spring Initializr generates for non-multi-module projects).
     */
    private fun resolveParentViaLocalRepository(
        parent: MavenDomParent,
        model: MavenDomProjectModel,
        project: Project
    ): MavenDomProjectModel? {
        val groupId = parent.groupId.rawText?.trim()
        val artifactId = parent.artifactId.rawText?.trim()
        val rawVersion = parent.version.rawText?.trim()
        if (groupId.isNullOrEmpty() || artifactId.isNullOrEmpty() || rawVersion.isNullOrEmpty()) return null
        val version = MavenPropertyResolver.resolve(rawVersion, model)

        val pomFile = Gav(groupId, artifactId, version).pomFileIn(localRepositoryDir)
        val virtualFile = LocalFileSystem.getInstance().findFileByIoFile(pomFile) ?: return null
        return MavenDomUtil.getMavenDomProjectModel(project, virtualFile)
    }
}
