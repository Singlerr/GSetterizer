package io.github.singlerr.gsetterizer.actions


import com.intellij.codeInsight.actions.ReformatCodeProcessor
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.util.ProgressWindow
import com.intellij.openapi.project.DumbService
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import io.github.singlerr.gsetterizer.processor.GSetterizeProcessor
import io.github.singlerr.gsetterizer.utils.walk
import io.github.singlerr.gsetterizer.visitor.VariableSearcher
import io.ktor.utils.io.*


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
        var progressWindow = ProgressWindow(true,e.project!!)
        progressWindow.title = "GSetterizing..."
        ApplicationManager.getApplication().executeOnPooledThread {
            if (e.place == PROJECT_SCOPE) {
                val psiFiles = ApplicationManager.getApplication().runReadAction<List<PsiFile>> {
                    val srcFiles = walk(currentFile) {
                        it.name.endsWith(".java")
                    }
                    val psiFiles = srcFiles.mapNotNull {
                        manager.findFile(it)
                    }
                    psiFiles
                }
                val searcherList = HashSet<VariableSearcher>()
                ProgressManager.getInstance().runInReadActionWithWriteActionPriority({
                    try{
                        for (psiFile in psiFiles) {
                            val searcher = VariableSearcher(psiFile)

                            searcher.startWalking()

                            progressWindow.text = "Processing ${psiFile.name}"
                            searcherList.add(searcher)
                        }
                    }catch (e:ProcessCanceledException){
                        return@runInReadActionWithWriteActionPriority
                    }
                }, progressWindow)
                progressWindow = ProgressWindow(true,e.project!!)
                progressWindow.title = "GSetterizing..."
                ProgressManager.getInstance().runProcess({
                    try{
                        for(searcher in searcherList){
                            val processor = GSetterizeProcessor(e.project!!,searcher)
                            processor.run()
                        }
                    }catch (e:ProcessCanceledException){
                        return@runProcess
                    }
                }, progressWindow)
                println("Success")
            }
        }
    }
}