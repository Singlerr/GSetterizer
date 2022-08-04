package io.github.singlerr.gsetterizer.utils

import com.google.common.base.CaseFormat

fun generateGetter(originalName: String, isBoolean: Boolean): String {
    return if (isBoolean) {
        if (originalName.startsWith("is")) "is".plus(
            CaseFormat.LOWER_CAMEL.to(
                CaseFormat.UPPER_CAMEL,
                originalName.substringAfter("is")
            )
        ) else "is".plus(CaseFormat.LOWER_CAMEL.to(CaseFormat.UPPER_CAMEL, originalName))
    } else {
        "get".plus(CaseFormat.LOWER_CAMEL.to(CaseFormat.UPPER_CAMEL, originalName))
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