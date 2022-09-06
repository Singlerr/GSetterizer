package io.github.singlerr.gsetterizer.visitor

import com.intellij.psi.JavaRecursiveElementWalkingVisitor
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiFile

class ClassSearcher(private val psiFile: PsiFile) {

    val classes:MutableList<PsiClass> = ArrayList()
    private val visitor:JavaRecursiveElementWalkingVisitor = object: JavaRecursiveElementWalkingVisitor(){
        override fun visitClass(aClass: PsiClass?) {
            if(aClass != null)
                classes.add(aClass)
            super.visitClass(aClass)
        }
    }

    fun processSearch(){
        psiFile.accept(visitor)
    }
}