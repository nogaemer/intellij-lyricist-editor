package de.nogaemer.i18neditor.toolwindow

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.JBUI
import javax.swing.JLabel
import javax.swing.SwingConstants

class I18nToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        // On first open show a placeholder — OpenI18nEditorAction swaps in the real panel
        val placeholder = JLabel(
            "<html><center>Right-click any Lyricist strings .kt file<br>" +
                    "and choose <b>Open in i18n Editor</b></center></html>",
            SwingConstants.CENTER
        ).apply { border = JBUI.Borders.empty(24) }

        val content = ContentFactory.getInstance()
            .createContent(placeholder, "No file loaded", false)
        toolWindow.contentManager.addContent(content)
    }

    override fun shouldBeAvailable(project: Project) = true
}