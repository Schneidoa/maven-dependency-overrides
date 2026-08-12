package cloud.schneidoa.ui

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.externalSystem.dependency.analyzer.AbstractDependencyAnalyzerAction
import com.intellij.openapi.externalSystem.dependency.analyzer.DAArtifact
import com.intellij.openapi.externalSystem.dependency.analyzer.DependencyAnalyzerDependency
import com.intellij.openapi.externalSystem.model.ProjectSystemId
import com.intellij.openapi.module.Module
import org.jetbrains.idea.maven.utils.MavenUtil

/**
 * Opens IntelliJ's Maven Dependency Analyzer with one artifact preselected.
 *
 * Extending [AbstractDependencyAnalyzerAction] rather than calling
 * `DependencyAnalyzerManager.getInstance(project).getOrCreate(...)` directly is
 * deliberate and load-bearing: the manager is `@ApiStatus.Internal`, which the
 * IntelliJ Plugin Verifier reports as an INTERNAL_API_USAGES problem - failing
 * `./gradlew verifyPlugin` and the JetBrains Marketplace review with it. The
 * abstract action is public API and performs exactly the same work, just inside
 * the platform. Do not "simplify" this back into a direct manager call.
 *
 * The instance carries an already-resolved [module] and [artifact] instead of
 * digging them out of the event's DataContext, because the resolution work
 * (`ModuleUtilCore.findModuleForFile`) is a slow operation and has to happen off
 * the EDT, while [getModule] and [getDependencyData] are called by the base
 * class while handling the action. `Unit` stands in for the selected data: the
 * selection is fixed at construction, so there is nothing left to read from the
 * event.
 */
internal class AnalyzeDependencyAction(
    private val module: Module,
    private val artifact: DAArtifact,
) : AbstractDependencyAnalyzerAction<Unit>() {

    override fun getSystemId(e: AnActionEvent): ProjectSystemId = MavenUtil.SYSTEM_ID

    override fun getSelectedData(e: AnActionEvent) = Unit

    override fun getModule(e: AnActionEvent, selectedData: Unit): Module = module

    override fun getDependencyData(e: AnActionEvent, selectedData: Unit): DependencyAnalyzerDependency.Data = artifact

    /**
     * Null means "don't preselect a scope". A `<dependencyManagement>` entry
     * declares the version, not necessarily the scope the dependency is finally
     * used at, so any scope this picked would be a guess - and a wrong guess
     * would filter the analyzer's view down to something that doesn't contain
     * the artifact at all.
     */
    override fun getDependencyScope(e: AnActionEvent, selectedData: Unit): String? = null
}
