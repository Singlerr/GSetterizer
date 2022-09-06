package io.github.singlerr.gsetterizer.processor

import com.intellij.codeInsight.generation.GenerateMembersUtil
import com.intellij.psi.PsiField
import com.intellij.refactoring.encapsulateFields.FieldDescriptorImpl

class SimpleFieldDescriptorImpl(psiField: PsiField) : FieldDescriptorImpl(
    psiField,
    GenerateMembersUtil.suggestGetterName(psiField),
    GenerateMembersUtil.suggestSetterName(psiField),
    GenerateMembersUtil.generateGetterPrototype(psiField),
    GenerateMembersUtil.generateSetterPrototype(psiField)
) {
}