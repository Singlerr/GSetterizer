package io.github.singlerr.gsetterizer.visitor

import com.intellij.psi.*

abstract class JavaRecursiveElementWalkingAccumulator : JavaRecursiveElementWalkingVisitor() {
    val visitedVariables = HashMap<String, JavaClassInfo>()

    fun visitVariable(psiClass: PsiClass, variable: JavaVariableInfo) {
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
    val psiVariable: PsiVariable,
    val references: MutableSet<JavaReferenceInfo>,
    val accessor: PsiElement,
    val isBoolean: Boolean,
    var needGetter: Boolean = false,
    var needSetter: Boolean = false
)

data class JavaReferenceInfo(
    val element: PsiElement,
    val valueRead: Boolean,
    val parent: PsiElement,
    val identifier: PsiIdentifier? = null
)