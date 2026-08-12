package cloud.schneidoa.resolver

import java.io.File

/**
 * A Maven `groupId:artifactId` pair, without a version.
 *
 * Deliberately excludes classifier/type: BOM matching throughout this project
 * (`BomVersionResolver`, `DependencyManagementScanner`, `BomChainResolver`) is
 * groupId+artifactId only. A dependency managed at different versions under
 * different classifiers/types (e.g. OS-classified native artifacts) is out of
 * scope for v1 - such an override could be misclassified. Rare in practice for
 * typical CVE-driven overrides, but a real, known gap if this ever needs
 * extending.
 */
data class Ga(val groupId: String, val artifactId: String) {
    override fun toString(): String = "$groupId:$artifactId"
}

/** A fully qualified Maven `groupId:artifactId:version`. */
data class Gav(val groupId: String, val artifactId: String, val version: String) {
    fun toGa(): Ga = Ga(groupId, artifactId)
    override fun toString(): String = "$groupId:$artifactId:$version"
}

/**
 * The file a Maven local repository would store this artifact's POM at,
 * following the standard `groupId/artifactId/version/artifactId-version.pom`
 * layout.
 */
fun Gav.pomFileIn(localRepositoryDir: File): File = File(
    localRepositoryDir,
    "${groupId.replace('.', '/')}/$artifactId/$version/$artifactId-$version.pom"
)
