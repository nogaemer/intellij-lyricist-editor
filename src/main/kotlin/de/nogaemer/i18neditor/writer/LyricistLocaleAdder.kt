package de.nogaemer.i18neditor.writer

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiParserFacade
import org.jetbrains.kotlin.psi.*

class LyricistLocaleAdder(private val project: Project) {

    fun addLocale(
        virtualFile: VirtualFile,
        localeTag:   String,
        valName:     String
    ) {
        val ktFile  = PsiManager.getInstance(project).findFile(virtualFile) as? KtFile ?: return
        val factory = KtPsiFactory(project)

        WriteCommandAction.runWriteCommandAction(project, "Add i18n Locale", null, {

            // ── Find the default locale val to use as template ────────────────
            val defaultProp = ktFile.declarations
                .filterIsInstance<KtProperty>()
                .firstOrNull { prop ->
                    prop.annotationEntries.any {
                        it.shortName?.identifier == "LyricistStrings"
                    }
                } ?: return@runWriteCommandAction

            // ── Determine the root class name from the default val ────────────
            val rootCallName = (defaultProp.initializer as? KtCallExpression)
                ?.calleeExpression?.text?.trim() ?: return@runWriteCommandAction

            // ── Deep-clone the call tree with all string values set to "" ─────
            val blankCall = blankifyCall(
                defaultProp.initializer as KtCallExpression,
                factory
            )

            // ── Build the new property source ─────────────────────────────────
            val newPropSrc = """
                |@LyricistStrings(languageTag = "$localeTag")
                |val $valName: $rootCallName = $blankCall
            """.trimMargin()

            val newProp = factory.createProperty(newPropSrc)
            val nl      = PsiParserFacade.getInstance(project)
                .createWhiteSpaceFromText("\n\n")

            // Insert after the last @LyricistStrings val
            val lastLocaleProp = ktFile.declarations
                .filterIsInstance<KtProperty>()
                .lastOrNull { prop ->
                    prop.annotationEntries.any { it.shortName?.identifier == "LyricistStrings" }
                } ?: return@runWriteCommandAction

            ktFile.addAfter(nl,      lastLocaleProp)
            ktFile.addAfter(newProp, lastLocaleProp)

        }, ktFile)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Recursively clones a KtCallExpression but replaces every string literal
     * with "" and every lambda body string with a blank lambda { params -> "" }.
     */
    private fun blankifyCall(
        call: KtCallExpression,
        factory: KtPsiFactory
    ): String {
        val callee = call.calleeExpression?.text ?: return ""
        val args   = call.valueArguments.joinToString(",\n    ") { arg ->
            val name  = arg.getArgumentName()?.asName?.identifier
            val value = arg.getArgumentExpression()
            val blankValue = when {
                value is KtCallExpression            -> blankifyCall(value, factory)
                value is KtLambdaExpression          -> blankifyLambda(value)
                value is KtStringTemplateExpression  -> "\"\""
                value?.text?.startsWith("{") == true -> blankifyLambdaText(value.text)
                else                                 -> "\"\""
            }
            if (name != null) "$name = $blankValue" else blankValue
        }
        return if (args.isBlank()) "$callee()" else "$callee(\n    $args\n)"
    }

    private fun blankifyLambda(lambda: KtLambdaExpression): String {
        val params = lambda.valueParameters.joinToString(", ") { it.name ?: "_" }
        return "{ $params -> \"\" }"
    }

    private fun blankifyLambdaText(text: String): String {
        val arrowIdx = text.indexOf("->")
        if (arrowIdx < 0) return "{ -> \"\" }"
        val params = text.substring(1, arrowIdx).trim()
        return "{ $params -> \"\" }"
    }
}