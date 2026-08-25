package org.zephy.kotlinhotreload.internal.remap

import org.zephy.kotlinhotreload.internal.Http
import java.io.File
import java.util.zip.ZipFile

object FabricMappingsFetcher {
    private const val MAVEN_BASE = "https://maven.fabricmc.net"

    fun fetchIntermediary(mcVersionName: String, cacheDir: File): File {
        val cached = File(cacheDir, "$mcVersionName-intermediary.tiny")
        if (cached.isFile && cached.length() > 0) return cached

        val jarUrl = "$MAVEN_BASE/net/fabricmc/intermediary/$mcVersionName/intermediary-$mcVersionName-v2.jar"
        downloadTinyFromJar(jarUrl, cached)
        return cached
    }

    private fun downloadTinyFromJar(jarUrl: String, dest: File) {
        dest.parentFile?.mkdirs()
        val bytes = Http.getBytes(jarUrl)

        val tmpJar = File.createTempFile("fabric-mappings-", ".jar", dest.parentFile)
        try {
            tmpJar.writeBytes(bytes)
            ZipFile(tmpJar).use { zip ->
                val entry = zip.getEntry("mappings/mappings.tiny")
                    ?: error("'$jarUrl' did not contain mappings/mappings.tiny - unexpected jar layout.")
                zip.getInputStream(entry).use { input ->
                    val tmpTiny = File.createTempFile("mappings-", ".tiny", dest.parentFile)
                    tmpTiny.outputStream().use { input.copyTo(it) }
                    if (!tmpTiny.renameTo(dest)) {
                        dest.writeBytes(tmpTiny.readBytes())
                        tmpTiny.delete()
                    }
                }
            }
        } finally {
            tmpJar.delete()
        }
    }
}
