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

            // Capture prev BEFORE deletion, only keep it if it belongs to the real file
            val prev = prop.prevSibling
                ?.takeIf { it is PsiWhiteSpace && it.isValid && it.containingFile == ktFile }

            // Delete whitespace + property as a range if possible, otherwise separately
            if (prev != null) {
                ktFile.deleteChildRange(prev, prop)
            } else {
                prop.delete()
            }
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