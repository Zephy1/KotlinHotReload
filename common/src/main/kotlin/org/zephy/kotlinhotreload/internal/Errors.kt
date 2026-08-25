package org.zephy.kotlinhotreload.internal

import java.lang.reflect.InvocationTargetException

fun Throwable.describe(): String {
    val seen = mutableSetOf(this)
    val chain = generateSequence(this) { t -> t.cause?.takeIf { seen.add(it) } }.toList()

    val meaningful = chain.filter {
        it !is InvocationTargetException && it !is ExceptionInInitializerError
    }.ifEmpty { chain }

    return meaningful.joinToString(" -> ") { t ->
        val detail = t.message
            ?: t.stackTrace.firstOrNull()?.let { "(no message, thrown at $it)" }
            ?: "(no message, no stack trace)"
        "${t.javaClass.simpleName}: $detail"
    }
}
