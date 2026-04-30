package de.nogaemer.i18neditor.writer

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import de.nogaemer.i18neditor.model.I18nKey
import de.nogaemer.i18neditor.model.I18nLocale
import de.nogaemer.i18neditor.util.escapeForKotlinString
import de.nogaemer.i18neditor.util.navigateToCallExpr
import org.jetbrains.kotlin.psi.*

class LyricistStringWriter(private val project: Project) {

    fun write(
        virtualFile: VirtualFile,
        locale: I18nLocale,
        key: I18nKey,
        displayValue: String   // always the stripped display value, never the raw lambda wrapper
    ) {
        val ktFile  = PsiManager.getInstance(project).findFile(virtualFile) as? KtFile ?: return
        val factory = KtPsiFactory(project)

        WriteCommandAction.runWriteCommandAction(project, "Edit i18n Value", null, {
            val localeProps = ktFile.declarations
                .filterIsInstance<KtProperty>()
                .filter { prop ->
                    prop.annotationEntries.any { it.shortName?.identifier == "LyricistStrings" }
                }

            val targetProp = localeProps.firstOrNull { prop ->
                extractLocaleTag(prop) == locale.tag
            } ?: return@runWriteCommandAction

            val rootCall = targetProp.initializer as? KtCallExpression ?: return@runWriteCommandAction
            val groupPath = key.fullPath.split(".").dropLast(1)
            val target    = navigateToCallExpr(rootCall, groupPath) ?: return@runWriteCommandAction
            val argList   = target.valueArgumentList ?: return@runWriteCommandAction

            val arg = argList.arguments.firstOrNull { it.getArgumentName()?.asName?.identifier == key.name }
                ?: return@runWriteCommandAction

            val escaped = displayValue.escapeForKotlinString()

            // Build replacement expression
            val newExprSrc = if (key.isLambda) {
                val params = key.lambdaParams.joinToString(", ")
                "{ $params -> \"$escaped\" }"
            } else {
                "\"$escaped\""
            }

            val newExpr = factory.createExpression(newExprSrc)
            arg.getArgumentExpression()?.replace(newExpr)

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