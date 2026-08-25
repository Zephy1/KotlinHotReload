package org.zephy.kotlinhotreload.internal

import com.google.gson.GsonBuilder
import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Opcodes
import java.io.File
import java.util.concurrent.ConcurrentHashMap

enum class MixinLoadState {
    LOADED,
    PENDING_RESTART,
    ERROR,
    ;
}

data class MixinStatus(
    val mixinClassName: String,
    val state: MixinLoadState,
    val message: String? = null,
)

data class MixinConfigData(
    val required: Boolean = true,
    val minVersion: String = "0.8",
    val compatibilityLevel: String = "JAVA_${ScriptCompiler.JavaVersion.jvmVersion.replace("1.8", "8")}",
    val `package`: String,
    val mixins: List<String>,
    val injectors: Map<String, Int> = mapOf("defaultRequire" to 1),
    val remap: Boolean = false,
)

object MixinStaging {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val registeredMixinsByProject = ConcurrentHashMap<String, Set<String>>()
    private val stagingErrorsByProject = ConcurrentHashMap<String, Map<String, String>>()
    private val registrationErrorsByProject = ConcurrentHashMap<String, Map<String, String>>()

    fun registeredMixins(projectName: String): Set<String> {
        return registeredMixinsByProject[projectName] ?: emptySet()
    }

    fun markRegistered(projectName: String, mixinClassNames: Collection<String>) {
        registeredMixinsByProject[projectName] = mixinClassNames.toSet()
    }

    fun markRegistrationErrors(projectName: String, failures: Map<String, String>) {
        if (failures.isEmpty()) return
        registrationErrorsByProject.merge(projectName, failures) { existing, new -> existing + new }
    }

    fun statusFor(projectName: String, declaredMixinClassNames: List<String>): List<MixinStatus> {
        val loaded = registeredMixins(projectName)
        val registrationErrors = registrationErrorsByProject[projectName] ?: emptyMap()
        val stagingErrors = stagingErrorsByProject[projectName] ?: emptyMap()

        return declaredMixinClassNames.map { name ->
            when {
                name in loaded -> MixinStatus(name, MixinLoadState.LOADED)
                registrationErrors.containsKey(name) ->
                    MixinStatus(name, MixinLoadState.ERROR, registrationErrors.getValue(name))
                stagingErrors.containsKey(name) ->
                    MixinStatus(name, MixinLoadState.ERROR, stagingErrors.getValue(name))
                else -> MixinStatus(name, MixinLoadState.PENDING_RESTART)
            }
        }
    }

    fun statusOf(projectName: String, mixinClassName: String): MixinStatus {
        return statusFor(projectName, listOf(mixinClassName)).first()
    }

    fun stage(
        projectName: String,
        compiledOutputDir: File,
        cacheRoot: File,
        mixinClassNames: List<String>,
    ): List<String> {
        val stageDir = File(cacheRoot, "build/$projectName/mixins")
        if (mixinClassNames.isEmpty()) {
            stageDir.deleteRecursively()
            return emptyList()
        }

        val compiledClasses = scanCompiledClasses(compiledOutputDir)
        val byQualifiedName = compiledClasses.associateBy { it.qualifiedName }
        val bySourceFileKey = compiledClasses.mapNotNull { it.sourceKeyOrNull()?.let { key -> key to it } }.toMap()
        val bySimpleClassName = compiledClasses.groupBy { it.qualifiedName.substringAfterLast('.') }
        val bySimpleSourceFileName = compiledClasses.mapNotNull { info -> info.sourceFileBaseName?.let { it to info } }.groupBy({ it.first }, { it.second })

        fun simpleNameCandidates(name: String): List<CompiledClassInfo> {
            val simple = name.substringAfterLast('.')
            return (bySimpleClassName[simple].orEmpty() + bySimpleSourceFileName[simple].orEmpty())
                .distinctBy { it.qualifiedName }
        }

        val failures = linkedMapOf<String, String>()
        val aliases = linkedMapOf<String, String>()
        val refs = mixinClassNames.mapNotNull { name ->
            val candidates = simpleNameCandidates(name)
            val info = byQualifiedName[name] ?: bySourceFileKey[name] ?: candidates.singleOrNull()
            if (info == null) {
                failures[name] = if (candidates.size > 1) {
                    "Ambiguous mixin name \"$name\" - multiple classes match name (${candidates.joinToString { it.qualifiedName }}) - use the full class name in metadata.json to disambiguate."
                } else {
                    "No compiled class was found for $name."
                }
                return@mapNotNull null
            }

            if (!info.isMixinAnnotated) {
                failures[name] = "The class $name has no @Mixin annotation."
                return@mapNotNull null
            }

            if (name != info.qualifiedName) {
                aliases[name] = info.qualifiedName
            }

            MixinClassRef(info.qualifiedName, info.relativePath)
        }

        stagingErrorsByProject[projectName] = failures

        if (failures.isNotEmpty()) {
            throw IllegalArgumentException("metadata.json lists ${failures.size} invalid mixin(s) for project \"$projectName\": ${failures.entries.joinToString("; ") { (name, reason) -> "\"$name\" - $reason" }}")
        }

        stageDir.deleteRecursively()
        val classesDir = File(stageDir, "classes")
        classesDir.mkdirs()

        for ((_, relativePath) in refs) {
            val src = File(compiledOutputDir, relativePath)
            val dst = File(classesDir, relativePath)
            dst.parentFile.mkdirs()
            src.copyTo(dst, overwrite = true)
        }

        val aliasFile = File(classesDir, "aliases.json")
        if (aliases.isNotEmpty()) {
            aliasFile.writeText(gson.toJson(aliases))
        }

        return refs
            .groupBy { it.packageName }
            .map { (pkg, group) ->
                val configName = mixinConfigName(projectName, pkg)
                val config = MixinConfigData(`package` = pkg, mixins = group.map { it.simpleName })
                File(classesDir, configName).writeText(gson.toJson(config))
                configName
            }
    }

    private data class MixinClassRef(val qualifiedName: String, val relativePath: String) {
        val packageName: String get() = qualifiedName.substringBeforeLast('.', "")
        val simpleName: String get() = qualifiedName.substringAfterLast('.')
    }

    private data class CompiledClassInfo(
        val qualifiedName: String,
        val relativePath: String,
        val sourceFileBaseName: String?,
        val isMixinAnnotated: Boolean,
    ) {
        val packageName: String get() = qualifiedName.substringBeforeLast('.', "")

        fun sourceKeyOrNull(): String? {
            val base = sourceFileBaseName ?: return null
            return if (packageName.isEmpty()) base else "$packageName.$base"
        }
    }

    private fun scanCompiledClasses(compiledOutputDir: File): List<CompiledClassInfo> {
        if (!compiledOutputDir.isDirectory) return emptyList()

        return compiledOutputDir.walkTopDown()
            .filter { it.isFile && it.extension == "class" }
            .map { file -> readClassInfo(file, compiledOutputDir) }
            .toList()
    }

    private fun readClassInfo(file: File, compiledOutputDir: File): CompiledClassInfo {
        var internalName = ""
        var sourceFile: String? = null
        var isMixin = false

        ClassReader(file.readBytes()).accept(object : ClassVisitor(Opcodes.ASM9) {
            override fun visit(
                version: Int,
                access: Int,
                name: String,
                signature: String?,
                superName: String?,
                interfaces: Array<out String>?,
            ) {
                internalName = name
            }

            override fun visitSource(source: String?, debug: String?) {
                sourceFile = source
            }

            override fun visitAnnotation(descriptor: String?, visible: Boolean): AnnotationVisitor? {
                if (descriptor == MIXIN_ANNOTATION_DESC) isMixin = true
                return null
            }
        }, ClassReader.SKIP_CODE or ClassReader.SKIP_FRAMES)

        return CompiledClassInfo(
            qualifiedName = internalName.replace('/', '.'),
            relativePath = file.relativeTo(compiledOutputDir).path,
            sourceFileBaseName = sourceFile?.substringBeforeLast('.'),
            isMixinAnnotated = isMixin,
        )
    }

    private fun mixinConfigName(projectName: String, pkg: String): String =
        "mixins.$projectName.${pkg.ifEmpty { "default" }}.json"
}
