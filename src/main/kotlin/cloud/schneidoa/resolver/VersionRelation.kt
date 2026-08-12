package cloud.schneidoa.resolver

import org.apache.maven.artifact.versioning.ComparableVersion

/** How a declared override version relates to what a BOM chain manages the same artifact at. */
enum class VersionRelation { SAME, NEWER, OLDER, INCOMPARABLE }

/**
 * Characters that only appear in Maven version *ranges* ("[1.0,2.0)"), which
 * name an interval rather than a point and so have nothing to compare against
 * a single managed version.
 */
private val RANGE_MARKERS = listOf('[', ']', '(', ')', ',')

/**
 * Maven metaversions - legal in a <version> tag, but not points on the version line.
 * ComparableVersion orders both of these as *below* any numeric release, which would
 * otherwise produce a confident-looking BEHIND_BOM. Matched case-insensitively: Maven
 * itself treats these as literal uppercase tokens, but a POM may spell them any way,
 * and guessing wrong here must fall to the safe (INCOMPARABLE) side, not the unsafe one.
 */
private val METAVERSIONS = setOf("LATEST", "RELEASE")

/**
 * Orders [declared] against [managed] using Maven's own version semantics, so
 * "2.21.10" ranks above "2.21.9" and "1.0-alpha" below "1.0" — both of which a
 * lexical comparison gets wrong.
 *
 * Returns [VersionRelation.INCOMPARABLE] rather than guessing whenever an input
 * isn't a single concrete version. ComparableVersion will happily order any
 * string, so without this guard an unresolved "${jackson.version}" (which is what
 * MavenPropertyResolver hands back when the property is undefined), a version
 * range, or a metaversion like LATEST would produce a confident-looking verdict
 * derived from nonsense. This plugin declines to answer instead - a wrong "safe
 * to remove" can silently reintroduce a patched CVE.
 *
 * [VersionRelation.SAME] requires the trimmed strings to be textually identical,
 * not merely ComparableVersion-equal. Maven resolves an artifact by its literal
 * version string - "2.3.4" and "2.3.4.RELEASE" are different paths in the local
 * repository and different files, even though ComparableVersion normalizes them
 * to the same ordering (it also does this for "1.0"/"1.0.0" and the "-final"/"-ga"
 * qualifiers). Reporting SAME for such a pair would recommend removing an override
 * that actually pins a different artifact than the one the BOM manages - so when
 * the ordering says equal but the text disagrees, this returns INCOMPARABLE rather
 * than inventing a verdict that isn't backed by identity.
 */
fun compareDeclaredToManaged(declared: String, managed: String): VersionRelation {
    val trimmedDeclared = declared.trim()
    val trimmedManaged = managed.trim()
    if (!isSingleConcreteVersion(trimmedDeclared) || !isSingleConcreteVersion(trimmedManaged)) {
        return VersionRelation.INCOMPARABLE
    }

    if (trimmedDeclared == trimmedManaged) return VersionRelation.SAME

    val comparison = ComparableVersion(trimmedDeclared).compareTo(ComparableVersion(trimmedManaged))
    return when {
        // Ordering-equal but textually different - see the SAME contract above.
        comparison == 0 -> VersionRelation.INCOMPARABLE
        comparison > 0 -> VersionRelation.NEWER
        else -> VersionRelation.OLDER
    }
}

private fun isSingleConcreteVersion(version: String): Boolean {
    val trimmed = version.trim()
    return trimmed.isNotEmpty() &&
        !trimmed.contains("\${") &&
        RANGE_MARKERS.none { trimmed.contains(it) } &&
        trimmed.uppercase() !in METAVERSIONS
}
