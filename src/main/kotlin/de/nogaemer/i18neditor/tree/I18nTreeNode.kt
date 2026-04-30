package de.nogaemer.i18neditor.tree

import de.nogaemer.i18neditor.model.I18nGroup
import de.nogaemer.i18neditor.model.I18nKey
import javax.swing.tree.DefaultMutableTreeNode

/**
 * Sealed hierarchy so the UI can pattern-match on node types safely,
 * instead of casting Object userObject at every call site.
 */
sealed class I18nTreeNode(userObject: Any) : DefaultMutableTreeNode(userObject) {

    /**
     * Root node — invisible in the tree (JTree uses it as the hidden root).
     */
    class Root : I18nTreeNode("Strings") {
        override fun toString() = "Strings"
    }

    /**
     * One data class group, e.g. CommonStrings, GameLobbyStrings.
     * Shown as a folder row in the tree.
     *
     * [group.fieldPath] drives navigation (e.g. ["gameLobbySettings", "gameRoundSettingsStrings"]).
     * [displayName] is the last segment of the path, or the class name for root groups.
     */
    class Group(val group: I18nGroup) : I18nTreeNode(group) {
        val displayName: String
            get() = group.fieldPath.lastOrNull() ?: group.className

        override fun toString() = displayName

        override fun isLeaf() = false
    }

    /**
     * One string key leaf, e.g. "appName", "backToHome".
     * Carries the full [I18nKey] so the edit form has all info it needs.
     */
    class Key(var key: I18nKey) : I18nTreeNode(key) {
        override fun toString() = key.name

        override fun isLeaf() = true
    }
}