package de.nogaemer.i18neditor.dialog

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import de.nogaemer.i18neditor.model.I18nFileModel
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.JComponent
import javax.swing.JPanel

class AddLocaleDialog(private val fileModel: I18nFileModel) : DialogWrapper(true) {

    private val localeTagField = JBTextField(12).apply { emptyText.text = "e.g. de" }
    private val valNameField   = JBTextField(24).apply { emptyText.text = "e.g. deStrings" }

    val localeTag: String get() = localeTagField.text.trim().lowercase()
    val valName:   String get() = valNameField.text.trim()

    init {
        title = "Add New Language"
        setOKButtonText("Add Language")
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
        row("Language tag:", localeTagField, 0)
        row("Val name:",     valNameField,   1)

        // Auto-fill val name when locale tag is typed
        localeTagField.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent?)  = sync()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent?)  = sync()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent?) = sync()
            private fun sync() {
                if (valNameField.text.isBlank() || valNameField.text == autoName()) {
                    valNameField.text = autoName()
                }
            }
            private fun autoName() = "${localeTagField.text.trim().lowercase()}Strings"
        })

        return panel
    }

    override fun doValidate(): ValidationInfo? {
        if (localeTag.isEmpty())
            return ValidationInfo("Language tag cannot be empty", localeTagField)
        if (!localeTag.matches(Regex("[a-z]{2}(-[A-Z]{2})?")))
            return ValidationInfo("Use BCP-47 format: 'de', 'en', 'pt-BR'", localeTagField)
        if (fileModel.locales.any { it.tag == localeTag })
            return ValidationInfo("Locale '$localeTag' already exists", localeTagField)
        if (valName.isEmpty())
            return ValidationInfo("Val name cannot be empty", valNameField)
        if (!valName.matches(Regex("[a-z][a-zA-Z0-9]*")))
            return ValidationInfo("Must be a valid Kotlin identifier", valNameField)
        return null
    }
}