package de.nogaemer.i18neditor.writer

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import de.nogaemer.i18neditor.model.I18nKey
import de.nogaemer.i18neditor.util.navigateToCallExpr
import org.jetbrains.kotlin.psi.*

class LyricistKeyConverter(private val project: Project) {

    /**
     * Converts a plain String key to a lambda key, or updates lambda param names.
     * - Changes data class parameter type: String → (String, ...) -> String
     * - Wraps every locale's string value:  "foo" → { p1, p2 -> "foo" }
     * - If already a lambda, rewraps with the new param names.
     */
    fun convertToLambda(
        virtualFile:  VirtualFile,
        key:          I18nKey,
        lambdaParams: List<String>
    ) {
        val ktFile  = PsiManager.getInstance(project).findFile(virtualFile) as? KtFile ?: return
        val factory = KtPsiFactory(project)

        WriteCommandAction.runWriteCommandAction(project, "Convert Key to Lambda", null, {

            // ── 1. Update data class parameter type ───────────────────────────
            val dataClass = ktFile.declarations
                .filterIsInstance<KtClass>()
                .firstOrNull { it.name == key.groupClass }
                ?: return@runWriteCommandAction

            val param = dataClass.primaryConstructor
                ?.valueParameters
                ?.firstOrNull { it.name == key.name }
                ?: return@runWriteCommandAction

            val paramTypes = lambdaParams.joinToString(", ") { "String" }
            val newTypeRef = factory.createType("($paramTypes) -> String")
            param.typeReference?.replace(newTypeRef)

            // ── 2. Wrap / rewrap every locale val's argument ──────────────────
            val localeProps = ktFile.declarations
                .filterIsInstance<KtProperty>()
                .filter { p -> p.annotationEntries.any { it.shortName?.identifier == "LyricistStrings" } }

            val groupPath = key.fullPath.split(".").dropLast(1)
            val paramStr  = lambdaParams.joinToString(", ")

            for (prop in localeProps) {
                val rootCall = prop.initializer as? KtCallExpression ?: continue
                val target   = navigateToCallExpr(rootCall, groupPath) ?: continue
                val argList  = target.valueArgumentList ?: continue
                val arg      = argList.arguments
                    .firstOrNull { it.getArgumentName()?.asName?.identifier == key.name }
                    ?: continue

                val currentExpr = arg.getArgumentExpression() ?: continue

                // Extract the bare string body regardless of whether it's already a lambda
                val body = extractBody(currentExpr.text)

                val newExprSrc = "{ $paramStr -> \"$body\" }"
                val newExpr    = factory.createExpression(newExprSrc)
                currentExpr.replace(newExpr)
            }

        }, ktFile)
    }

    /**
     * Converts a lambda key back to a plain String key.
     * - Changes type: (String) -> String → String
     * - Unwraps every locale val's lambda:  { p -> "foo" } → "foo"
     */
    fun convertToString(
        virtualFile: VirtualFile,
        key:         I18nKey
    ) {
        val ktFile  = PsiManager.getInstance(project).findFile(virtualFile) as? KtFile ?: return
        val factory = KtPsiFactory(project)

        WriteCommandAction.runWriteCommandAction(project, "Convert Key to String", null, {

            // ── 1. Update data class parameter type ───────────────────────────
            val dataClass = ktFile.declarations
                .filterIsInstance<KtClass>()
                .firstOrNull { it.name == key.groupClass }
                ?: return@runWriteCommandAction

            val param = dataClass.primaryConstructor
                ?.valueParameters
                ?.firstOrNull { it.name == key.name }
                ?: return@runWriteCommandAction

            param.typeReference?.replace(factory.createType("String"))

            // ── 2. Unwrap every locale val's argument ─────────────────────────
            val localeProps = ktFile.declarations
                .filterIsInstance<KtProperty>()
                .filter { p -> p.annotationEntries.any { it.shortName?.identifier == "LyricistStrings" } }

            val groupPath = key.fullPath.split(".").dropLast(1)

            for (prop in localeProps) {
                val rootCall = prop.initializer as? KtCallExpression ?: continue
                val target   = navigateToCallExpr(rootCall, groupPath) ?: continue
                val argList  = target.valueArgumentList ?: continue
                val arg      = argList.arguments
                    .firstOrNull { it.getArgumentName()?.asName?.identifier == key.name }
                    ?: continue

                val currentExpr = arg.getArgumentExpression() ?: continue
                val body        = extractBody(currentExpr.text)
                currentExpr.replace(factory.createExpression("\"$body\""))
            }

        }, ktFile)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Strips any lambda wrapper and surrounding quotes to get the raw body string.
     * "foo $bar"          → foo $bar
     * { p -> "foo $bar" } → foo $bar
     */
    private fun extractBody(raw: String): String {
        val s = raw.trim()
        // Lambda form: { params -> "body" }
        val arrowIdx = s.indexOf("->")
        if (arrowIdx >= 0) {
            return s.substring(arrowIdx + 2).trim()
                .removeSuffix("}")
                .trim()
                .removeSurrounding("\"")
        }
        // Plain string
        return s.removeSurrounding("\"")
    }
}