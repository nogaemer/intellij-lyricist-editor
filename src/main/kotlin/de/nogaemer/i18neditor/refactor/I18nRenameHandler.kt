package de.nogaemer.i18neditor.refactor

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.refactoring.rename.RenameHandler
import de.nogaemer.i18neditor.toolwindow.I18nEditorPanel
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtFile

class I18nRenameHandler : RenameHandler {

    /**
     * Called from background threads during action update in IDEA 2024.3.
     * Content manager and PSI access both require EDT — return false immediately
     * when not on EDT to avoid threading violations.
     */
    override fun isAvailableOnDataContext(dataContext: DataContext): Boolean {
        if (!ApplicationManager.getApplication().isDispatchThread) return false
        return resolveParam(dataContext) != null
    }

    override fun isRenaming(dataContext: DataContext): Boolean {
        if (!ApplicationManager.getApplication().isDispatchThread) return false
        return resolveParam(dataContext) != null
    }

    override fun invoke(
        project: Project, editor: Editor?, file: PsiFile?, dataContext: DataContext
    ) = doRename(project, editor, dataContext)

    override fun invoke(
        project: Project, elements: Array<out PsiElement>, dataContext: DataContext
    ) = doRename(project, null, dataContext)

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun doRename(project: Project, editor: Editor?, dataContext: DataContext) {
        val resolved = resolveParam(dataContext) ?: return

        // Trigger the platform RenameElement action instead of constructing
        // RenameDialog directly — avoids slow workspace-index calls on EDT
        // that IDEA 2024.3 prohibits (getSuggestedNames, createSearchScopePanel).
        val ctx = SimpleDataContext.builder()
            .add(CommonDataKeys.PROJECT, project)
            .add(CommonDataKeys.PSI_ELEMENT, resolved.param)
            .apply { if (editor != null) add(CommonDataKeys.EDITOR, editor) }
            .build()

        ActionUtil.invokeAction(
            ActionManager.getInstance().getAction("RenameElement"),
            ctx,
            ActionPlaces.UNKNOWN,
            null, null
        )
        resolved.panel.refresh()
    }

    private data class Resolved(
        val param: org.jetbrains.kotlin.psi.KtParameter,
        val panel: I18nEditorPanel
    )

    /**
     * Must only be called on EDT (guarded by callers).
     * Accesses contentManager and PSI — both EDT-only in IDEA 2024.3.
     */
    private fun resolveParam(dataContext: DataContext): Resolved? {
        val project = ProjectManager.getInstance().openProjects.firstOrNull() ?: return null
        val panel   = activePanel(project) ?: return null
        val key     = panel.getSelectedKey() ?: return null

        val ktFile = PsiManager.getInstance(project)
            .findFile(panel.virtualFile) as? KtFile ?: return null

        val param = ktFile.declarations
            .filterIsInstance<KtClass>()
            .firstOrNull { it.name == key.groupClass }
            ?.primaryConstructor
            ?.valueParameters
            ?.firstOrNull { it.name == key.name } ?: return null

        return Resolved(param, panel)
    }

    /**
     * EDT-only — contentManager access is not safe on background threads.
     */
    private fun activePanel(project: Project): I18nEditorPanel? =
        ToolWindowManager.getInstance(project)
            .getToolWindow("i18n Editor")
            ?.contentManager
            ?.contents
            ?.mapNotNull { it.component as? I18nEditorPanel }
            ?.firstOrNull()
}