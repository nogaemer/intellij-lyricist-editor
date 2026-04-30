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

    private val paramsField = JBTextField(24).apply {
        text       = key.lambdaParams.joinToString(", ")
        emptyText.text = "e.g. teamName, score"
    }

    val lambdaParams: List<String>
        get() = paramsField.text.split(",").map { it.trim() }.filter { it.isNotEmpty() }

    init {
        title = if (key.isLambda) "Edit Lambda Parameters" else "Convert to Lambda"
        setOKButtonText("Apply")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(GridBagLayout()).apply { border = JBUI.Borders.empty(4, 0) }
        val gc = GridBagConstraints().apply {
            fill   = GridBagConstraints.HORIZONTAL
            anchor = GridBagConstraints.WEST
            insets = JBUI.insets(4, 4, 4, 4)
        }
        gc.gridx = 0; gc.gridy = 0; gc.weightx = 0.0
        panel.add(JBLabel("Parameter names:"), gc)
        gc.gridx = 1; gc.weightx = 1.0
        panel.add(paramsField, gc)

        gc.gridx = 0; gc.gridy = 1; gc.gridwidth = 2; gc.weightx = 1.0
        panel.add(JBLabel("<html><small>Comma-separated. Used in template as \$paramName</small></html>").apply {
            foreground = JBUI.CurrentTheme.Label.disabledForeground()
            border     = JBUI.Borders.emptyTop(4)
        }, gc)
        return panel
    }

    override fun doValidate(): ValidationInfo? {
        if (lambdaParams.isEmpty())
            return ValidationInfo("Provide at least one parameter name", paramsField)
        val invalid = lambdaParams.firstOrNull { !it.matches(Regex("[a-zA-Z][a-zA-Z0-9_]*")) }
        if (invalid != null)
            return ValidationInfo("'$invalid' is not a valid Kotlin identifier", paramsField)
        return null
    }
}