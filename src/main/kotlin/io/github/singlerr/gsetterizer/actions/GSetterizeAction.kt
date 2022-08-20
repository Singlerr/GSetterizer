package io.github.singlerr.gsetterizer.actions


import com.intellij.diff.actions.impl.OpenInEditorAction
import com.intellij.ide.actions.OpenFileAction
import com.intellij.ide.actions.OpenInRightSplitAction
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.EditorCoreUtil
import com.intellij.openapi.editor.actionSystem.EditorActionManager
import com.intellij.openapi.editor.actions.EditorActionUtil
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.util.ProgressWindow
import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FilenameIndex
import io.github.singlerr.gsetterizer.listener.ThreadingListener
import io.github.singlerr.gsetterizer.processor.GSetterizeProcessor
import io.github.singlerr.gsetterizer.visitor.VariableSearcher


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

        val manager = PsiManager.getInstance(e.project!!)
        val currentFile = e.dataContext.getData(PlatformDataKeys.VIRTUAL_FILE)!!

        var progressWindow = ProgressWindow(true, e.project!!)
        progressWindow.title = "GSetterizing..."

        val listener = ThreadingListener()
        ApplicationManager.getApplication().addApplicationListener(listener, Disposer.newDisposable())

        ApplicationManager.getApplication().executeOnPooledThread {
            if (e.place == PROJECT_SCOPE) {
                val psiFiles = ApplicationManager.getApplication().runReadAction<List<PsiFile>> {
                    val srcFiles = FilenameIndex.getAllFilesByExt(e.project!!, "java")
                        .filter { file -> file.path.startsWith(currentFile.path) }
                    val psiFiles = srcFiles.mapNotNull {
                        manager.findFile(it)
                    }
                    psiFiles
                }

                val searcherList = HashSet<VariableSearcher>()
                ProgressManager.getInstance().runInReadActionWithWriteActionPriority({
                    try {
                        for (psiFile in psiFiles) {
                            val searcher = VariableSearcher(psiFile)

                            searcher.startWalking()

                            progressWindow.text = "Processing ${psiFile.name}"
                            searcherList.add(searcher)
                        }
                    } catch (e: ProcessCanceledException) {
                        return@runInReadActionWithWriteActionPriority
                    }
                }, progressWindow)
                progressWindow = ProgressWindow(true, e.project!!)
                progressWindow.title = "GSetterizing..."
                ProgressManager.getInstance().runProcess({
                    try {
                        for (searcher in searcherList) {
                            val processor = GSetterizeProcessor(e.project!!,searcher)
                            processor.run()
                        }
                    } catch (e: ProcessCanceledException) {
                        return@runProcess
                    }
                }, progressWindow)
                println("Success")
            }
        }
    }
}