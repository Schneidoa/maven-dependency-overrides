package cloud.schneidoa.detection

import cloud.schneidoa.resolver.Ga
import cloud.schneidoa.resolver.ManagedVersionCatalog

/**
 * The rules relating the Add dialog's Group ID and Artifact ID fields.
 *
 * These live here rather than in the dialog because this project leaves its Swing
 * classes hand-verified rather than unit-tested, which is only affordable while they
 * hold nothing worth testing. Field coupling is worth testing, so it does not go
 * there. Nothing in this file touches the IntelliJ Platform.
 */
class DependencyCandidates(private val catalog: ManagedVersionCatalog) {

    /** False when a BOM in the chain could not be read, so these suggestions omit unknown entries. */
    val isComplete: Boolean = catalog.uncheckedBoms.isEmpty()

    val unreadableBomCount: Int = catalog.uncheckedBoms.size

    // The catalog is immutable for the lifetime of an instance (one per module
    // selection), but the dialog calls these on every keystroke in fields the chain
    // can have ~1400 entries under - a Spring Boot chain, for instance. Rebuilding
    // map+distinct+sort per call turns typing into a full-catalog scan; computing
    // each index once and reusing it turns it back into a map lookup.

    private val groupsLazy: List<String> by lazy { catalog.versions.keys.map { it.groupId }.distinct().sorted() }

    private val artifactIdsByGroup: Map<String, List<String>> by lazy {
        catalog.versions.keys.groupBy({ it.groupId }, { it.artifactId }).mapValues { (_, ids) -> ids.distinct().sorted() }
    }

    private val groupsByArtifactId: Map<String, List<String>> by lazy {
        catalog.versions.keys.groupBy({ it.artifactId }, { it.groupId }).mapValues { (_, groups) -> groups.distinct() }
    }

    private val allArtifactIdsLazy: List<String> by lazy {
        catalog.versions.keys.map { it.artifactId }.distinct().sorted()
    }

    private val allCoordinatesLazy: List<Ga> by lazy {
        catalog.versions.keys.sortedWith(compareBy({ it.groupId }, { it.artifactId }))
    }

    fun groups(): List<String> = groupsLazy

    fun artifactsIn(groupId: String): List<String> = artifactIdsByGroup[groupId] ?: emptyList()

    fun allArtifactIds(): List<String> = allArtifactIdsLazy

    fun allCoordinates(): List<Ga> = allCoordinatesLazy

    /**
     * The group owning [artifactId] - but only when exactly one does. Null both for an
     * unknown artifactId and, deliberately, for an ambiguous one: filling the group
     * field from a guess would be worse than leaving it, and the caller still has the
     * full-coordinate list to disambiguate with.
     */
    fun uniqueGroupFor(artifactId: String): String? = groupsByArtifactId[artifactId]?.singleOrNull()

    fun managedVersionOf(ga: Ga): String? = catalog.versions[ga]
}
