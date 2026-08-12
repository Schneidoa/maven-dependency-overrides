package cloud.schneidoa.ui

import cloud.schneidoa.detection.BomChainEntry
import cloud.schneidoa.detection.BomChainReport
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreeSelectionModel

/**
 * Read-only view of every BOM that was consulted for one override, in the
 * precedence order Maven itself would apply. Exists because the panel's
 * "Managed By" column can only show the winner, truncated - which is no help
 * when the question is why a given version won, or why no answer was reached.
 */
class BomChainDialog(project: Project, private val report: BomChainReport) : DialogWrapper(project) {

    init {
        title = "BOM Chain for ${report.ga}"
        init()
    }

    override fun createCenterPanel(): JComponent {
        val root = DefaultMutableTreeNode("${report.ga} — declared ${report.declaredVersion} in ${report.moduleLabel}")

        if (report.entries.isEmpty()) {
            root.add(DefaultMutableTreeNode("No BOMs are imported by this module or its parents"))
        }
        for (entry in report.entries) {
            root.add(nodeFor(entry))
        }

        val tree = Tree(DefaultTreeModel(root)).apply {
            selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
            isRootVisible = true
            showsRootHandles = true
        }
        // rowCount grows as rows are expanded, so it must be re-read each iteration - a `for` loop
        // that captures it once would silently stop expanding partway if a third tree level is
        // ever added.
        var row = 0
        while (row < tree.rowCount) {
            tree.expandRow(row)
            row++
        }

        return JBScrollPane(tree).apply { preferredSize = Dimension(620, 320) }
    }

    private fun nodeFor(entry: BomChainEntry): DefaultMutableTreeNode {
        val via = if (entry.declaredVia.isEmpty()) {
            "declared in this module"
        } else {
            "via ${entry.declaredVia.joinToString(" → ")}"
        }
        val node = DefaultMutableTreeNode("${entry.bom} ($via)")

        // Three distinct messages for BomChainEntry.Manages, not two: a plain loss (another,
        // earlier BOM already resolved and manages the artifact - we know precedence, and can
        // say so) reads very differently from a loss to an unresolvable BOM (we don't know what
        // that earlier BOM manages, so we don't know whether it would have taken precedence
        // here either - saying "an earlier BOM takes precedence" would assert something this
        // report was never able to confirm, contradicting the very Inconclusive verdict this
        // dialog exists to make legible).
        val detail = when (entry) {
            is BomChainEntry.Manages -> when {
                entry.wins -> "manages this at ${entry.version} — this is the version that applies"
                entry.blockedByUnresolvable ->
                    "manages this at ${entry.version}, but an earlier BOM could not be resolved locally, " +
                        "so it isn't known whether this or that earlier BOM's version actually applies"
                else -> "manages this at ${entry.version}, but an earlier BOM takes precedence"
            }
            is BomChainEntry.DoesNotManage -> "does not manage this dependency"
            is BomChainEntry.Unresolvable -> "could not be resolved from the local Maven repository"
        }
        node.add(DefaultMutableTreeNode(detail))
        return node
    }

    override fun createActions() = arrayOf(okAction)
}
