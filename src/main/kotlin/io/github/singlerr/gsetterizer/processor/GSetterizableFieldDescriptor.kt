package io.github.singlerr.gsetterizer.processor

import com.intellij.psi.PsiClass
import com.intellij.psi.PsiField
import com.intellij.psi.PsiModifier
import com.intellij.refactoring.encapsulateFields.EncapsulateFieldsDescriptor
import com.intellij.refactoring.encapsulateFields.FieldDescriptor
import com.intellij.refactoring.util.DocCommentPolicy

class GSetterizableFieldDescriptor(private val javaClass:PsiClass) : EncapsulateFieldsDescriptor {
    private val fieldDescriptors:Array<FieldDescriptor> = javaClass.allFields.filter{ it.hasModifierProperty(PsiModifier.PUBLIC) }.map { SimpleFieldDescriptorImpl(it) }.toTypedArray()

    override fun getSelectedFields(): Array<FieldDescriptor> = fieldDescriptors

    override fun isToEncapsulateGet(): Boolean = true

    override fun isToEncapsulateSet(): Boolean = true

    override fun isToUseAccessorsWhenAccessible(): Boolean = true

    override fun getFieldsVisibility(): String = PsiModifier.PRIVATE

    override fun getAccessorsVisibility(): String = PsiModifier.PRIVATE


    override fun getJavadocPolicy(): Int = DocCommentPolicy.COPY

    override fun getTargetClass(): PsiClass = javaClass
}