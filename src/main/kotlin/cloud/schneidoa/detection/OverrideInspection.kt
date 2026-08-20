package cloud.schneidoa.detection

import cloud.schneidoa.detection.quickfix.RemoveOverrideQuickFix
import cloud.schneidoa.detection.quickfix.SuppressOverrideQuickFix
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.XmlElementVisitor
import com.intellij.psi.xml.XmlFile
import org.jetbrains.idea.maven.dom.MavenDomUtil

/**
 * [detectorFactory] defaults to the real, IDE-backed OverrideDetector.forProject,
 * but is overridable so tests can inject a detector backed by a fixture
 * repository directly, instead of depending on how MavenProjectsManager /
 * MavenSettingsCache resolve a local repository path inside a lightweight
 * test sandbox.
 */
class OverrideInspection(
    private val detectorFactory: (Project) -> OverrideDetector = OverrideDetector::forProject
) : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor =
        object : XmlElementVisitor() {
            override fun visitXmlFile(file: XmlFile) {
                val project = file.project
                val virtualFile = file.virtualFile ?: return
                val model = MavenDomUtil.getMavenDomProjectModel(project, virtualFile) ?: return
                val detector = detectorFactory(project)

                for (result in detector.detect(model, project)) {
                    when (verdictOf(result)) {
                        // Redundant: the BOM caught up, so removal is the whole point.
                        // Behind the BOM: removing the pin raises the version to the BOM's,
                        // which is the desired outcome - such a pin is nearly always one set
                        // once and never revisited, now holding the dependency below what the
                        // BOM already ships. The message says so explicitly, so the fix isn't
                        // a silent build-affecting change.
                        OverrideVerdict.REDUNDANT, OverrideVerdict.BEHIND_BOM -> holder.registerProblem(
                            result.candidate.versionXmlTag,
                            verdictExplanation(result),
                            ProblemHighlightType.WEAK_WARNING,
                            RemoveOverrideQuickFix(),
                            SuppressOverrideQuickFix()
                        )
                        OverrideVerdict.INCONCLUSIVE -> holder.registerProblem(
                            result.candidate.versionXmlTag,
                            verdictExplanation(result),
                            ProblemHighlightType.WEAK_WARNING,
                            SuppressOverrideQuickFix()
                        )
                        // Silent on purpose. AHEAD_OF_BOM is a pin that is still doing its
                        // job - warning about it is noise. NOT_COMPARABLE reached no verdict,
                        // so there is nothing to say. UNMANAGED is not an override of anything
                        // the BOM chain says, so the editor has no BOM-based claim to make about
                        // it at all - and a warning on every hand-pinned transitive version would
                        // be the loudest possible noise in exactly the projects that have most of
                        // them. All three stay visible in the tool window.
                        OverrideVerdict.AHEAD_OF_BOM,
                        OverrideVerdict.NOT_COMPARABLE,
                        OverrideVerdict.UNMANAGED -> Unit
                    }
                }
            }
        }
}
