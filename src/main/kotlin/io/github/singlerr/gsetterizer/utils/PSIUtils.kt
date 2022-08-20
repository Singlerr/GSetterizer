package io.github.singlerr.gsetterizer.utils

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import io.github.singlerr.gsetterizer.utils.ThreadingUtils.invokeReadAction

fun <E : PsiElement> createSmartPointer(project: Project, element: E): SmartPsiElementPointer<E> {
    return invokeReadAction(project) { SmartPointerManager.createPointer(element) }
}

inline fun <reified E : PsiElement> PsiElement.lookUpParents(): E? {
    var parent: PsiElement? = this.parent
    while (parent != null && parent !is E) {
        parent = parent.parent
    }
    return if (parent == null) null else parent as E?
}