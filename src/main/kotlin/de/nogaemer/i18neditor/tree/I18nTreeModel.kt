package de.nogaemer.i18neditor.tree

import de.nogaemer.i18neditor.model.I18nFileModel
import de.nogaemer.i18neditor.model.I18nGroup
import de.nogaemer.i18neditor.model.I18nKey
import javax.swing.tree.DefaultTreeModel

/**
 * Builds and owns the tree model for the left-pane JTree.
 *
 * Tree shape:
 *
 *   (hidden root)
 *   └── common        [Group — fieldPath = ["common"]]
 *       ├── appName   [Key]
 *       ├── start     [Key]
 *       └── back      [Key]
 *   └── gameLobbySettings   [Group — fieldPath = ["gameLobbySettings"]]
 *       ├── lobbySettingsTitle   [Key]
 *       └── gameRoundSettingsStrings   [Group — nested]
 *           ├── roundTime   [Key]
 *           └── roundsPerTeam [Key]
 *   ...
 *
 * Nested groups (depth > 1) are placed as children of their parent group node,
 * NOT directly under the hidden root.
 */
class I18nTreeModel(fileModel: I18nFileModel) : DefaultTreeModel(I18nTreeNode.Root()) {

    private val root get() = super.getRoot() as I18nTreeNode.Root

    init {
        buildTree(fileModel)
    }

    // ── Tree construction ─────────────────────────────────────────────────────

    private fun buildTree(fileModel: I18nFileModel) {
        // Index groups by their fieldPath for parent lookup
        val groupsByPath: Map<List<String>, I18nTreeNode.Group> = fileModel.groups
            .associate { group ->
                val node = I18nTreeNode.Group(group)
                group.fieldPath to node
            }

        // Attach each group to its parent (or to root if top-level)
        for (group in fileModel.groups) {
            val node = groupsByPath[group.fieldPath] ?: continue

            // Parent path = all segments except the last
            val parentPath = group.fieldPath.dropLast(1)
            val parentNode: I18nTreeNode = groupsByPath[parentPath] ?: root

            parentNode.add(node)

            // Attach key leaves to their group node
            for (key in group.keys) {
                node.add(I18nTreeNode.Key(key))
            }
        }
    }

    // ── Rebuild ───────────────────────────────────────────────────────────────

    /**
     * Full rebuild after an Add/Delete operation.
     * Preserves nothing — callers must restore selection separately.
     */
    fun rebuild(fileModel: I18nFileModel) {
        root.removeAllChildren()
        buildTree(fileModel)
        reload()
    }

    // ── Lookup helpers ────────────────────────────────────────────────────────

    /** Returns the [I18nTreeNode.Key] matching the given full path, or null. */
    fun findKeyNode(fullPath: String): I18nTreeNode.Key? {
        return findInNode(root) { node ->
            node is I18nTreeNode.Key && node.key.fullPath == fullPath
        } as? I18nTreeNode.Key
    }

    /** Returns the [I18nTreeNode.Group] matching the given class name, or null. */
    fun findGroupNode(className: String): I18nTreeNode.Group? {
        return findInNode(root) { node ->
            node is I18nTreeNode.Group && node.group.className == className
        } as? I18nTreeNode.Group
    }

    private fun findInNode(
        node: I18nTreeNode,
        predicate: (I18nTreeNode) -> Boolean
    ): I18nTreeNode? {
        if (predicate(node)) return node
        for (i in 0 until node.childCount) {
            val child = node.getChildAt(i) as? I18nTreeNode ?: continue
            val result = findInNode(child, predicate)
            if (result != null) return result
        }
        return null
    }

    // ── Mutation helpers (used by Add/Delete) ─────────────────────────────────

    /**
     * Inserts a new [I18nTreeNode.Key] as the last child of the given group node
     * and fires the appropriate tree model event.
     */
    fun insertKey(groupNode: I18nTreeNode.Group, key: I18nKey) {
        val keyNode = I18nTreeNode.Key(key)
        val index   = groupNode.childCount
        groupNode.add(keyNode)
        nodesWereInserted(groupNode, intArrayOf(index))
    }

    /**
     * Removes a key node from the tree and fires the appropriate tree model event.
     */
    fun removeKey(keyNode: I18nTreeNode.Key) {
        val parent = keyNode.parent as? I18nTreeNode.Group ?: return
        val index  = parent.getIndex(keyNode)
        parent.remove(keyNode)
        nodesWereRemoved(parent, intArrayOf(index), arrayOf(keyNode))
    }

    /**
     * Replaces the key in an existing leaf node (used after a rename).
     * Fires a node-changed event so the tree label updates.
     */
    fun updateKey(keyNode: I18nTreeNode.Key, newKey: I18nKey) {
        keyNode.key = newKey  // requires `key` to be var — see note below
        nodeChanged(keyNode)
    }
}