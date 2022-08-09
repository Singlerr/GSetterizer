package io.github.singlerr.gsetterizer.processor

import com.google.common.base.CaseFormat
import com.intellij.find.findUsages.JavaFindUsagesHelper
import com.intellij.find.findUsages.JavaVariableFindUsagesOptions
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.usageView.UsageInfo
import com.intellij.util.alsoIfNull
import io.github.singlerr.gsetterizer.utils.generateGetter
import io.github.singlerr.gsetterizer.utils.generateSetter
import io.github.singlerr.gsetterizer.utils.isUpperUnderscore
import io.github.singlerr.gsetterizer.visitor.JavaClassInfo
import io.github.singlerr.gsetterizer.visitor.JavaVariableInfo
import io.github.singlerr.gsetterizer.visitor.VariableSearcher
import org.jetbrains.concurrency.createError

class GSetterizeProcessor(private val project: Project, private val variableSearcher: VariableSearcher) {

    private val factory: PsiElementFactory = JavaPsiFacade.getElementFactory(project)
    private val lombokGetterClass =
        ApplicationManager.getApplication().runReadAction<PsiClass?> {
            JavaPsiFacade.getInstance(project).findClass("lombok.Getter", GlobalSearchScope.allScope(project))
        }.alsoIfNull {
            throw createError("Lombok @Getter not found in project scope.")
        }

    private val lombokSetterClass =
        ApplicationManager.getApplication().runReadAction<PsiClass?> {
            JavaPsiFacade.getInstance(project).findClass("lombok.Setter", GlobalSearchScope.allScope(project))
        }.alsoIfNull {
            throw createError("Lombok @Setter not found in project scope.")
        }

    fun run() {
        variableSearcher.iterate { classInfo ->
            processClass(classInfo)
        }
    }

    private fun processClass(classInfo: JavaClassInfo) {
        for (variableInfo in classInfo.variables) {
            val modifierList = ApplicationManager.getApplication().runReadAction<PsiModifierList> {
                variableInfo.pointer.element!!.children.filterIsInstance<PsiModifierList>().first()
            }

            val skip = ApplicationManager.getApplication().runReadAction<Boolean> { modifierList.hasModifierProperty("public") || modifierList.hasModifierProperty("final") }
            if (! (skip || isUpperUnderscore(variableInfo.pointer.element!!.name!!)))
                continue
            val indicator = ProgressManager.getGlobalProgressIndicator()!!
            ApplicationManager.getApplication()
                .runReadAction { indicator.text = "Processing ${variableInfo.pointer.element?.text}" }
            processVariable(variableInfo, classInfo.psiClass, modifierList)
        }
    }

    private fun processVariable(variableInfo: JavaVariableInfo, contextClass: PsiClass, modifierList: PsiModifierList) {
        val element = ApplicationManager.getApplication().runReadAction<PsiElement> { variableInfo.pointer.element!! }
        ProgressManager.getGlobalProgressIndicator()?.text =
            "Processing ${ApplicationManager.getApplication().runReadAction<String> { element.text }}"
        val needGetter = processReadAccess(element) {
            processReadReference(
                it.element!!,
                variableInfo.pointer.element!!.nameIdentifier!!,
                variableInfo.isBoolean
            )
        }
        val needSetter = processWriteAccess(element) {
            processWriteReference(
                it.element!!,
                variableInfo.pointer.element!!.nameIdentifier!!,
                variableInfo.isBoolean
            )
        }
        val psiFile = ApplicationManager.getApplication().runReadAction<PsiFile> { contextClass.containingFile }
        if (psiFile !is PsiJavaFile)
            return

        if (needGetter) {
            invokeWriteAction {
                psiFile.importClass(lombokGetterClass!!)
                modifierList.addAnnotation("lombok.Getter")
            }
        }
        if (needSetter) {
            invokeWriteAction {
                psiFile.importClass(lombokSetterClass!!)
                modifierList.addAnnotation("lombok.Setter")
            }
        }

        invokeWriteAction {
            modifierList.setModifierProperty("private", true)
        }

    }

    private fun processReadReference(
        element: PsiElement,
        identifier: PsiIdentifier,
        isBoolean: Boolean
    ) {
        val targetIdentifier = element.children.find { e -> e.text.equals(identifier.text) }!!
        val isImportStatement = element is PsiImportStaticReferenceElement
        val getter = factory.createExpressionFromText(generateGetter(identifier.text, isBoolean, isImportStatement), null)
        invokeWriteAction {
            targetIdentifier.replace(getter)
        }
    }

    private fun processWriteReference(
        element: PsiElement,
        identifier: PsiIdentifier,
        isBoolean: Boolean
    ) {
        val parent = ApplicationManager.getApplication().runReadAction<PsiElement> { element.parent }
        if (parent !is PsiAssignmentExpression)
            return

        val targetIdentifier =
            parent.lExpression.children.filterIsInstance<PsiIdentifier>().last()

        val isImportStatement = element is PsiImportStaticReferenceElement

        val setter = factory.createExpressionFromText(
            generateSetter(
                identifier.text,
                isBoolean
            ).plus(if(isImportStatement) "" else "(${parent.rExpression?.text})"), null
        )

        invokeWriteAction {
            parent.operationSign.delete()
            parent.rExpression?.delete()
            targetIdentifier.replace(setter)
        }
    }

    private fun processReadAccess(element: PsiElement, compute: (usageInfo: UsageInfo) -> Unit): Boolean {
        return JavaFindUsagesHelper.processElementUsages(element, JavaVariableFindUsagesOptions(project).apply {
            isReadAccess = true
            isWriteAccess = false
        }) {
            compute(it)
            true
        }
    }

    private fun processWriteAccess(element: PsiElement, compute: (usageInfo: UsageInfo) -> Unit): Boolean {
        return JavaFindUsagesHelper.processElementUsages(element, JavaVariableFindUsagesOptions(project).apply {
            isReadAccess = false
            isWriteAccess = true
        }) {
            compute(it)
            true
        }
    }

    private fun invokeWriteAction(compute: () -> Unit) {
        ApplicationManager.getApplication().invokeLater({
            WriteCommandAction.runWriteCommandAction(project) {
                compute()
            }
        }, ModalityState.NON_MODAL)
    }
}

data class TempData(val element: SmartPsiElementPointer<PsiElement>, val isReadAccess: Boolean, val isBoolean: Boolean)
