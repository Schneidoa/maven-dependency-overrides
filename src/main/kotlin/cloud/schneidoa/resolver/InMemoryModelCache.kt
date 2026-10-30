package cloud.schneidoa.resolver

import org.apache.maven.model.building.ModelCache
import java.util.concurrent.ConcurrentHashMap

/**
 * Lets Maven reuse a parent POM it has already read. In a reactor nearly every module shares the
 * same parents, and without this each module's build parses them all from scratch. Failed
 * lookups are never stored, so a POM that goes missing is still reported once per build.
 */
class InMemoryModelCache : ModelCache {
    private data class Key(val groupId: String, val artifactId: String, val version: String, val tag: String)

    private val entries = ConcurrentHashMap<Key, Any>()

    override fun put(groupId: String, artifactId: String, version: String, tag: String, data: Any) {
        entries[Key(groupId, artifactId, version, tag)] = data
    }

    override fun get(groupId: String, artifactId: String, version: String, tag: String): Any? =
        entries[Key(groupId, artifactId, version, tag)]
}
