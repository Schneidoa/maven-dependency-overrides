package cloud.schneidoa.detection

import cloud.schneidoa.resolver.isReadablePom
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import org.apache.maven.model.building.ModelSource2
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.net.URI

/**
 * Hands a POM to Maven's model builder through the VFS rather than the disk, so a parent or BOM
 * being edited is read as it stands in the editor. [getRelatedSource] is what the builder calls
 * to follow a `<relativePath>`, and keeps that lookup on the VFS too.
 */
class VfsModelSource(private val file: VirtualFile) : ModelSource2 {

    override fun getInputStream(): InputStream {
        val unsaved = FileDocumentManager.getInstance().getCachedDocument(file)?.text
        return ByteArrayInputStream(unsaved?.toByteArray(file.charset) ?: file.contentsToByteArray())
    }

    override fun getLocation(): String = file.path

    override fun getRelatedSource(relativePath: String): ModelSource2? {
        val baseDir = file.parent ?: return null
        val related = VfsUtilCore.findRelativeFile(relativePath.replace('\\', '/'), baseDir) ?: return null
        val pom = if (related.isDirectory) related.findChild("pom.xml") else related
        return pom?.let(::VfsModelSource)?.also {
            // Refused rather than returned as null. Null tells Maven to go on to the repository,
            // which would quietly substitute an installed, possibly stale copy of a parent that
            // a real build of this checkout would use, and fail on, right here.
            check(isReadablePom(it)) { "${pom.path} is not a POM" }
        }
    }

    override fun getLocationURI(): URI = File(file.path).toURI()
}
