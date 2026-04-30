package de.nogaemer.i18neditor.editor

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorLocation
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import de.nogaemer.i18neditor.toolwindow.I18nEditorPanel
import java.beans.PropertyChangeListener
import javax.swing.JComponent

class I18nFileEditor(project: Project, private val file: VirtualFile) : FileEditor {

    private val panel          = I18nEditorPanel(project, file)
    private val userDataHolder = UserDataHolderBase()

    override fun getComponent(): JComponent                  = panel
    override fun getPreferredFocusedComponent(): JComponent  = panel
    override fun getName(): String                           = "I18n Editor"   // fix: title case
    override fun getFile(): VirtualFile                      = file
    override fun isValid(): Boolean                          = file.isValid
    override fun isModified(): Boolean                       = false

    // setState is sufficient — getState is a default method in 2024.3 SDK, no override needed
    override fun setState(state: FileEditorState) {}

    override fun addPropertyChangeListener(listener: PropertyChangeListener) {}
    override fun removePropertyChangeListener(listener: PropertyChangeListener) {}
    override fun getCurrentLocation(): FileEditorLocation? = null

    // Remove `Any?` upper bound — plain <T> is correct
    override fun <T> getUserData(key: Key<T>): T?          = userDataHolder.getUserData(key)
    override fun <T> putUserData(key: Key<T>, value: T?)   = userDataHolder.putUserData(key, value)

    override fun dispose() {}
}