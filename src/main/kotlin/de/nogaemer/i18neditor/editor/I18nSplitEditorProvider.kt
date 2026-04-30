package de.nogaemer.i18neditor.editor

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.fileEditor.TextEditorWithPreview
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

class I18nSplitEditorProvider : FileEditorProvider, DumbAware {

    private val previewProvider = I18nFileEditorProvider()

    override fun getEditorTypeId(): String = "i18n-split-editor"

    override fun accept(project: Project, file: VirtualFile): Boolean =
        previewProvider.accept(project, file)

    override fun createEditor(project: Project, file: VirtualFile): FileEditor {
        val textEditor    = TextEditorProvider.getInstance().createEditor(project, file) as TextEditor
        val previewEditor = previewProvider.createEditor(project, file)
        return TextEditorWithPreview(
            textEditor,
            previewEditor,
            "I18n Split Editor",
            // Start in split mode so both panes are immediately visible
            TextEditorWithPreview.Layout.SHOW_EDITOR_AND_PREVIEW
        )
    }

    // HIDE_DEFAULT_EDITOR: replaces the normal Kotlin editor tab entirely.
    // The Code/Split/Design buttons inside the editor let the user switch back
    // to pure code view at any time — so nothing is lost.
    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}