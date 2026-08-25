package org.zephy.kotlinhotreload.internal.remap

import java.io.File
import java.util.zip.ZipFile

class MappingStubClasspathCache(private val cacheRoot: File) {
    private val stubBuildLocks = java.util.concurrent.ConcurrentHashMap<String, Any>()

    fun get(cacheKey: String, obfToFriendly: ProGuardMappings.Direction, runtimeClasspath: List<File>): List<File> {
        val stubDir = File(cacheRoot, "mapping-stubs/$cacheKey")
        val markerFile = File(stubDir, ".complete")
        val manifestFile = File(stubDir, ".remapped-jars")

        synchronized(stubBuildLocks.computeIfAbsent(cacheKey) { Any() }) {
            if (!markerFile.isFile) {
                build(obfToFriendly, runtimeClasspath, stubDir, markerFile, manifestFile)
            }
        }

        val remappedNames = if (manifestFile.isFile) manifestFile.readLines().toSet() else emptySet()
        return runtimeClasspath.map { entry ->
            if (entry.name in remappedNames) File(stubDir, entry.name) else entry
        }
    }

    private fun build(
        obfToFriendly: ProGuardMappings.Direction,
        runtimeClasspath: List<File>,
        stubDir: File,
        markerFile: File,
        manifestFile: File,
    ) {
        if (stubDir.exists()) stubDir.deleteRecursively()
        stubDir.mkdirs()

        val jarsToRemap = runtimeClasspath.filter { it.extension == "jar" }
        val obfClassNames = obfToFriendly.classes.keys

        val (needsRemap, _) = jarsToRemap.partition { jarContainsAnyOf(it, obfClassNames) }

        if (needsRemap.isNotEmpty()) {
            BytecodeRemapper.remapJars(
                inputJars = needsRemap,
                outputDir = stubDir,
                mapping = TinyMappingAdapter.toMappingProvider(obfToFriendly),
                classpathJars = jarsToRemap,
            )
        }

        manifestFile.writeText(needsRemap.joinToString("\n") { it.name })
        markerFile.writeText("ok")
    }

    private fun jarContainsAnyOf(jar: File, obfClassNames: Set<String>): Boolean {
        return obfClassNames.isNotEmpty() && try {
            ZipFile(jar).use { zip ->
                zip.entries().asSequence().any { entry ->
                    entry.name.endsWith(".class") && entry.name.removeSuffix(".class") in obfClassNames
                }
            }
        } catch (_: Exception) {
            true
        }
    }
}
