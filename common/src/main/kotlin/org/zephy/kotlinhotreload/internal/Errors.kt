package org.zephy.kotlinhotreload.internal

import java.lang.reflect.InvocationTargetException

fun Throwable.describe(): String {
    var current: Throwable = this
    while ((current is InvocationTargetException || current is ExceptionInInitializerError) && current.cause != null) {
        current = current.cause!!
    }
    return current.message ?: current.javaClass.simpleName
}
