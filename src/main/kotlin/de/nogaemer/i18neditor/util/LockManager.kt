package de.nogaemer.i18neditor.util

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ide.util.PropertiesComponent

object LockManager {

    private fun key(file: VirtualFile) = "i18n_editor_locked:${file.path}"

    private fun load(file: VirtualFile): MutableSet<String> {
        val raw = PropertiesComponent.getInstance().getValue(key(file), "")
        return raw.split(",").filter { it.isNotEmpty() }.toMutableSet()
    }

    private fun save(file: VirtualFile, set: Set<String>) {
        PropertiesComponent.getInstance().setValue(key(file), set.joinToString(","))
    }

    fun isLocked(file: VirtualFile, path: String): Boolean =
        load(file).contains(path)

    fun toggle(file: VirtualFile, path: String) {
        val set = load(file)
        if (!set.add(path)) set.remove(path)
        save(file, set)
    }

    fun lockedPaths(file: VirtualFile): Set<String> = load(file)
}