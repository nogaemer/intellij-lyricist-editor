package de.nogaemer.i18neditor.action

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.ContentFactory
import de.nogaemer.i18neditor.toolwindow.I18nEditorPanel

class OpenI18nEditorAction : DumbAwareAction() {

    // Declare BGT so getData(VIRTUAL_FILE) is allowed
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        e.presentation.isEnabledAndVisible = file?.extension == "kt"
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project     = e.project ?: return
        val virtualFile = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return

        if (virtualFile.extension != "kt") return
        val content = String(virtualFile.contentsToByteArray())
        if (!content.contains("@LyricistStrings")) return

        val toolWindow = ToolWindowManager.getInstance(project)
            .getToolWindow("i18n Editor") ?: return

        // If already showing this exact file, just focus
        val existing = toolWindow.contentManager.contents
            .mapNotNull { it.component as? I18nEditorPanel }
            .firstOrNull()
        if (existing?.virtualFile == virtualFile) {
            toolWindow.show()
            return
        }

        val panel   = I18nEditorPanel(project, virtualFile)
        val content2 = ContentFactory.getInstance()
            .createContent(panel, virtualFile.name, false)

        toolWindow.contentManager.contents.forEach { content ->
            (content.component as? I18nEditorPanel)?.dispose()
        }
        toolWindow.contentManager.removeAllContents(true)
        toolWindow.contentManager.addContent(content2)
        toolWindow.show()
    }
}