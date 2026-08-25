package cloud.schneidoa.detection

import cloud.schneidoa.resolver.BomVersionResolver
import cloud.schneidoa.resolver.Gav
import cloud.schneidoa.resolver.ManagedVersionLookup
import cloud.schneidoa.resolver.VersionRelation
import cloud.schneidoa.resolver.compareDeclaredToManaged
import cloud.schneidoa.resolver.plusUnchecked
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

    /**
     * Every BOM in the chain was read successfully and none of them manages this
     * artifact — so the entry pins a version that would otherwise come from
     * Maven's transitive dependency mediation, not from dependency management.
     *
     * This is the shape of the most common CVE pin there is: an artifact pulled in
     * transitively (`org.apache.qpid:proton-j` under `com.azure:azure-core-amqp`,
     * say) gets pinned in `<dependencyManagement>` precisely *because* no BOM
     * governs it. Such a pin used to be dropped from the results entirely, which
     * made the tool window silently incomplete on exactly the entries a user is
     * most likely to be hunting for. It is reported instead, with no verdict on
     * whether it is still needed: answering that needs the resolved dependency
     * tree, which this plugin does not compute.
     *
     * [checkedBoms] is every BOM the chain did contain, in precedence order — the
     * evidence behind "none of these manages it", and empty only when a caller
     * constructs this outside [OverrideDetector] (detection itself never reports
     * Unmanaged for a module with no BOM chain at all; see [OverrideDetector.evaluate]).
     */
    data class Unmanaged(
        val candidate: OverrideCandidate,
        val checkedBoms: List<Gav>
    ) : DetectedOverride()
}

/** The candidate common to every outcome - lets callers navigate/display without a `when`. */
val DetectedOverride.candidate: OverrideCandidate
    get() = when (this) {
        is DetectedOverride.Confirmed -> candidate
        is DetectedOverride.Inconclusive -> candidate
        is DetectedOverride.Unmanaged -> candidate
    }

class OverrideDetector(private val bomVersionResolver: BomVersionResolver) {

    private val bomChainResolver =
        BomChainResolver(bomVersionResolver.localRepositoryDir, bomVersionResolver.onMissingPom)

    fun detect(model: MavenDomProjectModel, project: Project): List<DetectedOverride> {
        val bomChain = bomChainResolver.resolveBomChain(model, project)
        // Hoisted out of evaluate(): bomChain is the same for every candidate in this
        // module, so this projection is otherwise rebuilt once per candidate (twice for
        // an Unmanaged one) for no reason - wasted allocation on the editor's
        // per-keystroke inspection hot path, worse the larger the chain (a Spring Boot
        // chain can hold on the order of 1000+ entries).
        val bomGavs = bomChain.imports.map { it.bom }

        return DependencyManagementScanner.scan(model)
            .filterNot { it.suppressed }
            .mapNotNull { candidate -> evaluate(candidate, bomChain, bomGavs) }
    }

    private fun evaluate(candidate: OverrideCandidate, bomChain: BomChain, bomGavs: List<Gav>): DetectedOverride? {
        // A parent we could not read is exactly as disqualifying as a BOM we could not read:
        // both mean the chain we searched was not the whole chain. plusUnchecked is the same
        // merge ManagedVersionHint uses for the identical fold, applied right at the source
        // rather than re-derived here, so the two can't drift apart.
        val lookup = bomVersionResolver.resolveManagedVersion(bomGavs, candidate.ga)
            .plusUnchecked(bomChain.truncatedAt)

        return when (lookup) {
            is ManagedVersionLookup.Found ->
                if (lookup.uncheckedBoms.isNotEmpty()) {
                    DetectedOverride.Inconclusive(candidate, lookup.uncheckedBoms)
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
                when {
                    lookup.uncheckedBoms.isNotEmpty() -> DetectedOverride.Inconclusive(candidate, lookup.uncheckedBoms)
                    // A readable chain that manages this artifact nowhere: the pin acts on
                    // transitive resolution rather than on a BOM. Reported, not dropped.
                    bomChain.imports.isNotEmpty() -> DetectedOverride.Unmanaged(candidate, bomGavs)
                    // No BOM anywhere in the chain, so there is nothing here to override in the
                    // first place - every literal-version <dependencyManagement> entry in the
                    // module would otherwise be reported, turning a plain dependency-management
                    // block into a full page of findings that say nothing.
                    else -> null
                }
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
