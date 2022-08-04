package io.github.singlerr.gsetterizer.actions


import com.intellij.find.findUsages.JavaFindUsagesHelper
import com.intellij.find.findUsages.JavaVariableFindUsagesOptions
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.util.ProgressWindow
import com.intellij.openapi.project.DumbService
import com.intellij.psi.*
import com.intellij.psi.impl.PsiJavaParserFacadeImpl
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.elementType
import io.github.singlerr.gsetterizer.utils.generateGetter
import io.github.singlerr.gsetterizer.utils.generateSetter
import io.github.singlerr.gsetterizer.utils.walk
import io.github.singlerr.gsetterizer.visitor.JavaRecursiveElementWalkingAccumulator
import io.github.singlerr.gsetterizer.visitor.JavaReferenceInfo
import io.github.singlerr.gsetterizer.visitor.JavaVariableInfo


const val PROJECT_SCOPE = "ProjectViewPopup"
const val EDITOR_SCOPE = "EditorPopup"

class GSetterizeAction : AnAction() {
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = !(e.place != PROJECT_SCOPE || e.place != EDITOR_SCOPE) || e.project != null

    }

    /**
     * Implement this method to provide your action handler.
     *
     * @param e Carries information on the invocation place
     */
    override fun actionPerformed(e: AnActionEvent) {
        val manager = PsiManager.getInstance(e.project!!)
        val currentFile = e.dataContext.getData(PlatformDataKeys.VIRTUAL_FILE)!!

        /***
         * <Executed in ReadAction>
         * Visit variable references and save to container
         */
        val visitor = object : JavaRecursiveElementWalkingAccumulator() {
            override fun visitVariable(variable: PsiVariable?) {

                if (variable?.parent !is PsiClass) return

                val accessor = variable.children.find { e -> e.elementType?.debugName == "MODIFIER_LIST" } ?: return

                if (!accessor.text.contains("public") || accessor.text.contains("final")) return

                val progressWindow = ProgressWindow(false, e.project)
                progressWindow.title = "Processing"

                val containingClass = variable.context as PsiClass
                val javaVariableInfo = JavaVariableInfo(variable, HashSet(), accessor, variable.type == PsiType.BOOLEAN)


                ProgressManager.getInstance().runProcess({
                    DumbService.getInstance(e.project!!).runReadActionInSmartMode {
                        /**
                         * Find write access references and add to container.
                         */
                        JavaFindUsagesHelper.processElementUsages(
                            variable,
                            JavaVariableFindUsagesOptions(e.project!!).apply {
                                isReadAccess = false
                                isWriteAccess = true
                            }) { usageInfo ->
                            println("Found : ${usageInfo.element?.text}")
                            if (usageInfo.element?.parent is PsiAssignmentExpression)
                                javaVariableInfo.references.add(
                                    JavaReferenceInfo(
                                        usageInfo.element!!,
                                        false,
                                        usageInfo.element?.parent!!
                                    )
                                )
                            true
                        }
                        /**
                         * Find read access references and add to container.
                         */
                        JavaFindUsagesHelper.processElementUsages(
                            variable,
                            JavaVariableFindUsagesOptions(e.project!!).apply {
                                isReadAccess = true
                                isWriteAccess = false
                            }) { usageInfo ->
                            println("Found : ${usageInfo.element?.text}")

                            javaVariableInfo.references.add(
                                JavaReferenceInfo(
                                    usageInfo.element!!,
                                    true,
                                    usageInfo.element?.parent!!,
                                    usageInfo.element?.children?.find { e ->
                                        e is PsiIdentifier && e.text.equals(
                                            variable.name
                                        )
                                    } as PsiIdentifier?
                                )
                            )
                            true
                        }
                    }
                }, progressWindow)
                visitVariable(containingClass, javaVariableInfo)

            }

        }

        /**
         * Lambda for adding @Getter annotation to variable declaration statement.
         */
        val addGetter: (impl: PsiJavaParserFacade, javaVariable: JavaVariableInfo) -> Unit = { impl, variable ->
            variable.accessor.replace(
                impl.createStatementFromText(
                    variable.accessor.text.replace("public", "private"), variable.psiVariable
                )
            )

            variable.psiVariable.addBefore(
                impl.createAnnotationFromText("@Getter", variable.psiVariable),
                variable.psiVariable.children.first()
            )
        }

        /**
         * Lambda for adding @Setter annotation to variable declaration statement.
         */
        val addSetter: (impl: PsiJavaParserFacade, javaVariable: JavaVariableInfo) -> Unit = { impl, variable ->
            variable.accessor.replace(
                impl.createStatementFromText(
                    variable.accessor.text.replace("public", "private"), variable.psiVariable
                )
            )
            variable.psiVariable.addBefore(
                impl.createAnnotationFromText("@Setter", variable.psiVariable),
                variable.psiVariable.children.first()
            )
        }

        /**
         * Iterate all variable references and replace expression(direct field access) to getter / setter expression.
         */
        val gSetterize: (psiClass: PsiClass, javaVariable: JavaVariableInfo) -> Unit = { psiClass, variable ->
            val impl = PsiJavaParserFacadeImpl(e.project!!)

            /**
             * Replace read access to getter
             */
            val resolveGetter: (ref: JavaReferenceInfo) -> Unit = { ref ->
                val getterExpressionRaw = generateGetter(
                    ref.identifier!!.text,
                    variable.isBoolean
                ).plus(if (ref.element !is PsiImportStaticReferenceElement) "()" else "")

                val newExpression = impl.createExpressionFromText(getterExpressionRaw, psiClass)
                /**
                 * Target that will be replaced to getter
                 */

                /***
                 * ERROR OCCURRED
                 * Argument for @NotNull parameter 'manager' of com/intellij/psi/impl/source/tree/JavaSourceUtil.addParenthToReplacedChild must not be null
                 */
                ref.identifier.replace(newExpression)
                variable.needGetter = true

            }

            /**
             * Replace write access to setter
             */
            val resolveSetter: (ref: JavaReferenceInfo) -> Unit = { ref ->
                val parentExpression = ref.parent as PsiAssignmentExpression

                /**
                 * Target that will be replaced to setter
                 */
                val identifier = parentExpression.lExpression.children.find { e -> e is PsiIdentifier } as PsiIdentifier

                /**
                 * Generate expression text.
                 */
                val setterExpressionRaw =
                    generateSetter(identifier.text, variable.isBoolean).plus("(${parentExpression.rExpression?.text})")

                /**
                 * In shape "foo = bar"
                 * It will remove operationSign(=) and rExpression
                 */
                parentExpression.operationSign.delete()
                parentExpression.rExpression?.delete()

                val newExpression = impl.createExpressionFromText(setterExpressionRaw, variable.psiVariable)

                identifier.replace(newExpression)
                variable.needSetter = true
            }

            DumbService.getInstance(e.project!!).smartInvokeLater {
                WriteCommandAction.runWriteCommandAction(e.project!!) {
                    variable.references.forEach { ref ->

                        /**
                         * Import @Getter,@Setter annotation class
                         */
                        val getterClass = JavaPsiFacade.getInstance(e.project!!)
                            .findClass("lombok.Getter", GlobalSearchScope.allScope(e.project!!))
                        val setterClass = JavaPsiFacade.getInstance(e.project!!)
                            .findClass("lombok.Setter", GlobalSearchScope.allScope(e.project!!))

                        if (getterClass != null && setterClass != null) {
                            val javaFile = psiClass.containingFile as PsiJavaFile?
                            javaFile?.importClass(getterClass)
                            javaFile?.importClass(setterClass)
                        }
                        /**
                         * If a reference is read access then resolveGetter else resolveSetter
                         */
                        if (ref.valueRead) resolveGetter(ref)
                        else resolveSetter(ref)

                    }
                }
            }
            /***
             * Add @Getter @Setter annotation to variable declaration
             */
            DumbService.getInstance(e.project!!).smartInvokeLater {
                WriteCommandAction.runWriteCommandAction(e.project!!) {
                    if (variable.needGetter)
                        addGetter(impl, variable)
                    if (variable.needSetter)
                        addSetter(impl, variable)
                }
            }

        }
        ApplicationManager.getApplication().executeOnPooledThread {
            if (e.place == PROJECT_SCOPE) {
                /**
                 * Iterate all java source files
                 */
                val srcFiles = walk(currentFile) {
                    it.name.endsWith(".java")
                }

                /**
                 * Convert virtual file to psi file.
                 */
                val psiFiles = srcFiles.mapNotNull {
                    ApplicationManager.getApplication().runReadAction<PsiFile> {
                        manager.findFile(it)
                    }
                }

                psiFiles.forEach {
                    it.accept(visitor)
                }
                visitor.visitedVariables.forEach { (_, classInfo) ->
                    classInfo.variables.forEach { variable -> gSetterize(classInfo.psiClass, variable) }
                }
            } else {
                val psiFile = ApplicationManager.getApplication().runReadAction<PsiFile> {
                    manager.findFile(currentFile)
                }
                ApplicationManager.getApplication().runReadAction {
                    psiFile.accept(visitor)
                }
                visitor.visitedVariables.forEach { (_, classInfo) ->
                    classInfo.variables.forEach { variable -> gSetterize(classInfo.psiClass, variable) }
                }
            }
        }

    }
}