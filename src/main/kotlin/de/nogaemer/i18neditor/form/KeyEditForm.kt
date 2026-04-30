package de.nogaemer.i18neditor.form

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import de.nogaemer.i18neditor.model.I18nKey
import de.nogaemer.i18neditor.model.I18nTable
import de.nogaemer.i18neditor.writer.LyricistStringWriter
import java.awt.*
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import javax.swing.JPanel
import javax.swing.JTextArea

class KeyEditForm(
    private val project: Project,
    private val virtualFile: VirtualFile,
    private val key: I18nKey,
    private val table: I18nTable,
    private val onValueChanged: () -> Unit
) : JPanel(BorderLayout()) {

    private val writer = LyricistStringWriter(project)

    init { build() }

    private fun build() {
        val inner = JPanel(GridBagLayout()).apply {
            border = JBUI.Borders.empty(12, 16, 12, 16)
        }
        val gc = GridBagConstraints().apply {
            fill    = GridBagConstraints.HORIZONTAL
            anchor  = GridBagConstraints.NORTHWEST
            weightx = 1.0
            gridx   = 0
            gridy   = 0
        }

        // ── Header ─────────────────────────────────────────────────────────────
        gc.gridwidth = 2
        inner.add(JBLabel(key.fullPath).apply {
            font   = font.deriveFont(Font.BOLD, font.size2D + 1f)
            border = JBUI.Borders.emptyBottom(14)
        }, gc)
        gc.gridy++
        gc.gridwidth = 1

        // ── Map: not editable ──────────────────────────────────────────────────
        if (key.isMap) {
            gc.gridwidth = 2
            inner.add(JBLabel("Map type — not directly editable in this editor").apply {
                foreground = JBUI.CurrentTheme.Label.disabledForeground()
            }, gc)
            add(JBScrollPane(inner), BorderLayout.CENTER)
            return
        }

        // ── One row per locale ─────────────────────────────────────────────────
        for (locale in table.model.locales) {
            val currentValue = table.getValue(key, locale) ?: ""

            // Locale tag
            gc.gridx   = 0
            gc.weightx = 0.0
            gc.fill    = GridBagConstraints.NONE
            inner.add(JBLabel(locale.tag.uppercase()).apply {
                font       = font.deriveFont(Font.BOLD)
                foreground = if (locale.isDefault) JBUI.CurrentTheme.Label.foreground()
                else Color(100, 149, 237)
                preferredSize = Dimension(36, preferredSize.height)
                border        = JBUI.Borders.emptyRight(10)
            }, gc)

            // Value field
            gc.gridx   = 1
            gc.weightx = 1.0
            gc.fill    = GridBagConstraints.HORIZONTAL

            if (key.isLambda) {
                // Single-line field for lambda body templates
                val field = JBTextField(currentValue).apply {
                    toolTipText = "Lambda body — use \$paramName for string template expressions"
                    emptyText.text = "e.g. \$teamName wins"
                }
                attachSaveListener(field, locale.tag)
                inner.add(field, gc)
            } else {
                // Multi-line area for regular strings (supports newlines like "Host a\nGame")
                val area = JTextArea(currentValue, 2, 30).apply {
                    lineWrap      = true
                    wrapStyleWord = true
                    font          = JBTextField().font
                    border        = JBUI.Borders.customLine(
                        JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground(), 1
                    )
                }
                attachSaveListenerArea(area, locale.tag)
                inner.add(area, gc)
            }
            gc.gridy++

            // Row spacer
            gc.gridx     = 0
            gc.gridwidth = 2
            gc.weightx   = 1.0
            inner.add(JPanel().apply { preferredSize = Dimension(1, 6) }, gc)
            gc.gridwidth = 1
            gc.gridy++
        }

        // Vertical filler — pushes rows to top
        gc.gridx     = 0
        gc.gridwidth = 2
        gc.weighty   = 1.0
        gc.fill      = GridBagConstraints.BOTH
        inner.add(JPanel(), gc)

        add(JBScrollPane(inner), BorderLayout.CENTER)
    }

    private fun attachSaveListener(field: JBTextField, localeTag: String) {
        field.addFocusListener(object : FocusAdapter() {
            private var last = field.text
            override fun focusLost(e: FocusEvent) {
                val new = field.text
                if (new == last) return
                last = new
                val locale = table.model.locales.firstOrNull { it.tag == localeTag } ?: return
                writer.write(virtualFile, locale, key, new)
                onValueChanged()
            }
        })
    }

    private fun attachSaveListenerArea(area: JTextArea, localeTag: String) {
        area.addFocusListener(object : FocusAdapter() {
            private var last = area.text
            override fun focusLost(e: FocusEvent) {
                val new = area.text
                if (new == last) return
                last = new
                val locale = table.model.locales.firstOrNull { it.tag == localeTag } ?: return
                writer.write(virtualFile, locale, key, new)
                onValueChanged()
            }
        })
    }
}