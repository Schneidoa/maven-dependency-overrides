package cloud.schneidoa.detection

import cloud.schneidoa.resolver.VersionRelation
import cloud.schneidoa.resolver.isConcrete

/**
 * Formats "declared version → what the BOM chain manages it at" for display; "?" when the
 * managed version can't be confirmed, and a spelled-out "not managed" when the chain was
 * fully read and simply manages nothing here - deliberately not another glyph, since the
 * difference from "?" (we could not check) is the whole point of the distinction.
 */
fun declaredToManaged(override: DetectedOverride): String = when (override) {
    is DetectedOverride.Confirmed -> "${override.candidate.declaredVersion} → ${override.bomVersion}"
    is DetectedOverride.Inconclusive -> "${override.candidate.declaredVersion} → ?"
    is DetectedOverride.Unmanaged -> "${override.candidate.declaredVersion} → not managed"
}

/**
 * What the developer should conclude about an override. Deliberately semantic
 * rather than presentational - the panel maps this to an icon and the inspection
 * maps it to whether to register a problem at all, and those two answers differ.
 */
enum class OverrideVerdict { REDUNDANT, AHEAD_OF_BOM, BEHIND_BOM, NOT_COMPARABLE, INCONCLUSIVE, UNMANAGED }

fun verdictOf(override: DetectedOverride): OverrideVerdict = when (override) {
    is DetectedOverride.Inconclusive -> OverrideVerdict.INCONCLUSIVE
    is DetectedOverride.Unmanaged -> OverrideVerdict.UNMANAGED
    is DetectedOverride.Confirmed -> when (override.relation) {
        VersionRelation.SAME -> OverrideVerdict.REDUNDANT
        VersionRelation.NEWER -> OverrideVerdict.AHEAD_OF_BOM
        VersionRelation.OLDER -> OverrideVerdict.BEHIND_BOM
        VersionRelation.INCOMPARABLE -> OverrideVerdict.NOT_COMPARABLE
    }
}

/** Short label for the panel's Status column. */
fun verdictLabel(override: DetectedOverride): String = when (override) {
    is DetectedOverride.Inconclusive -> {
        val count = override.uncheckedBoms.size
        // "POM", not "BOM": uncheckedBoms carries two kinds of thing since the parent-chain
        // truncation fix - BOMs whose own POM could not be read, and parent POMs that ended
        // the chain walk. Calling a missing parent a BOM sends the reader looking for a BOM
        // in "Show BOM Chain" that isn't there, because the problem sits a level above.
        "Inconclusive ($count POM${if (count == 1) "" else "s"} unchecked)"
    }
    is DetectedOverride.Unmanaged -> {
        val count = override.checkedBoms.size
        "Not managed by BOM ($count BOM${if (count == 1) "" else "s"} checked)"
    }
    is DetectedOverride.Confirmed -> when (override.relation) {
        VersionRelation.SAME -> "Redundant"
        VersionRelation.NEWER -> "Ahead of BOM"
        VersionRelation.OLDER -> "Behind BOM"
        VersionRelation.INCOMPARABLE -> "Not comparable"
    }
}

/** Full sentence, used as the column tooltip and as the inspection's problem description. */
fun verdictExplanation(override: DetectedOverride): String = when (override) {
    is DetectedOverride.Inconclusive ->
        "Cannot confirm whether this override is still needed - some POMs in the BOM chain could not be resolved locally, so removal isn't offered here"
    // Deliberately suggests nothing. Whether this pin is still doing useful work depends on
    // what version the transitive dependency tree resolves to, which this plugin does not
    // compute - so it says what it knows and points at the tool that does know.
    is DetectedOverride.Unmanaged ->
        "None of the ${override.checkedBoms.size} BOM${if (override.checkedBoms.size == 1) "" else "s"} in this module's " +
            "chain manages this artifact, so this entry isn't overriding a BOM version - it pins a version that " +
            "would otherwise come from Maven's transitive dependency resolution. Whether it's still needed depends " +
            "on the dependency tree, not the BOM chain, so no action is suggested here - use Analyze Dependencies to check"
    is DetectedOverride.Confirmed -> when (override.relation) {
        VersionRelation.SAME ->
            "${managedByChain(override)} already manages this at ${override.bomVersion} - this override can be removed"
        VersionRelation.NEWER ->
            "This override raises the version above the ${override.bomVersion} managed by ${managedByChain(override)}, so it is still taking effect"
        VersionRelation.OLDER ->
            "This override holds the version below the ${override.bomVersion} managed by ${managedByChain(override)} - removing it lets the newer BOM version apply"
        VersionRelation.INCOMPARABLE ->
            "The declared version can't be confirmed as the same, older, or newer than the ${override.bomVersion} " +
                "managed by ${managedByChain(override)} - it's either not a concrete version to begin with " +
                "(a property or a range), or a differently-written version string that happens to order the " +
                "same, which isn't enough to say it's the same artifact - so no action is suggested here"
    }
}

/**
 * The full parent-chain path from the module down to the BOM that actually
 * manages a confirmed override's version - e.g. "spring-boot-starter-parent
 * → spring-boot-dependencies → com.fasterxml.jackson:jackson-bom:2.21.2".
 * Degenerates to just the BOM itself when declared directly in the module.
 * Empty for Inconclusive and Unmanaged results, neither of which has a single
 * managing BOM to name - for opposite reasons, which is what the Status column
 * says and this column deliberately does not try to repeat.
 */
fun managedByChain(override: DetectedOverride): String = when (override) {
    is DetectedOverride.Confirmed -> (override.managedByChain + override.declaredInBom.toString()).joinToString(" → ")
    is DetectedOverride.Inconclusive -> ""
    is DetectedOverride.Unmanaged -> ""
}

/**
 * Whether this result was computed from text that still holds an unresolved `${...}`, i.e.
 * whether Maven property resolution was unavailable when it was produced.
 *
 * `MavenPropertyResolver.resolve` returns its input unchanged until Maven has initialized, and two
 * things then go wrong at once - both visible here. The declared version arrives as placeholder
 * text and can be compared to nothing (`NOT_COMPARABLE`), and a BOM imported at a property version
 * resolves to no POM at all, which makes every override in that module `Inconclusive`. Only
 * `Inconclusive` can carry a placeholder BOM: `Confirmed` and `Unmanaged` both required every BOM
 * in the chain to have been read, which a placeholder coordinate never is.
 *
 * Deliberately evidence rather than a platform flag. `MavenProjectsManager.isInitialized()` looks
 * like the direct question, but it is also false for a project that was never imported as a Maven
 * project - where no sync is pending, refreshing changes nothing, and detection works fine off
 * `FilenameIndex`. Gating the UI on that flag makes such a project permanently unusable; asking
 * the data instead keeps the answer tied to the defect it is meant to describe. It also separates
 * cleanly from the other reason a row says "?": a BOM whose coordinate is fully known but whose
 * POM is simply absent is not a property problem, and returns false here.
 */
fun dependsOnUnresolvedProperty(override: DetectedOverride): Boolean =
    override.candidate.declaredVersion.contains("\${") || when (override) {
        is DetectedOverride.Inconclusive -> override.uncheckedBoms.any { !it.isConcrete() }
        is DetectedOverride.Confirmed, is DetectedOverride.Unmanaged -> false
    }
