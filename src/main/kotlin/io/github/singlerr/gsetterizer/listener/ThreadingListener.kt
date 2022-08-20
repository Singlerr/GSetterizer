package io.github.singlerr.gsetterizer.listener

import com.intellij.openapi.application.ApplicationListener
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import io.github.singlerr.gsetterizer.utils.ThreadingUtils


val queuedActions = HashSet<Class<*>>()

class ThreadingListener : ApplicationListener {
    override fun beforeWriteActionStart(action: Any) {
        if(action is Class<*>){
            if(action.name.startsWith(ThreadingUtils::class.java.name)){
                queuedActions.add(action)
            }
        }
    }
    override fun writeActionFinished(action: Any) {
        if(action is Class<*>){
            if(action.name.startsWith(ThreadingUtils::class.java.name))
                queuedActions.removeIf { it.name.equals(action.name) }
        }
    }
}