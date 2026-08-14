package cloud.schneidoa.ui

import cloud.schneidoa.detection.DependencyCandidates
import cloud.schneidoa.detection.ManagedVersionHint
import cloud.schneidoa.detection.addOverride
import cloud.schneidoa.detection.dependencyEntryHint
import cloud.schneidoa.detection.resolvingMissingPoms
import cloud.schneidoa.resolver.Ga
import cloud.schneidoa.resolver.Gav
import cloud.schneidoa.resolver.ManagedVersionLookup
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComponentWithBrowseButton
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.ui.TextFieldWithAutoCompletion
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import org.jetbrains.idea.maven.dom.MavenDomUtil
import org.jetbrains.idea.maven.dom.model.MavenDomProjectModel
import java.awt.Dimension
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JTextField
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import com.intellij.openapi.editor.event.DocumentEvent as EditorDocumentEvent
import com.intellij.openapi.editor.event.DocumentListener as EditorDocumentListener

internal data class ModuleChoice(val label: String, val model: MavenDomProjectModel) {
    override fun toString(): String = label
}

internal fun findAddOverrideModules(project: Project): List<ModuleChoice> {
    val pomFiles = FilenameIndex.getVirtualFilesByName("pom.xml", GlobalSearchScope.projectScope(project))
    return pomFiles.mapNotNull { pomFile ->
        val model = MavenDomUtil.getMavenDomProjectModel(project, pomFile) ?: return@mapNotNull null
        val artifactId = model.artifactId.rawText?.trim()
        val label = if (!artifactId.isNullOrEmpty()) artifactId else (pomFile.parent?.name ?: pomFile.name)
        ModuleChoice(label, model)
    }.sortedBy { it.label }
}

private class SimpleDocumentListener(private val onChange: () -> Unit) : DocumentListener {
    override fun insertUpdate(e: DocumentEvent) = onChange()
    override fun removeUpdate(e: DocumentEvent) = onChange()
    override fun changedUpdate(e: DocumentEvent) = onChange()
}

class AddOverrideDialog internal constructor(
    private val project: Project,
    modules: List<ModuleChoice>,
    private val hintProvider: (Project, (Gav) -> Unit) -> ManagedVersionHint = ManagedVersionHint::forProject
) : DialogWrapper(project, true) {

    private val moduleCombo = JComboBox(modules.toTypedArray())
    private val groupField = TextFieldWithAutoCompletion.create(project, emptyList(), false, "")
    private val artifactField = TextFieldWithAutoCompletion.create(project, emptyList(), false, "")
    private val groupPicker = ComponentWithBrowseButton(groupField) { chooseGroup() }.apply {
        setButtonIcon(AllIcons.General.ArrowDown)
        setButtonEnabled(false)
    }
    private val artifactPicker = ComponentWithBrowseButton(artifactField) { chooseArtifact() }.apply {
        setButtonIcon(AllIcons.General.ArrowDown)
        setButtonEnabled(false)
    }
    private val versionField = JTextField()
    private val reasonField = JTextField()
    private val hintLabel = JLabel(" ")
    private val hintDebounce = Timer(300) { loadHint() }.apply { isRepeats = false }
    private val hintGeneration = AtomicInteger(0)

    private var candidates: DependencyCandidates? = null
    private var candidatesLoadFailed = false
    private val catalogGeneration = AtomicInteger(0)

    private var versionEditedByUser = false
    private var applyingPrefill = false

    init {
        title = "Add Dependency Override"
        init()
        updateOkEnabled()
        Disposer.register(disposable, groupPicker)
        Disposer.register(disposable, artifactPicker)
        versionField.document.addDocumentListener(SimpleDocumentListener {
            if (!applyingPrefill) versionEditedByUser = true
            updateOkEnabled()
            updateHint()
        })
        groupField.addDocumentListener(object : EditorDocumentListener {
            override fun documentChanged(event: EditorDocumentEvent) {
                maybeSplitPastedCoordinate()
                refreshArtifactVariants()
                updateOkEnabled()
                updateHint()
            }
        })
        artifactField.addDocumentListener(object : EditorDocumentListener {
            override fun documentChanged(event: EditorDocumentEvent) {
                maybeFillGroupFromArtifact()
                updateOkEnabled()
                updateHint()
            }
        })
        moduleCombo.addActionListener {
            reloadCandidates()
        }
        reloadCandidates()
    }

    override fun createCenterPanel(): JComponent =
        FormBuilder.createFormBuilder()
            .addLabeledComponent("Module", moduleCombo)
            .addLabeledComponent("Group ID", groupPicker)
            .addLabeledComponent("Artifact ID", artifactPicker)
            .addLabeledComponent("Version to pin", versionField)
            .addLabeledComponent("Reason (optional)", reasonField)
            .addComponent(hintLabel)
            .panel
            .apply {
                // Widening the panel is what widens the fields: FormBuilder gives the
                // input column weightx = 1.0, so every pixel added here goes to the
                // components rather than to the labels. Left to its own preferred size the
                // form is about as wide as a default JTextField, which is too narrow for a
                // real groupId and far too narrow for the hint line - the longest text in
                // the dialog and the one the version prefill depends on being read.
                //
                // Height is deliberately left at the form's own: there are six rows and
                // nothing that grows, so a taller dialog would only add empty space.
                preferredSize = Dimension(JBUI.scale(620), preferredSize.height)
            }

    /**
     * Persists the user's own resize, which is the other half of opening wider: the
     * width below is a starting point, and anyone who wants a different one gets to
     * set it by dragging the edge once. The key is namespaced to this plugin because
     * the dimension service is application-wide and shared with the platform's own
     * dialogs.
     */
    override fun getDimensionServiceKey(): String = "cloud.schneidoa.AddOverrideDialog"

    override fun doOKAction() {
        val ga = parseGa() ?: return
        val choice = moduleCombo.selectedItem as? ModuleChoice ?: return
        addOverride(project, choice.model, ga, versionField.text.trim(), reasonField.text.trim().ifBlank { null })
        super.doOKAction()
    }

    private fun updateOkEnabled() {
        isOKActionEnabled = parseGa() != null && versionField.text.isNotBlank()
    }

    private fun parseGa(): Ga? {
        val groupId = groupField.text.trim()
        val artifactId = artifactField.text.trim()
        if (groupId.isBlank() || artifactId.isBlank()) return null
        return Ga(groupId, artifactId)
    }

    /**
     * The only place that writes the version field programmatically, and the only
     * place that needs to guard against re-entering itself. `JTextField.setText` on a
     * non-empty field goes through `AbstractDocument.replace`, which does a remove
     * then an insert, firing `removeUpdate` *while holding the document's write lock*
     * and with the field transiently reading `""`. The version field's own listener
     * reacts to that by calling `updateHint()`, which (via `dependencyEntryHint`) can
     * decide to prefill again - and without this flag that second call would call
     * `setText` again from inside the first `setText`'s own notification, throwing
     * `IllegalStateException: Attempt to mutate in notification` and leaving the field
     * blank (the remove completed, the insert never ran).
     *
     * A guard that compares the field's *current text* to the version being written
     * does not fix this - it only prevents re-entry once the field is empty, since a
     * *non-empty* field transiently reads empty mid-write regardless of what value is
     * being applied. Only a flag, set for the full duration of the write, survives
     * that. `applyingPrefill` is also what tells the version field's listener not to
     * mark this as a user edit.
     */
    private fun prefillVersion(version: String) {
        if (applyingPrefill) return
        applyingPrefill = true
        try {
            versionField.text = version
        } finally {
            applyingPrefill = false
        }
    }

    /**
     * Reads the verdict and prefill decision straight out of the catalog via the pure
     * [dependencyEntryHint], rather than re-resolving the chain, so it updates as fast
     * as typing. The async [ManagedVersionHint] lookup in [loadHint] stays as the
     * fallback for when the catalog could not be loaded at all.
     */
    private fun updateHint() {
        val available = candidates
        if (available == null) {
            if (candidatesLoadFailed) {
                hintLabel.text = "Could not read this module's BOM chain — suggestions unavailable"
            } else {
                hintDebounce.restart()
            }
            return
        }

        val guidance = dependencyEntryHint(available, parseGa(), versionField.text.trim(), versionEditedByUser)
        guidance.prefillVersion?.let { prefillVersion(it) }
        hintLabel.text = guidance.hintText
    }

    private fun loadHint() {
        val ga = parseGa()
        val choice = moduleCombo.selectedItem as? ModuleChoice
        val generation = hintGeneration.incrementAndGet()
        if (ga == null || choice == null) {
            hintLabel.text = " "
            return
        }
        hintLabel.text = "Checking BOM..."
        ApplicationManager.getApplication().executeOnPooledThread {
            val text = try {
                when (val lookup = resolvingMissingPoms(project) { onMissing ->
                    hintProvider(project, onMissing).lookup(choice.model, project, ga)
                }) {
                    is ManagedVersionLookup.Found ->
                        withUncheckedSuffix("Currently managed at ${lookup.version}", lookup.uncheckedBoms.size)
                    is ManagedVersionLookup.NotFound ->
                        withUncheckedSuffix(
                            "Not currently managed by any BOM in this module's chain",
                            lookup.uncheckedBoms.size
                        )
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
            SwingUtilities.invokeLater {
                if (generation == hintGeneration.get()) hintLabel.text = text
            }
        }
    }

    /**
     * Qualifies a [loadHint] answer the same way the catalog-loaded hint qualifies its
     * suggestions: [ManagedVersionLookup]'s own contract is that "not found" (and even
     * "found", since a higher-precedence unchecked BOM could outrank the one that
     * matched) is not a confident answer once any BOM in the chain could not be read.
     * This fallback path fires disproportionately in exactly that situation, since it
     * is what runs when the full catalog itself could not be built.
     */
    private fun withUncheckedSuffix(text: String, uncheckedCount: Int): String {
        if (uncheckedCount == 0) return text
        val plural = if (uncheckedCount == 1) "" else "s"
        return "$text — $uncheckedCount BOM$plural could not be read, so this may not be conclusive"
    }

    /**
     * Reloaded whenever the module changes, because the suggestions are exactly what
     * *that* module's BOM chain manages. Off the EDT: building every BOM's effective
     * model reads POMs from the local repository.
     */
    private fun reloadCandidates() {
        val choice = moduleCombo.selectedItem as? ModuleChoice ?: return
        val generation = catalogGeneration.incrementAndGet()
        candidates = null
        candidatesLoadFailed = false
        setPickersEnabled(false)

        ApplicationManager.getApplication().executeOnPooledThread {
            val loaded = try {
                resolvingMissingPoms(project) { onMissing ->
                    DependencyCandidates(hintProvider(project, onMissing).catalog(choice.model, project))
                }
            } catch (e: CancellationException) {
                // See loadHint(): cancellation propagates instead of being reported as a failed
                // candidate load, which would disable the pickers as if the chain were unreadable.
                throw e
            } catch (e: Throwable) {
                null
            }

            SwingUtilities.invokeLater {
                if (generation != catalogGeneration.get()) return@invokeLater
                candidates = loaded
                candidatesLoadFailed = loaded == null
                groupField.setVariants(loaded?.groups() ?: emptyList())
                refreshArtifactVariants()
                setPickersEnabled(loaded != null)
                // Direct, not debounced: candidates was just assigned above, so this is
                // a one-off read of already-loaded data rather than a per-keystroke cost.
                updateHint()
            }
        }
    }

    private fun setPickersEnabled(enabled: Boolean) {
        groupPicker.setButtonEnabled(enabled)
        artifactPicker.setButtonEnabled(enabled)
    }

    /** Group chosen -> only that group's artifacts; otherwise every artifactId in the chain. */
    private fun refreshArtifactVariants() {
        val available = candidates ?: return
        val group = groupField.text.trim()
        val artifacts = if (group.isNotBlank() && group in available.groups()) {
            available.artifactsIn(group)
        } else {
            available.allArtifactIds()
        }
        artifactField.setVariants(artifacts)
    }

    /**
     * Fills the group in once the typed artifactId belongs to exactly one - the common
     * case, since developers remember artifactIds and not groupIds. Deferred via
     * invokeLater because this runs inside the artifact field's document event, and
     * writing to another editor's document mid-event is illegal.
     *
     * The artifactId is captured now and re-checked (both against the field's text and
     * against a freshly-derived [DependencyCandidates.uniqueGroupFor]) once the lambda
     * actually runs, rather than trusting the answer computed at scheduling time: the
     * module - and so `candidates` - can change before this fires, and the user can
     * keep typing. Bound to a [ModalityState] tied to the field, which IntelliJ's
     * invocator drops once that component's window closes, so this never writes to a
     * field whose dialog has already gone away.
     */
    private fun maybeFillGroupFromArtifact() {
        if (groupField.text.isNotBlank()) return
        val artifactId = artifactField.text.trim()
        if (artifactId.isBlank()) return
        ApplicationManager.getApplication().invokeLater(
            {
                if (groupField.text.isNotBlank()) return@invokeLater
                if (artifactField.text.trim() != artifactId) return@invokeLater
                val group = candidates?.uniqueGroupFor(artifactId) ?: return@invokeLater
                groupField.text = group
            },
            ModalityState.stateForComponent(artifactField)
        )
    }

    /**
     * Muscle memory from the pre-Phase-8 single combined field: a pasted or typed
     * "groupId:artifactId" lands whole in the Group ID field. Splitting it only when
     * there is exactly one colon and the artifact field is still empty keeps this from
     * fighting a user who is mid-typing a real groupId, or overwriting an artifactId
     * they already entered. Deferred and modality-bound for the same reasons as
     * [maybeFillGroupFromArtifact]: it writes to a second editor's document from
     * inside the first one's change notification, and the field's text is re-checked
     * once the lambda runs rather than trusted from scheduling time.
     */
    private fun maybeSplitPastedCoordinate() {
        if (artifactField.text.isNotBlank()) return
        val text = groupField.text
        val firstColon = text.indexOf(':')
        if (firstColon == -1 || text.indexOf(':', firstColon + 1) != -1) return
        val groupId = text.substring(0, firstColon).trim()
        val artifactId = text.substring(firstColon + 1).trim()
        if (groupId.isBlank() || artifactId.isBlank()) return

        ApplicationManager.getApplication().invokeLater(
            {
                if (groupField.text != text) return@invokeLater
                if (artifactField.text.isNotBlank()) return@invokeLater
                groupField.text = groupId
                artifactField.text = artifactId
            },
            ModalityState.stateForComponent(groupField)
        )
    }

    private fun chooseGroup() {
        val available = candidates ?: return
        showChooser("Group ID", available.groups(), groupPicker) { groupField.text = it }
    }

    /**
     * With no group chosen the list shows full coordinates, so picking one fills both
     * fields - which is also how an ambiguous artifactId gets disambiguated, since
     * uniqueGroupFor deliberately refuses to guess in that case.
     */
    private fun chooseArtifact() {
        val available = candidates ?: return
        val group = groupField.text.trim()

        if (group.isNotBlank() && group in available.groups()) {
            showChooser("Artifact ID", available.artifactsIn(group), artifactPicker) {
                artifactField.text = it
            }
            return
        }

        val coordinates = available.allCoordinates()
        showChooser("Dependency", coordinates.map { it.toString() }, artifactPicker) { chosen ->
            val ga = coordinates.firstOrNull { it.toString() == chosen } ?: return@showChooser
            groupField.text = ga.groupId
            artifactField.text = ga.artifactId
        }
    }

    private fun showChooser(
        title: String,
        items: List<String>,
        anchor: JComponent,
        onChosen: (String) -> Unit
    ) {
        if (items.isEmpty()) return
        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(items)
            .setTitle(title)
            .setNamerForFiltering { it }
            // Always visible: a BOM chain can manage well over a thousand artifacts,
            // and scrolling that is not browsing.
            .setFilterAlwaysVisible(true)
            .setItemChosenCallback { onChosen(it) }
            .createPopup()
            .showUnderneathOf(anchor)
    }
}
