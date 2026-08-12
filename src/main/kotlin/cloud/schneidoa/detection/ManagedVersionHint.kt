package cloud.schneidoa.detection

import cloud.schneidoa.resolver.BomVersionResolver
import cloud.schneidoa.resolver.Ga
import cloud.schneidoa.resolver.ManagedVersionCatalog
import cloud.schneidoa.resolver.ManagedVersionLookup
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

    private val bomChainResolver = BomChainResolver(bomVersionResolver.localRepositoryDir)

    fun lookup(model: MavenDomProjectModel, project: Project, ga: Ga): ManagedVersionLookup {
        val bomChain = bomChainResolver.resolveBomChain(model, project)
        return bomVersionResolver.resolveManagedVersion(bomChain.map { it.bom }, ga)
    }

    /**
     * Everything the module's BOM chain manages, for the Add dialog's suggestions.
     * Same chain resolution as [lookup], different projection - and a heavier one,
     * since it cannot stop at a first match. Call it once per module selection, not
     * per keystroke.
     */
    fun catalog(model: MavenDomProjectModel, project: Project): ManagedVersionCatalog {
        val bomChain = bomChainResolver.resolveBomChain(model, project)
        return bomVersionResolver.collectManagedVersions(bomChain.map { it.bom })
    }

    companion object {
        /** Convenience factory for real IDE usage, matching OverrideDetector.forProject. */
        fun forProject(project: Project): ManagedVersionHint {
            val localRepositoryDir = MavenProjectsManager.getInstance(project).repositoryPath.toFile()
            return ManagedVersionHint(BomVersionResolver(localRepositoryDir))
        }
    }
}
