package de.nogaemer.i18neditor.writer

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiWhiteSpace
import org.jetbrains.kotlin.psi.*

class LyricistLocaleDeleter(private val project: Project) {

    fun deleteLocale(virtualFile: VirtualFile, localeTag: String) {
        val ktFile = PsiManager.getInstance(project).findFile(virtualFile) as? KtFile ?: return

        WriteCommandAction.runWriteCommandAction(project, "Delete i18n Locale", null, {
            val prop = ktFile.declarations
                .filterIsInstance<KtProperty>()
                .firstOrNull { p ->
                    p.annotationEntries.any { it.shortName?.identifier == "LyricistStrings" }
                            && extractLocaleTag(p) == localeTag
                } ?: return@runWriteCommandAction

            // Also remove the blank line before the property
            val prev = prop.prevSibling
            prop.delete()
            if (prev is PsiWhiteSpace) prev.delete()
        }, ktFile)
    }

    private fun extractLocaleTag(prop: KtProperty): String? {
        val ann = prop.annotationEntries.firstOrNull {
            it.shortName?.identifier == "LyricistStrings"
        } ?: return null
        val tagArg = ann.valueArguments.firstOrNull {
            it.getArgumentName()?.asName?.identifier == "languageTag"
        } ?: ann.valueArguments.firstOrNull()
        return (tagArg?.getArgumentExpression() as? KtStringTemplateExpression)
            ?.entries?.joinToString("") { it.text }
    }
}