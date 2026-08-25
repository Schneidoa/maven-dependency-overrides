package cloud.schneidoa.ui

import cloud.schneidoa.detection.BomChainReport
import cloud.schneidoa.detection.OverrideVerdict
import cloud.schneidoa.detection.ProjectOverrideEntry
import cloud.schneidoa.detection.ProjectOverrideScanner
import cloud.schneidoa.detection.buildBomChainReportFor
import cloud.schneidoa.detection.candidate
import cloud.schneidoa.detection.declaredToManaged
import cloud.schneidoa.detection.managedByChain
import cloud.schneidoa.detection.removeOverride
import cloud.schneidoa.detection.resolvingMissingPoms
import cloud.schneidoa.detection.verdictExplanation
import cloud.schneidoa.detection.verdictLabel
import cloud.schneidoa.detection.verdictOf
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.externalSystem.dependency.analyzer.DAArtifact
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import org.jetbrains.idea.maven.dom.MavenDomUtil
import org.jetbrains.idea.maven.dom.model.MavenDomProjectModel
import org.jetbrains.idea.maven.project.MavenProjectsManager
import java.awt.BorderLayout
import java.awt.Component
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JTable
import javax.swing.SwingUtilities
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.DefaultTableModel

class OverrideOverviewToolWindowFactory : ToolWindowFactory {
    override fun shouldBeAvailable(project: Project) = true

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = OverrideOverviewPanel(project)
        val content = ContentFactory.getInstance().createContent(panel.component, null, false)
        toolWindow.contentManager.addContent(content)
        panel.refresh()
    }
}

private val COLUMNS = arrayOf("Module", "Dependency", "Declared → Managed", "Managed By", "Status")

private class OverrideOverviewPanel(private val project: Project) {
    private val scanner = ProjectOverrideScanner()

    /**
     * Every row the last scan found, before the status filter. [currentEntries] /
     * [mavenSyncedRows] are the filtered projection of these two that the table actually
     * shows - [applyFilter] rebuilds them (and the table) from here whenever either the
     * scan result or [activeVerdicts] changes, so a filter the user set stays applied
     * across a Refresh instead of resetting.
     */
    private var allEntries: List<ProjectOverrideEntry> = emptyList()

    /** Parallel to [allEntries]; see [mavenSyncedRows] for what the values mean. */
    private var allSyncedRows: List<Boolean> = emptyList()

    /** Which verdicts the Filter by Status popup currently shows. All of them by default. */
    private val activeVerdicts: MutableSet<OverrideVerdict> = OverrideVerdict.entries.toMutableSet()

    private var currentEntries: List<ProjectOverrideEntry> = emptyList()

    /**
     * Whether row N's module has a resolved [org.jetbrains.idea.maven.project.MavenProject],
     * parallel to [currentEntries] and always replaced together with it in [applyFilter]. This
     * is computed in [refresh], off the EDT, because the first `MavenProjectsManager.findProject`
     * call after startup can lazily deserialize the cached projects tree from disk - not safe to
     * do from [showContextMenu], which runs on the EDT. A boolean captured at the last refresh
     * can go stale if a Maven sync completes afterward, but that's the same staleness this panel
     * already tolerates elsewhere (see the stale-snapshot handling around `removeOverride`), and
     * the next refresh (manual or row mutation) picks up the change.
     */
    private var mavenSyncedRows: List<Boolean> = emptyList()
    private val refreshGeneration = AtomicInteger(0)
    private val logger = Logger.getInstance(OverrideOverviewPanel::class.java)

    private val tableModel = object : DefaultTableModel(COLUMNS, 0) {
        override fun isCellEditable(row: Int, column: Int) = false
    }
    private val table = JBTable(tableModel).apply {
        setShowGrid(false)
        rowSelectionAllowed = true
        columnSelectionAllowed = false
        columnModel.getColumn(COLUMNS.lastIndex).cellRenderer = VerdictCellRenderer()
    }
    private val offlineNotice = JLabel().apply { isVisible = false }

    /** How many of [allEntries] the active status filter is currently hiding, if any. */
    private val filterNotice = JLabel().apply { isVisible = false }

    /** Two independent notices that can both apply at once, so they stack rather than share a slot. */
    private val notices = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        add(filterNotice)
        add(offlineNotice)
    }

    val component: JPanel = JPanel(BorderLayout()).apply {
        add(createToolbar().component, BorderLayout.NORTH)
        add(JBScrollPane(table), BorderLayout.CENTER)
        add(notices, BorderLayout.SOUTH)
    }

    init {
        table.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount != 2) return
                val row = table.rowAtPoint(e.point)
                if (row < 0 || row >= currentEntries.size) return
                navigateTo(currentEntries[row])
            }

            override fun mousePressed(e: MouseEvent) = maybeShowContextMenu(e)
            override fun mouseReleased(e: MouseEvent) = maybeShowContextMenu(e)
        })
    }

    private fun maybeShowContextMenu(e: MouseEvent) {
        if (!e.isPopupTrigger) return
        val row = table.rowAtPoint(e.point)
        if (row < 0 || row >= currentEntries.size) return
        table.setRowSelectionInterval(row, row)
        showContextMenu(row, currentEntries[row], e)
    }

    private fun showContextMenu(row: Int, entry: ProjectOverrideEntry, e: MouseEvent) {
        val menu = JPopupMenu()
        menu.add("Edit...").addActionListener { openEditDialog(entry) }
        menu.add("Remove").addActionListener { removeWithConfirmation(entry) }
        menu.addSeparator()
        menu.add("Show BOM Chain").addActionListener { showBomChain(entry) }
        // Bounds-checked defensively like the Status column renderer: a stale/out-of-range
        // row falls back to disabled rather than throwing or trusting an EDT lookup.
        val mavenSynced = mavenSyncedRows.getOrNull(row) ?: false
        // Swing does not deliver mouse events to a disabled lightweight component, so a
        // disabled item's tooltip will almost never actually show - ToolTipManager is driven
        // entirely by mouse events. The reason still needs to be visible without hovering, so
        // it goes in the label itself; the tooltip is kept too, since it costs nothing and does
        // work on some platforms/LaFs.
        val analyzeLabel = if (mavenSynced) "Analyze Dependencies" else "Analyze Dependencies (requires Maven sync)"
        val analyze = menu.add(analyzeLabel)
        if (!mavenSynced) {
            analyze.isEnabled = false
            analyze.toolTipText = "Requires a completed Maven sync"
        } else {
            analyze.addActionListener { analyzeDependencies(entry) }
        }
        menu.show(e.component, e.x, e.y)
    }

    private fun createToolbar(): ActionToolbar {
        val refreshAction = object : AnAction(
            "Refresh",
            "Rescan the project for dependencyManagement overrides",
            AllIcons.Actions.Refresh
        ) {
            override fun actionPerformed(e: AnActionEvent) = refresh()
        }
        val addAction = object : AnAction(
            "Add",
            "Add a new dependencyManagement override",
            AllIcons.General.Add
        ) {
            override fun actionPerformed(e: AnActionEvent) = openAddDialog()
        }
        val filterAction = object : AnAction(
            "Filter by Status",
            "Show only overrides whose status is checked",
            AllIcons.General.Filter
        ) {
            override fun actionPerformed(e: AnActionEvent) = showFilterPopup(e)
        }
        val group = DefaultActionGroup(refreshAction, addAction, filterAction)
        return ActionManager.getInstance().createActionToolbar(ActionPlaces.TOOLBAR, group, true).apply {
            targetComponent = table
        }
    }

    /**
     * A plain component popup with real checkboxes, not a `DefaultActionGroup`/`ToggleAction`
     * popup: whether a checkable action item keeps a popup open across repeated clicks (needed
     * here, since checking several statuses in one go is the point) depends on platform-version
     * behavior this project has no way to verify without a running IDE, whereas a checkbox
     * inside a plain hosted component never closes its popup on its own regardless of platform
     * version - only dismissing the popup itself (click-away, Escape) does.
     */
    private fun showFilterPopup(e: AnActionEvent) {
        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(4)
        }
        for (verdict in OverrideVerdict.entries) {
            panel.add(
                JBCheckBox(filterLabelFor(verdict), verdict in activeVerdicts).apply {
                    addActionListener {
                        if (isSelected) activeVerdicts += verdict else activeVerdicts -= verdict
                        applyFilter()
                    }
                }
            )
        }

        val popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(panel, null)
            .setRequestFocus(true)
            .setResizable(false)
            .createPopup()
        // Anchored under the toolbar button that was clicked when there is one (the normal
        // case); falls back to the platform's own placement for a keyboard-triggered
        // invocation, which carries no mouse-sourced component to anchor under.
        val anchor = e.inputEvent?.component as? JComponent
        if (anchor != null) popup.showUnderneathOf(anchor) else popup.showInBestPositionFor(e.dataContext)
    }

    fun refresh() {
        val generation = refreshGeneration.incrementAndGet()
        ApplicationManager.getApplication().executeOnPooledThread {
            val entries = try {
                resolvingMissingPoms(project) { onMissing -> scanner.scan(project, onMissing) }
            } catch (e: CancellationException) {
                // Must precede the broad catch. RemotePomFetcher deliberately rethrows cancellation
                // rather than folding it into its "could not fetch" degradation; catching it here
                // would undo that. ProcessCanceledException extends
                // java.util.concurrent.CancellationException, so this covers a cancelled progress
                // indicator and a closing project too - and logging one of those is the classic
                // PCE-logging anti-pattern, not a failure worth a warning.
                throw e
            } catch (e: Throwable) {
                logger.warn("Failed to scan project for dependency overrides", e)
                SwingUtilities.invokeLater {
                    if (generation == refreshGeneration.get()) populate(emptyList(), emptyList(), false)
                }
                return@executeOnPooledThread
            }

            // Scoped separately from the scan above so a throw here - e.g. from
            // MavenProjectsManager.findProject - degrades to "sync status unknown" for every
            // row instead of discarding an otherwise-successful scan. Off the EDT because
            // findProject can lazily deserialize the cached projects tree from disk the first
            // time it's called - see the mavenSyncedRows KDoc.
            val syncedRows = try {
                // Looked up once per distinct pom.xml, not once per row: a module with
                // several flagged overrides would otherwise repeat the exact same
                // findProject call once per row it contributes.
                val syncedByFile = entries.map { it.pomFile }.distinct()
                    .associateWith { MavenProjectsManager.getInstance(project).findProject(it) != null }
                entries.map { entry -> syncedByFile.getValue(entry.pomFile) }
            } catch (e: Throwable) {
                logger.warn("Failed to determine Maven sync status for scanned overrides", e)
                entries.map { false }
            }

            // Scoped separately from both probes above for the same reason: a throw reading
            // the offline setting must not discard an otherwise-successful scan, it should
            // just leave the notice unable to explain itself and default to "online".
            val offline = try {
                MavenProjectsManager.getInstance(project).generalSettings.isWorkOffline
            } catch (e: Throwable) {
                logger.warn("Failed to read Maven offline setting", e)
                false
            }

            SwingUtilities.invokeLater {
                if (generation == refreshGeneration.get()) populate(entries, syncedRows, offline)
            }
        }
    }

    private fun populate(
        entries: List<ProjectOverrideEntry>,
        syncedRows: List<Boolean>,
        offline: Boolean
    ) {
        allEntries = entries
        allSyncedRows = syncedRows
        // One message for the whole table rather than a per-row explanation: the cause is
        // global (Maven's offline setting), and repeating it on every Inconclusive row would
        // bury it rather than surface it. This is also why the reason is not threaded through
        // DetectedOverride - every row would end up saying the same sentence. Read off the full,
        // unfiltered scan: whether Maven is offline is unrelated to what the status filter is
        // currently showing, so a hidden Inconclusive row must still be able to explain itself.
        val anyInconclusive = entries.any { verdictOf(it.override) == OverrideVerdict.INCONCLUSIVE }
        offlineNotice.isVisible = offline && anyInconclusive
        offlineNotice.text = if (offlineNotice.isVisible) {
            "Maven is in offline mode - BOMs missing from the local repository were not downloaded."
        } else {
            ""
        }
        applyFilter()
    }

    /**
     * Rebuilds [currentEntries]/[mavenSyncedRows] and the table from [allEntries]/
     * [allSyncedRows], keeping only the rows whose verdict is in [activeVerdicts]. Called both
     * after every [populate] and on every checkbox toggle in [showFilterPopup], so the filter
     * applies immediately and stays applied across the next Refresh. No `TableRowSorter`/
     * `RowFilter` involved on purpose: the table is rebuilt from a smaller list instead of
     * having rows hidden underneath it, so every other row-index lookup in this class (the
     * double-click handler, the context menu, [VerdictCellRenderer]) keeps indexing into
     * [currentEntries] by view row with no view-to-model translation needed anywhere.
     */
    private fun applyFilter() {
        val kept = allEntries.indices.filter { verdictOf(allEntries[it].override) in activeVerdicts }
        currentEntries = kept.map { allEntries[it] }
        mavenSyncedRows = kept.map { allSyncedRows[it] }

        val hiddenCount = allEntries.size - currentEntries.size
        filterNotice.isVisible = hiddenCount > 0
        filterNotice.text = if (filterNotice.isVisible) {
            "$hiddenCount override${if (hiddenCount == 1) "" else "s"} hidden by the status filter."
        } else {
            ""
        }

        tableModel.rowCount = 0
        for (entry in currentEntries) {
            tableModel.addRow(
                arrayOf(
                    entry.moduleLabel,
                    entry.override.candidate.ga.toString(),
                    declaredToManaged(entry.override),
                    managedByChain(entry.override),
                    verdictLabel(entry.override)
                )
            )
        }
    }

    private fun navigateTo(entry: ProjectOverrideEntry) {
        val offset = ReadAction.compute<Int?, Throwable> {
            val tag = entry.override.candidate.versionXmlTag
            if (tag.isValid) tag.textOffset else null
        } ?: return
        OpenFileDescriptor(project, entry.pomFile, offset).navigate(true)
    }

    private fun removeWithConfirmation(entry: ProjectOverrideEntry) {
        val answer = Messages.showYesNoDialog(
            project,
            "Remove the override for ${entry.override.candidate.ga}?",
            "Remove Dependency Override",
            Messages.getQuestionIcon()
        )
        if (answer != Messages.YES) return
        try {
            removeOverride(project, entry.override.candidate)
        } catch (e: Exception) {
            logger.warn("Failed to remove override for ${entry.override.candidate.ga}", e)
            Messages.showErrorDialog(
                project,
                "Could not remove this override - it may be out of date. Try refreshing and retrying.",
                "Remove Failed"
            )
        }
        refresh()
    }

    /**
     * Re-resolves the chain for just this row rather than carrying it in every
     * scan result - the dialog is opened rarely, and widening
     * DetectedOverride to hold the whole per-entry chain would make every
     * project scan heavier to serve it.
     */
    private fun showBomChain(entry: ProjectOverrideEntry) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val report = try {
                resolvingMissingPoms(project) { onMissing ->
                    buildBomChainReportFor(
                        project = project,
                        pomFile = entry.pomFile,
                        ga = entry.override.candidate.ga,
                        declaredVersion = entry.override.candidate.declaredVersion,
                        moduleLabel = entry.moduleLabel,
                        onMissingPom = onMissing
                    )
                }
            } catch (ex: CancellationException) {
                // See refresh(): cancellation propagates instead of degrading to an error dialog.
                throw ex
            } catch (ex: Throwable) {
                logger.warn("Failed to build BOM chain report for ${entry.override.candidate.ga}", ex)
                null
            }

            SwingUtilities.invokeLater {
                if (report == null) {
                    Messages.showErrorDialog(
                        project,
                        "Could not resolve the BOM chain for this entry. Try refreshing and retrying.",
                        "Show BOM Chain Failed"
                    )
                } else {
                    BomChainDialog(project, report).show()
                }
            }
        }
    }

    /**
     * Hands the artifact coordinate to IntelliJ's own Maven Dependency Analyzer
     * rather than computing a transitive tree here - that view already exists in
     * the Maven support this plugin depends on, and it answers a different
     * question from the BOM chain dialog: who pulls this in, not who manages its
     * version.
     */
    private fun analyzeDependencies(entry: ProjectOverrideEntry) {
        // Off the EDT, not merely inside a ReadAction: findModuleForFile goes through
        // ProjectFileIndex, which forces the workspace model up to date and trips
        // SlowOperations.assertSlowOperationsAreAllowed. A read action grants the read lock
        // but does not move the work off the calling thread - the same distinction that
        // applies to the Maven-sync probe in refresh().
        ApplicationManager.getApplication().executeOnPooledThread {
            val module = try {
                ReadAction.compute<Module?, Throwable> {
                    ModuleUtilCore.findModuleForFile(entry.pomFile, project)
                }
            } catch (e: Throwable) {
                logger.warn("Failed to resolve the module owning ${entry.pomFile.path}", e)
                null
            }

            SwingUtilities.invokeLater {
                if (module == null) {
                    Messages.showErrorDialog(
                        project,
                        "Could not determine which module this pom.xml belongs to.",
                        "Analyze Dependencies Failed"
                    )
                    return@invokeLater
                }

                // Back on the EDT: opening the analyzer's tool window is UI work.
                val ga = entry.override.candidate.ga
                val action = AnalyzeDependencyAction(
                    module,
                    DAArtifact(ga.groupId, ga.artifactId, entry.override.candidate.declaredVersion)
                )
                ActionUtil.performAction(
                    action,
                    AnActionEvent.createEvent(
                        action,
                        SimpleDataContext.getProjectContext(project),
                        null,
                        ActionPlaces.TOOLWINDOW_POPUP,
                        ActionUiKind.POPUP,
                        null
                    )
                )
            }
        }
    }

    /**
     * Resolves the DOM model off the EDT for the same reason [analyzeDependencies] does:
     * getMavenDomProjectModel turns a VirtualFile into PSI, which can parse the file on
     * demand - work SlowOperations forbids on the EDT. Unlike the analyzer path this hasn't
     * been observed throwing, but it is the same shape, and the dialog is only built once
     * the model is in hand, so moving it costs nothing.
     */
    private fun openEditDialog(entry: ProjectOverrideEntry) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val model = try {
                ReadAction.compute<MavenDomProjectModel?, Throwable> {
                    MavenDomUtil.getMavenDomProjectModel(project, entry.pomFile)
                }
            } catch (e: Throwable) {
                logger.warn("Failed to load the POM model for ${entry.override.candidate.ga}", e)
                null
            } ?: return@executeOnPooledThread

            SwingUtilities.invokeLater {
                val dialog = EditOverrideDialog(project, model, entry.override.candidate)
                try {
                    if (!dialog.showAndGet()) return@invokeLater
                } catch (e: Exception) {
                    logger.warn("Failed to save edit for ${entry.override.candidate.ga}", e)
                    Messages.showErrorDialog(
                        project,
                        "Could not save this edit - the entry may be out of date. Try refreshing and retrying.",
                        "Edit Failed"
                    )
                    refresh()
                    return@invokeLater
                }
                refresh()
            }
        }
    }

    private fun openAddDialog() {
        ApplicationManager.getApplication().executeOnPooledThread {
            // Unlike every other action in this class, this used to have no try/catch at
            // all around its ReadAction.compute: a throw here (e.g. a PSI/VFS hiccup while
            // indexing) propagated out of executeOnPooledThread uncaught, so clicking "Add"
            // would silently do nothing with no error ever shown.
            val modules = try {
                ReadAction.compute<List<ModuleChoice>, Throwable> { findAddOverrideModules(project) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logger.warn("Failed to load this project's modules for the Add dialog", e)
                null
            }

            SwingUtilities.invokeLater {
                if (modules == null) {
                    Messages.showErrorDialog(
                        project,
                        "Could not load this project's modules. Try refreshing and retrying.",
                        "Add Failed"
                    )
                    return@invokeLater
                }
                val dialog = AddOverrideDialog(project, modules)
                try {
                    if (!dialog.showAndGet()) return@invokeLater
                } catch (e: Exception) {
                    logger.warn("Failed to add new override", e)
                    Messages.showErrorDialog(
                        project,
                        "Could not add this override. Try refreshing and retrying.",
                        "Add Failed"
                    )
                    refresh()
                    return@invokeLater
                }
                refresh()
            }
        }
    }

    /**
     * Renders the Status column's icon and tooltip alongside the label already in the
     * cell value. The cell value stays a plain String (not the ProjectOverrideEntry)
     * because JBTable inherits Swing's built-in Ctrl+C copy handler, which reads
     * getValueAt(...).toString() independent of any installed renderer — putting the
     * entry there would paste its data-class toString() into copied rows instead of
     * the verdict label. So the entry is looked up by row index from currentEntries
     * instead, via this inner class.
     */
    private inner class VerdictCellRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable,
            value: Any?,
            isSelected: Boolean,
            hasFocus: Boolean,
            row: Int,
            column: Int
        ): Component {
            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
            // row is a view-row index; treated as a model-row index here because no
            // TableRowSorter is installed on this table, so the two coincide. Bounds-checked
            // defensively rather than assumed, so a stale/out-of-range row degrades to a plain
            // label instead of throwing.
            val entry = currentEntries.getOrNull(row)
            icon = entry?.let { iconFor(verdictOf(it.override)) }
            toolTipText = entry?.let { verdictExplanation(it.override) }
            return this
        }
    }
}

/**
 * Static per-verdict label for the status filter's checkboxes - deliberately not
 * [verdictLabel], which bakes in a per-instance count ("Inconclusive (3 POMs unchecked)")
 * that names one row's evidence and has no meaning for a checkbox naming the whole category.
 */
private fun filterLabelFor(verdict: OverrideVerdict): String = when (verdict) {
    OverrideVerdict.REDUNDANT -> "Redundant"
    OverrideVerdict.AHEAD_OF_BOM -> "Ahead of BOM"
    OverrideVerdict.BEHIND_BOM -> "Behind BOM"
    OverrideVerdict.NOT_COMPARABLE -> "Not comparable"
    OverrideVerdict.INCONCLUSIVE -> "Inconclusive"
    OverrideVerdict.UNMANAGED -> "Not managed by BOM"
}

private fun iconFor(verdict: OverrideVerdict) = when (verdict) {
    OverrideVerdict.REDUNDANT -> AllIcons.General.GreenCheckmark
    OverrideVerdict.AHEAD_OF_BOM -> AllIcons.General.ArrowUp
    OverrideVerdict.BEHIND_BOM -> AllIcons.General.Warning
    OverrideVerdict.NOT_COMPARABLE -> AllIcons.General.Information
    OverrideVerdict.INCONCLUSIVE -> AllIcons.General.ShowWarning
    // Neutral rather than cautionary: nothing is wrong with the entry, the BOM chain simply
    // has no opinion on it. Deliberately not the Information icon NOT_COMPARABLE uses - the
    // two rows sit next to each other in the same table and mean different things.
    OverrideVerdict.UNMANAGED -> AllIcons.General.Note
}
