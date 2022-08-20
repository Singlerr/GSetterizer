package io.github.singlerr.gsetterizer.utils

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement

object ThreadingUtils {
    private const val DEFAULT_COMMAND_NAME = "undefined"
    fun invokeWriteAction(project: Project, compute: () -> Unit) {
        ApplicationManager.getApplication().invokeLaterOnWriteThread({
            CommandProcessor.getInstance().executeCommand(project, {
                ApplicationManager.getApplication().runWriteAction(compute)
            }, DEFAULT_COMMAND_NAME, null)
        }, ModalityState.NON_MODAL)
    }

    fun <T> PsiElement.invokeReadAction(compute: PsiElement.() -> T): T {
        return invokeReadAction(this.project) { compute(this) }
    }

    fun <T> invokeWriteAction(project: Project, compute: () -> T): T {
        return WriteCommandAction.runWriteCommandAction<T>(project, compute)
    }

    fun <T> invokeReadAction(project: Project, compute: () -> T): T {
        return DumbService.getInstance(project).runReadActionInSmartMode(compute)
    }
}
