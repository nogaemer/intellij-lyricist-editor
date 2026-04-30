package de.nogaemer.i18neditor.dialog

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import de.nogaemer.i18neditor.model.I18nFileModel
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel

class AddGroupDialog(private val fileModel: I18nFileModel) : DialogWrapper(true) {

    private val classNameField = JBTextField(24).apply { emptyText.text = "e.g. FooterStrings" }
    private val fieldNameField = JBTextField(24).apply { emptyText.text = "e.g. footer" }

    // Parent options: root Strings class, or any existing group
    private val parentOptions  = listOf("<root Strings>") +
            fileModel.groups.map { it.fieldPath.joinToString(".").ifEmpty { it.className } }
    private val parentCombo    = JComboBox(parentOptions.toTypedArray())

    val className:  String       get() = classNameField.text.trim()
    val fieldName:  String       get() = fieldNameField.text.trim()
    /** Empty list = attach to root Strings; otherwise the fieldPath of the parent group */
    val parentPath: List<String> get() {
        val idx = parentCombo.selectedIndex
        if (idx <= 0) return emptyList()
        return fileModel.groups[idx - 1].fieldPath
    }

    init {
        title = "Add New Group"
        setOKButtonText("Add Group")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(GridBagLayout()).apply { border = JBUI.Borders.empty(4, 0) }
        val gc = GridBagConstraints().apply {
            fill   = GridBagConstraints.HORIZONTAL
            anchor = GridBagConstraints.WEST
            insets = JBUI.insets(4, 4, 4, 4)
        }
        fun row(label: String, comp: JComponent, r: Int) {
            gc.gridx = 0; gc.gridy = r; gc.weightx = 0.0
            panel.add(JBLabel(label), gc)
            gc.gridx = 1; gc.weightx = 1.0
            panel.add(comp, gc)
        }
        row("Class name:", classNameField, 0)
        row("Field name:", fieldNameField, 1)
        row("Parent:",     parentCombo,    2)
        return panel
    }

    override fun doValidate(): ValidationInfo? {
        if (className.isEmpty())
            return ValidationInfo("Class name cannot be empty", classNameField)
        if (!className.matches(Regex("[A-Z][a-zA-Z0-9]*")))
            return ValidationInfo("Must start with uppercase letter", classNameField)
        if (fieldName.isEmpty())
            return ValidationInfo("Field name cannot be empty", fieldNameField)
        if (!fieldName.matches(Regex("[a-z][a-zA-Z0-9]*")))
            return ValidationInfo("Must start with lowercase letter", fieldNameField)
        if (fileModel.groups.any { it.className == className })
            return ValidationInfo("'$className' already exists", classNameField)
        return null
    }
}