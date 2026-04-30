package de.nogaemer.i18neditor.writer

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiParserFacade
import de.nogaemer.i18neditor.util.navigateToCallExpr
import org.jetbrains.kotlin.psi.*

class LyricistGroupAdder(private val project: Project) {

    fun addGroup(
        virtualFile: VirtualFile,
        className:   String,
        fieldName:   String,
        parentPath:  List<String>
    ) {
        val ktFile  = PsiManager.getInstance(project).findFile(virtualFile) as? KtFile ?: return
        val factory = KtPsiFactory(project)

        WriteCommandAction.runWriteCommandAction(project, "Add i18n Group", null, {

            // ── 1. Create new data class after the last existing data class ────
            val lastDataClass = ktFile.declarations
                .filterIsInstance<KtClass>()
                .filter { it.isData() }
                .lastOrNull() ?: return@runWriteCommandAction

            val newClassSrc = "\n\ndata class $className(\n    val placeholder: String,\n)"
            val newClass    = factory.createClass(newClassSrc)
            val nlBefore    = PsiParserFacade.getInstance(project)
                .createWhiteSpaceFromText("\n\n")

            ktFile.addAfter(newClass,  lastDataClass)
            ktFile.addAfter(nlBefore,  lastDataClass)

            // ── 2. Find the parent data class to receive the new parameter ─────
            // parentPath is empty → attach to root Strings class
            // parentPath non-empty → navigate to that data class
            val rootClass = ktFile.declarations
                .filterIsInstance<KtClass>()
                .filter { it.isData() }
                .lastOrNull { it.name != className } // last before our new one
                ?: return@runWriteCommandAction

            // Find the class that should receive the new field
            val parentClass: KtClass = if (parentPath.isEmpty()) {
                // Root Strings class — the one that holds all top-level group fields
                // Heuristic: the data class whose parameters are all of types that are
                // themselves data classes in this file (i.e. the root aggregator).
                // More reliably: find by matching the locale val's call expression class name.
                findRootStringsClass(ktFile) ?: return@runWriteCommandAction
            } else {
                // Navigate to the parent group's class by name
                val parentClassName = ktFile.declarations
                    .filterIsInstance<KtClass>()
                    .firstOrNull { cls ->
                        // Find the class whose field path matches parentPath
                        // by checking if it's referenced at that path from root
                        cls.name != null && matchesPath(ktFile, cls.name!!, parentPath)
                    } ?: return@runWriteCommandAction
                parentClassName
            }

            val paramList = parentClass.primaryConstructor?.valueParameterList
                ?: return@runWriteCommandAction

            val ws       = PsiParserFacade.getInstance(project).createWhiteSpaceFromText("\n    ")
            val newParam = factory.createParameter("val $fieldName: $className")
            val lastParam = paramList.parameters.lastOrNull()

            if (lastParam != null) {
                paramList.addParameterAfter(newParam, lastParam)
                val inserted = paramList.parameters.last()
                paramList.node.addChild(ws.node, inserted.node)
            } else {
                paramList.addParameter(newParam)
            }

            // ── 3. Insert argument in every locale val ────────────────────────
            val localeProps = ktFile.declarations
                .filterIsInstance<KtProperty>()
                .filter { prop ->
                    prop.annotationEntries.any { it.shortName?.identifier == "LyricistStrings" }
                }

            for (prop in localeProps) {
                val rootCall = prop.initializer as? KtCallExpression ?: continue
                val target   = navigateToCallExpr(rootCall, parentPath) ?: continue
                val argList  = target.valueArgumentList ?: continue

                val wsArg  = PsiParserFacade.getInstance(project)
                    .createWhiteSpaceFromText("\n        ")
                val dummy  = factory.createExpression(
                    "dummy($fieldName = $className(placeholder = \"\"))"
                ) as? KtCallExpression ?: continue
                val newArg = dummy.valueArguments.firstOrNull() ?: continue

                val lastArg = argList.arguments.lastOrNull()
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

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun findRootStringsClass(ktFile: KtFile): KtClass? {
        val localeProps = ktFile.declarations
            .filterIsInstance<KtProperty>()
            .firstOrNull { p -> p.annotationEntries.any { it.shortName?.identifier == "LyricistStrings" } }
            ?: return null
        val callName = (localeProps.initializer as? KtCallExpression)
            ?.calleeExpression?.text?.trim() ?: return null
        return ktFile.declarations.filterIsInstance<KtClass>().firstOrNull { it.name == callName }
    }

    private fun matchesPath(ktFile: KtFile, className: String, path: List<String>): Boolean {
        if (path.isEmpty()) return false
        val rootClass = findRootStringsClass(ktFile) ?: return false
        var current   = rootClass
        for ((i, segment) in path.withIndex()) {
            val param = current.primaryConstructor?.valueParameters
                ?.firstOrNull { it.name == segment } ?: return false
            val typeName = param.typeReference?.text?.trim() ?: return false
            if (i == path.lastIndex) return typeName == className
            current = ktFile.declarations.filterIsInstance<KtClass>()
                .firstOrNull { it.name == typeName } ?: return false
        }
        return false
    }
}