package de.nogaemer.i18neditor.writer

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import de.nogaemer.i18neditor.model.I18nGroup
import de.nogaemer.i18neditor.util.navigateToCallExpr
import org.jetbrains.kotlin.psi.*

class LyricistGroupDeleter(private val project: Project) {

    fun deleteGroup(virtualFile: VirtualFile, group: I18nGroup) {
        val ktFile = PsiManager.getInstance(project).findFile(virtualFile) as? KtFile ?: return
        val factory = KtPsiFactory(project)

        WriteCommandAction.runWriteCommandAction(project, "Delete Group", null, {
            val dataClasses = ktFile.declarations
                .filterIsInstance<KtClass>()
                .filter { it.isData() }
                .associateBy { it.name ?: "" }

            val localeProps = ktFile.declarations
                .filterIsInstance<KtProperty>()
                .filter { p ->
                    p.annotationEntries.any { it.shortName?.identifier == "LyricistStrings" }
                }

            // Collect all data class names reachable from this group (depth-first)
            // so we can delete them all after the structural changes
            val classesToDelete = mutableListOf<String>()
            collectNestedClassNames(group.className, dataClasses, classesToDelete)

            val groupParamName = group.fieldPath.last()
            val parentPath = group.fieldPath.dropLast(1)

            // ── 1. Remove param from parent data class ────────────────
            val parentClass = ktFile.declarations.filterIsInstance<KtClass>()
                .filter { it.isData() }
                .firstOrNull { cls ->
                    cls.primaryConstructor?.valueParameters?.any { p ->
                        p.name == groupParamName && p.typeReference?.text?.trim() == group.className
                    } == true
                }

            parentClass?.let { cls ->
                val paramList = cls.primaryConstructor?.valueParameterList ?: return@let
                val toRemove = paramList.parameters.firstOrNull { it.name == groupParamName }
                toRemove?.let { param ->
                    // Remove trailing comma or preceding comma
                    val nextSibling = param.nextSibling
                    val prevSibling = param.prevSibling
                    if (nextSibling?.text?.trim() == ",") nextSibling.delete()
                    else if (prevSibling?.text?.trim() == ",") prevSibling.delete()
                    param.delete()
                }
            }

            // ── 2. Remove arg from every locale val ───────────────────
            for (prop in localeProps) {
                val rootCall = prop.initializer as? KtCallExpression ?: continue
                val parentCall = navigateToCallExpr(rootCall, parentPath) ?: continue

                val argList = parentCall.valueArgumentList ?: continue
                val toRemove = argList.arguments.firstOrNull {
                    it.getArgumentName()?.asName?.identifier == groupParamName
                } ?: continue

                val nextSibling = toRemove.nextSibling
                val prevSibling = toRemove.prevSibling
                if (nextSibling?.text?.trim() == ",") nextSibling.delete()
                else if (prevSibling?.text?.trim() == ",") prevSibling.delete()
                toRemove.delete()
            }

            // ── 3. Delete all collected data classes ──────────────────
            for (className in classesToDelete) {
                ktFile.declarations.filterIsInstance<KtClass>()
                    .firstOrNull { it.name == className }
                    ?.delete()
            }

        }, ktFile)
    }

    /**
     * Recursively collects [className] and all data class names
     * referenced as constructor params inside it.
     */
    private fun collectNestedClassNames(
        className: String,
        dataClasses: Map<String, KtClass>,
        result: MutableList<String>
    ) {
        if (result.contains(className)) return
        result += className
        val ktClass = dataClasses[className] ?: return
        ktClass.primaryConstructor?.valueParameters?.forEach { param ->
            val typeName = param.typeReference?.text?.trim() ?: return@forEach
            if (dataClasses.containsKey(typeName)) {
                collectNestedClassNames(typeName, dataClasses, result)
            }
        }
    }
}