package cloud.schneidoa.detection

import cloud.schneidoa.resolver.BomVersionResolver
import cloud.schneidoa.resolver.Ga
import cloud.schneidoa.resolver.Gav
import cloud.schneidoa.resolver.ManagedVersionCatalog
import cloud.schneidoa.resolver.ManagedVersionLookup
import cloud.schneidoa.resolver.plusUnchecked
import com.intellij.openapi.project.Project
import org.jetbrains.idea.maven.dom.model.MavenDomProjectModel
import org.jetbrains.idea.maven.project.MavenProjectsManager

/**
 * Looks up what a module's BOM chain currently manages a given GA at,
 * without requiring the GA to already be declared in that module's own
 * <dependencyManagement> - used by the Add/Edit dialogs to show a live hint
 * of what's already managed, informing what version to pin an override at.
 * Reuses exactly the same resolution BomChainResolver/BomVersionResolver
 * already perform for real detection; this is a thin wrapper, not new logic.
 *
 * Unlike OverrideDetector, this returns the raw ManagedVersionLookup as-is
 * rather than translating it into a Confirmed/Inconclusive distinction: a
 * hint has nothing to be confident or inconclusive *about* - the dialog
 * either shows what's managed or shows nothing, so there's no decision here
 * that needs the tri-state OverrideDetector uses to gate a real detection.
 */
class ManagedVersionHint(private val bomVersionResolver: BomVersionResolver) {

    private val bomChainResolver =
        BomChainResolver(bomVersionResolver.localRepositoryDir, bomVersionResolver.onMissingPom)

    /**
     * [BomChain.truncatedAt] is merged into the answer for the same reason
     * [OverrideDetector] merges it: a parent POM we could not read ended the walk early, so
     * the BOM list searched here is not the whole chain. Without the merge the dialog would
     * state "not currently managed by any BOM in this module's chain" as fact, when a BOM
     * above the break might manage it - and a user acting on that creates a pin they may not
     * need, or trusts an answer the plugin was never entitled to give.
     */
    fun lookup(model: MavenDomProjectModel, project: Project, ga: Ga): ManagedVersionLookup {
        val bomChain = bomChainResolver.resolveBomChain(model, project)
        return bomVersionResolver.resolveManagedVersion(bomChain.imports.map { it.bom }, ga)
            .plusUnchecked(bomChain.truncatedAt)
    }

    /**
     * Everything the module's BOM chain manages, for the Add dialog's suggestions.
     * Same chain resolution as [lookup], different projection - and a heavier one,
     * since it cannot stop at a first match. Call it once per module selection, not
     * per keystroke.
     *
     * Merges [BomChain.truncatedAt] for the same reason [lookup] does, with one extra
     * consequence: `DependencyCandidates.isComplete` is derived from `uncheckedBoms`, so
     * dropping it would let the Add dialog present a suggestion list as exhaustive when a
     * truncated chain means it cannot be.
     */
    fun catalog(model: MavenDomProjectModel, project: Project): ManagedVersionCatalog {
        val bomChain = bomChainResolver.resolveBomChain(model, project)
        return bomVersionResolver.collectManagedVersions(bomChain.imports.map { it.bom })
            .plusUnchecked(bomChain.truncatedAt)
    }

    companion object {
        /** Convenience factory for real IDE usage, matching OverrideDetector.forProject. */
        fun forProject(project: Project, onMissingPom: (Gav) -> Unit = {}): ManagedVersionHint {
            val localRepositoryDir = MavenProjectsManager.getInstance(project).repositoryPath.toFile()
            return ManagedVersionHint(BomVersionResolver(localRepositoryDir, onMissingPom))
        }
    }
}
