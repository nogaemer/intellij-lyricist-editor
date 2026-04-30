package de.nogaemer.i18neditor.util

import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtStringTemplateExpression

/** Strips surrounding quotes, preserving template expressions like $teamName verbatim. */
val KtStringTemplateExpression.rawContent: String
    get() = text.let { src ->
        when {
            src.startsWith("\"\"\"") -> src.removeSurrounding("\"\"\"")
            src.startsWith("\"")     -> src.removeSurrounding("\"")
            else                     -> src
        }
    }

/**
 * Escapes characters that would break a Kotlin string literal when inserted as source text.
 * Intentionally does NOT escape `$` — template expressions like `$teamName` are preserved.
 */
fun String.escapeForKotlinString(): String =
    replace("\\", "\\\\").replace("\"", "\\\"")

/**
 * Walks a nested chain of named constructor call arguments,
 * returning the [KtCallExpression] at the end of [path].
 *
 * e.g. path = ["gameLobbySettings", "gameRoundSettingsStrings"]
 * starts at the root Strings(...) call and returns the
 * GameRoundSettingsStrings(...) call expression.
 */
fun navigateToCallExpr(root: KtCallExpression, path: List<String>): KtCallExpression? {
    var current = root
    for (segment in path) {
        val arg = current.valueArguments.firstOrNull {
            it.getArgumentName()?.asName?.identifier == segment
        } ?: return null
        current = arg.getArgumentExpression() as? KtCallExpression ?: return null
    }
    return current
}