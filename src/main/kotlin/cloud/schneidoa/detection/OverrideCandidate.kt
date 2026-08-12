package cloud.schneidoa.detection

import cloud.schneidoa.resolver.Ga
import com.intellij.psi.xml.XmlTag

/**
 * A literal-version entry found in a module's own `<dependencyManagement>` —
 * not yet compared against any BOM, just what's textually present in the POM.
 * [xmlTag] is the `<dependency>` block, kept for navigation; [versionXmlTag]
 * is specifically the `<version>` child, used to anchor editor highlights
 * precisely on the version text rather than the whole dependency block.
 */
data class OverrideCandidate(
    val ga: Ga,
    val declaredVersion: String,
    val xmlTag: XmlTag,
    val versionXmlTag: XmlTag,
    val reason: String?,
    val suppressed: Boolean
)
