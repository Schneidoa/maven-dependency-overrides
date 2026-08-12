package cloud.schneidoa.resolver

import org.apache.maven.model.Model
import org.apache.maven.model.building.DefaultModelBuilderFactory
import org.apache.maven.model.building.DefaultModelBuildingRequest
import org.apache.maven.model.building.ModelBuildingRequest
import java.io.File
import java.util.concurrent.ConcurrentHashMap

sealed class BomModelResult {
    data class Success(val effectiveModel: Model) : BomModelResult()
    data class Failure(val gav: Gav, val reason: String) : BomModelResult()
}

/**
 * Builds the fully effective Maven model (parent chain resolved, properties
 * interpolated, nested BOM imports merged) of a single BOM artifact, in
 * isolation from whatever project might be importing it. This is what makes
 * it possible to ask "what would this BOM manage this artifact at" without
 * caring whether some consumer POM has locally overridden it.
 */
class BomEffectiveModelResolver(private val localRepositoryDir: File) {

    /**
     * Memoizes per resolver instance, which is what makes the cache safe rather than a
     * staleness hazard: detection builds a fresh resolver for every inspection pass
     * (see `OverrideDetector.forProject`), so nothing here outlives one pass over one
     * POM. Within that pass the same BOM is asked for once per override in the file -
     * ten overrides over a three-BOM chain means thirty model builds instead of three,
     * each one parsing the BOM's whole parent chain off disk.
     *
     * Deliberately not hoisted to a longer-lived (project- or application-level) cache:
     * a BOM POM in the local repository is only immutable for release versions, and a
     * re-installed SNAPSHOT BOM would then be answered from a stale model - which is
     * exactly how this plugin would come to claim an override is "safe to remove" on
     * evidence that no longer holds.
     *
     * Concurrent rather than a plain map because nothing in this class's contract
     * confines an instance to one thread. Today's callers each build their own (a
     * detection pass, one dialog hint load), so there is no contention to speak of -
     * but that's their choice, not a guarantee worth depending on here. Safe for
     * `computeIfAbsent` because the build below never re-enters this method:
     * parents and nested imports are resolved by `LocalRepositoryModelResolver`,
     * which does not call back into it.
     */
    private val effectiveModels = ConcurrentHashMap<Gav, BomModelResult>()

    fun buildEffectiveModel(bom: Gav): BomModelResult =
        effectiveModels.computeIfAbsent(bom) { buildEffectiveModelUncached(it) }

    private fun buildEffectiveModelUncached(bom: Gav): BomModelResult {
        val pomFile = bom.pomFileIn(localRepositoryDir)
        if (!pomFile.isFile) {
            return BomModelResult.Failure(bom, "BOM POM not found in local repository: $pomFile")
        }

        val request = DefaultModelBuildingRequest()
        request.setPomFile(pomFile)
        request.setModelResolver(LocalRepositoryModelResolver(localRepositoryDir))
        request.setValidationLevel(ModelBuildingRequest.VALIDATION_LEVEL_MINIMAL)
        request.setProcessPlugins(false)
        // Without this, profile activation that depends on the running JDK
        // version (common in Apache-parented POMs, e.g. org.apache:apache's
        // "jdk9+" profile) fails outright instead of just skipping the
        // profile - breaking effective-model building for any BOM whose
        // parent chain includes one.
        request.setSystemProperties(System.getProperties())

        return try {
            val result = DefaultModelBuilderFactory().newInstance().build(request)
            BomModelResult.Success(result.effectiveModel)
        } catch (e: Exception) {
            // Deliberately broad: this result feeds an IDE inspection pass over
            // potentially many BOMs across a multi-module project. One malformed
            // or unexpectedly-behaving third-party BOM (or a bug in a custom
            // ModelResolver) must not crash the whole pass - report it as a
            // Failure for this one BOM instead of propagating.
            BomModelResult.Failure(bom, e.message ?: "Failed to build effective model for $bom")
        }
    }
}
