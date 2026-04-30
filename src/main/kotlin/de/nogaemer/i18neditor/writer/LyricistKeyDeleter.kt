package de.nogaemer.i18neditor.writer

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiWhiteSpace
import de.nogaemer.i18neditor.model.I18nKey
import de.nogaemer.i18neditor.util.navigateToCallExpr
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.*

/**
 * Removes a key from its data class constructor and from every locale val's
 * constructor call chain. Handles comma cleanup on both sides.
 */
class LyricistKeyDeleter(private val project: Project) {

    fun deleteKey(virtualFile: VirtualFile, key: I18nKey) {
        val ktFile = PsiManager.getInstance(project).findFile(virtualFile) as? KtFile ?: return

        WriteCommandAction.runWriteCommandAction(project, "Delete i18n Key", null, {

            // ── 1. Remove parameter from the data class ───────────────────────
            val dataClass = ktFile.declarations
                .filterIsInstance<KtClass>()
                .firstOrNull { it.name == key.groupClass }
                ?: return@runWriteCommandAction

            dataClass.primaryConstructor
                ?.valueParameters
                ?.firstOrNull { it.name == key.name }
                ?.let { deleteElementWithComma(it) }

            // ── 2. Remove named argument from every locale val ────────────────
            ktFile.declarations
                .filterIsInstance<KtProperty>()
                .filter { prop ->
                    prop.annotationEntries.any { it.shortName?.identifier == "LyricistStrings" }
                }
                .forEach { prop ->
                    val rootCall = prop.initializer as? KtCallExpression ?: return@forEach
                    val groupPath = key.fullPath.split(".").dropLast(1)
                    val target    = navigateToCallExpr(rootCall, groupPath) ?: return@forEach
                    val arg      = target.valueArguments.firstOrNull {
                        it.getArgumentName()?.asName?.identifier == key.name
                    } ?: return@forEach
                    // KtValueArgumentList.removeArgument handles comma cleanup internally
                    target.valueArgumentList?.removeArgument(arg)
                }

        }, ktFile)
    }

    /**
     * Deletes [element] and its adjacent comma from a parameter list.
     * Prefers removing the trailing comma; falls back to leading comma
     * when [element] is the last in the list.
     */
    private fun deleteElementWithComma(element: PsiElement) {
        // Try trailing comma first
        var next: PsiElement? = element.nextSibling
        var deletedComma = false
        while (next != null) {
            when {
                next is PsiWhiteSpace -> next = next.nextSibling
                next.node.elementType == KtTokens.COMMA -> {
                    next.delete()
                    deletedComma = true
                    break
                }
                else -> break
            }
        }

        // No trailing comma found — delete leading comma (element is last in list)
        if (!deletedComma) {
            var prev: PsiElement? = element.prevSibling
            while (prev != null) {
                when {
                    prev is PsiWhiteSpace -> prev = prev.prevSibling
                    prev.node.elementType == KtTokens.COMMA -> {
                        prev.delete()
                        break
                    }
                    else -> break
                }
            }
        }

        element.delete()
    }
}