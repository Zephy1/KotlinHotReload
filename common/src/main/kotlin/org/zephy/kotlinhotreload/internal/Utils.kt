package org.zephy.kotlinhotreload.internal

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

const val MIXIN_ANNOTATION_DESC = "Lorg/spongepowered/asm/mixin/Mixin;"

internal fun MessageDigest.updateUtf8(value: String) {
    update(value.toByteArray(StandardCharsets.UTF_8))
}

internal fun classpathEntryFor(clazz: Class<*>): File {
    val location = clazz.protectionDomain?.codeSource?.location
        ?: error("Could not determine the classpath location for ${clazz.name} - it may have been loaded from somewhere other than a regular jar file.")
    return File(location.toURI())
}

internal fun remapDescriptorClasses(descriptor: String, classMap: Map<String, String>): String {
    val sb = StringBuilder()
    var i = 0
    while (i < descriptor.length) {
        val c = descriptor[i]
        if (c == 'L') {
            val end = descriptor.indexOf(';', i)
            if (end < 0) {
                sb.append(c)
                i++
                continue
            }
            val internal = descriptor.substring(i + 1, end)
            sb.append('L').append(classMap[internal] ?: internal).append(';')
            i = end + 1
        } else {
            sb.append(c)
            i++
        }
    }
    return sb.toString()
}

internal object Http {
    fun getBytes(url: String): ByteArray {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.instanceFollowRedirects = true
        try {
            val status = connection.responseCode
            check(status in 200..299) { "GET $url returned HTTP $status" }
            return connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }

    fun getText(url: String): String = String(getBytes(url), Charsets.UTF_8)
}
