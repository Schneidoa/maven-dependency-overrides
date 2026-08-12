package cloud.schneidoa.detection

import cloud.schneidoa.resolver.BomVersionResolver
import cloud.schneidoa.resolver.Ga
import cloud.schneidoa.resolver.Gav
import cloud.schneidoa.resolver.ManagedVersionLookup

/** One BOM in a module's chain, and what it had to say about a particular artifact. */
sealed class BomChainEntry {
    abstract val bom: Gav
    abstract val declaredVia: List<String>

    /**
     * [wins] marks the first BOM in precedence order that manages the artifact - the one Maven would
     * actually use. It is only true when every BOM ahead of this one in the chain was resolvable: an
     * earlier BOM that could not be checked locally might manage the artifact at a different version,
     * and precedence would then go to that unverified answer, not to this one. Claiming a win anyway
     * would be exactly the sort of confident-looking but unverified "this is the version that applies"
     * this plugin exists to avoid - the same "miss rather than false-safe" principle that drives the
     * Confirmed/Inconclusive split elsewhere.
     *
     * [blockedByUnresolvable] is true for the entry that would otherwise have been the winner - the
     * first BOM in the chain that manages the artifact - but couldn't claim it because an earlier BOM
     * in precedence order was unresolvable. It exists so callers (the BOM chain dialog) can tell that
     * case apart from a plain loss to an earlier BOM that *did* resolve and manage the artifact, since
     * the two warrant different wording: one names a known winner, the other says precedence is unknown.
     */
    data class Manages(
        override val bom: Gav,
        override val declaredVia: List<String>,
        val version: String,
        val wins: Boolean,
        val blockedByUnresolvable: Boolean = false
    ) : BomChainEntry()

    data class DoesNotManage(
        override val bom: Gav,
        override val declaredVia: List<String>
    ) : BomChainEntry()

    /** The BOM's POM wasn't in the local repository, so it could not be consulted at all. */
    data class Unresolvable(
        override val bom: Gav,
        override val declaredVia: List<String>
    ) : BomChainEntry()
}

data class BomChainReport(
    val ga: Ga,
    val declaredVersion: String,
    val moduleLabel: String,
    val entries: List<BomChainEntry>
)

/**
 * Probes every BOM in [bomChain] individually, rather than asking the resolver
 * for one answer over the whole list. The normal detection path stops at the
 * first BOM that manages the artifact, which is the right answer but a poor
 * explanation - this builds the full picture the "Show BOM Chain" dialog needs,
 * including the BOMs that lost and the ones that could not be read.
 *
 * Passing a single-element list per BOM lets an unresolvable BOM be told apart
 * from a merely silent one: the resolver reports the former as NotFound with
 * that BOM listed in uncheckedBoms.
 */
fun buildBomChainReport(
    ga: Ga,
    declaredVersion: String,
    moduleLabel: String,
    bomChain: List<BomImport>,
    resolver: BomVersionResolver
): BomChainReport {
    var winnerAlreadySeen = false
    var blockedByUnresolvable = false

    val entries = bomChain.map { import ->
        when (val lookup = resolver.resolveManagedVersion(listOf(import.bom), ga)) {
            is ManagedVersionLookup.Found -> {
                val isFirstManager = !winnerAlreadySeen
                val wins = isFirstManager && !blockedByUnresolvable
                winnerAlreadySeen = true
                BomChainEntry.Manages(
                    import.bom,
                    import.declaredVia,
                    lookup.version,
                    wins = wins,
                    blockedByUnresolvable = isFirstManager && blockedByUnresolvable
                )
            }
            is ManagedVersionLookup.NotFound ->
                if (lookup.uncheckedBoms.isEmpty()) {
                    BomChainEntry.DoesNotManage(import.bom, import.declaredVia)
                } else {
                    blockedByUnresolvable = true
                    BomChainEntry.Unresolvable(import.bom, import.declaredVia)
                }
        }
    }

    return BomChainReport(ga, declaredVersion, moduleLabel, entries)
}
