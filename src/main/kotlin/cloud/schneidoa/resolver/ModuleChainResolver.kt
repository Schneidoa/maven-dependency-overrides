package cloud.schneidoa.resolver

import org.apache.maven.model.Dependency
import org.apache.maven.model.DependencyManagement
import org.apache.maven.model.Model
import org.apache.maven.model.building.DefaultModelBuilderFactory
import org.apache.maven.model.building.DefaultModelBuildingRequest
import org.apache.maven.model.building.ModelBuildingException
import org.apache.maven.model.building.ModelBuildingRequest
import org.apache.maven.model.building.ModelBuildingResult
import org.apache.maven.model.building.ModelSource2
import org.apache.maven.model.io.xpp3.MavenXpp3Reader
import org.apache.maven.model.resolution.UnresolvableModelException
import java.io.File
import java.util.concurrent.CancellationException

/**
 * A BOM found in a module's effective model, plus the path of artifactIds climbed from the
 * original module (exclusive) down to (and including) the POM that declares the import. Empty
 * when the BOM is declared directly in the module itself.
 */
data class BomImport(val bom: Gav, val declaredVia: List<String>)

/**
 * A version an ancestor manages through a plain `<dependencyManagement>` entry rather than an
 * import. [declaredVia] is the path of artifactIds climbed from the module (exclusive) to the
 * declaring POM (exclusive), so it reads the same as [BomImport.declaredVia] followed by
 * [declaredIn].
 */
data class InheritedManagement(val version: String, val declaredIn: Gav, val declaredVia: List<String>)

/**
 * What a module's parent hierarchy contributes to its dependency management once the module's
 * own literal entries are taken away, which is exactly what would apply if they were removed.
 *
 * [inherited] wins over every entry of every BOM in [imports]: Maven injects imported entries
 * only for keys that are still free once inheritance is done, wherever the import is declared.
 *
 * [declaredVersions] holds the module's own entries with their properties interpolated, for the
 * ones written as a `${...}` expression.
 *
 * [truncatedAt] names every POM the hierarchy could not be walked past. When it is not empty
 * the rest of this object is incomplete rather than exhaustive, and a caller that ignores it
 * will read a partial hierarchy as a complete one. Its entries may carry "?" for coordinate
 * parts the POM never declared.
 */
data class BomChain(
    val imports: List<BomImport>,
    val truncatedAt: List<Gav>,
    val inherited: Map<Ga, InheritedManagement> = emptyMap(),
    val declaredVersions: Map<Ga, String> = emptyMap()
)

/**
 * Answers "what manages this module's dependencies" by having Maven build the module, rather
 * than by reading its POMs one at a time. Parent inheritance, precedence between direct entries
 * and imports, deduplicated imports, profiles, and properties redefined further down the
 * hierarchy are all decided here by the same code a real build runs.
 *
 * The build stops after Maven's first phase (see [ModelBuildingRequest.setTwoPhaseBuilding]).
 * At that point the effective model is inherited and interpolated but its BOM imports are still
 * plain entries, which is what lets [BomVersionResolver] go on probing them one at a time, so a
 * BOM that cannot be read costs a single unchecked entry instead of the whole answer.
 *
 * [reactorPoms] and the `onMissingPom` contract are shared with [LocalRepositoryModelResolver].
 * One instance holds a [ModelCache][InMemoryModelCache], and the lifetime rule documented on
 * [BomEffectiveModelResolver] applies to it for the same reason.
 */
class ModuleChainResolver(
    private val localRepositoryDir: File,
    private val onMissingPom: (Gav) -> Unit = {},
    private val reactorPoms: (Gav) -> ModelSource2? = { null }
) {
    private val modelBuilder = DefaultModelBuilderFactory().newInstance()
    private val modelCache = InMemoryModelCache()

    /**
     * [pomFile] is optional because [pom] may be something other than a file on disk; supplying
     * it lets file-based profile activation see the project directory. [activeProfiles] and
     * [inactiveProfiles] are the ids selected in the IDE, the equivalent of `-P` on a real build.
     */
    fun resolve(
        pom: ModelSource2,
        pomFile: File? = null,
        activeProfiles: List<String> = emptyList(),
        inactiveProfiles: List<String> = emptyList()
    ): BomChain {
        val rawModel = try {
            pom.inputStream.use { MavenXpp3Reader().read(it, false) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return BomChain(emptyList(), listOf(Gav("?", pom.location, "?")))
        }
        rawModel.pomFile = pomFile

        val missing = linkedSetOf<Gav>()
        val build = { model: Model ->
            val resolver = LocalRepositoryModelResolver(
                localRepositoryDir,
                { gav ->
                    missing += gav
                    onMissingPom(gav)
                },
                reactorPoms
            )
            val request = DefaultModelBuildingRequest()
            request.setRawModel(model)
            request.setModelSource(pom)
            request.setPomFile(pomFile)
            request.setModelResolver(resolver)
            request.setModelCache(modelCache)
            request.setValidationLevel(ModelBuildingRequest.VALIDATION_LEVEL_MINIMAL)
            request.setProcessPlugins(false)
            request.setTwoPhaseBuilding(true)
            request.setLocationTracking(true)
            request.setActiveProfileIds(activeProfiles)
            request.setInactiveProfileIds(inactiveProfiles)
            // See BomEffectiveModelResolver: JDK-activated profiles fail outright without this.
            request.setSystemProperties(System.getProperties())
            modelBuilder.build(request)
        }

        return try {
            val withoutOwnEntries = build(rawModel.clone().apply { removeOwnEntries() })
            val declaredVersions = if (rawModel.declaresPropertyBasedEntry()) {
                build(rawModel).effectiveModel.literalEntries().associate { it.ga() to it.version }
            } else {
                emptyMap()
            }
            toChain(withoutOwnEntries, declaredVersions)
        } catch (e: ModelBuildingException) {
            // Names the POM that actually stopped the build, however far up the hierarchy.
            val unresolvable = e.problems.mapNotNull { it.exception as? UnresolvableModelException }
            incompleteChain(rawModel, missing + unresolvable.map { Gav(it.groupId, it.artifactId, it.version) })
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Deliberately broad, matching BomEffectiveModelResolver: an incomplete <parent> or
            // a malformed POM can surface as anything here, and one bad POM must degrade to
            // Inconclusive for this module rather than crash a whole project scan.
            incompleteChain(rawModel, missing)
        }
    }

    private fun toChain(result: ModelBuildingResult, declaredVersions: Map<Ga, String>): BomChain {
        val lineage = result.modelIds.filter { it.isNotEmpty() }
        // Index of the POM declaring [dependency]. No recorded location means the entry came
        // from the module's own raw model, which is read outside the model builder.
        val declaringIndex = { dependency: Dependency ->
            val declaringId = dependency.getLocation("")?.source?.modelId?.substringBeforeLast(':')
            lineage.indexOfFirst { it.substringBeforeLast(':') == declaringId }.coerceAtLeast(0)
        }
        val hops = { upToExclusive: Int -> lineage.subList(1, upToExclusive.coerceAtLeast(1)).map(::artifactIdOf) }

        val model = result.effectiveModel
        val entries = model.dependencyManagement?.dependencies.orEmpty()
        val imports = entries.filter { it.isBomImport() }.mapNotNull { dependency ->
            val gav = dependency.gavOrNull() ?: return@mapNotNull null
            BomImport(gav, hops(declaringIndex(dependency) + 1))
        }

        val inherited = LinkedHashMap<Ga, InheritedManagement>()
        for (dependency in model.literalEntries()) {
            val index = declaringIndex(dependency)
            inherited.putIfAbsent(
                dependency.ga(),
                InheritedManagement(dependency.version, gavOf(lineage[index], model), hops(index))
            )
        }
        return BomChain(imports, emptyList(), inherited, declaredVersions)
    }

    /**
     * Without an effective model all that is left is what the module itself says. Its literal
     * imports are still worth listing, so a chain dialog opened on the resulting Inconclusive
     * row shows what is known instead of nothing.
     */
    private fun incompleteChain(rawModel: Model, missing: Set<Gav>): BomChain {
        val ownImports = rawModel.dependencyManagement?.dependencies.orEmpty()
            .filter { it.isBomImport() }
            .mapNotNull { it.gavOrNull() }
            .filter { it.isConcrete() }
            .map { BomImport(it, emptyList()) }
        return BomChain(ownImports, missing.toList().ifEmpty { listOf(blockingCoordinateOf(rawModel)) })
    }

    /** The nearest coordinate to blame, with "?" for whatever the POM left out. */
    private fun blockingCoordinateOf(rawModel: Model): Gav {
        val parent = rawModel.parent
        return Gav(
            (parent?.groupId ?: rawModel.groupId).orUnknown(),
            (parent?.artifactId ?: rawModel.artifactId).orUnknown(),
            (if (parent != null) parent.version else rawModel.version).orUnknown()
        )
    }

    private fun String?.orUnknown(): String = this?.trim().takeUnless { it.isNullOrEmpty() } ?: "?"

    private fun artifactIdOf(modelId: String): String = modelId.split(':').getOrElse(1) { modelId }

    /**
     * Ids are built from raw POMs, so a version written as ${revision} is still a placeholder
     * here. The effective model carries the real value for every module sharing that version.
     */
    private fun gavOf(modelId: String, effectiveModel: Model): Gav {
        val parts = modelId.split(':')
        val version = parts.getOrElse(2) { "?" }
        return Gav(parts[0], parts.getOrElse(1) { "?" }, if ("\${" in version) effectiveModel.version else version)
    }

    private fun Model.ownManagements(): List<DependencyManagement> =
        listOfNotNull(dependencyManagement) + profiles.mapNotNull { it.dependencyManagement }

    /** Keeps imports: they are part of what a removed override would fall back to. */
    private fun Model.removeOwnEntries() {
        ownManagements().forEach { management -> management.dependencies.removeIf { !it.isImportScoped() } }
    }

    private fun Model.declaresPropertyBasedEntry(): Boolean =
        ownManagements().any { management ->
            management.dependencies.any { !it.isImportScoped() && it.version?.contains("\${") == true }
        }

    private fun Model.literalEntries(): List<Dependency> =
        dependencyManagement?.dependencies.orEmpty().filter { !it.isImportScoped() && it.version != null }

    private fun Dependency.isImportScoped() = scope == "import"

    private fun Dependency.isBomImport() = isImportScoped() && type == "pom"

    private fun Dependency.ga() = Ga(groupId, artifactId)

    private fun Dependency.gavOrNull(): Gav? {
        if (groupId.isNullOrEmpty() || artifactId.isNullOrEmpty() || version.isNullOrEmpty()) return null
        return Gav(groupId, artifactId, version)
    }
}
