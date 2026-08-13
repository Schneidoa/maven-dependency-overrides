package cloud.schneidoa.detection

import cloud.schneidoa.resolver.BomVersionResolver
import cloud.schneidoa.resolver.Gav
import cloud.schneidoa.resolver.ManagedVersionLookup
import cloud.schneidoa.resolver.VersionRelation
import cloud.schneidoa.resolver.compareDeclaredToManaged
import cloud.schneidoa.resolver.uncheckedBoms
import com.intellij.openapi.project.Project
import org.jetbrains.idea.maven.dom.model.MavenDomProjectModel
import org.jetbrains.idea.maven.project.MavenProjectsManager

sealed class DetectedOverride {
    /**
     * An unsuppressed override whose managing BOM chain resolved cleanly, so we
     * know exactly what the BOM manages it at. [relation] says how the declared
     * version compares to that — which is what decides whether the override is
     * redundant, still doing useful work, or holding the version back.
     */
    data class Confirmed(
        val candidate: OverrideCandidate,
        val bomVersion: String,
        val declaredInBom: Gav,
        val managedByChain: List<String>,
        val relation: VersionRelation
    ) : DetectedOverride()

    /**
     * Looks like it might be an override, but at least one BOM in the chain
     * couldn't be resolved locally — so "not managed anywhere" can't be
     * confidently ruled out. See ManagedVersionLookup.NotFound's contract.
     */
    data class Inconclusive(
        val candidate: OverrideCandidate,
        val uncheckedBoms: List<Gav>
    ) : DetectedOverride()
}

/** The candidate common to both outcomes - lets callers navigate/display without a `when`. */
val DetectedOverride.candidate: OverrideCandidate
    get() = when (this) {
        is DetectedOverride.Confirmed -> candidate
        is DetectedOverride.Inconclusive -> candidate
    }

class OverrideDetector(private val bomVersionResolver: BomVersionResolver) {

    private val bomChainResolver =
        BomChainResolver(bomVersionResolver.localRepositoryDir, bomVersionResolver.onMissingPom)

    fun detect(model: MavenDomProjectModel, project: Project): List<DetectedOverride> {
        val bomChain = bomChainResolver.resolveBomChain(model, project)

        return DependencyManagementScanner.scan(model)
            .filterNot { it.suppressed }
            .mapNotNull { candidate -> evaluate(candidate, bomChain) }
    }

    private fun evaluate(candidate: OverrideCandidate, bomChain: BomChain): DetectedOverride? {
        val lookup = bomVersionResolver.resolveManagedVersion(bomChain.imports.map { it.bom }, candidate.ga)
        // A parent we could not read is exactly as disqualifying as a BOM we could not read:
        // both mean the chain we searched was not the whole chain.
        val unchecked = lookup.uncheckedBoms() + bomChain.truncatedAt

        return when (lookup) {
            is ManagedVersionLookup.Found ->
                if (unchecked.isNotEmpty()) {
                    DetectedOverride.Inconclusive(candidate, unchecked)
                } else {
                    // Equal versions are reported too, not filtered out: a pin the BOM has
                    // exactly caught up to is the redundant one this plugin exists to find.
                    val declaredVia =
                        bomChain.imports.firstOrNull { it.bom == lookup.declaredIn }?.declaredVia ?: emptyList()
                    DetectedOverride.Confirmed(
                        candidate,
                        lookup.version,
                        lookup.declaredIn,
                        declaredVia,
                        compareDeclaredToManaged(candidate.declaredVersion, lookup.version)
                    )
                }
            is ManagedVersionLookup.NotFound ->
                if (unchecked.isNotEmpty()) DetectedOverride.Inconclusive(candidate, unchecked) else null
        }
    }

    companion object {
        /** Convenience factory for real IDE usage — reads the local repo path IntelliJ's Maven support already knows about. */
        fun forProject(project: Project): OverrideDetector {
            val localRepositoryDir = MavenProjectsManager.getInstance(project).repositoryPath.toFile()
            return OverrideDetector(BomVersionResolver(localRepositoryDir))
        }
    }
}
