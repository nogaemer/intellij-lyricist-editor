package de.nogaemer.i18neditor.writer

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiParserFacade
import de.nogaemer.i18neditor.model.I18nGroup
import de.nogaemer.i18neditor.util.escapeForKotlinString
import de.nogaemer.i18neditor.util.navigateToCallExpr
import de.nogaemer.i18neditor.util.rawContent
import org.jetbrains.kotlin.psi.*

class LyricistKeyAdder(private val project: Project) {

    fun addKey(
        virtualFile: VirtualFile,
        group: I18nGroup,
        keyName: String,
        valuesByLocaleTag: Map<String, String>,
        isLambda: Boolean = false,
        lambdaParams: List<String> = emptyList(),
        lambdaReturnType: String = "String"
    ) {
        val ktFile = PsiManager.getInstance(project).findFile(virtualFile) as? KtFile ?: return
        val factory = KtPsiFactory(project)
        val ws = PsiParserFacade.getInstance(project).createWhiteSpaceFromText("\n    ")

        WriteCommandAction.runWriteCommandAction(project, "Add i18n Key", null, {

            // ── 1. Build the parameter type string ────────────────────────────
            val typeStr = if (isLambda) {
                val types = lambdaParams.joinToString(", ") { it.substringAfter(":").trim().ifEmpty { "String" } }
                "($types) -> $lambdaReturnType"
            } else "String"


            // ── 2. Insert parameter into the data class ───────────────────────
            val dataClass = ktFile.declarations
                .filterIsInstance<KtClass>()
                .firstOrNull { it.name == group.className }
                ?: return@runWriteCommandAction

            val paramList = dataClass.primaryConstructor?.valueParameterList
                ?: return@runWriteCommandAction

            val newParam = factory.createParameter("val $keyName: $typeStr")
            val lastParam = paramList.parameters.lastOrNull()

            if (lastParam != null) {
                // Insert newline before the new param so it's on its own line
                paramList.addParameterAfter(newParam, lastParam)
                val inserted = paramList.parameters.last()
                paramList.node.addChild(ws.node, inserted.node)
            } else {
                paramList.addParameter(newParam)
            }

            // ── 3. Insert named argument in every locale val ──────────────────
            val localeProps = ktFile.declarations
                .filterIsInstance<KtProperty>()
                .filter { prop ->
                    prop.annotationEntries.any { it.shortName?.identifier == "LyricistStrings" }
                }

            for (prop in localeProps) {
                val localeTag = extractLocaleTag(prop) ?: continue
                val rawValue = valuesByLocaleTag[localeTag] ?: ""
                val escaped = rawValue.escapeForKotlinString()

                val rootCall = prop.initializer as? KtCallExpression ?: continue
                val target = navigateToCallExpr(rootCall, group.fieldPath) ?: continue
                val argList = target.valueArgumentList ?: continue

                // Build the argument expression depending on type
                val argValueSrc = if (isLambda) {
                    val names = lambdaParams.joinToString(", ") { it.substringBefore(":").trim() }
                    "{ $names -> \"$escaped\" }"
                } else "\"$escaped\""

                val dummy = factory.createExpression(
                    "dummy($keyName = $argValueSrc)"
                ) as? KtCallExpression ?: continue
                val newArg = dummy.valueArguments.firstOrNull() ?: continue

                val lastArg = argList.arguments.lastOrNull()
                val wsArg = PsiParserFacade.getInstance(project)
                    .createWhiteSpaceFromText("\n        ")

                if (lastArg != null) {
                    argList.addArgumentAfter(newArg, lastArg)
                    val inserted = argList.arguments.last()
                    argList.node.addChild(wsArg.node, inserted.node)
                } else {
                    argList.addArgument(newArg)
                }
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
        return (tagArg?.getArgumentExpression() as? KtStringTemplateExpression)?.rawContent
    }
}