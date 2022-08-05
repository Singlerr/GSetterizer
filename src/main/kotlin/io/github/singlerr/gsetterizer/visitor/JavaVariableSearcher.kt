package io.github.singlerr.gsetterizer.visitor

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.psi.*
import com.intellij.psi.util.elementType
import io.ktor.utils.io.*

class VariableSearcher(private val psiFile: PsiFile) {

    private val variableVisitor: JavaRecursiveElementWalkingVisitor
    private val visitedVariables = HashMap<String, JavaClassInfo>()

    init {
        variableVisitor = object : JavaRecursiveElementWalkingVisitor() {
            override fun visitVariable(variable: PsiVariable?) {
                super.visitVariable(variable)
                if (variable?.parent !is PsiClass) return

                val accessor = variable.children.find { e -> e.elementType?.debugName == "MODIFIER_LIST" } ?: return

                if (!accessor.text.contains("public") || accessor.text.contains("final")) return

                val varPointer = SmartPointerManager.createPointer(variable)
                val accessorPointer = SmartPointerManager.createPointer(accessor)
                val isBoolean = variable.type == PsiType.BOOLEAN
                visitVariable(
                    variable.parent as PsiClass,
                    JavaVariableInfo(pointer = varPointer, accessor = accessorPointer, isBoolean = isBoolean)
                )
            }
        }

    }

    fun startWalking(){
        psiFile.accept(variableVisitor)
    }

    fun startWalkingWithReadAction(){
        ApplicationManager.getApplication().runReadAction { startWalking() }
    }

    fun iterate(compute: (variable: JavaClassInfo) -> Unit) =
        visitedVariables.forEach { (_, variable) -> compute(variable) }

    private fun visitVariable(psiClass: PsiClass, variable: JavaVariableInfo) {
        if (visitedVariables.containsKey(psiClass.qualifiedName!!))
            visitedVariables[psiClass.qualifiedName!!]?.variables?.add(variable)
        else
            visitedVariables[psiClass.qualifiedName!!] = JavaClassInfo(psiClass, HashSet()).apply {
                variables.add(variable)
            }
    }

}

data class JavaClassInfo(val psiClass: PsiClass, val variables: MutableSet<JavaVariableInfo>)
data class JavaVariableInfo(
    val pointer: SmartPsiElementPointer<PsiVariable>,
    val accessor: SmartPsiElementPointer<PsiElement>,
    val isBoolean: Boolean,
    var needGetter: Boolean = false,
    var needSetter: Boolean = false
)
