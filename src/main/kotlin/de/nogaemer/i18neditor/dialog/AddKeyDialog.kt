package de.nogaemer.i18neditor.dialog

import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import de.nogaemer.i18neditor.model.I18nFileModel
import de.nogaemer.i18neditor.model.I18nGroup
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel

class AddKeyDialog(private val fileModel: I18nFileModel) : DialogWrapper(true) {

    private val keyNameField    = JBTextField(24)
    private val groupCombo      = ComboBox(fileModel.groups.map { it.className }.toTypedArray())
    private val typeCombo       = ComboBox(arrayOf("String", "Lambda"))
    private val lambdaParamField = JBTextField(24).apply {
        emptyText.text = "e.g. teamName, score"
        isEnabled      = false
    }
    private val valueFields = fileModel.locales.associateWith { JBTextField(24) }

    val keyName:       String    get() = keyNameField.text.trim()
    val selectedGroup: I18nGroup get() = fileModel.groups[groupCombo.selectedIndex]
    val isLambda:      Boolean   get() = typeCombo.selectedIndex == 1
    val lambdaParams:  List<String>
        get() = lambdaParamField.text.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    val valuesByLocale: Map<String, String>
        get() = valueFields.entries.associate { (l, f) -> l.tag to f.text.trim() }

    init {
        title = "Add New String Key"
        setOKButtonText("Add Key")
        typeCombo.addActionListener {
            lambdaParamField.isEnabled = typeCombo.selectedIndex == 1
        }
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(GridBagLayout()).apply { border = JBUI.Borders.empty(4, 0) }
        val gc = GridBagConstraints().apply {
            fill   = GridBagConstraints.HORIZONTAL
            anchor = GridBagConstraints.WEST
            insets = JBUI.insets(4, 4, 4, 4)
        }

        fun row(label: String, field: JComponent, r: Int) {
            gc.gridx = 0; gc.gridy = r; gc.weightx = 0.0
            panel.add(JBLabel(label), gc)
            gc.gridx = 1; gc.weightx = 1.0
            panel.add(field, gc)
        }

        row("Group:",            groupCombo,       0)
        row("Key name:",         keyNameField,     1)
        row("Type:",             typeCombo,        2)
        row("Lambda params:",    lambdaParamField, 3)

        gc.gridx = 0; gc.gridy = 4; gc.gridwidth = 2; gc.weightx = 1.0
        panel.add(JBLabel("Initial values:").apply {
            border     = JBUI.Borders.emptyTop(6)
            foreground = JBUI.CurrentTheme.Label.disabledForeground()
        }, gc)
        gc.gridwidth = 1

        var r = 5
        for ((locale, field) in valueFields) {
            field.emptyText.text = if (locale.isDefault) "Required" else "Optional"
            row("${locale.tag.uppercase()}:", field, r++)
        }
        return panel
    }

    override fun doValidate(): ValidationInfo? {
        if (keyName.isEmpty())
            return ValidationInfo("Key name cannot be empty", keyNameField)
        if (!keyName.matches(Regex("[a-zA-Z][a-zA-Z0-9_]*")))
            return ValidationInfo("Must be a valid Kotlin identifier", keyNameField)
        if (selectedGroup.keys.any { it.name == keyName })
            return ValidationInfo("'$keyName' already exists in ${selectedGroup.className}", keyNameField)
        if (isLambda && lambdaParams.isEmpty())
            return ValidationInfo("Provide at least one lambda parameter name", lambdaParamField)
        return null
    }
}