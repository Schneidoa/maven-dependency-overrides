package cloud.schneidoa.resolver

import java.io.File

sealed class ManagedVersionLookup {
    data class Found(val version: String, val declaredIn: Gav, val uncheckedBoms: List<Gav>) : ManagedVersionLookup()

    /**
     * No BOM in the supplied precedence list manages this artifact.
     * [uncheckedBoms] lists any BOMs that could NOT be resolved locally
     * (POM missing from the repository) — when non-empty, "not found" is
     * not a confident answer, since one of the unchecked BOMs might have
     * managed it. Callers must treat that case as "can't tell yet", not as
     * proof the override is safe to remove.
     */
    data class NotFound(val uncheckedBoms: List<Gav>) : ManagedVersionLookup()
}

/**
 * Every version an ordered BOM list manages, rather than the answer to one lookup.
 *
 * [uncheckedBoms] carries the same contract as [ManagedVersionLookup]'s: when it is
 * not empty the catalog is *partial*, and a Ga that is absent from [versions] means
 * "we could not check" rather than "nothing manages this". Callers that show these
 * entries to a user must say so rather than presenting the list as exhaustive.
 */
data class ManagedVersionCatalog(
    val versions: Map<Ga, String>,
    val uncheckedBoms: List<Gav>
)

/**
 * Folds POMs that could not be read from *outside* this resolver's BOM walk into the
 * answer's unchecked list - in practice, a parent whose POM was missing, which ended the
 * chain walk before this resolver ever saw the BOMs above it.
 *
 * Without this, a caller that resolves a truncated chain gets a confident answer computed
 * from a chain it does not know was incomplete: "no BOM manages this" when a BOM above the
 * break might. Both callers of these helpers used to do exactly that. Kept here rather than
 * in the callers so the merge cannot be forgotten at one of them - which is how the gap
 * arose in the first place.
 */
fun ManagedVersionLookup.plusUnchecked(additional: List<Gav>): ManagedVersionLookup =
    if (additional.isEmpty()) this else when (this) {
        is ManagedVersionLookup.Found -> copy(uncheckedBoms = uncheckedBoms + additional)
        is ManagedVersionLookup.NotFound -> copy(uncheckedBoms = uncheckedBoms + additional)
    }

/** Catalog counterpart of [plusUnchecked]; see its doc for why this exists. */
fun ManagedVersionCatalog.plusUnchecked(additional: List<Gav>): ManagedVersionCatalog =
    if (additional.isEmpty()) this else copy(uncheckedBoms = uncheckedBoms + additional)

/**
 * Searches an ordered list of BOM coordinates (nearest/highest-precedence
 * first) for the version a given `groupId:artifactId` is managed at,
 * independent of any local override in the consuming project's own POM.
 */
class BomVersionResolver(
    val localRepositoryDir: File,
    val onMissingPom: (Gav) -> Unit = {}
) {

    private val modelResolver = BomEffectiveModelResolver(localRepositoryDir, onMissingPom)

    fun resolveManagedVersion(bomsInPrecedenceOrder: List<Gav>, target: Ga): ManagedVersionLookup {
        val unchecked = mutableListOf<Gav>()

        for (bom in bomsInPrecedenceOrder) {
            when (val result = modelResolver.buildEffectiveModel(bom)) {
                is BomModelResult.Failure -> unchecked += bom
                is BomModelResult.Success -> {
                    val managedVersion = result.effectiveModel.dependencyManagement
                        ?.dependencies
                        ?.firstOrNull { it.groupId == target.groupId && it.artifactId == target.artifactId }
                        ?.version
                    if (managedVersion != null) {
                        return ManagedVersionLookup.Found(managedVersion, bom, unchecked.toList())
                    }
                }
            }
        }

        return ManagedVersionLookup.NotFound(unchecked)
    }

    /**
     * Projects the whole `dependencyManagement` of an ordered BOM list into one map,
     * for callers that need to offer choices rather than answer a single lookup.
     *
     * Unlike [resolveManagedVersion] this cannot stop at the first match - it has to
     * build every BOM's effective model - so detection deliberately keeps using the
     * cheaper single-target path and only the Add dialog pays for this walk.
     */
    fun collectManagedVersions(bomsInPrecedenceOrder: List<Gav>): ManagedVersionCatalog {
        val versions = LinkedHashMap<Ga, String>()
        val unchecked = mutableListOf<Gav>()

        for (bom in bomsInPrecedenceOrder) {
            when (val result = modelResolver.buildEffectiveModel(bom)) {
                is BomModelResult.Failure -> unchecked += bom
                is BomModelResult.Success ->
                    result.effectiveModel.dependencyManagement?.dependencies?.forEach { dependency ->
                        val groupId = dependency.groupId ?: return@forEach
                        val artifactId = dependency.artifactId ?: return@forEach
                        val version = dependency.version ?: return@forEach
                        // putIfAbsent, not put: the first BOM in precedence order wins,
                        // mirroring resolveManagedVersion's early return.
                        versions.putIfAbsent(Ga(groupId, artifactId), version)
                    }
            }
        }

        return ManagedVersionCatalog(versions, unchecked.toList())
    }
}

/** The unchecked list regardless of outcome - both branches carry the same contract. */
fun ManagedVersionLookup.uncheckedBoms(): List<Gav> = when (this) {
    is ManagedVersionLookup.Found -> uncheckedBoms
    is ManagedVersionLookup.NotFound -> uncheckedBoms
}
