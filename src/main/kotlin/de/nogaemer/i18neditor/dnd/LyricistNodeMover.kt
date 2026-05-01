package de.nogaemer.i18neditor.writer

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import de.nogaemer.i18neditor.model.I18nGroup
import de.nogaemer.i18neditor.model.I18nKey
import de.nogaemer.i18neditor.util.navigateToCallExpr
import org.jetbrains.kotlin.psi.*

class LyricistNodeMover(private val project: Project) {

    /**
     * Move [key] into [targetGroup], inserting before [beforeKey] (or at end if null).
     * Works for same-group reorder AND cross-group moves.
     */
    fun moveKey(
        virtualFile: VirtualFile,
        key: I18nKey,
        targetGroup: I18nGroup,
        beforeKey: I18nKey? = null
    ) {
        val ktFile = PsiManager.getInstance(project).findFile(virtualFile) as? KtFile ?: return
        val factory = KtPsiFactory(project)

        WriteCommandAction.runWriteCommandAction(project, "Move Key", null, {
            val dataClasses = ktFile.declarations.filterIsInstance<KtClass>()
                .filter { it.isData() }.associateBy { it.name ?: "" }

            val sourceClass = dataClasses[key.groupClass] ?: return@runWriteCommandAction
            val targetClass = dataClasses[targetGroup.className] ?: return@runWriteCommandAction

            val sourceParam = sourceClass.primaryConstructor
                ?.valueParameters?.firstOrNull { it.name == key.name }
                ?: return@runWriteCommandAction
            val paramText = sourceParam.text

            // ── 1. Remove from source data class ─────────────────────────
            rebuildParamList(sourceClass, factory) { params ->
                params.filter { it.name != key.name }
            }

            // ── 2. Insert into target data class ─────────────────────────
            rebuildParamList(targetClass, factory) { params ->
                val newParam = factory.createParameter(paramText)
                if (beforeKey == null) {
                    params + newParam
                } else {
                    val idx = params.indexOfFirst { it.name == beforeKey.name }
                        .takeIf { it >= 0 } ?: params.size
                    params.toMutableList().also { it.add(idx, newParam) }
                }
            }

            // ── 3. Move argument in every locale val ──────────────────────
            val localeProps = ktFile.declarations.filterIsInstance<KtProperty>()
                .filter { p -> p.annotationEntries.any { it.shortName?.identifier == "LyricistStrings" } }

            val sourceGroupPath = key.fullPath.split(".").dropLast(1)
            val targetGroupPath = targetGroup.fieldPath
            val sameGroup = sourceGroupPath == targetGroupPath

            for (prop in localeProps) {
                val rootCall = prop.initializer as? KtCallExpression ?: continue

                // Always collect value text BEFORE any mutation
                val sourceCall = navigateToCallExpr(rootCall, sourceGroupPath) ?: continue
                val sourceArg = sourceCall.valueArgumentList?.arguments
                    ?.firstOrNull { it.getArgumentName()?.asName?.identifier == key.name }
                    ?: continue
                val argValueText = sourceArg.getArgumentExpression()?.text ?: ""

                if (sameGroup) {
                    // Same group reorder — single atomic rebuild, no stale refs
                    rebuildArgList(sourceCall, factory) { args ->
                        val filtered = args.filter {
                            it.getArgumentName()?.asName?.identifier != key.name
                        }.toMutableList()
                        val newArg = makeNamedArg(factory, key.name, argValueText)
                        val idx = if (beforeKey == null) filtered.size
                        else filtered.indexOfFirst {
                            it.getArgumentName()?.asName?.identifier == beforeKey.name
                        }.takeIf { it >= 0 } ?: filtered.size
                        filtered.also { it.add(idx, newArg) }
                    }
                } else {
                    // Cross-group: remove first, then re-walk fresh PSI for target
                    rebuildArgList(sourceCall, factory) { args ->
                        args.filter { it.getArgumentName()?.asName?.identifier != key.name }
                    }
                    val freshRoot = prop.initializer as? KtCallExpression ?: continue
                    val targetCall = navigateToCallExpr(freshRoot, targetGroupPath) ?: continue
                    rebuildArgList(targetCall, factory) { args ->
                        val newArg = makeNamedArg(factory, key.name, argValueText)
                        val idx = if (beforeKey == null) args.size
                        else args.indexOfFirst {
                            it.getArgumentName()?.asName?.identifier == beforeKey.name
                        }.takeIf { it >= 0 } ?: args.size
                        args.toMutableList().also { it.add(idx, newArg) }
                    }
                }
            }
        }, ktFile)
    }

    /**
     * Reorder [group] within its parent, inserting before [beforeGroup] (or at end).
     * Only supports reordering at the same nesting level.
     */
    fun moveGroup(
        virtualFile: VirtualFile,
        group: I18nGroup,
        targetParent: I18nGroup?,   // null = root level
        beforeGroup: I18nGroup?
    ) {
        val ktFile = PsiManager.getInstance(project).findFile(virtualFile) as? KtFile ?: return
        val factory = KtPsiFactory(project)

        WriteCommandAction.runWriteCommandAction(project, "Move Group", null, {
            val groupParamName  = group.fieldPath.last()
            val beforeParamName = beforeGroup?.fieldPath?.last()

            // ── Resolve source parent class ───────────────────────────────
            val sourceParentClass = ktFile.declarations.filterIsInstance<KtClass>()
                .filter { it.isData() }
                .firstOrNull { cls ->
                    cls.primaryConstructor?.valueParameters?.any { p ->
                        p.name == groupParamName && p.typeReference?.text?.trim() == group.className
                    } == true
                } ?: return@runWriteCommandAction

            // ── Resolve target parent class ───────────────────────────────
            val targetParentClass: KtClass = if (targetParent != null) {
                ktFile.declarations.filterIsInstance<KtClass>()
                    .firstOrNull { it.name == targetParent.className }
                    ?: return@runWriteCommandAction
            } else {
                // Root class = type of the first @LyricistStrings val
                val rootClassName = ktFile.declarations.filterIsInstance<KtProperty>()
                    .firstOrNull { p -> p.annotationEntries.any { it.shortName?.identifier == "LyricistStrings" } }
                    ?.let { (it.initializer as? KtCallExpression)?.calleeExpression?.text }
                ktFile.declarations.filterIsInstance<KtClass>()
                    .firstOrNull { it.name == rootClassName }
                    ?: return@runWriteCommandAction
            }

            val sameParent = sourceParentClass.name == targetParentClass.name

            // Grab param text before any mutation
            val paramText = sourceParentClass.primaryConstructor
                ?.valueParameters?.firstOrNull { it.name == groupParamName }?.text
                ?: return@runWriteCommandAction

            // ── 1. Update data class param lists ──────────────────────────
            if (sameParent) {
                rebuildParamList(sourceParentClass, factory) { params ->
                    val moving = params.firstOrNull { it.name == groupParamName }
                        ?: return@rebuildParamList params
                    val rest = params.filter { it.name != groupParamName }.toMutableList()
                    val idx = if (beforeParamName != null)
                        rest.indexOfFirst { it.name == beforeParamName }.takeIf { it >= 0 } ?: rest.size
                    else rest.size
                    rest.also { it.add(idx, moving) }
                }
            } else {
                // Remove from source
                rebuildParamList(sourceParentClass, factory) { params ->
                    params.filter { it.name != groupParamName }
                }
                // Insert into target
                rebuildParamList(targetParentClass, factory) { params ->
                    val newParam = factory.createParameter(paramText)
                    if (beforeParamName == null) {
                        params + newParam
                    } else {
                        val idx = params.indexOfFirst { it.name == beforeParamName }
                            .takeIf { it >= 0 } ?: params.size
                        params.toMutableList().also { it.add(idx, newParam) }
                    }
                }
            }

            // ── 2. Update every locale val's call tree ────────────────────
            val localeProps = ktFile.declarations.filterIsInstance<KtProperty>()
                .filter { p -> p.annotationEntries.any { it.shortName?.identifier == "LyricistStrings" } }

            val sourceParentPath = group.fieldPath.dropLast(1)
            val targetParentPath = targetParent?.fieldPath ?: emptyList()

            for (prop in localeProps) {
                val rootCall = prop.initializer as? KtCallExpression ?: continue
                val sourceParentCall = navigateToCallExpr(rootCall, sourceParentPath) ?: continue

                if (sameParent) {
                    rebuildArgList(sourceParentCall, factory) { args ->
                        val argText = args.firstOrNull {
                            it.getArgumentName()?.asName?.identifier == groupParamName
                        }?.text ?: return@rebuildArgList args
                        val rest = args.filter {
                            it.getArgumentName()?.asName?.identifier != groupParamName
                        }.toMutableList()
                        val newArg = factory.createCallArguments("($argText)").arguments.first()
                        val idx = if (beforeParamName != null)
                            rest.indexOfFirst { it.getArgumentName()?.asName?.identifier == beforeParamName }
                                .takeIf { it >= 0 } ?: rest.size
                        else rest.size
                        rest.also { it.add(idx, newArg) }
                    }
                } else {
                    // Collect arg text BEFORE removing it
                    val argText = sourceParentCall.valueArgumentList?.arguments
                        ?.firstOrNull { it.getArgumentName()?.asName?.identifier == groupParamName }
                        ?.text ?: continue

                    rebuildArgList(sourceParentCall, factory) { args ->
                        args.filter { it.getArgumentName()?.asName?.identifier != groupParamName }
                    }

                    // Re-walk fresh PSI after mutation
                    val freshRoot = prop.initializer as? KtCallExpression ?: continue
                    val targetParentCall = navigateToCallExpr(freshRoot, targetParentPath) ?: continue

                    rebuildArgList(targetParentCall, factory) { args ->
                        val newArg = factory.createCallArguments("($argText)").arguments.first()
                        if (beforeParamName == null) {
                            args + newArg
                        } else {
                            val idx = args.indexOfFirst {
                                it.getArgumentName()?.asName?.identifier == beforeParamName
                            }.takeIf { it >= 0 } ?: args.size
                            args.toMutableList().also { it.add(idx, newArg) }
                        }
                    }
                }
            }
        }, ktFile)
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun rebuildParamList(
        ktClass: KtClass,
        factory: KtPsiFactory,
        transform: (List<KtParameter>) -> List<KtParameter>
    ) {
        val ctor = ktClass.primaryConstructor ?: return
        val paramList = ctor.valueParameterList ?: return
        val current = paramList.parameters.toList()
        val reordered = transform(current)
        val newSrc = reordered.joinToString(",\n    ") { it.text }
        val newParamList = factory.createParameterList("(\n    $newSrc\n)")
        paramList.replace(newParamList)
    }

    private fun rebuildArgList(
        callExpr: KtCallExpression,
        factory: KtPsiFactory,
        transform: (List<KtValueArgument>) -> List<KtValueArgument>
    ) {
        val argList = callExpr.valueArgumentList ?: return
        val current = argList.arguments.toList()
        val reordered = transform(current)
        val newSrc = reordered.joinToString(",\n    ") { it.text }
        val newArgList = factory.createCallArguments("(\n    $newSrc\n)")
        argList.replace(newArgList)
    }

    private fun makeNamedArg(factory: KtPsiFactory, name: String, valueText: String): KtValueArgument =
        factory.createCallArguments("($name = $valueText)").arguments.first()
}