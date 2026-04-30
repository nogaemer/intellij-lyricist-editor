package de.nogaemer.i18neditor.editor

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

class I18nFileEditorProvider : FileEditorProvider, DumbAware {

    override fun getEditorTypeId(): String = "i18n-strings-editor"

    override fun accept(project: Project, file: VirtualFile): Boolean {
        if (file.extension != "kt") return false
        // Quick scan — avoids full PSI parse during project open
        return try {
            String(file.contentsToByteArray()).contains("@LyricistStrings")
        } catch (e: Exception) {
            false
        }
    }

    override fun createEditor(project: Project, file: VirtualFile): FileEditor =
        I18nFileEditor(project, file)

    // PLACE_AFTER_DEFAULT_EDITOR = appears as an alternative tab ("i18n Editor")
    // alongside the normal Kotlin editor — user can switch via the tab strip
    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.PLACE_AFTER_DEFAULT_EDITOR
}