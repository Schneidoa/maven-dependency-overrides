package cloud.schneidoa.detection

import cloud.schneidoa.resolver.Gav
import cloud.schneidoa.resolver.isConcrete
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project

/**
 * Runs a local, offline resolution repeatedly, fetching the POMs it reported missing between
 * attempts, until it stops reporting new ones.
 *
 * The obvious alternative - having the resolver fetch on demand, in the middle of the model
 * build - does not work, for two independent reasons worth recording here so nobody
 * "simplifies" it back:
 *
 *  1. A freshly downloaded file is invisible to the VFS until refreshed, and a synchronous
 *     VFS refresh throws under the read lock. Every caller resolves inside a read action.
 *  2. BomModelResult.Failure names the BOM whose build failed, not the POM that was actually
 *     missing - which for a broken parent is an artifact already on disk. An on-demand
 *     fetcher would keep re-requesting something it already has and never converge.
 *
 * [maxRounds] is a termination guarantee, not a tuning knob: a newly fetched BOM can reveal
 * nested imports and parents that were not visible before, so rounds could otherwise chain
 * indefinitely. Running out of rounds costs a residual Inconclusive, never a wrong answer.
 *
 * Must be called on a background thread: it takes the read lock itself, and [fetcher] does
 * network I/O and a VFS refresh outside it.
 */
fun <T> resolvingMissingPoms(
    project: Project,
    fetcher: (Set<Gav>) -> Set<Gav> = { RemotePomFetcher(project).fetch(it) },
    maxRounds: Int = 3,
    resolve: (onMissingPom: (Gav) -> Unit) -> T
): T {
    val unfetchable = mutableSetOf<Gav>()
    var round = 0

    while (true) {
        val missing = linkedSetOf<Gav>()
        val result = ReadAction.compute<T, Throwable> { resolve { missing += it } }
        round++

        // isConcrete filters out coordinates still carrying ${...}: before Maven sync,
        // MavenPropertyResolver returns its input unchanged, so a BOM declared at a property
        // version arrives as a placeholder. Those are not misses to fetch - and leaving them in
        // would make every round report the same unfetchable coordinate and burn the round
        // budget without ever converging.
        val toFetch = missing.filter { it.isConcrete() }.toSet() - unfetchable
        if (toFetch.isEmpty() || round >= maxRounds) return result

        val fetched = fetcher(toFetch)
        if (fetched.isEmpty()) return result
        unfetchable += toFetch - fetched
    }
}
