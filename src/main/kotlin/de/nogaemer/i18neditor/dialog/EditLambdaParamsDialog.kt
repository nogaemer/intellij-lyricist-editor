package de.nogaemer.i18neditor.dialog

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import de.nogaemer.i18neditor.model.I18nKey
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.JComponent
import javax.swing.JPanel

class EditLambdaParamsDialog(key: I18nKey) : DialogWrapper(true) {

    private val paramField = JBTextField(24).apply {
        emptyText.text = "e.g. wins: Int, total: Int"
        text = key.lambdaParams.joinToString(", ")
    }
    private val returnTypeField = JBTextField(12).apply {
        emptyText.text = "String"
        text = key.lambdaReturnType.ifEmpty { "String" }
    }


    val lambdaParams: List<String>
        get() = paramField.text.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    val lambdaReturnType: String
        get() = returnTypeField.text.trim().ifEmpty { "String" }


    init {
        title = if (key.isLambda) "Edit Lambda Parameters" else "Convert to Lambda"
        setOKButtonText("Apply")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(GridBagLayout()).apply { border = JBUI.Borders.empty(4, 0) }
        val gc = GridBagConstraints().apply {
            fill = GridBagConstraints.HORIZONTAL
            anchor = GridBagConstraints.WEST
            insets = JBUI.insets(4, 4, 4, 4)
        }
        fun row(label: String, comp: JComponent, r: Int) {
            gc.gridx = 0; gc.gridy = r; gc.weightx = 0.0
            panel.add(JBLabel(label), gc)
            gc.gridx = 1; gc.weightx = 1.0
            panel.add(comp, gc)
        }
        row("Parameters:", paramField,       0)
        row("Return type:", returnTypeField, 1)
        return panel
    }

    override fun doValidate(): ValidationInfo? {
        if (lambdaParams.isEmpty())
            return ValidationInfo("Provide at least one parameter", paramField)
        val paramRegex = Regex("[a-z][a-zA-Z0-9]*\\s*:\\s*[A-Z][a-zA-Z0-9<>?,\\s]*")
        if (lambdaParams.any { !it.matches(paramRegex) })
            return ValidationInfo("Format must be 'name: Type' (e.g. score: Int)", paramField)
        return null
    }

}