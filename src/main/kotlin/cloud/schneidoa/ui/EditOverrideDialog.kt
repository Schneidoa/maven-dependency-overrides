package cloud.schneidoa.ui

import cloud.schneidoa.detection.ManagedVersionHint
import cloud.schneidoa.detection.OverrideCandidate
import cloud.schneidoa.detection.resolvingMissingPoms
import cloud.schneidoa.detection.setReason
import cloud.schneidoa.detection.setVersion
import cloud.schneidoa.resolver.Gav
import cloud.schneidoa.resolver.ManagedVersionLookup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.util.ui.FormBuilder
import org.jetbrains.idea.maven.dom.model.MavenDomProjectModel
import java.util.concurrent.CancellationException
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JTextField
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

class EditOverrideDialog(
    private val project: Project,
    private val model: MavenDomProjectModel,
    private val candidate: OverrideCandidate,
    private val hintProvider: (Project, (Gav) -> Unit) -> ManagedVersionHint = ManagedVersionHint::forProject
) : DialogWrapper(project, true) {

    private val originalVersion = candidate.declaredVersion
    private val versionField = JTextField(originalVersion)
    private val reasonField = JTextField(candidate.reason ?: "")
    private val hintLabel = JLabel("Checking BOM...")

    init {
        title = "Edit Dependency Override"
        init()
        updateOkEnabled()
        versionField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = updateOkEnabled()
            override fun removeUpdate(e: DocumentEvent) = updateOkEnabled()
            override fun changedUpdate(e: DocumentEvent) = updateOkEnabled()
        })
        loadHint()
    }

    override fun createCenterPanel(): JComponent =
        FormBuilder.createFormBuilder()
            .addLabeledComponent("Dependency", JLabel(candidate.ga.toString()))
            .addLabeledComponent("Version", versionField)
            .addLabeledComponent("Reason", reasonField)
            .addComponent(hintLabel)
            .panel

    override fun doOKAction() {
        WriteCommandAction.runWriteCommandAction(project) {
            // candidate.declaredVersion is the *resolved* value - a property-based
            // <version>${x}</version> reads back as the literal it resolves to. If
            // the user never touched the field, writing it back would silently
            // replace the property reference with that literal, even though they
            // only meant to change the reason. Only write when it actually changed.
            val newVersion = versionField.text.trim()
            if (newVersion != originalVersion) {
                setVersion(project, candidate, newVersion)
            }
            setReason(project, candidate, reasonField.text.trim().ifBlank { null })
        }
        super.doOKAction()
    }

    private fun updateOkEnabled() {
        isOKActionEnabled = versionField.text.isNotBlank()
    }

    private fun loadHint() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val text = try {
                when (val lookup = resolvingMissingPoms(project) { onMissing ->
                    hintProvider(project, onMissing).lookup(model, project, candidate.ga)
                }) {
                    is ManagedVersionLookup.Found -> "Currently managed at ${lookup.version}"
                    is ManagedVersionLookup.NotFound -> "Not currently managed by any BOM in this module's chain"
                }
            } catch (e: CancellationException) {
                // Must precede the broad catch. RemotePomFetcher deliberately rethrows cancellation
                // rather than folding it into its "could not fetch" degradation; catching it here
                // would undo that, and a cancelled lookup is not a "Could not check BOM" answer -
                // it is no answer. ProcessCanceledException extends
                // java.util.concurrent.CancellationException, so this covers the platform's own
                // cancellation too.
                throw e
            } catch (e: Throwable) {
                "Could not check BOM"
            }
            SwingUtilities.invokeLater { hintLabel.text = text }
        }
    }
}
