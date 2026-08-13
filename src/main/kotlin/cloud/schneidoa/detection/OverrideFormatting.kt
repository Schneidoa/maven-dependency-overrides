package cloud.schneidoa.detection

import cloud.schneidoa.resolver.VersionRelation

/** Formats "declared version → what the BOM chain manages it at" for display; "?" when the managed version can't be confirmed. */
fun declaredToManaged(override: DetectedOverride): String = when (override) {
    is DetectedOverride.Confirmed -> "${override.candidate.declaredVersion} → ${override.bomVersion}"
    is DetectedOverride.Inconclusive -> "${override.candidate.declaredVersion} → ?"
}

/**
 * What the developer should conclude about an override. Deliberately semantic
 * rather than presentational - the panel maps this to an icon and the inspection
 * maps it to whether to register a problem at all, and those two answers differ.
 */
enum class OverrideVerdict { REDUNDANT, AHEAD_OF_BOM, BEHIND_BOM, NOT_COMPARABLE, INCONCLUSIVE }

fun verdictOf(override: DetectedOverride): OverrideVerdict = when (override) {
    is DetectedOverride.Inconclusive -> OverrideVerdict.INCONCLUSIVE
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
 * Empty for Inconclusive results, which have no single managing BOM to name.
 */
fun managedByChain(override: DetectedOverride): String = when (override) {
    is DetectedOverride.Confirmed -> (override.managedByChain + override.declaredInBom.toString()).joinToString(" → ")
    is DetectedOverride.Inconclusive -> ""
}
