package de.nogaemer.i18neditor.toolwindow

import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.ActionCallback
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiUtilBase
import com.intellij.refactoring.rename.RenameDialog
import com.intellij.ui.Gray
import com.intellij.ui.JBColor
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.popup.PopupFactoryImpl
import com.intellij.ui.table.JBTable
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.messages.MessageBusConnection
import com.intellij.util.ui.JBUI
import de.nogaemer.i18neditor.dialog.AddGroupDialog
import de.nogaemer.i18neditor.dialog.AddKeyDialog
import de.nogaemer.i18neditor.dialog.AddLocaleDialog
import de.nogaemer.i18neditor.dialog.EditLambdaParamsDialog
import de.nogaemer.i18neditor.model.I18nKey
import de.nogaemer.i18neditor.parser.LyricistFileParser
import de.nogaemer.i18neditor.writer.*
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtParameter
import java.awt.*
import java.awt.event.*
import javax.swing.*
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableCellEditor

class I18nEditorPanel(
    private val project: Project,
    val virtualFile: VirtualFile
) : JPanel(BorderLayout()) {

    private val parser        = LyricistFileParser(project)
    private val writer        = LyricistStringWriter(project)
    private val adder         = LyricistKeyAdder(project)
    private val deleter       = LyricistKeyDeleter(project)
    private val groupAdder    = LyricistGroupAdder(project)
    private val localeAdder   = LyricistLocaleAdder(project)
    private val localeDeleter = LyricistLocaleDeleter(project)
    private val converter     = LyricistKeyConverter(project)

    private var currentTable = parser.parse(virtualFile)
    private lateinit var tableModel: I18nTableModel
    private lateinit var jbTable: JBTable
    private var busConnection: MessageBusConnection? = null

    init {
        if (currentTable != null) buildUI()
        else add(JLabel("No @LyricistStrings found.", SwingConstants.CENTER), BorderLayout.CENTER)
        setupAutoRefresh()
    }

    // ── Auto-refresh ──────────────────────────────────────────────────────────

    private fun setupAutoRefresh() {
        busConnection = project.messageBus.connect()
        busConnection!!.subscribe(com.intellij.openapi.vfs.VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    if (events.any { it is VFileContentChangeEvent && it.file == virtualFile }) {
                        ApplicationManager.getApplication().invokeLater { refresh() }
                    }
                }
            })
    }

    fun dispose() { busConnection?.disconnect() }

    // ── Build ─────────────────────────────────────────────────────────────────

    private fun buildUI() {
        val t = currentTable ?: return
        tableModel = I18nTableModel(t)

        jbTable = JBTable(tableModel).apply {
            setDefaultRenderer(Any::class.java, I18nCellRenderer())
            setDefaultEditor(Any::class.java, I18nCellEditor())
            tableHeader.reorderingAllowed = false
            rowHeight                     = 28
            intercellSpacing              = Dimension(0, 1)
            fillsViewportHeight           = true
            columnModel.getColumn(0).preferredWidth = 240
            columnModel.getColumn(0).minWidth       = 140
        }

        // ── Keyboard: Shift+F6 → rename ───────────────────────────────────────
        val shiftF6 = KeyStroke.getKeyStroke(KeyEvent.VK_F6, InputEvent.SHIFT_DOWN_MASK)
        jbTable.getInputMap(JComponent.WHEN_FOCUSED).put(shiftF6, "renameKey")
        jbTable.actionMap.put("renameKey", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) = onRenameKey()
        })

        // ── Mouse: row clicks + right-click popup ─────────────────────────────
        jbTable.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val row = jbTable.rowAtPoint(e.point)
                if (row < 0) return
                when (val r = tableModel.getRow(row)) {
                    is I18nRow.GroupHeader ->
                        if (e.clickCount == 1 && !SwingUtilities.isRightMouseButton(e))
                            tableModel.toggleCollapse(row)
                    is I18nRow.KeyRow ->
                        if (e.clickCount == 2 && SwingUtilities.isLeftMouseButton(e))
                            navigateToKey(r.key)
                }
            }

            override fun mousePressed(e: MouseEvent)  = maybeShowRowPopup(e)
            override fun mouseReleased(e: MouseEvent) = maybeShowRowPopup(e)

            private fun maybeShowRowPopup(e: MouseEvent) {
                if (!e.isPopupTrigger) return
                val row = jbTable.rowAtPoint(e.point)
                if (row < 0) return
                val r = tableModel.getRow(row) as? I18nRow.KeyRow ?: return
                jbTable.selectionModel.setSelectionInterval(row, row)
                showKeyPopup(e, r.key)
            }
        })

        // ── Mouse: right-click column header → delete language ────────────────
        jbTable.tableHeader.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent)  = maybeShowColMenu(e)
            override fun mouseReleased(e: MouseEvent) = maybeShowColMenu(e)

            private fun maybeShowColMenu(e: MouseEvent) {
                if (!e.isPopupTrigger) return
                val col = jbTable.columnAtPoint(e.point)
                if (col <= 0) return
                val tag = tableModel.getLocaleTag(col) ?: return
                showLocalePopup(e, tag)
            }
        })

        val decorated = ToolbarDecorator.createDecorator(jbTable)
            .setAddAction    { onAddKey() }
            .setRemoveAction { onDeleteKey() }
            .addExtraAction(object : AnAction("Add Group", "Add a new string group",
                com.intellij.icons.AllIcons.Actions.NewFolder) {
                override fun actionPerformed(e: AnActionEvent) = onAddGroup()
            })
            .addExtraAction(object : AnAction("Add Language", "Add a new locale",
                com.intellij.icons.AllIcons.Actions.AddList) {
                override fun actionPerformed(e: AnActionEvent) = onAddLocale()
            })
            .addExtraAction(object : AnAction("Rename Key", "Rename selected key (Shift+F6)",
                com.intellij.icons.AllIcons.Actions.Edit) {
                override fun actionPerformed(e: AnActionEvent) = onRenameKey()
            })
            .addExtraAction(object : AnAction("Refresh", "Re-parse strings file",
                com.intellij.icons.AllIcons.Actions.Refresh) {
                override fun actionPerformed(e: AnActionEvent) = refresh()
            })
            .createPanel()

        add(decorated, BorderLayout.CENTER)
    }

    // ── Modern popups (JBPopupFactory → rounded IntelliJ style) ──────────────

    private fun showKeyPopup(e: MouseEvent, key: I18nKey) {
        val group = DefaultActionGroup()

        group.add(object : AnAction("Rename Key…  (Shift+F6)", null,
            com.intellij.icons.AllIcons.Actions.Edit) {
            override fun actionPerformed(ev: AnActionEvent) = onRenameKey()
        })
        group.addSeparator()

        if (!key.isLambda) {
            group.add(object : AnAction("Convert to Lambda…", null,
                com.intellij.icons.AllIcons.Nodes.Lambda) {
                override fun actionPerformed(ev: AnActionEvent) = showLambdaParamDialog(key)
            })
        } else {
            group.add(object : AnAction("Edit Lambda Params…", null,
                com.intellij.icons.AllIcons.Nodes.Lambda) {
                override fun actionPerformed(ev: AnActionEvent) = showLambdaParamDialog(key)
            })
            group.add(object : AnAction("Convert to Plain String", null,
                com.intellij.icons.AllIcons.Nodes.Field) {
                override fun actionPerformed(ev: AnActionEvent) {
                    converter.convertToString(virtualFile, key); refresh()
                }
            })
        }

        group.addSeparator()
        group.add(object : AnAction("Delete Key", null,
            com.intellij.icons.AllIcons.Actions.GC) {
            override fun actionPerformed(ev: AnActionEvent) = onDeleteKey()
        })

        com.intellij.openapi.ui.popup.JBPopupFactory.getInstance()
            .createActionGroupPopup(
                null, group,
                DataManager.getInstance().getDataContext(jbTable),
                com.intellij.openapi.ui.popup.JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
                false
            )
            .show(RelativePoint(e))
    }

    private fun showLocalePopup(e: MouseEvent, localeTag: String) {
        val group = DefaultActionGroup()
        group.add(object : AnAction("Delete Language '$localeTag'", null,
            com.intellij.icons.AllIcons.Actions.GC) {
            override fun actionPerformed(ev: AnActionEvent) {
                val ok = Messages.showYesNoDialog(
                    project,
                    "Remove all translations for locale '$localeTag'?\nThis cannot be undone.",
                    "Delete Language",
                    Messages.getWarningIcon()
                )
                if (ok == Messages.YES) {
                    localeDeleter.deleteLocale(virtualFile, localeTag)
                    refresh()
                }
            }
        })
        com.intellij.openapi.ui.popup.JBPopupFactory.getInstance()
            .createActionGroupPopup(
                null, group,
                DataManager.getInstance().getDataContext(jbTable.tableHeader),
                com.intellij.openapi.ui.popup.JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
                false
            )
            .show(RelativePoint(e))
    }

    // ── Navigation ────────────────────────────────────────────────────────────

    private fun navigateToKey(key: I18nKey) {
        val ktFile = PsiManager.getInstance(project).findFile(virtualFile) as? KtFile ?: return
        val param  = ktFile.declarations
            .filterIsInstance<KtClass>()
            .firstOrNull { it.name == key.groupClass }
            ?.primaryConstructor?.valueParameters
            ?.firstOrNull { it.name == key.name } ?: return
        OpenFileDescriptor(project, virtualFile, param.textOffset).navigate(true)
    }

    // ── Actions ───────────────────────────────────────────────────────────────

    private fun onAddKey() {
        val t = currentTable ?: return
        val dialog = AddKeyDialog(t.model)
        if (!dialog.showAndGet()) return
        adder.addKey(
            virtualFile       = virtualFile,
            group             = dialog.selectedGroup,
            keyName           = dialog.keyName,
            valuesByLocaleTag = dialog.valuesByLocale,
            isLambda          = dialog.isLambda,
            lambdaParams      = dialog.lambdaParams
        )
        refresh()
        val fullPath = (dialog.selectedGroup.fieldPath + dialog.keyName).joinToString(".")
        val row = tableModel.rowForKey(fullPath)
        if (row >= 0) {
            jbTable.selectionModel.setSelectionInterval(row, row)
            jbTable.scrollRectToVisible(jbTable.getCellRect(row, 0, true))
        }
    }

    private fun onAddGroup() {
        val t = currentTable ?: return
        val dialog = AddGroupDialog(t.model)
        if (!dialog.showAndGet()) return
        groupAdder.addGroup(
            virtualFile = virtualFile,
            className   = dialog.className,
            fieldName   = dialog.fieldName,
            parentPath  = dialog.parentPath
        )
        refresh()
    }

    private fun onAddLocale() {
        val t = currentTable ?: return
        val dialog = AddLocaleDialog(t.model)
        if (!dialog.showAndGet()) return
        localeAdder.addLocale(
            virtualFile = virtualFile,
            localeTag   = dialog.localeTag,
            valName     = dialog.valName
        )
        refresh()
    }

    private fun onDeleteKey() {
        val row = jbTable.selectedRow
        if (row < 0) return
        val r = tableModel.getRow(row) as? I18nRow.KeyRow ?: return
        deleter.deleteKey(virtualFile, r.key)
        refresh()
    }

    /**
     * Resolves the KtParameter on a background read action, then opens
     * RenameDialog on EDT — avoids SlowOperations crash on 2024.3.
     */
    private fun onRenameKey() {
        val row = jbTable.selectedRow
        if (row < 0) return
        val r = tableModel.getRow(row) as? I18nRow.KeyRow ?: return

        // Resolve PSI element on BGT, then trigger the platform RenameElement
        // action via ActionManager — avoids constructing RenameDialog on EDT
        // which internally calls slow workspace-index operations in IDEA 2024.3.
        com.intellij.openapi.application.ReadAction
            .nonBlocking<com.intellij.psi.PsiElement?> {
                val ktFile = PsiManager.getInstance(project)
                    .findFile(virtualFile) as? KtFile ?: return@nonBlocking null
                ktFile.declarations
                    .filterIsInstance<KtClass>()
                    .firstOrNull { it.name == r.key.groupClass }
                    ?.primaryConstructor?.valueParameters
                    ?.firstOrNull { it.name == r.key.name }
            }
            .finishOnUiThread(ModalityState.defaultModalityState()) { param ->
                if (param == null) return@finishOnUiThread
                val dataContext = com.intellij.openapi.actionSystem.impl.SimpleDataContext.builder()
                    .add(com.intellij.openapi.actionSystem.CommonDataKeys.PROJECT, project)
                    .add(com.intellij.openapi.actionSystem.CommonDataKeys.PSI_ELEMENT, param)
                    .build()
                val action = com.intellij.openapi.actionSystem.ActionManager.getInstance()
                    .getAction("RenameElement")
                com.intellij.openapi.actionSystem.ex.ActionUtil.invokeAction(
                    action, dataContext,
                    com.intellij.openapi.actionSystem.ActionPlaces.UNKNOWN,
                    null, null
                )
                // refresh after rename dialog closes
                SwingUtilities.invokeLater { refresh() }
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun showLambdaParamDialog(key: I18nKey) {
        val dialog = EditLambdaParamsDialog(key)
        if (!dialog.showAndGet()) return
        converter.convertToLambda(virtualFile, key, dialog.lambdaParams)
        refresh()
    }

    // ── Public ────────────────────────────────────────────────────────────────

    fun refresh() {
        currentTable = parser.parse(virtualFile) ?: return
        tableModel.refresh(currentTable!!)
    }

    fun getSelectedKey(): I18nKey? {
        if (!::jbTable.isInitialized) return null
        val row = jbTable.selectedRow
        if (row < 0) return null
        return (tableModel.getRow(row) as? I18nRow.KeyRow)?.key
    }

    fun getTable(): JBTable? = if (::jbTable.isInitialized) jbTable else null

    // ── Cell Renderer ─────────────────────────────────────────────────────────

    inner class I18nCellRenderer : DefaultTableCellRenderer() {
        private val groupBg  = JBColor(Color(230, 235, 245), Color(60, 63, 65))
        private val lambdaBg = JBColor(Color(255, 252, 235), Color(70, 67, 50))
        private val headerFg = JBColor(Gray._50, Gray._210)
        private val INDENT   = 16

        override fun getTableCellRendererComponent(
            table: JTable, value: Any?, isSelected: Boolean,
            hasFocus: Boolean, row: Int, column: Int
        ): Component {
            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
            val depth   = tableModel.getDepth(row)
            val leftPad = 6 + depth * INDENT

            when (val r = tableModel.getRow(row)) {
                is I18nRow.GroupHeader -> {
                    val arrow = if (tableModel.isCollapsed(row)) "▶" else "▼"
                    text       = if (column == 0)
                        "$arrow  ${r.group.className}  ·  ${r.group.fieldPath.joinToString(".")}"
                    else ""
                    font       = font.deriveFont(Font.BOLD)
                    background = if (isSelected) table.selectionBackground else groupBg
                    foreground = if (isSelected) table.selectionForeground else headerFg
                    border     = JBUI.Borders.empty(0, leftPad, 0, 6)
                }
                is I18nRow.KeyRow -> {
                    background = when {
                        isSelected                    -> table.selectionBackground
                        r.key.isLambda || r.key.isMap -> lambdaBg
                        else                          -> table.background
                    }
                    foreground = when {
                        isSelected     -> table.selectionForeground
                        r.key.isLambda -> JBColor(Color(120, 80, 180), Color(170, 130, 210))
                        r.key.isMap    -> JBColor(Color(150, 100, 0),  Color(180, 150, 80))
                        else           -> table.foreground
                    }
                    text = when {
                        column == 0 -> r.key.name
                        r.key.isMap -> "[map — not editable]"
                        else        -> value?.toString() ?: ""
                    }
                    toolTipText = when {
                        r.key.isLambda -> "λ  params: (${r.key.lambdaParams.joinToString(", ")})"
                        r.key.isMap    -> "Map — edit directly in source"
                        else           -> null
                    }
                    border = if (column == 0)
                        JBUI.Borders.empty(0, leftPad, 0, 6)
                    else
                        JBUI.Borders.empty(0, 6)
                }
            }
            return this
        }
    }

    // ── Cell Editor ───────────────────────────────────────────────────────────

    inner class I18nCellEditor : AbstractCellEditor(), TableCellEditor {
        private val field = JTextField()
        private var editingRow = -1
        private var editingCol = -1

        override fun getCellEditorValue(): Any = field.text

        override fun stopCellEditing(): Boolean {
            val row = editingRow; val col = editingCol
            if (row >= 0 && col > 0) {
                val r   = tableModel.getRow(row) as? I18nRow.KeyRow
                val tag = tableModel.getLocaleTag(col)
                val t   = currentTable
                if (r != null && tag != null && t != null && !r.key.isMap) {
                    val locale = t.model.locales.firstOrNull { it.tag == tag }
                    if (locale != null) {
                        writer.write(virtualFile, locale, r.key, field.text)
                        currentTable = parser.parse(virtualFile)
                        tableModel.setValueAt(field.text, row, col)
                    }
                }
            }
            return super.stopCellEditing()
        }

        override fun getTableCellEditorComponent(
            table: JTable, value: Any?, isSelected: Boolean, row: Int, col: Int
        ): Component {
            editingRow = row; editingCol = col
            field.text   = value?.toString() ?: ""
            field.border = JBUI.Borders.empty(0, 6)
            return field
        }
    }
}