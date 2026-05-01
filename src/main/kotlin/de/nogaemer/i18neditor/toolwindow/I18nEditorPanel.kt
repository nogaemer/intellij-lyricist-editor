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
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
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
    private val groupDeleter = LyricistGroupDeleter(project)
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
        busConnection = null
    }

    // ── Locking ────────────────────────────────────────────────────────────────

    private fun onToggleLock() {
        val row = jbTable.selectedRow
        if (row < 0) return
        val path = when (val r = tableModel.getRow(row)) {
            is I18nRow.KeyRow -> r.key.fullPath
            is I18nRow.GroupHeader -> r.group.fieldPath.joinToString(".")
        }
        LockManager.toggle(virtualFile, path)
        jbTable.repaint()
    }


    // ── Build ─────────────────────────────────────────────────────────────────
    private var dragStartPoint: Point? = null
    private var dragStartRow: Int = -1

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

            override fun paintComponent(g: Graphics) {
                super.paintComponent(g)
                val dtr = dropTargetRow
                if (dtr < 0) return
                val g2 = g as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = JBUI.CurrentTheme.Link.Foreground.ENABLED
                g2.stroke = BasicStroke(2f)
                val y = if (dtr < rowCount) {
                    getCellRect(dtr, 0, true).y
                } else {
                    val last = getCellRect(rowCount - 1, 0, true)
                    last.y + last.height
                }
                g2.drawLine(0, y, width, y)
                g2.fillPolygon(intArrayOf(0, 8, 0), intArrayOf(y - 4, y, y + 4), 3)
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
            setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
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
                if (SwingUtilities.isLeftMouseButton(e) &&
                    (e.modifiersEx and InputEvent.CTRL_DOWN_MASK) != 0) {
                    val row = jbTable.rowAtPoint(e.point)
                    val col = jbTable.columnAtPoint(e.point)
                    if (row >= 0) {
                        val r = tableModel.getRow(row) as? I18nRow.KeyRow
                        if (r != null) {
                            if (col == 0) {
                                navigateToKey(r.key)
                            } else {
                                val tag = tableModel.getLocaleTag(col)
                                if (tag != null) navigateToLocaleValue(r.key, tag)
                            }
                            e.consume()
                            return
                        }
                    }
                }


                if (SwingUtilities.isLeftMouseButton(e)) {
                    dragStartPoint = e.point
                    dragStartRow = jbTable.rowAtPoint(e.point)
                }

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
            }

            override fun mouseReleased(e: MouseEvent) {
                dragStartPoint = null
                dragStartRow = -1
                maybeShowRowPopup(e)
            }

            override fun mouseClicked(e: MouseEvent) {
                if (groupAddButtonClicked) { groupAddButtonClicked = false; return }
                val row = jbTable.rowAtPoint(e.point)
                if (row < 0) return

                when (val r = tableModel.getRow(row)) {
                    is I18nRow.GroupHeader ->
                        if (e.clickCount == 2 && !SwingUtilities.isRightMouseButton(e))
                            tableModel.toggleCollapse(row)

                    is I18nRow.KeyRow -> {
                        val col = jbTable.columnAtPoint(e.point)
                        when {
                            // Double-click col 0 → navigate to key definition
                            e.clickCount == 2 && SwingUtilities.isLeftMouseButton(e) && col == 0 ->
                                navigateToKey(r.key)

                            // Ctrl+click on value cell → navigate to that locale value
                            e.clickCount == 1 && col > 0 &&
                                    SwingUtilities.isLeftMouseButton(e) &&
                                    (e.modifiersEx and InputEvent.CTRL_DOWN_MASK) != 0 -> {
                                val tag = tableModel.getLocaleTag(col)
                                if (tag != null) navigateToLocaleValue(r.key, tag)
                            }
                        }
                    }
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

        jbTable.addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseDragged(e: MouseEvent) {
                val start = dragStartPoint ?: return
                if (e.point.distance(start) < 8) return
                val row = dragStartRow.takeIf { it >= 0 } ?: return

                // Only start drag from the key column — value columns are edit-only
                val col = jbTable.columnAtPoint(start)
                if (col != 0) {
                    dragStartPoint = null
                    dragStartRow = -1
                    return
                }

                if (tableModel.isLocked(row, virtualFile)) {
                    dragStartPoint = null
                    dragStartRow = -1
                    return
                }

                dragStartPoint = null
                dragStartRow = -1
                jbTable.transferHandler?.exportAsDrag(jbTable, e, TransferHandler.MOVE)
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
                    if (row < 0) {
                        e.presentation.isEnabled = false; return
                    }
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
        installDragAndDrop()
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
        group.add(object : AnAction(
            "Go to Source  (Ctrl+Click)", null,
            AllIcons.Actions.StepOut
        ) {
            override fun actionPerformed(ev: AnActionEvent) {
                val col = jbTable.selectedColumn
                val tag = if (col > 0) tableModel.getLocaleTag(col) else null
                if (tag != null) navigateToLocaleValue(key, tag)
                else navigateToKey(key)
            }
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
        OpenFileDescriptor(project, virtualFile, param.textOffset).navigate(false)
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
        OpenFileDescriptor(project, virtualFile, offset).navigate(false)
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

        // Create the group
        groupAdder.addGroup(
            virtualFile = virtualFile,
            className = dialog.className,
            fieldName = dialog.fieldName,
            parentPath = dialog.parentPath
        )
        refresh()

        // Find the freshly created group in the updated model
        val newGroup = currentTable?.model?.groups
            ?.firstOrNull { it.className == dialog.className }

        if (newGroup == null) {
            // Group creation failed for some reason, nothing to roll back
            return
        }

        // Immediately prompt for the first key
        val keyDialog = AddKeyDialog(currentTable!!.model, newGroup)
        if (!keyDialog.showAndGet()) {
            // User cancelled → roll back the entire group
            groupDeleter.deleteGroup(virtualFile, newGroup)
            refresh()
            return
        }

        adder.addKey(
            virtualFile = virtualFile,
            group = keyDialog.selectedGroup,
            keyName = keyDialog.keyName,
            valuesByLocaleTag = keyDialog.valuesByLocale,
            isLambda = keyDialog.isLambda,
            lambdaParams = keyDialog.lambdaParams,
            lambdaReturnType = keyDialog.lambdaReturnType
        )
        refresh()

        // Select the new key
        val fullPath = (keyDialog.selectedGroup.fieldPath + keyDialog.keyName).joinToString(".")
        val row = tableModel.rowForKey(fullPath)
        if (row >= 0) {
            jbTable.selectionModel.setSelectionInterval(row, row)
            jbTable.scrollRectToVisible(jbTable.getCellRect(row, 0, true))
        }
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
        when (val r = tableModel.getRow(row)) {
            is I18nRow.KeyRow -> {
                if (tableModel.isLocked(row, virtualFile)) return
                deleter.deleteKey(virtualFile, r.key)
                refresh()
            }
            is I18nRow.GroupHeader -> {
                if (tableModel.isLocked(row, virtualFile)) return
                val hasContent = r.group.keys.isNotEmpty() ||
                        currentTable?.model?.groups?.any { g ->
                            g.fieldPath.size > r.group.fieldPath.size &&
                                    g.fieldPath.take(r.group.fieldPath.size) == r.group.fieldPath
                        } == true
                if (hasContent) {
                    val ok = Messages.showYesNoDialog(
                        project,
                        "Delete group '${r.group.className}' and all its keys and subgroups?\nThis cannot be undone.",
                        "Delete Group",
                        Messages.getWarningIcon()
                    )
                    if (ok != Messages.YES) return
                }
                groupDeleter.deleteGroup(virtualFile, r.group)
                refresh()
            }
        }
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

    // ── Drag & drop data ──────────────────────────────────────────────────────

    private data class DragPayload(val row: Int, val data: I18nRow)

    private var dropTargetRow: Int = -1  // -1 = none, row index = insert before that row
    private val mover = LyricistNodeMover(project)

    private fun installDragAndDrop() {
        System.setProperty("awt.dnd.drag.threshold", "8")

        jbTable.dragEnabled = false
        jbTable.dropMode = DropMode.INSERT_ROWS
        jbTable.transferHandler = object : TransferHandler() {

            private val flavor = DataFlavor(DragPayload::class.java, "i18nRow")

            override fun getSourceActions(c: JComponent) = MOVE

            override fun createTransferable(c: JComponent): Transferable? {
                val row = jbTable.selectedRow.takeIf { it >= 0 } ?: return null
                if (tableModel.isLocked(row, virtualFile)) return null
                val payload = DragPayload(row, tableModel.getRow(row))
                return object : Transferable {
                    override fun getTransferDataFlavors() = arrayOf(flavor)
                    override fun isDataFlavorSupported(f: DataFlavor) = f == flavor
                    override fun getTransferData(f: DataFlavor): Any = payload
                }
            }

            override fun canImport(support: TransferSupport): Boolean {
                if (!support.isDataFlavorSupported(flavor)) return false
                val loc = (support.dropLocation as? JTable.DropLocation) ?: return false
                val targetRow = loc.row.coerceIn(0, tableModel.rowCount)
                // Can't drop onto a locked row
                if (targetRow < tableModel.rowCount && tableModel.isLocked(targetRow, virtualFile)) return false
                dropTargetRow = targetRow
                jbTable.repaint()
                return true
            }

            override fun importData(support: TransferSupport): Boolean {
                if (!canImport(support)) return false
                val payload = support.getTransferable().getTransferData(flavor) as? DragPayload ?: return false
                val loc = support.dropLocation as? JTable.DropLocation ?: return false
                val insertBeforeRow = loc.row.coerceIn(0, tableModel.rowCount)

                dropTargetRow = -1
                jbTable.repaint()

                performMove(payload, insertBeforeRow)
                return true
            }

            override fun exportDone(source: JComponent?, data: Transferable?, action: Int) {
                dropTargetRow = -1
                jbTable.repaint()
            }
        }
    }

    private fun performMove(payload: DragPayload, insertBeforeRow: Int) {
        val targetRow = insertBeforeRow.coerceIn(0, tableModel.rowCount - 1)
        if (targetRow == payload.row || targetRow == payload.row + 1) return

        when (val dragged = payload.data) {
            is I18nRow.KeyRow -> {
                val key = dragged.key
                val targetRowData = tableModel.getRow(targetRow)

                val (targetGroup, beforeKey) = when (targetRowData) {
                    is I18nRow.GroupHeader -> {
                        val prevRow = targetRow - 1
                        if (prevRow >= 0 && tableModel.getRow(prevRow) is I18nRow.KeyRow) {
                            // Keep in the group above, insert at end
                            val prevKey = (tableModel.getRow(prevRow) as I18nRow.KeyRow).key
                            Pair(tableModel.groupForKey(prevKey), null)
                        } else {
                            // Genuinely dropping onto a group header → insert at top
                            Pair(targetRowData.group, tableModel.firstKeyInGroup(targetRowData.group))
                        }
                    }
                    is I18nRow.KeyRow -> Pair(tableModel.groupForKey(targetRowData.key), targetRowData.key)
                }
                if (targetGroup == null) return

                if (targetGroup.className != key.groupClass &&
                    targetGroup.keys.any { it.name == key.name }) {
                    Messages.showWarningDialog(
                        project,
                        "Group '${targetGroup.className}' already contains a key named '${key.name}'.",
                        "Move Cancelled"
                    )
                    return
                }
                mover.moveKey(virtualFile, key, targetGroup, beforeKey)
            }

            is I18nRow.GroupHeader -> {
                val group = dragged.group
                val targetRowData = if (targetRow < tableModel.rowCount)
                    tableModel.getRow(targetRow) else null

                val (targetParent, beforeGroup) = when (targetRowData) {
                    // Dropping before another group header → become its sibling
                    is I18nRow.GroupHeader ->
                        Pair(tableModel.getParentGroup(targetRowData.group), targetRowData.group)

                    // Dropping before a key row → insert into that key's owning group
                    is I18nRow.KeyRow ->
                        Pair(tableModel.groupForKey(targetRowData.key), null)

                    // Dropping at the very end → keep same parent, append
                    null ->
                        Pair(tableModel.getParentGroup(group), null)
                }

                // Guard: can't move a group into itself or one of its own descendants
                if (targetParent != null &&
                    targetParent.fieldPath.size >= group.fieldPath.size &&
                    targetParent.fieldPath.take(group.fieldPath.size) == group.fieldPath
                ) return

                // Guard: already in place (same parent, same before)
                if (targetParent == tableModel.getParentGroup(group) &&
                    beforeGroup == null && targetRowData == null) return

                mover.moveGroup(virtualFile, group, targetParent, beforeGroup)
            }
        }
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
                        isSelected -> table.selectionForeground
                        locked -> JBColor(Color(160, 160, 160), Color(110, 110, 110))
                        r.key.isLambda -> JBColor(Color(120, 80, 180), Color(170, 130, 210))
                        r.key.isMap -> JBColor(Color(150, 100, 0), Color(180, 150, 80))
                        else -> table.foreground
                    }
                    background = when {
                        isSelected -> table.selectionBackground
                        r.key.isLambda || r.key.isMap -> lambdaBg
                        else -> table.background
                    }
                    foreground = baseFg
                    text = when {
                        column == 0 -> r.key.name
                        r.key.isMap -> "[map — not editable]"
                        else -> value?.toString() ?: ""
                    }
                    toolTipText = when {
                        locked -> "Locked — edit directly in source"
                        r.key.isLambda -> "λ  params: (${r.key.lambdaParams.joinToString(", ")})"
                        r.key.isMap -> "Map — edit directly in source"
                        else -> null
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
            return field
        }
    }
}