package io.github.singlerr.gsetterizer.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.*
import com.intellij.psi.impl.PsiJavaParserFacadeImpl
import com.intellij.psi.impl.source.resolve.JavaResolveUtil
import com.intellij.psi.impl.source.tree.java.PsiJavaTokenImpl
import com.intellij.psi.search.PsiSearchScopeUtil
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.tree.java.IJavaElementType
import com.intellij.psi.util.elementType
import com.intellij.psi.util.siblings
import com.intellij.refactoring.RefactoringFactory
import com.jetbrains.rd.util.string.PrettyPrinter
import com.jetbrains.rd.util.string.println
import io.github.singlerr.gsetterizer.utils.walk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.apache.commons.text.CaseUtils

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

        val visitor = object : JavaRecursiveElementWalkingVisitor() {
            override fun visitVariable(variable: PsiVariable?) {
                if(variable?.parent !is PsiClass)
                    return


                val accessor = variable?.children.find { e -> e.elementType?.debugName == "MODIFIER_LIST" } ?: return

                if(! accessor.text.contains("public"))
                    return

                val impl = PsiJavaParserFacadeImpl(e.project!!)

                ReferencesSearch.search(variable).forEach { ref ->
                    val leftSiblings = ref.element.siblings(forward = false, withSelf = false)
                    val rightSiblings = ref.element.siblings(forward = true, withSelf = false)

                    //Determine variable read or write
                    val variableWrite = leftSiblings.find { it.elementType?.debugName == "EQ" } == null  && rightSiblings.count { it.elementType?.debugName == "EQ" } > 0

                    //Java Psi Element factory



                    val camelCaseName = CaseUtils.toCamelCase(variable!!.name, true)
                    //Variable read
                    if (!variableWrite) {
                        val getter =  "get".plus(camelCaseName).plus("()")
                        val replacement = impl.createMethodFromText(getter, variable.containingFile)

                        val caller = ref.element.children.find { it.elementType?.debugName == "IDENTIFIER" }

                        WriteCommandAction.runWriteCommandAction(e.project){
                            caller?.replace(replacement)
                            accessor.replace(impl.createStatementFromText(accessor.text.replace("public","private"),variable))
                            variable.addBefore(impl.createAnnotationFromText("@Getter",variable),variable.children.first())
                        }

                    } else {
                        val target = ref.element.children.find { it.elementType?.debugName == "IDENTIFIER" }
                        val eqElementIndex = ref.element.parent.children.indexOfFirst { e -> e.elementType?.debugName == "EQ" }
                        val lastElementIndex = ref.element.parent.children.lastIndex

                        val parameters = ref.element.parent.children.slice(eqElementIndex + 2..lastElementIndex)

                        val methodExpression = impl.createExpressionFromText("${"set".plus(camelCaseName)}(${parameters.joinToString { it.text }})",variable.containingFile) as PsiMethodCallExpression

                        WriteCommandAction.runWriteCommandAction(e.project){
                            ref.element.parent.deleteChildRange(ref.element.parent.children[eqElementIndex],ref.element.parent.children[lastElementIndex])
                            target?.replace(methodExpression)
                            accessor.replace(impl.createStatementFromText(accessor.text.replace("public","private"),variable))
                            variable.addBefore(impl.createAnnotationFromText("@Setter",variable),variable.children.first())
                        }

                        /*
                        ref.element.parent.children.forEach {
                            println(it.text)
                            println(it.elementType?.debugName)
                            println(it.javaClass.name)
                        }

                         */
                        /*
                        WriteCommandAction.runWriteCommandAction(e.project){
                            ref.element.parent.addAfter( impl.createExpressionFromText("(",ref.element.parent),eqElement)
                            ref.element.parent.addAfter( impl.createExpressionFromText(")",ref.element.parent),lastElement)
                            //Delete EQ element
                            ref.element.parent.deleteChildRange(eqElement,eqElement)

                            val setter = "set".plus(camelCaseName)
                            val methodSetter = impl.createExpressionFromText(setter,ref.element.parent)

                            target?.replace(methodSetter)
                        }
                         */


                        //val replacement = impl.createMethodFromText(setter,variable.containingFile)

                        //println(setter)
                        /*
                        WriteCommandAction.runWriteCommandAction(e.project){
                            ref.element.parent.replace(replacement)
                        }

                         */


                    }

                }
            }
        }
        ApplicationManager.getApplication().runReadAction {
            if (e.place == PROJECT_SCOPE) {
                val srcFiles = walk(currentFile) {
                    it.name.endsWith(".java")
                }
                srcFiles.forEach {
                    val psiFile = manager.findFile(it)
                    ProgressManager.progress("Processing ${psiFile?.name}")
                    psiFile?.accept(visitor)
                }
            } else {
                val psiFile = manager.findFile(currentFile)
                psiFile?.accept(visitor)
            }
        }

    }
}