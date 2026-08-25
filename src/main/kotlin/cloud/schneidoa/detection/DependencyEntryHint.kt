package cloud.schneidoa.detection

import cloud.schneidoa.resolver.Ga
import cloud.schneidoa.resolver.VersionRelation
import cloud.schneidoa.resolver.compareDeclaredToManaged

/**
 * What the Add dialog's live hint area should say for one snapshot of its state,
 * and - separately - the version the version field should be prefilled with, if any.
 *
 * Pure by design. The prefill used to be decided and applied in the same synchronous
 * step inside the dialog, guarded by comparing the field's *current text* to the
 * managed version to stop recursion. That guard looks like it terminates, but only
 * when the field starts empty - `JTextField.setText` on a non-empty field fires a
 * `removeUpdate` while the field transiently reads `""`, so the listener sees
 * `"" != managed` and re-enters the write mid-notification. Splitting "what should
 * the field/hint be" (this function, callable any number of times, with no memory of
 * previous calls) from "make it so" (the dialog's flag-guarded write, see
 * `AddOverrideDialog.prefillVersion`) removes the trap entirely: there is nothing
 * here to recurse into.
 */
/**
 * [prefillVersion] is `null` for "leave the field alone", never for "there is nothing to
 * say" - clearing the field is itself a prefill decision, written as `""`. Without that
 * distinction, switching the entered coordinate from one the chain manages to one it
 * doesn't would leave a stale, no-longer-relevant version sitting in the field from the
 * previous coordinate's auto-prefill, with nothing to prompt the user to notice it no
 * longer means anything.
 */
data class DependencyEntryHint(val hintText: String, val prefillVersion: String? = null)

/**
 * @param candidates the module's BOM-chain catalog, or null when it has not loaded
 *   (or failed to) - in which case there is nothing to prefill or verdict against.
 * @param ga the coordinate currently entered in the Group ID / Artifact ID fields,
 *   or null while either is blank or unparsable.
 * @param declaredVersion the version field's current text, untrimmed.
 * @param versionEditedByUser whether the user has typed into the version field
 *   themselves - prefilling never overwrites a user-entered value.
 */
fun dependencyEntryHint(
    candidates: DependencyCandidates?,
    ga: Ga?,
    declaredVersion: String,
    versionEditedByUser: Boolean
): DependencyEntryHint {
    if (candidates == null) return DependencyEntryHint(" ")

    val enteredVersion = declaredVersion.trim()
    val managed = ga?.let { candidates.managedVersionOf(it) }

    // A non-blank field the user hasn't edited can only hold text a previous call's
    // prefill wrote (versionEditedByUser sticks true forever after the first manual
    // keystroke - see AddOverrideDialog). If the coordinate has since changed to one
    // the chain doesn't manage, that leftover value belongs to a different artifact
    // entirely and is cleared rather than left to look like a still-relevant answer.
    val prefillVersion = when {
        versionEditedByUser -> null
        managed != null && enteredVersion != managed -> managed
        managed == null && enteredVersion.isNotBlank() -> ""
        else -> null
    }

    // The verdict reflects what the field will read once the prefill (if any) lands,
    // not the pre-prefill text - so the hint is correct immediately instead of one
    // keystroke behind the write the caller is about to make.
    val effectiveVersion = prefillVersion ?: enteredVersion

    val verdict = when {
        ga == null -> ""
        managed == null -> "No BOM in this module's chain manages this"
        effectiveVersion.isBlank() -> "The BOM manages this at $managed"
        else -> when (compareDeclaredToManaged(effectiveVersion, managed)) {
            VersionRelation.SAME -> "Same version the BOM already manages — this override would be redundant"
            VersionRelation.NEWER -> "Raises this above the BOM's $managed"
            VersionRelation.OLDER -> "Holds this below the BOM's $managed"
            VersionRelation.INCOMPARABLE -> "Can't be compared with the BOM's $managed"
        }
    }

    // Stated, not implied: a dropdown that silently omits entries reads as "this
    // artifact isn't managed", which is the false negative this project's
    // miss-rather-than-false-safe rule exists to prevent, inverted into the UI.
    val incomplete = if (candidates.isComplete) {
        ""
    } else {
        val n = candidates.unreadableBomCount
        "${if (verdict.isEmpty()) "" else " — "}$n BOM${if (n == 1) "" else "s"} could not be read, so these suggestions may be incomplete"
    }

    return DependencyEntryHint((verdict + incomplete).ifBlank { " " }, prefillVersion)
}
