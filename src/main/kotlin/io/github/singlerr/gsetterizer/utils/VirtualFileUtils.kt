package io.github.singlerr.gsetterizer.utils

import com.intellij.openapi.vfs.VirtualFile

fun walk(start:VirtualFile, filter: (VirtualFile) -> Boolean) : Set<VirtualFile>{
    val fileFilter:(VirtualFile) -> Boolean = {
        it.isDirectory || filter(it)
    }
    val resolvedFiles = HashSet<VirtualFile>()
    val queue = ArrayDeque<VirtualFile>()
    var currentVertex = start

    resolvedFiles.add(currentVertex)

    queue.addLast(currentVertex)

    while(! queue.isEmpty()){
        currentVertex = queue.removeFirst()
        val children = currentVertex.children.filter(fileFilter).filter{
           ! resolvedFiles.contains(it)
        }
        children.forEach {
            queue.addLast(it)
            resolvedFiles.add(it)
        }
    }
    return resolvedFiles
}