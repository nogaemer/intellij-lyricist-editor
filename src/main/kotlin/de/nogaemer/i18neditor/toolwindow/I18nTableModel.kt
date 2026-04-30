package de.nogaemer.i18neditor.toolwindow

import de.nogaemer.i18neditor.model.I18nGroup
import de.nogaemer.i18neditor.model.I18nKey
import de.nogaemer.i18neditor.model.I18nTable
import javax.swing.table.AbstractTableModel

sealed class I18nRow {
    abstract val depth: Int
    data class GroupHeader(val group: I18nGroup, override val depth: Int) : I18nRow()
    data class KeyRow(val key: I18nKey, val values: MutableMap<String, String?>, override val depth: Int) : I18nRow()
}

class I18nTableModel(private var table: I18nTable) : AbstractTableModel() {

    private val collapsed = mutableSetOf<String>()
    private var rows: List<I18nRow> = buildRows()
    private val locales get() = table.model.locales

    override fun getRowCount() = rows.size
    override fun getColumnCount() = 1 + locales.size
    override fun getColumnName(col: Int) = if (col == 0) "Key" else locales[col - 1].tag.uppercase()

    override fun isCellEditable(row: Int, col: Int): Boolean {
        val r = rows[row]
        // Allow editing for all key types except Map
        return r is I18nRow.KeyRow && col > 0 && !r.key.isMap
    }

    override fun getValueAt(row: Int, col: Int): Any? {
        return when (val r = rows[row]) {
            is I18nRow.GroupHeader -> if (col == 0) r else null
            is I18nRow.KeyRow      -> when (col) {
                0    -> r.key.name
                else -> r.values[locales[col - 1].tag] ?: ""
            }
        }
    }

    override fun setValueAt(value: Any?, row: Int, col: Int) {
        val r = rows[row] as? I18nRow.KeyRow ?: return
        if (col < 1) return
        r.values[locales[col - 1].tag] = value as? String
        fireTableCellUpdated(row, col)
    }

    fun getRow(index: Int): I18nRow = rows[index]
    fun getDepth(row: Int): Int = rows[row].depth
    fun getLocaleTag(col: Int): String? = locales.getOrNull(col - 1)?.tag

    fun refresh(newTable: I18nTable) {
        table = newTable
        rows  = buildRows()
        fireTableStructureChanged()
    }

    fun toggleCollapse(row: Int) {
        val r = rows[row] as? I18nRow.GroupHeader ?: return
        val key = r.group.fieldPath.joinToString(".")
        if (collapsed.contains(key)) collapsed.remove(key) else collapsed.add(key)
        rows = buildRows()
        fireTableDataChanged()
    }

    fun isCollapsed(row: Int): Boolean {
        val r = rows[row] as? I18nRow.GroupHeader ?: return false
        return collapsed.contains(r.group.fieldPath.joinToString("."))
    }

    fun rowForKey(fullPath: String): Int =
        rows.indexOfFirst { it is I18nRow.KeyRow && it.key.fullPath == fullPath }

    fun isGroupHeader(row: Int) = getRow(row) is I18nRow.GroupHeader

    // ── Build ─────────────────────────────────────────────────────────────────

    private fun buildRows(): List<I18nRow> {
        val result = mutableListOf<I18nRow>()
        val collapsedPaths = collapsed.toSet()

        for (group in table.model.groups) {
            val depth    = group.fieldPath.size
            val groupKey = group.fieldPath.joinToString(".")

            // Only check PARENT paths (drop own segment), not the group itself.
            // This prevents the group header from vanishing when it's collapsed.
            val ancestorCollapsed = (0 until group.fieldPath.size - 1).any { i ->
                collapsedPaths.contains(group.fieldPath.take(i + 1).joinToString("."))
            }
            if (ancestorCollapsed) continue

            // Always emit the group header
            result += I18nRow.GroupHeader(group, depth)

            // Only emit key rows if this group itself is not collapsed
            if (!collapsedPaths.contains(groupKey)) {
                for (key in group.keys) {
                    val values = table.model.locales.associate { locale ->
                        locale.tag to table.getValue(key, locale)
                    }.toMutableMap()
                    result += I18nRow.KeyRow(key, values, depth + 1)
                }
            }
        }
        return result
    }

}