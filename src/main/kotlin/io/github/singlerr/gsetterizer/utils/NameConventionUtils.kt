package io.github.singlerr.gsetterizer.utils

import com.google.common.base.CaseFormat

fun generateGetter(originalName: String, isBoolean: Boolean, isInImportStatement:Boolean): String {
    val suffix = if(isInImportStatement) "" else "()"
    return if (isBoolean) {
        if (originalName.startsWith("is")) "is".plus(
            CaseFormat.LOWER_CAMEL.to(
                CaseFormat.UPPER_CAMEL,
                originalName.substringAfter("is")
            ).plus(suffix)
        ) else "is".plus(CaseFormat.LOWER_CAMEL.to(CaseFormat.UPPER_CAMEL, originalName)).plus(suffix)
    } else {
        "get".plus(CaseFormat.LOWER_CAMEL.to(CaseFormat.UPPER_CAMEL, originalName)).plus(suffix)
    }
}

fun generateSetter(originalName: String, isBoolean: Boolean): String {
    return if (isBoolean) {
        if (originalName.startsWith("is")) "set".plus(
            CaseFormat.LOWER_CAMEL.to(
                CaseFormat.UPPER_CAMEL,
                originalName.substringAfter("is")
            )
        ) else "set".plus(CaseFormat.LOWER_CAMEL.to(CaseFormat.UPPER_CAMEL, originalName))
    } else {
        "set".plus(CaseFormat.LOWER_CAMEL.to(CaseFormat.UPPER_CAMEL, originalName))
    }
}

fun isUpperUnderscore(str:String) : Boolean{
    for(ch in str.toCharArray()){
        if(ch !in 'A'..'Z' || ch != '_')
            return false
    }
    return true
}