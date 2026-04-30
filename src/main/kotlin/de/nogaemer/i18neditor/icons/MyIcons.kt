package de.nogaemer.i18neditor.icons

import com.intellij.openapi.util.IconLoader

object MyIcons {
    @JvmField
    val Locked = IconLoader.getIcon("/icons/locked.svg", MyIcons::class.java)
    @JvmField
    val Unlocked = IconLoader.getIcon("/icons/unlocked.svg", MyIcons::class.java)

}