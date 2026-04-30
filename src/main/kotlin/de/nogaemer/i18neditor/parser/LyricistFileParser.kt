package de.nogaemer.i18neditor.parser

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import de.nogaemer.i18neditor.model.*
import de.nogaemer.i18neditor.util.rawContent
import org.jetbrains.kotlin.psi.*

/**
 * Parses a single Lyricist-style Kotlin strings file into an [I18nTable].
 *
 * Assumptions that match the target file format:
 * - All data classes are declared in the same file as the locale vals
 * - Locale vals are annotated with @LyricistStrings(languageTag = "xx")
 * - Nested groups are expressed as constructor arguments whose type
 *   is itself a data class declared in the same file
 * - Lambda fields have a function type: (A, B, ...) -> R
 * - Map fields use Map<K, V> type syntax
 */
class LyricistFileParser(private val project: Project) {

    // ── Public API ────────────────────────────────────────────────────────────

    fun parse(virtualFile: VirtualFile): I18nTable? {
        val psiFile = PsiManager.getInstance(project).findFile(virtualFile) as? KtFile
            ?: return null
        return parse(psiFile)
    }

    fun parse(ktFile: KtFile): I18nTable? {
        // 1. Collect every data class declared in the file, keyed by simple name
        val dataClasses: Map<String, KtClass> = ktFile.declarations
            .filterIsInstance<KtClass>()
            .filter { it.isData() }
            .associateBy { it.name ?: "" }
            .filterKeys { it.isNotEmpty() }

        if (dataClasses.isEmpty()) return null

        // 2. Find all locale properties (@LyricistStrings-annotated top-level vals)
        val localeProps: List<KtProperty> = ktFile.declarations
            .filterIsInstance<KtProperty>()
            .filter { prop ->
                prop.annotationEntries.any { ann ->
                    ann.shortName?.asString() == "LyricistStrings"
                }
            }

        if (localeProps.isEmpty()) return null

        // 3. Extract locale metadata from annotations
        val locales: List<I18nLocale> = localeProps.mapNotNull { prop ->
            extractLocale(prop)
        }
        if (locales.isEmpty()) return null

        // 4. Find the root data class (type of the first locale property's initializer)
        val rootCallExpr = localeProps.first().initializer as? KtCallExpression
            ?: return null
        val rootClassName = rootCallExpr.calleeExpression?.text?.trim()
            ?: return null
        val rootClass = dataClasses[rootClassName]
            ?: return null

        // 5. Build the group/key tree by walking data class declarations
        val groups = mutableListOf<I18nGroup>()
        val defaultLocaleProp = localeProps.firstOrNull { p ->
            p.annotationEntries.any {
                it.valueArguments.any { a -> a.getArgumentExpression()?.text == "true" }
            }
        } ?: localeProps.first()   // fall back to first locale if none marked default

        val defaultCallExpr = defaultLocaleProp.initializer as? KtCallExpression

        collectGroups(
            ktClass          = rootClass,
            dataClasses      = dataClasses,
            pathSoFar        = emptyList(),
            result           = groups,
            defaultCallExpr  = defaultCallExpr
        )

        // 6. Walk each locale val constructor to harvest string values
        //    values[fullKeyPath][localeTag] = rawStringContent
        val values = mutableMapOf<String, MutableMap<String, String?>>()
        for ((prop, locale) in localeProps.zip(locales)) {
            val callExpr = prop.initializer as? KtCallExpression ?: continue
            collectValues(
                callExpr = callExpr,
                dataClasses = dataClasses,
                pathSoFar = emptyList(),
                locale = locale,
                result = values
            )
        }

        val fileModel = I18nFileModel(
            locales     = locales,
            groups      = groups,
            psiFilePath = ktFile.virtualFile.path
        )
        val table = I18nTable(model = fileModel)

        // Flatten all keys from all groups to populate values
        val allKeys = groups.flatMap { it.keys }.associateBy { it.fullPath }
        for ((fullPath, localeMap) in values) {
            val key = allKeys[fullPath] ?: continue
            for ((localeTag, raw) in localeMap) {
                table.setValue(key, localeTag, raw)
            }
        }
        return table
    }

    // ── Locale extraction ─────────────────────────────────────────────────────

    private fun extractLocale(prop: KtProperty): I18nLocale? {
        val ann = prop.annotationEntries.firstOrNull {
            it.shortName?.identifier == "LyricistStrings"
        } ?: return null

        val tagArg = ann.valueArguments.firstOrNull {
            it.getArgumentName()?.asName?.identifier == "languageTag"
        } ?: ann.valueArguments.firstOrNull()

        val tag = (tagArg?.getArgumentExpression() as? KtStringTemplateExpression)?.rawContent
            ?: return null

        val defaultArg = ann.valueArguments.firstOrNull {
            it.getArgumentName()?.asName?.identifier == "default"
        }
        val isDefault = defaultArg?.getArgumentExpression()?.text == "true"

        return I18nLocale(
            tag = tag,
            propertyName = prop.name ?: return null,
            isDefault = isDefault
        )
    }

    // ── Structure pass ────────────────────────────────────────────────────────

    /**
     * Recursively walks data class constructors, building [I18nGroup] entries.
     * A parameter whose type is another data class in this file is a nested group (recurse).
     * Everything else becomes a leaf [I18nKey].
     */
    private fun collectGroups(
        ktClass: KtClass,
        dataClasses: Map<String, KtClass>,
        pathSoFar: List<String>,
        result: MutableList<I18nGroup>,
        defaultCallExpr: KtCallExpression?
    ) {
        val params = ktClass.primaryConstructor?.valueParameters ?: return
        val leafKeys = mutableListOf<I18nKey>()
        // Collect nested groups as (paramName, nestedClass, nestedCall) to recurse AFTER
        val nestedGroups = mutableListOf<Triple<String, KtClass, KtCallExpression?>>()

        val defaultArgs: Map<String, KtExpression?> = defaultCallExpr
            ?.valueArguments
            ?.associate { arg ->
                (arg.getArgumentName()?.asName?.identifier ?: "") to arg.getArgumentExpression()
            } ?: emptyMap()

        for (param in params) {
            val paramName = param.name ?: continue
            val typeText = param.typeReference?.text?.trim()?.removeSuffix("?") ?: continue

            when {
                dataClasses.containsKey(typeText) -> {
                    val nestedCall = defaultArgs[paramName] as? KtCallExpression
                    nestedGroups += Triple(paramName, dataClasses[typeText]!!, nestedCall)
                }

                typeText.contains("->") -> {
                    val defaultArg = defaultArgs[paramName]
                    val lambdaParams = extractLambdaParams(param.typeReference, defaultArg)
                    leafKeys += I18nKey(
                        name = paramName,
                        fullPath = (pathSoFar + paramName).joinToString("."),
                        groupClass = ktClass.name ?: "",
                        isLambda = true,
                        lambdaParams = lambdaParams
                    )
                }

                typeText.startsWith("Map<") || typeText.startsWith("Map ") -> {
                    leafKeys += I18nKey(
                        name = paramName,
                        fullPath = (pathSoFar + paramName).joinToString("."),
                        groupClass = ktClass.name ?: "",
                        isMap = true
                    )
                }

                else -> {
                    leafKeys += I18nKey(
                        name = paramName,
                        fullPath = (pathSoFar + paramName).joinToString("."),
                        groupClass = ktClass.name ?: ""
                    )
                }
            }
        }

        // Emit this group's own keys FIRST, before any nested groups
        if (leafKeys.isNotEmpty()) {
            result += I18nGroup(
                className = ktClass.name ?: "",
                fieldPath = pathSoFar,
                keys = leafKeys
            )
        }

        // Then recurse into nested groups in declaration order
        for ((paramName, nestedClass, nestedCall) in nestedGroups) {
            collectGroups(
                ktClass = nestedClass,
                dataClasses = dataClasses,
                pathSoFar = pathSoFar + paramName,
                result = result,
                defaultCallExpr = nestedCall
            )
        }
    }

    // ── Value pass ────────────────────────────────────────────────────────────

    /**
     * Recursively walks a locale val's constructor call tree, filling [result]
     * with [fullKeyPath → localeTag → rawStringContent] entries.
     */
    private fun collectValues(
        callExpr: KtCallExpression,
        dataClasses: Map<String, KtClass>,
        pathSoFar: List<String>,
        locale: I18nLocale,
        result: MutableMap<String, MutableMap<String, String?>>
    ) {
        for (arg in callExpr.valueArguments) {
            val argName = arg.getArgumentName()?.asName?.identifier ?: continue
            val argExpr = arg.getArgumentExpression() ?: continue
            val newPath = pathSoFar + argName

            when (argExpr) {
                // Nested constructor call: recurse
                is KtCallExpression -> collectValues(argExpr, dataClasses, newPath, locale, result)

                // Plain string literal
                is KtStringTemplateExpression -> {
                    result.bucket(newPath.joinToString("."))[locale.tag] = argExpr.rawContent
                }

                // Lambda: { param -> "template" }
                is KtLambdaExpression -> {
                    val body = extractLambdaStringBody(argExpr)
                    result.bucket(newPath.joinToString("."))[locale.tag] = body
                }

                // Anything else (Map literal, constant expression, etc.) → null
                else -> {
                    result.bucket(newPath.joinToString("."))[locale.tag] = null
                }
            }
        }
    }

    /**
     * Extracts the raw string body from a simple lambda of the form:
     *   { param -> "some $string template" }
     *
     * Returns null if the body is too complex to represent as a single string.
     */
    private fun extractLambdaStringBody(lambda: KtLambdaExpression): String? {
        val statements = lambda.functionLiteral.bodyExpression?.statements ?: return null

        // Only handle single-statement lambdas whose body is a string template
        if (statements.size != 1) return null
        val stmt = statements[0]

        return when (stmt) {
            is KtStringTemplateExpression -> stmt.rawContent
            // e.g. "${minutes}m ${seconds}s" is a string template even with block entries
            else -> null
        }
    }

    /**
     * Extracts parameter names from a lambda argument expression.
     * e.g.  { teamName, score -> "Go $teamName" }  →  ["teamName", "score"]
     * Falls back to ["p0", "p1", ...] based on the type arity if the lambda body is unavailable.
     */
    private fun extractLambdaParams(
        typeRef: org.jetbrains.kotlin.psi.KtTypeReference?,
        defaultLocaleArg: org.jetbrains.kotlin.psi.KtExpression?
    ): List<String> {
        // Try to read param names from the lambda literal in the default locale value
        if (defaultLocaleArg is org.jetbrains.kotlin.psi.KtLambdaExpression) {
            val names = defaultLocaleArg.valueParameters.mapNotNull { it.name }
            if (names.isNotEmpty()) return names
        }
        // Fall back: count functional type params from the type reference text
        // e.g. "(String, String) -> String" → 2 params → ["p0", "p1"]
        val typeText = typeRef?.text ?: return emptyList()
        val arrowIdx = typeText.indexOf("->")
        if (arrowIdx < 0) return emptyList()
        val paramSection = typeText.substring(1, arrowIdx).trim().trimEnd()
        val count = if (paramSection.isBlank()) 0
        else paramSection.split(",").size
        return (0 until count).map { "p$it" }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Gets or creates the inner map for a key path. */
    private fun MutableMap<String, MutableMap<String, String?>>.bucket(key: String) =
        getOrPut(key) { mutableMapOf() }
}

// ── Extensions ────────────────────────────────────────────────────────────────

/**
 * Returns the raw source content of this string template, minus the surrounding quotes.
 * Preserves escape sequences (e.g. `\n`) and template expressions (e.g. `$teamName`)
 * as written in source, making round-trip editing lossless.
 *
 * Examples:
 *   "Unspeakable"            → Unspeakable
 *   "Host  a\nGame"          → Host  a\nGame
 *   "$teamName wins"         → $teamName wins
 *   "${minutes}m ${seconds}s" → ${minutes}m ${seconds}s
 */
val KtStringTemplateExpression.rawContent: String
    get() = text.let { src ->
        when {
            src.startsWith("\"\"\"") -> src.removeSurrounding("\"\"\"")
            src.startsWith("\"") -> src.removeSurrounding("\"")
            else -> src
        }
    }