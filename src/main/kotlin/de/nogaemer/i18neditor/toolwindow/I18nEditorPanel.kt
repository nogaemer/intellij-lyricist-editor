package de.nogaemer.i18neditor.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.psi.PsiManager
import com.intellij.ui.Gray
import com.intellij.ui.JBColor
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.table.JBTable
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.messages.MessageBusConnection
import com.intellij.util.ui.JBUI
import de.nogaemer.i18neditor.dialog.AddGroupDialog
import de.nogaemer.i18neditor.dialog.AddKeyDialog
import de.nogaemer.i18neditor.dialog.AddLocaleDialog
import de.nogaemer.i18neditor.dialog.EditLambdaParamsDialog
import de.nogaemer.i18neditor.icons.MyIcons
import de.nogaemer.i18neditor.model.I18nGroup
import de.nogaemer.i18neditor.model.I18nKey
import de.nogaemer.i18neditor.parser.LyricistFileParser
import de.nogaemer.i18neditor.util.LockManager
import de.nogaemer.i18neditor.writer.*
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtFile
import java.awt.*
import java.awt.event.*
import javax.swing.*
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableCellEditor

class I18nEditorPanel(
    private val project: Project,
    val virtualFile: VirtualFile
) : JPanel(BorderLayout()) {

    private val parser = LyricistFileParser(project)
    private val writer = LyricistStringWriter(project)
    private val adder = LyricistKeyAdder(project)
    private val deleter = LyricistKeyDeleter(project)
    private val groupAdder = LyricistGroupAdder(project)
    private val localeAdder = LyricistLocaleAdder(project)
    private val localeDeleter = LyricistLocaleDeleter(project)
    private val converter = LyricistKeyConverter(project)

    private val addIcon = AllIcons.General.Add
    private var groupAddButtonClicked = false

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
        busConnection!!.subscribe(
            com.intellij.openapi.vfs.VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    if (events.any { it is VFileContentChangeEvent && it.file == virtualFile }) {
                        ApplicationManager.getApplication().invokeLater { refresh() }
                    }
                }
            })
    }

    fun dispose() {
        busConnection?.disconnect()
    }

    // ── Locking ────────────────────────────────────────────────────────────────

    private fun onToggleLock() {
        val row = jbTable.selectedRow
        if (row < 0) return
        val path = when (val r = tableModel.getRow(row)) {
            is I18nRow.KeyRow     -> r.key.fullPath
            is I18nRow.GroupHeader -> r.group.fieldPath.joinToString(".")
        }
        LockManager.toggle(virtualFile, path)
        jbTable.repaint()
    }


    // ── Build ─────────────────────────────────────────────────────────────────

    private fun buildUI() {
        val t = currentTable ?: return
        tableModel = I18nTableModel(t)

        jbTable = object : JBTable(tableModel) {
            override fun isCellEditable(row: Int, column: Int): Boolean {
                if (column == 0) return false
                if (tableModel.isGroupHeader(row)) return false
                if (tableModel.isLocked(row, virtualFile)) return false
                return super.isCellEditable(row, column)
            }


            override fun paint(g: Graphics) {
                super.paint(g)
                val g2 = g as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val pane = javax.swing.CellRendererPane()
                for (row in 0 until rowCount) {
                    if (!tableModel.isGroupHeader(row)) continue
                    val rect = super.getCellRect(row, 0, true)
                    val renderer = prepareRenderer(getDefaultRenderer(Any::class.java), row, 0)
                    pane.paintComponent(g2, renderer, this, 0, rect.y, width, rect.height, true)

                    val btnX = width - addIcon.iconWidth - 8
                    val btnY = rect.y + (rect.height - addIcon.iconHeight) / 2
                    addIcon.paintIcon(this, g2, btnX, btnY)
                }
            }

        }.apply {
            setDefaultRenderer(Any::class.java, I18nCellRenderer())
            setDefaultEditor(Any::class.java, I18nCellEditor())
            tableHeader.reorderingAllowed = false
            rowHeight = 28
            intercellSpacing = Dimension(0, 1)
            fillsViewportHeight = true
            columnModel.getColumn(0).preferredWidth = 240
            columnModel.getColumn(0).minWidth = 140
        }

        // ── Keyboard: Shift+F6 → rename ───────────────────────────────────────
        val shiftF6 = KeyStroke.getKeyStroke(KeyEvent.VK_F6, InputEvent.SHIFT_DOWN_MASK)
        jbTable.getInputMap(JComponent.WHEN_FOCUSED).put(shiftF6, "renameKey")
        jbTable.actionMap.put("renameKey", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) = onRenameKey()
        })

        // ── Keyboard: Enter → confirm edit, Escape → cancel edit ──────────────
        val enterKey = KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0)
        val escapeKey = KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0)
        jbTable.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(enterKey, "confirmEdit")
        jbTable.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(escapeKey, "cancelEdit")
        jbTable.actionMap.put("confirmEdit", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) {
                if (jbTable.isEditing) jbTable.cellEditor?.stopCellEditing()
            }
        })
        jbTable.actionMap.put("cancelEdit", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) {
                if (jbTable.isEditing) jbTable.cellEditor?.cancelCellEditing()
            }
        })


        // ── Mouse: row clicks + right-click popup ─────────────────────────────
        jbTable.addMouseListener(object : MouseAdapter() {

            // Navigate locale value on mousePressed (before editor starts)
            override fun mousePressed(e: MouseEvent) {
                // "+" button hit detection on group header rows
                if (SwingUtilities.isLeftMouseButton(e)) {
                    val row = jbTable.rowAtPoint(e.point)
                    if (row >= 0 && tableModel.isGroupHeader(row)) {
                        val btnX = jbTable.width - addIcon.iconWidth - 8
                        if (e.x in btnX..(btnX + addIcon.iconWidth)) {
                            val r = tableModel.getRow(row) as I18nRow.GroupHeader
                            groupAddButtonClicked = true
                            showGroupAddPopup(e, r)
                            e.consume()
                            return
                        }
                    }
                }

                maybeShowRowPopup(e)
                if (e.isPopupTrigger || !SwingUtilities.isLeftMouseButton(e)) return
                val row = jbTable.rowAtPoint(e.point)
                if (row < 0) return
                val r = tableModel.getRow(row) as? I18nRow.KeyRow ?: return
                val col = jbTable.columnAtPoint(e.point)
                if (col > 0 && e.clickCount == 1) {
                    val tag = tableModel.getLocaleTag(col)
                    if (tag != null) navigateToLocaleValue(r.key, tag)
                }
            }

            override fun mouseReleased(e: MouseEvent) = maybeShowRowPopup(e)

            override fun mouseClicked(e: MouseEvent) {
                if (groupAddButtonClicked) {
                    groupAddButtonClicked = false; return
                }
                val row = jbTable.rowAtPoint(e.point)
                if (row < 0) return
                when (val r = tableModel.getRow(row)) {
                    is I18nRow.GroupHeader ->
                        if (e.clickCount == 1 && !SwingUtilities.isRightMouseButton(e))
                            tableModel.toggleCollapse(row)

                    is I18nRow.KeyRow ->
                        if (e.clickCount == 2 && SwingUtilities.isLeftMouseButton(e)
                            && jbTable.columnAtPoint(e.point) == 0
                        )
                            navigateToKey(r.key)
                }
            }

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
            override fun mousePressed(e: MouseEvent) = maybeShowColMenu(e)
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
            .setAddAction { onAddKey() }
            .setRemoveAction { onDeleteKey() }
            .addExtraAction(object : AnAction(
                "Add Group", "Add a new string group",
                AllIcons.Actions.NewFolder
            ) {
                override fun actionPerformed(e: AnActionEvent) = onAddGroup()
            })
            .addExtraAction(object : AnAction(
                "Add Language", "Add a new locale",
                AllIcons.Actions.AddList
            ) {
                override fun actionPerformed(e: AnActionEvent) = onAddLocale()
            })
            .addExtraAction(object : AnAction(
                "Rename Key", "Rename selected key (Shift+F6)",
                AllIcons.Actions.Edit
            ) {
                override fun actionPerformed(e: AnActionEvent) = onRenameKey()
            })
            .addExtraAction(object : AnAction(
                "Lock / Unlock", "Lock selected key or group from editing",
                MyIcons.Locked
            ) {
                override fun actionPerformed(e: AnActionEvent) = onToggleLock()
                override fun update(e: AnActionEvent) {
                    val row = if (::jbTable.isInitialized) jbTable.selectedRow else -1
                    if (row < 0) { e.presentation.isEnabled = false; return }
                    e.presentation.isEnabled = true
                    val locked = tableModel.isLocked(row, virtualFile)
                    e.presentation.icon = if (locked) MyIcons.Locked else MyIcons.Unlocked
                    e.presentation.text = if (locked) "Unlock" else "Lock"
                }
            })
            .addExtraAction(object : AnAction(
                "Refresh", "Re-parse strings file",
                AllIcons.Actions.Refresh
            ) {
                override fun actionPerformed(e: AnActionEvent) = refresh()
            })
            .createPanel()

        add(decorated, BorderLayout.CENTER)
    }



    // ── Popups ────────────────────────────────────────────────────────────────

    private fun showGroupAddPopup(e: MouseEvent, header: I18nRow.GroupHeader) {
        val group = DefaultActionGroup()
        group.add(object : AnAction(
            "Add Key in ${header.group.className}…", null,
            AllIcons.Actions.AddFile
        ) {
            override fun actionPerformed(ev: AnActionEvent) = onAddKey(header.group)
        })
        group.add(object : AnAction(
            "Add Subgroup in ${header.group.className}…", null,
            AllIcons.Actions.NewFolder
        ) {
            override fun actionPerformed(ev: AnActionEvent) = onAddGroup(header.group)
        })
        JBPopupFactory.getInstance()
            .createActionGroupPopup(
                null, group,
                DataManager.getInstance().getDataContext(jbTable),
                JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
                false
            )
            .show(RelativePoint(e))
    }

    private fun showKeyPopup(e: MouseEvent, key: I18nKey) {
        val group = DefaultActionGroup()

        group.add(object : AnAction(
            "Rename Key…  (Shift+F6)", null,
            AllIcons.Actions.Edit
        ) {
            override fun actionPerformed(ev: AnActionEvent) = onRenameKey()
        })
        group.addSeparator()

        if (!key.isLambda) {
            group.add(object : AnAction(
                "Convert to Lambda…", null,
                AllIcons.Nodes.Lambda
            ) {
                override fun actionPerformed(ev: AnActionEvent) = showLambdaParamDialog(key)
            })
        } else {
            group.add(object : AnAction(
                "Edit Lambda Params…", null,
                AllIcons.Nodes.Lambda
            ) {
                override fun actionPerformed(ev: AnActionEvent) = showLambdaParamDialog(key)
            })
            group.add(object : AnAction(
                "Convert to Plain String", null,
                AllIcons.Nodes.Field
            ) {
                override fun actionPerformed(ev: AnActionEvent) {
                    converter.convertToString(virtualFile, key); refresh()
                }
            })
        }

        group.addSeparator()
        group.add(object : AnAction(
            "Delete Key", null,
            AllIcons.Actions.GC
        ) {
            override fun actionPerformed(ev: AnActionEvent) = onDeleteKey()
        })

        JBPopupFactory.getInstance()
            .createActionGroupPopup(
                null, group,
                DataManager.getInstance().getDataContext(jbTable),
                JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
                false
            )
            .show(RelativePoint(e))
    }

    private fun showLocalePopup(e: MouseEvent, localeTag: String) {
        val group = DefaultActionGroup()
        group.add(object : AnAction(
            "Delete Language '$localeTag'", null,
            AllIcons.Actions.GC
        ) {
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
        JBPopupFactory.getInstance()
            .createActionGroupPopup(
                null, group,
                DataManager.getInstance().getDataContext(jbTable.tableHeader),
                JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
                false
            )
            .show(RelativePoint(e))
    }

    // ── Navigation ────────────────────────────────────────────────────────────

    private fun navigateToKey(key: I18nKey) {
        val ktFile = PsiManager.getInstance(project).findFile(virtualFile) as? KtFile ?: return
        val param = ktFile.declarations
            .filterIsInstance<KtClass>()
            .firstOrNull { it.name == key.groupClass }
            ?.primaryConstructor?.valueParameters
            ?.firstOrNull { it.name == key.name } ?: return
        OpenFileDescriptor(project, virtualFile, param.textOffset).navigate(true)
    }

    private fun navigateToLocaleValue(key: I18nKey, localeTag: String) {
        com.intellij.psi.PsiDocumentManager.getInstance(project).commitAllDocuments()

        val ktFile = PsiManager.getInstance(project).findFile(virtualFile) as? KtFile ?: return

        val localeVal = ktFile.declarations
            .filterIsInstance<org.jetbrains.kotlin.psi.KtProperty>()
            .firstOrNull { prop ->
                prop.annotationEntries.any { ann ->
                    ann.shortName?.asString() == "LyricistStrings" &&
                            ann.valueArguments.any { arg ->
                                arg.getArgumentExpression()?.text?.trim('"') == localeTag
                            }
                }
            } ?: return

        val rootCall = localeVal.initializer as? KtCallExpression ?: return

        fun findGroupCall(call: KtCallExpression): KtCallExpression? {
            if (call.calleeExpression?.text == key.groupClass) return call
            for (arg in call.valueArguments) {
                val nested = arg.getArgumentExpression() as? KtCallExpression ?: continue
                val found = findGroupCall(nested)
                if (found != null) return found
            }
            return null
        }

        val groupCall = findGroupCall(rootCall) ?: return

        // Named match first, positional fallback
        val valueArg = groupCall.valueArguments
            .firstOrNull { it.getArgumentName()?.asName?.identifier == key.name }
            ?: run {
                val idx = ktFile.declarations
                    .filterIsInstance<org.jetbrains.kotlin.psi.KtClass>()
                    .firstOrNull { it.name == key.groupClass }
                    ?.primaryConstructor?.valueParameters
                    ?.indexOfFirst { it.name == key.name }
                    ?.takeIf { it >= 0 } ?: return
                groupCall.valueArguments.getOrNull(idx)
            } ?: return

        val offset = valueArg.getArgumentExpression()?.textOffset ?: return
        OpenFileDescriptor(project, virtualFile, offset).navigate(true)
    }

    // ── Actions ───────────────────────────────────────────────────────────────

    private fun onAddKey(preselected: I18nGroup? = null) {
        val t = currentTable ?: return
        val dialog = AddKeyDialog(t.model, preselected)
        if (!dialog.showAndGet()) return
        adder.addKey(
            virtualFile = virtualFile,
            group = dialog.selectedGroup,
            keyName = dialog.keyName,
            valuesByLocaleTag = dialog.valuesByLocale,
            isLambda = dialog.isLambda,
            lambdaParams = dialog.lambdaParams,
            lambdaReturnType = dialog.lambdaReturnType
        )
        refresh()
        val fullPath = (dialog.selectedGroup.fieldPath + dialog.keyName).joinToString(".")
        val row = tableModel.rowForKey(fullPath)
        if (row >= 0) {
            jbTable.selectionModel.setSelectionInterval(row, row)
            jbTable.scrollRectToVisible(jbTable.getCellRect(row, 0, true))
        }
    }

    private fun onAddGroup(preselected: I18nGroup? = null) {
        val t = currentTable ?: return
        val dialog = AddGroupDialog(t.model, preselected)
        if (!dialog.showAndGet()) return
        groupAdder.addGroup(
            virtualFile = virtualFile,
            className = dialog.className,
            fieldName = dialog.fieldName,
            parentPath = dialog.parentPath
        )
        refresh()
    }

    private fun onAddLocale() {
        val t = currentTable ?: return
        val dialog = AddLocaleDialog(t.model)
        if (!dialog.showAndGet()) return
        localeAdder.addLocale(
            virtualFile = virtualFile,
            localeTag = dialog.localeTag,
            valName = dialog.valName
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
                val dataContext = SimpleDataContext.builder()
                    .add(CommonDataKeys.PROJECT, project)
                    .add(CommonDataKeys.PSI_ELEMENT, param)
                    .build()
                val action = ActionManager.getInstance().getAction("RenameElement")

                @Suppress("UnstableApiUsage")
                val event = AnActionEvent.createEvent(
                    dataContext,
                    action.templatePresentation.clone(),
                    ActionPlaces.UNKNOWN,
                    com.intellij.openapi.actionSystem.ActionUiKind.NONE,
                    null
                )
                action.actionPerformed(event)
                SwingUtilities.invokeLater { refresh() }
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun showLambdaParamDialog(key: I18nKey) {
        val dialog = EditLambdaParamsDialog(key)
        if (!dialog.showAndGet()) return
        converter.convertToLambda(virtualFile, key, dialog.lambdaParams, dialog.lambdaReturnType)
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
        private val groupBg = JBColor(Color(230, 235, 245), Color(60, 63, 65))
        private val lambdaBg = JBColor(Color(255, 252, 235), Color(70, 67, 50))
        private val headerFg = JBColor(Gray._50, Gray._210)
        private val INDENT = 16

        private val lockIcon = MyIcons.Locked
        private val keyPanel = JPanel(BorderLayout(4, 0)).apply { isOpaque = true }
        private val lockLabel = JLabel()
        private val keyLabel = JLabel()

        init {
            keyPanel.add(lockLabel, BorderLayout.WEST)
            keyPanel.add(keyLabel, BorderLayout.CENTER)
        }


        override fun getTableCellRendererComponent(
            table: JTable, value: Any?, isSelected: Boolean,
            hasFocus: Boolean, row: Int, column: Int
        ): Component {
            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
            val depth = tableModel.getDepth(row)
            val leftPad = 6 + depth * INDENT
            val locked = tableModel.isLocked(row, virtualFile)

            when (val r = tableModel.getRow(row)) {
                is I18nRow.GroupHeader -> {
                    val arrow = if (tableModel.isCollapsed(row)) "▶" else "▼"
                    text = if (column == 0)
                        "$arrow  ${r.group.className}  ·  ${r.group.fieldPath.joinToString(".")}"
                    else ""
                    font = font.deriveFont(Font.BOLD)
                    background = if (isSelected) table.selectionBackground else groupBg
                    foreground = if (isSelected) table.selectionForeground else headerFg
                    border = JBUI.Borders.empty(0, leftPad, 0, 6)

                    if (column == 0 && locked) {
                        icon = MyIcons.Locked
                        iconTextGap = 4
                        foreground = JBColor(Color(160, 160, 160), Color(110, 110, 110))
                        toolTipText = "Locked — edit directly in source"
                    } else {
                        icon = null
                    }
                }

                is I18nRow.KeyRow -> {
                    val baseFg = when {
                        isSelected  -> table.selectionForeground
                        locked      -> JBColor(Color(160, 160, 160), Color(110, 110, 110))
                        r.key.isLambda -> JBColor(Color(120, 80, 180), Color(170, 130, 210))
                        r.key.isMap -> JBColor(Color(150, 100, 0), Color(180, 150, 80))
                        else        -> table.foreground
                    }
                    background = when {
                        isSelected          -> table.selectionBackground
                        r.key.isLambda || r.key.isMap -> lambdaBg
                        else                -> table.background
                    }
                    foreground = baseFg
                    text = when {
                        column == 0 -> r.key.name
                        r.key.isMap -> "[map — not editable]"
                        else        -> value?.toString() ?: ""
                    }
                    toolTipText = when {
                        locked          -> "Locked — edit directly in source"
                        r.key.isLambda  -> "λ  params: (${r.key.lambdaParams.joinToString(", ")})"
                        r.key.isMap     -> "Map — edit directly in source"
                        else            -> null
                    }
                    border = if (column == 0)
                        JBUI.Borders.empty(0, leftPad, 0, 6)
                    else
                        JBUI.Borders.empty(0, 6)

                    if (column == 0 && locked) {
                        icon = MyIcons.Locked
                        iconTextGap = 4
                    } else {
                        icon = null
                    }
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
            val row = editingRow
            val col = editingCol
            if (row >= 0 && col > 0) {
                val r = tableModel.getRow(row) as? I18nRow.KeyRow
                val tag = tableModel.getLocaleTag(col)
                val t = currentTable
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
            field.text = value?.toString() ?: ""
            field.border = JBUI.Borders.empty(0, 6)

            // Navigate to the locale value definition when editing starts
            if (col > 0) {
                val r = tableModel.getRow(row) as? I18nRow.KeyRow
                val tag = tableModel.getLocaleTag(col)
                if (r != null && tag != null) {
                    navigateToLocaleValue(r.key, tag)
                }
            }

            return field
        }
    }
}