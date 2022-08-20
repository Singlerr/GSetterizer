package io.github.singlerr.gsetterizer.processor

import com.intellij.codeInsight.generation.GenerateMembersUtil
import com.intellij.codeInsight.generation.GenerationInfo
import com.intellij.codeInsight.generation.PsiFieldMember
import com.intellij.codeInsight.intention.QuickFixFactory
import com.intellij.find.findUsages.JavaFindUsagesHelper
import com.intellij.find.findUsages.JavaVariableFindUsagesOptions
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.suggested.endOffset
import com.intellij.refactoring.suggested.startOffset
import com.intellij.usageView.UsageInfo
import io.github.singlerr.gsetterizer.listener.queuedActions
import io.github.singlerr.gsetterizer.utils.ThreadingUtils.invokeReadAction
import io.github.singlerr.gsetterizer.utils.ThreadingUtils.invokeWriteAction
import io.github.singlerr.gsetterizer.utils.createSmartPointer
import io.github.singlerr.gsetterizer.utils.isUpperUnderscore
import io.github.singlerr.gsetterizer.visitor.JavaClassInfo
import io.github.singlerr.gsetterizer.visitor.JavaVariableInfo
import io.github.singlerr.gsetterizer.visitor.VariableSearcher
import java.util.*

class GSetterizeProcessor(private val project: Project, private val variableSearcher: VariableSearcher) {
    private val factory: PsiElementFactory = JavaPsiFacade.getElementFactory(project)

    private val readAccessRecords = ArrayList<ReadUsageRecord>()

    private val writeAccessRecords = ArrayList<WriteUsageRecord>()

    private val variableRecords = ArrayList<VariableWrapper>()

    fun run() {
        val indicator = ProgressManager.getGlobalProgressIndicator()!!
        variableSearcher.iterate { classInfo ->
            processClass(classInfo)
        }
        while (queuedActions.isNotEmpty()) {
            println(queuedActions.size)
        }
        variableRecords.forEach { record ->
            invokeReadAction(project) { indicator.text = "Processing ${record.variable.element!!.name}" }
            invokeWriteAction(project) {
                record.suggestedSetter?.insert(
                    record.variable.elementByReadAction()!!.containingClass!!,
                    record.variable.elementByReadAction()!!.containingClass!!.lastChild,
                    true
                )
                record.suggestedGetter?.insert(
                    record.variable.elementByReadAction()!!.containingClass!!,
                    record.variable.elementByReadAction()!!.containingClass!!.lastChild,
                    true
                )
            }

            invokeWriteAction(project) {
                val variable = record.variable.elementByReadAction()!!
                val modifierFix = QuickFixFactory.getInstance().createModifierListFix(
                    record.variable.elementByReadAction()!!.modifierList!!,
                    "private",
                    true,
                    false
                )
                val descriptor = OpenFileDescriptor(project, variable.containingFile.virtualFile)
                val currentEditor = FileEditorManager.getInstance(project).openTextEditor(descriptor, true)!!
                modifierFix.invoke(project, currentEditor, variable.containingFile)
            }

        }

        readAccessRecords.forEach { record ->
            val sorted = record.elements.sortedByDescending { it.element.elementByReadAction()!!.startOffset }
            for (subElement in sorted) {
                val identifier =
                    invokeReadAction(project) { subElement.element.elementByReadAction()!!.children.find { e -> e is PsiIdentifier } as PsiIdentifier? }
                        ?: continue
                invokeReadAction(project) {
                    indicator.text = "Processing ${subElement.element.elementByReadAction()!!.text}"
                }
                processReadReference(
                    subElement.element.elementByReadAction()!!,
                    identifier,
                    subElement.methodName,
                    subElement.sourceVariable.elementByReadAction()!!
                )
            }
        }
        writeAccessRecords.forEach { record ->
            invokeReadAction(project) {
                indicator.text = "Processing ${record.element.element.elementByReadAction()!!.text}"
            }
            processWriteReference(record.element.element.elementByReadAction()!!, record.element.methodName)
        }


    }

    private fun processClass(classInfo: JavaClassInfo) {
        for (variableInfo in classInfo.variables) {
            val modifierList = invokeReadAction(project) { variableInfo.pointer.elementByReadAction()!!.modifierList!! }

            val skip = invokeReadAction(project) {
                modifierList.hasModifierProperty(PsiModifier.STATIC) && modifierList.hasModifierProperty(PsiModifier.FINAL) || isUpperUnderscore(
                    variableInfo.pointer.elementByReadAction()!!.name!!
                )
            }
            if (skip)
                continue

            val indicator = ProgressManager.getGlobalProgressIndicator()!!
            invokeReadAction(project) {
                indicator.text = "Processing ${variableInfo.pointer.elementByReadAction()?.text}"
            }

            val variableRecord = processVariable(variableInfo) ?: continue

            variableRecords.add(variableRecord)
        }
    }

    private fun processVariable(
        variableInfo: JavaVariableInfo
    ): VariableWrapper? {
        val element = invokeReadAction<PsiElement>(project) { variableInfo.pointer.elementByReadAction()!! }
        ProgressManager.getGlobalProgressIndicator()?.text =
            "Processing ${invokeReadAction<String>(project) { element.text }}"

        val variableField = invokeReadAction(project) { variableInfo.pointer.elementByReadAction()!! }

        if (variableField !is PsiField)
            return null

        val getterName = invokeReadAction(project) { GenerateMembersUtil.suggestGetterName(variableField) }
        val setterName = invokeReadAction(project) { GenerateMembersUtil.suggestSetterName(variableField) }

        val needGetter = processReadAccess(element) { usageInfo ->
            val usageRef = usageInfo.element!!.parent!!
            val startOffset = usageRef.startOffset
            val endOffset = usageRef.endOffset
            val containingFile = usageRef.containingFile

            record(
                usageRef = usageRef,
                originalElement = usageInfo.element!!,
                getterName = getterName,
                startOffset = startOffset,
                endOffset = endOffset,
                containingFile = containingFile,
                variable = variableField
            )
        }

        val needSetter = processWriteAccess(element) { usageInfo ->
            invokeWriteAction(project) {
                writeAccessRecords.add(
                    WriteUsageRecord(
                        wrap(
                            usageInfo.element!!.children.filterIsInstance<PsiIdentifier>().first(),
                            setterName,
                            variableField
                        )
                    )
                )
            }

        }
        val generator = invokeReadAction(project) { PsiFieldMember(variableField) }
        return VariableWrapper(
            createSmartPointer(project, variableField),
            invokeReadAction(project) { if (needGetter) generator.generateGetter() else null },
            invokeReadAction(project) { if (needSetter) generator.generateSetter() else null }
        )
    }

    private fun record(
        usageRef: PsiElement,
        originalElement: PsiElement,
        getterName: String,
        startOffset: Int,
        endOffset: Int,
        containingFile: PsiFile,
        variable: PsiField
    ) {
        val recordedElements =
            readAccessRecords.filter { e -> e.fileLocation.elementByReadAction()!!.isEquivalentTo(containingFile) }

        val parentElement =
            invokeReadAction(project) { recordedElements.find { e -> e.mainElement.elementByReadAction()!!.parent.startOffset <= startOffset && e.mainElement.elementByReadAction()!!.parent.endOffset >= endOffset } }

        var childElement =
            invokeReadAction(project) { recordedElements.find { e -> startOffset <= e.mainElement.elementByReadAction()!!.parent.startOffset && e.mainElement.elementByReadAction()!!.parent.endOffset <= endOffset } }

        if (parentElement == null) {
            // usageRef would be new usage record
            if (childElement == null) {
                childElement = ReadUsageRecord(
                    createSmartPointer(project, containingFile),
                    startOffset,
                    endOffset,
                    createSmartPointer(project, usageRef),
                    arrayListOf(wrap(originalElement, getterName, variable))
                )
                readAccessRecords.add(childElement)
            } else {
                //main element of childElement would be usageRef
                childElement.addElement(wrap(originalElement, getterName, variable))
                childElement.mainElement = createSmartPointer(project, usageRef)
                childElement.startOffset = startOffset
                childElement.endOffset = endOffset
            }
        } else {
            //Just add it to child of parent
            parentElement.addElement(wrap(originalElement, getterName, variable))
        }
    }

    private fun processReadReference(
        element: PsiElement,
        targetIdentifier: PsiIdentifier,
        getterPreGenerated: String,
        sourceVariable: PsiField
    ) {


        if (element.invokeReadAction {
                PsiTreeUtil.getParentOfType(
                    this,
                    PsiReturnStatement::class.java
                )
            } != null && element is PsiReference) {
            val currentClass = invokeReadAction(project) { PsiTreeUtil.getParentOfType(element, PsiClass::class.java) }
            val origin = invokeReadAction(project) { element.resolve() }
            if (currentClass != null && origin is PsiField && invokeReadAction(project) {
                    origin.containingClass!!.isEquivalentTo(
                        currentClass
                    )
                }) {
                println("Skipped : ${element.invokeReadAction { text }}")
                return
            }
        }


        val method = invokeReadAction(project) {
            sourceVariable.containingClass!!.findMethodsByName(getterPreGenerated, false).firstOrNull()
        } ?: return

        if (element is PsiImportStaticReferenceElement) {
            invokeReadAction(project){
                println(element.children.joinToString { it.text })
            }
            return
        }

        val quickFix = invokeReadAction(project) {
            QuickFixFactory.getInstance()
                .createReplaceInaccessibleFieldWithGetterSetterFix(element, method, false)
        }
        ApplicationManager.getApplication().invokeAndWait({
            val descriptor = OpenFileDescriptor(project, element.containingFile.virtualFile)
            val currentEditor = FileEditorManager.getInstance(project).openTextEditor(descriptor, true)!!
            invokeWriteAction(project) {
                quickFix.invoke(project, currentEditor, element.containingFile)
            }
        }, ModalityState.NON_MODAL)

    }

    private fun processWriteReference(
        element: PsiElement,
        setterPreGenerated: String
    ) {
        val parent = invokeReadAction<PsiElement>(project) { element.parent }
        if (parent !is PsiAssignmentExpression)
            return

        val targetIdentifier =
            parent.lExpression.children.filterIsInstance<PsiIdentifier>().last()

        val isImportStatement = element is PsiImportStaticReferenceElement

        val setter = factory.createExpressionFromText(
            setterPreGenerated.plus(if (isImportStatement) "" else "(${parent.rExpression?.text})"), null
        )

        /*
        invokeWriteAction(project) {
            parent.operationSign.delete()
            parent.rExpression?.delete()
            targetIdentifier.replace(setter)
        }

         */
    }

    private fun processReadAccess(element: PsiElement, compute: (usageInfo: UsageInfo) -> Unit): Boolean {
        return JavaFindUsagesHelper.processElementUsages(element, JavaVariableFindUsagesOptions(project).apply {
            isReadAccess = true
            isWriteAccess = false
            searchScope = GlobalSearchScope.allScope(project)
        }) {
            compute(it)
            true
        }
    }

    private fun processWriteAccess(element: PsiElement, compute: (usageInfo: UsageInfo) -> Unit): Boolean {
        return JavaFindUsagesHelper.processElementUsages(element, JavaVariableFindUsagesOptions(project).apply {
            isReadAccess = false
            isWriteAccess = true
            searchScope = GlobalSearchScope.allScope(project)
        }) {
            compute(it)
            true
        }
    }

    private fun <E : PsiElement> SmartPsiElementPointer<E>.elementByReadAction(): E? {
        return invokeReadAction(project) { this.element }
    }
}

fun wrap(element: PsiElement, methodName: String, variable: PsiField): ElementWrapper =
    ElementWrapper(methodName, SmartPointerManager.createPointer(element), SmartPointerManager.createPointer(variable))

data class VariableWrapper(
    val variable: SmartPsiElementPointer<PsiField>,
    val suggestedGetter: GenerationInfo?,
    val suggestedSetter: GenerationInfo?
)

data class ReadUsageRecord(
    val fileLocation: SmartPsiElementPointer<PsiFile>,
    var startOffset: Int,
    var endOffset: Int,
    var mainElement: SmartPsiElementPointer<PsiElement>,
    val elements: MutableList<ElementWrapper>
) {
    fun addElement(element: ElementWrapper) {
        elements.add(element)
    }

    override fun equals(other: Any?): Boolean {
        if (other == null)
            return false
        if (other !is ReadUsageRecord)
            return false

        return this.fileLocation.element!!.isEquivalentTo(other.fileLocation.element!!) && this.startOffset == other.startOffset && this.endOffset == other.endOffset
    }

    override fun hashCode(): Int {
        return Objects.hash(fileLocation, startOffset, endOffset)
    }
}

data class ElementWrapper(
    val methodName: String,
    val element: SmartPsiElementPointer<PsiElement>,
    val sourceVariable: SmartPsiElementPointer<PsiField>
)

data class WriteUsageRecord(val element: ElementWrapper) {
    override fun hashCode(): Int {
        return Objects.hash(element)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is WriteUsageRecord) return false

        if (element != other.element) return false

        return true
    }
}
