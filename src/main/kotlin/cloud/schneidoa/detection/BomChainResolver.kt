package cloud.schneidoa.detection

import cloud.schneidoa.resolver.BomChain
import cloud.schneidoa.resolver.Gav
import cloud.schneidoa.resolver.ModuleChainResolver
import com.intellij.openapi.project.Project
import org.apache.maven.model.building.ModelSource2
import org.jetbrains.idea.maven.dom.model.MavenDomProjectModel
import org.jetbrains.idea.maven.project.MavenProjectsManager
import java.io.File

/**
 * Connects a module's DOM model to [ModuleChainResolver], which does the actual work. What
 * belongs here is only what needs the IDE: reading the POM through the VFS so unsaved edits
 * count, and knowing which profiles are selected in the Maven tool window.
 */
class BomChainResolver(
    localRepositoryDir: File,
    onMissingPom: (Gav) -> Unit = {},
    reactorPoms: (Gav) -> ModelSource2? = { null }
) {
    private val moduleChainResolver = ModuleChainResolver(localRepositoryDir, onMissingPom, reactorPoms)

    fun resolveBomChain(model: MavenDomProjectModel, project: Project): BomChain {
        val virtualFile = model.xmlTag?.containingFile?.virtualFile
            ?: return BomChain(emptyList(), listOf(Gav("?", "?", "?")))

        val profiles = MavenProjectsManager.getInstance(project).explicitProfiles
        return moduleChainResolver.resolve(
            VfsModelSource(virtualFile),
            virtualFile.takeIf { it.isInLocalFileSystem }?.let { File(it.path) },
            profiles.enabledProfiles.toList(),
            profiles.disabledProfiles.toList()
        )
    }
}
