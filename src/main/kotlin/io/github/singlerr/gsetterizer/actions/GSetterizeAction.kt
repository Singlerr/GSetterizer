package io.github.singlerr.gsetterizer.actions


import com.intellij.codeInsight.hint.HintManager
import com.intellij.notification.Notifications
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.util.ProgressWindow
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.ui.popup.Balloon
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiInvalidElementAccessException
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.refactoring.encapsulateFields.EncapsulateFieldsDialog
import com.intellij.refactoring.encapsulateFields.EncapsulateFieldsProcessor
import com.intellij.ui.BalloonLayout
import com.intellij.ui.GotItMessage
import io.github.singlerr.gsetterizer.processor.GSetterizableFieldDescriptor
import io.github.singlerr.gsetterizer.visitor.ClassSearcher


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
        val project = e.project!!
        val manager = PsiManager.getInstance(project)
        val currentFile = e.dataContext.getData(PlatformDataKeys.VIRTUAL_FILE)!!
        val progressWindow = ProgressWindow(false, project)

        progressWindow.title = "Encapsulating fields.."
        if (e.place == PROJECT_SCOPE) {
            ApplicationManager.getApplication().executeOnPooledThread {
                val psiFiles = ApplicationManager.getApplication().runReadAction<List<PsiFile>> {
                    FilenameIndex.getAllFilesByExt(project, "java")
                        .filter { !it.isDirectory && it.path.startsWith(currentFile.path) }
                        .map { manager.findFile(it)!! }
                }

                val searchers = mutableSetOf<ClassSearcher>()

                ProgressManager.getInstance().runInReadActionWithWriteActionPriority({
                    for (psiFile in psiFiles) {
                        progressWindow.text = "Searching class ${psiFile.name}"
                        val searcher = ClassSearcher(psiFile)
                        searcher.processSearch()
                        searchers.add(searcher)
                    }
                }, progressWindow)
                progressWindow.pushState()
                ApplicationManager.getApplication().invokeLaterOnWriteThread({
                    for (searcher in searchers) {
                        try{
                            searcher.classes.forEach { cls ->
                                progressWindow.text = "Processing ${cls.name}"
                                val descriptor = ApplicationManager.getApplication().runReadAction<GSetterizableFieldDescriptor> { GSetterizableFieldDescriptor(cls) }
                                val processor = EncapsulateFieldsProcessor(project, descriptor)
                                processor.run()

                            }
                        }catch (e: ProcessCanceledException){
                            break
                        }catch(_:IllegalArgumentException){

                        }catch(_:PsiInvalidElementAccessException){

                        }
                    }

                }, ModalityState.NON_MODAL)
            }
        }

    }
}