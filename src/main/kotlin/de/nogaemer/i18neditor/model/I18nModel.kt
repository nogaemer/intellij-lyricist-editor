package de.nogaemer.i18neditor.model

// ── Locale ────────────────────────────────────────────────────────────────────

/**
 * One language discovered via a @LyricistStrings-annotated top-level val.
 *
 * @param tag          The IETF language tag, e.g. "en", "de"
 * @param propertyName The Kotlin property name, e.g. "EnStrings"
 * @param isDefault    True when default = true is set on the annotation
 */
data class I18nLocale(
    val tag: String,
    val propertyName: String,
    val isDefault: Boolean
)

// ── Key ───────────────────────────────────────────────────────────────────────

/**
 * One string key within a data class group.
 *
 * @param name       The parameter name, e.g. "appName"
 * @param path       Ordered list of field names from the root Strings(...) constructor
 *                   down to (but NOT including) this key, e.g. ["common"].
 *                   Nested groups look like ["gameLobbySettings", "gameRoundSettingsStrings"].
 * @param groupClass The data class that declares this key, e.g. "CommonStrings"
 * @param isLambda   True if the parameter type is a function type, e.g. (String) -> String
 * @param isMap      True if the parameter type is a Map (e.g. Map<Locales, LanguageEntry>)
 */
data class I18nKey(
    val name:             String,
    val fullPath:         String,
    val groupClass:       String,
    val isLambda:         Boolean = false,
    val isMap:            Boolean = false,
    val lambdaParams:     List<String> = emptyList(), // e.g. ["wins: Int", "total: Int"]
    val lambdaReturnType: String = "String"
) {
    val isEditable: Boolean get() = !isMap
}

// ── Group ─────────────────────────────────────────────────────────────────────

/**
 * Corresponds to one data class that holds string fields.
 *
 * @param className The Kotlin class name, e.g. "CommonStrings"
 * @param fieldPath The path of field names from root to reach this group,
 *                  e.g. ["common"] — mirrors [I18nKey.path] for its children
 * @param keys      All keys declared inside this group's constructor
 */
data class I18nGroup(
    val className: String,
    val fieldPath: List<String>,
    val keys: List<I18nKey>
)

// ── File Model ────────────────────────────────────────────────────────────────

/**
 * The complete parsed state of one Lyricist strings file.
 *
 * @param locales      All discovered locales
 * @param groups       All groups in declaration order
 * @param psiFilePath  Virtual file path; used to re-resolve the PsiFile when needed
 */
data class I18nFileModel(
    val locales: List<I18nLocale>,
    val groups: List<I18nGroup>,
    val psiFilePath: String
) {
    val defaultLocale: I18nLocale? get() = locales.firstOrNull { it.isDefault }
    val allKeys: List<I18nKey>    get() = groups.flatMap { it.keys }
    val editableKeys: List<I18nKey> get() = allKeys.filter { it.isEditable }
}

// ── Values ────────────────────────────────────────────────────────────────────

/**
 * The resolved string value for one key in one locale.
 * [value] is null for lambda/map keys that cannot be represented as plain strings.
 */
data class I18nValue(
    val key: I18nKey,
    val locale: I18nLocale,
    val value: String?
)

/**
 * All values for every key × locale pair; this is the flat table used by the UI.
 */
class I18nTable(val model: I18nFileModel) {

    // Raw PSI text per key per locale — populated by the parser
    private val values = mutableMapOf<String, MutableMap<String, String?>>()

    fun setValue(key: I18nKey, localeTag: String, raw: String?) {
        values.getOrPut(key.fullPath) { mutableMapOf() }[localeTag] = raw
    }

    /**
     * Returns the display value for a cell:
     * - Lambda keys: strips `{ params -> "` prefix and `" }` suffix so the cell
     *   shows only the template body, e.g. `Go $teamName` instead of `{ teamName -> "Go $teamName" }`
     * - Plain keys: returns the raw string value
     */
    fun getValue(key: I18nKey, locale: I18nLocale): String? {
        val raw = values[key.fullPath]?.get(locale.tag) ?: return null
        if (!key.isLambda) return raw
        return stripLambdaWrapper(raw)
    }

    /** Raw value as it appears in source — used by the writer. */
    fun getRawValue(key: I18nKey, locale: I18nLocale): String? =
        values[key.fullPath]?.get(locale.tag)

    private fun stripLambdaWrapper(raw: String): String {
        // Handles: { p1, p2 -> "body" }  or  { p1 -> "body" }
        val s = raw.trim()
        val arrowIdx = s.indexOf("->")
        if (arrowIdx < 0) return s
        val afterArrow = s.substring(arrowIdx + 2).trim()
        // Strip surrounding quotes and closing brace
        return afterArrow
            .removeSuffix("}")
            .trim()
            .removeSurrounding("\"")
    }
}