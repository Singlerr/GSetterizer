package io.github.singlerr.gsetterizer.actions


import com.intellij.codeInspection.LocalQuickFix
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.util.ProgressWindow
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import io.github.singlerr.gsetterizer.utils.walk
import io.github.singlerr.gsetterizer.visitor.VariableSearcher
import io.ktor.utils.io.*


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
        val progressWindow = ProgressWindow(false,e.project!!)

        if (e.place == PROJECT_SCOPE) {
            val psiFiles = ApplicationManager.getApplication().runReadAction<List<PsiFile>> {
                val srcFiles = walk(currentFile) {
                    it.name.endsWith(".java")
                }

                val psiFiles = srcFiles.mapNotNull {
                    manager.findFile(it)
                }
                psiFiles
            }


            ProgressManager.getInstance().runInReadActionWithWriteActionPriority({
                for (psiFile in psiFiles) {
                    val searcher = VariableSearcher(psiFile)
                    searcher.startWalking()
                    progressWindow.text = "Processing ${psiFile.name}"
                }
            }, progressWindow)
        }


        /*
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
                val identifier =
                    parentExpression.lExpression.children.find { e -> e is PsiIdentifier } as PsiIdentifier

                /**
                 * Generate expression text.
                 */
                val setterExpressionRaw =
                    generateSetter(
                        identifier.text,
                        variable.isBoolean
                    ).plus("(${parentExpression.rExpression?.text})")

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
 */

    }
}