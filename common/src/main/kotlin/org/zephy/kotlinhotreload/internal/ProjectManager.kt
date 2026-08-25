package org.zephy.kotlinhotreload.internal

import org.zephy.kotlinhotreload.api.Project
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

data class LoadedProject(
    val projectName: String,
    val classLoader: ProjectClassLoader,
    val instance: Project?,
    val generation: Int,
)

sealed class ReloadOutcome {
    data class Success(val project: LoadedProject, val warnings: List<CompileDiagnostic>, val newMixins: List<String> = emptyList()) : ReloadOutcome()
    data class CompileFailure(val errors: List<CompileDiagnostic>) : ReloadOutcome()
    data class ProjectError(val message: String) : ReloadOutcome()
}

class ProjectManager(
    private val projectsRoot: File,
    private val cacheRoot: File,
    private val baseClassLoader: ClassLoader,
) {
    private val versionTokenRegex = Regex("(?:^|/)(minecraft-)?(\\d+\\.\\d+\\.\\d+(?:[-.][A-Za-z0-9.]+)?)(?:/|\\.jar$)")
    private val compiler = ScriptCompiler()
    private val dependencyResolver = MavenDependencyResolver(localRepoDir = File(cacheRoot, "maven"))
    private val loadedProjects = ConcurrentHashMap<String, LoadedProject>()

    private val projectLocks = ConcurrentHashMap<String, Any>()
    private fun lockFor(projectName: String): Any = projectLocks.computeIfAbsent(projectName) { Any() }

    private val engineOwnClasspath: List<File> by lazy {
        listOf(
            classpathEntryFor(Project::class.java),
            classpathEntryFor(kotlin.jvm.internal.Intrinsics::class.java),
        )
    }
    private val cachedRuntimeClasspath: List<File> by lazy {
        computeFullRuntimeClasspath()
    }

    private val mcVersionInt: Int by lazy { ModLoaderHolder.instance.getMcVersionInt() }
    private val mcVersionString: String by lazy { ModLoaderHolder.instance.getMcVersionString() }


    private val neoForgeRuntimeIsOfficial: Boolean by lazy {
        if (ModLoaderHolder.instance.loaderType != ModLoaderType.NEOFORGE) return@lazy false
        try {
            Class.forName("net.minecraft.world.entity.Entity", false, baseClassLoader)
            true
        } catch (e: Throwable) {
            false
        }
    }

    private val globalPreprocessorVariables: MutableMap<String, Int> = mutableMapOf(
        "MC" to mcVersionInt,
        "FABRIC" to if (ModLoaderHolder.instance.loaderType == ModLoaderType.FABRIC) 1 else 0,
        "NEOFORGE" to if (ModLoaderHolder.instance.loaderType == ModLoaderType.NEOFORGE) 1 else 0,
    )
    private val projectPreprocessorVariables: MutableMap<String, MutableMap<String, Int>> = mutableMapOf()
    fun registerPreprocessorVariable(projectName: String, variableName: String, value: Int) {
        projectPreprocessorVariables.getOrPut(projectName) { mutableMapOf() }[variableName] = value
    }
    fun getPreprocessorVariables(projectName: String): Map<String, Int> {
        val scoped = projectPreprocessorVariables[projectName] ?: emptyMap()
        return globalPreprocessorVariables + scoped
    }

    private val VALID_PROJECT_NAME = Regex("^[A-Za-z0-9_-]+$")

    private fun sourceFilesIn(projectDir: File): List<File> =
        projectDir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    private val PACKAGE_DECLARATION = Regex("""^\s*package\s+([A-Za-z_][A-Za-z0-9_.]*)""", RegexOption.MULTILINE)

    private fun sourceKeyFor(file: File): String {
        val pkg = PACKAGE_DECLARATION.find(file.readText())?.groupValues?.get(1) ?: ""
        val base = file.nameWithoutExtension
        return if (pkg.isEmpty()) base else "$pkg.$base"
    }

    private class MixinSourceFilterResult(val sourceFiles: List<File>, val excludedNames: List<String>)

    private fun filterOutOfVersionMixinSources(sourceFiles: List<File>, metadata: ProjectMetadata): MixinSourceFilterResult {
        val declaredNames = metadata.mixins.map { it.name }
        if (declaredNames.isEmpty()) return MixinSourceFilterResult(sourceFiles, emptyList())

        val excludedNames = declaredNames.filterNot(metadata.mixins.applicable(mcVersionInt).toSet()::contains)
        if (excludedNames.isEmpty()) return MixinSourceFilterResult(sourceFiles, emptyList())

        val excludedFullKeys = excludedNames.toSet()
        val excludedSimpleNames = excludedNames.map { it.substringAfterLast('.') }.toSet()

        val filtered = sourceFiles.filterNot { file ->
            file.extension == "kt" &&
                    (file.nameWithoutExtension in excludedSimpleNames || sourceKeyFor(file) in excludedFullKeys)
        }
        return MixinSourceFilterResult(filtered, excludedNames)
    }

    private fun fullClasspathFor(name: String, metadata: ProjectMetadata): List<File> {
        val resolvedDependencyJars = dependencyResolver.resolve(
            metadata.dependencies,
            cacheKeyDir = File(cacheRoot, "deps/$name"),
        )
        val projectDependencyJars = ClasspathProjectIDRegistry.resolve(metadata.projectDependencies)
        return engineOwnClasspath + compileTimeClasspath + projectDependencyJars + resolvedDependencyJars
    }

    fun listProjectNames(): List<String> =
        projectsRoot.listFiles { f -> f.isDirectory }?.map { it.name }?.sorted() ?: emptyList()

    fun projectsDirectory(): File = projectsRoot

    fun listLoadedProjects(): List<LoadedProject> = loadedProjects.values.toList()

    fun getLoaded(projectName: String): LoadedProject? = loadedProjects[projectName]

    fun reload(projectName: String): ReloadOutcome {
        if (!VALID_PROJECT_NAME.matches(projectName)) {
            return ReloadOutcome.ProjectError("Invalid project name '$projectName' - only letters, digits, '-' and '_' are allowed.")
        }
        return synchronized(lockFor(projectName)) { reloadLocked(projectName) }
    }

    private fun reloadLocked(projectName: String): ReloadOutcome {
        val projectDir = File(projectsRoot, projectName)
        if (!projectDir.isDirectory) {
            return ReloadOutcome.ProjectError("No project directory found at ${projectDir.absolutePath}.")
        }

        val sourceFiles = sourceFilesIn(projectDir)
        if (sourceFiles.isEmpty()) {
            return ReloadOutcome.ProjectError("Project '$projectName' has no .kt source files in ${projectDir.absolutePath}.")
        }

        val metadata = try {
            ProjectMetadata.parse(File(projectDir, "metadata.json"))
        } catch (e: Exception) {
            return ReloadOutcome.ProjectError(e.message ?: "Failed to parse metadata.json.")
        }

        val unknownProjectIDs = ClasspathProjectIDRegistry.unknownProjectIDs(metadata.projectDependencies)

        val fullClasspath = try {
            fullClasspathFor(projectName, metadata)
        } catch (e: Exception) {
            return ReloadOutcome.ProjectError("Dependency resolution failed: ${e.describe()}.")
        }

        val nextGeneration = (loadedProjects[projectName]?.generation ?: 0) + 1
        val outputDir = File(cacheRoot, "build/$projectName/gen-$nextGeneration")
        if (outputDir.exists()) outputDir.deleteRecursively()

        val mixinSourceFilter = filterOutOfVersionMixinSources(sourceFiles, metadata)

        val preprocessedDir = File(cacheRoot, "build/$projectName/preprocessed-gen-$nextGeneration")
        val preprocessedSources = try {
            ScriptPreprocessor.stage(mixinSourceFilter.sourceFiles, projectDir, preprocessedDir, getPreprocessorVariables(projectName))
        } catch (e: ScriptPreprocessor.PreprocessException) {
            return ReloadOutcome.CompileFailure(listOf(errorDiagnostic(e.message ?: "Preprocessing failed.")))
        }

        val compileResult: CompileResult = compiler.compile(
            sourceFiles = preprocessedSources,
            classpathEntries = fullClasspath,
            outputDir = outputDir,
        )

        if (!compileResult.success) {
            val extra = if (unknownProjectIDs.isNotEmpty()) {
                listOf(warningDiagnostic("Unknown classpath projectID(s) requested: ${unknownProjectIDs.joinToString()}."))
            } else emptyList()
            return ReloadOutcome.CompileFailure(compileResult.errors + extra)
        }

        val newMixins = metadata.mixins.filterNot { it in MixinStaging.registeredMixins(name) }
        val mappingsDiagnostics = namingResult.warning?.let { listOf(warningDiagnostic(it)) } ?: emptyList()
        val versionGatedMixinDiagnostics = if (mixinSourceFilter.excludedNames.isNotEmpty()) {
            listOf(warningDiagnostic("Skipped compiling mixin(s) not applicable to MC $mcVersionString: ${mixinSourceFilter.excludedNames.joinToString()}."))
        } else emptyList()

        val applicableMixinNames = metadata.mixins.applicable(mcVersionInt)
        val newMixins = applicableMixinNames.filterNot { it in MixinStaging.registeredMixins(projectName) }
        val mixinDiagnostics = if (newMixins.isNotEmpty()) {
            val message = "New mixin(s) found: ${newMixins.joinToString()} - restart the game to register ${if (newMixins.size == 1) "it" else "them"}."
            System.err.println("${ScriptEngine.LOG_PREFIX} $message")
            listOf(errorDiagnostic(message))
        } else emptyList()

        val newClassLoader = ProjectClassLoader(name, compileResult.outputDir!!, baseClassLoader).apply {
            generation = nextGeneration
        }

        val instance: Project? = try {
            instantiateEntryPoint(newClassLoader, compileResult.outputDir, metadata)
        } catch (e: Exception) {
            newClassLoader.close()
            return ReloadOutcome.ProjectError("Failed to instantiate entry point: ${e.describe()}.")
        }

        try {
            instance?.onLoad(projectName)
        } catch (e: Throwable) {
            newClassLoader.close()
            return ReloadOutcome.ProjectError("Entry point's onLoad() threw: ${e.describe()}.")
        }

        val previous = loadedProjects[projectName]
        if (previous?.instance != null) {
            try {
                previous.instance.onUnload()
            } catch (e: Throwable) {
                System.err.println("${ScriptEngine.LOG_PREFIX} onUnload() threw for project '$projectName': ${e.describe()}.")
            }
        }
        previous?.classLoader?.close()

        val loaded = LoadedProject(projectName, newClassLoader, instance, nextGeneration)
        loadedProjects[projectName] = loaded

        cleanupOldGenerations(projectName, nextGeneration)

        return ReloadOutcome.Success(loaded, compileResult.warnings + mixinDiagnostics + mappingsDiagnostics + versionGatedMixinDiagnostics, newMixins)
    }

    fun stagePrelaunchMixins(): List<String> {
        val projectNames = listProjectNames().filter { VALID_PROJECT_NAME.matches(it) }
        if (projectNames.isEmpty()) return emptyList()

        val pool = Executors.newFixedThreadPool(
            minOf(Runtime.getRuntime().availableProcessors(), projectNames.size)
        )
        return try {
            projectNames
                .map { name ->
                    pool.submit(Callable {
                        try {
                            synchronized(lockFor(name)) { stagePrelaunchMixinsForLocked(name) }
                        } catch (e: Throwable) {
                            System.err.println("${ScriptEngine.LOG_PREFIX} Mixin prelaunch setup crashed for project '$name': ${e.describe()}.")
                            emptyList()
                        }
                    })
                }
                .flatMap { it.get() }
        } finally {
            pool.shutdown()
        }
    }

    private fun stagePrelaunchMixinsForLocked(projectName: String): List<String> {
        val projectDir = File(projectsRoot, projectName)
        if (!projectDir.isDirectory) return emptyList()

        val sourceFiles = sourceFilesIn(projectDir)
        if (sourceFiles.isEmpty()) return emptyList()

        val metadataFile = File(projectDir, "metadata.json")
        val metadata = try {
            ProjectMetadata.parse(metadataFile)
        } catch (e: Exception) {
            System.err.println("${ScriptEngine.LOG_PREFIX} Skipping mixin prelaunch setup for '$projectName' - failed to parse metadata.json: ${e.describe()}.")
            return emptyList()
        }
        val applicableMixinNames = metadata.mixins.applicable(mcVersionInt)
        if (applicableMixinNames.isEmpty()) {
            buildFile(projectName, "mixins").deleteRecursively()
            buildFile(projectName, "prelaunch-remapped").deleteRecursively()
            buildFile(projectName, "prelaunch.sig").delete()
            return emptyList()
        }

        val compileOutputDir = buildFile(projectName, "prelaunch")
        val signatureFile = buildFile(projectName, "prelaunch.sig")
        val signature = sourceSignature(metadataFile, sourceFiles, projectName)

        if (outputDir.isDirectory && signatureFile.isFile && signatureFile.readText() == signature) {
            return try {
                MixinStaging.stage(name, outputDir, cacheRoot, metadata.mixins)
            } catch (e: IllegalArgumentException) {
                System.err.println("${ScriptEngine.LOG_PREFIX} Mixin prelaunch setup failed for project '$projectName': ${e.describe()}.")
                emptyList()
            }
        }

        System.err.println("${ScriptEngine.LOG_PREFIX} Compiling mixins for project '$projectName'...")

        val depStart = System.currentTimeMillis()
        val fullClasspath = try {
            fullClasspathFor(projectName, metadata)
        } catch (e: Exception) {
            System.err.println("${ScriptEngine.LOG_PREFIX} Skipping mixin prelaunch setup for '$projectName' - dependency resolution failed: ${e.describe()}.")
            return emptyList()
        }
        val depMs = System.currentTimeMillis() - depStart

        if (compileOutputDir.exists()) compileOutputDir.deleteRecursively()

        val preprocessedDir = File(cacheRoot, "build/$projectName/prelaunch-preprocessed")
        val mixinSourceFilter = filterOutOfVersionMixinSources(sourceFiles, metadata)
        if (mixinSourceFilter.excludedNames.isNotEmpty()) {
            System.err.println("${ScriptEngine.LOG_PREFIX} Skipping compilation of mixin(s) not applicable to MC $mcVersionString for project '$projectName': ${mixinSourceFilter.excludedNames.joinToString()}.")
        }
        val preprocessedSources = try {
            ScriptPreprocessor.stage(mixinSourceFilter.sourceFiles, projectDir, preprocessedDir, getPreprocessorVariables(projectName))
        } catch (e: ScriptPreprocessor.PreprocessException) {
            System.err.println("${ScriptEngine.LOG_PREFIX} Skipping mixin prelaunch setup for '$projectName' - preprocessing failed: ${e.message}")
            return emptyList()
        }

        val compileStart = System.currentTimeMillis()
        val compileResult = compiler.compile(
            sourceFiles = preprocessedSources,
            classpathEntries = fullClasspath,
            outputDir = compileOutputDir,
        )
        val compileMs = System.currentTimeMillis() - compileStart
        if (!compileResult.success) {
            System.err.println("${ScriptEngine.LOG_PREFIX} Skipping mixin prelaunch setup for '$name' - compile failed:")
            System.err.println("${ScriptEngine.LOG_PREFIX} Skipping mixin prelaunch setup for '$projectName' - compile failed:")
            compileResult.errors.forEach { System.err.println("  $it") }
            return emptyList()
        }

        return try {
            val staged = MixinStaging.stage(name, compileResult.outputDir!!, cacheRoot, metadata.mixins)
            signatureFile.parentFile.mkdirs()
            signatureFile.writeText(signature)
            System.err.println(
                "${ScriptEngine.LOG_PREFIX} Mixins for '$name' staged in ${depMs + compileMs}ms (dependency resolution: ${depMs}ms, compile: ${compileMs}ms)"
            )
            staged
        } catch (e: IllegalArgumentException) {
            System.err.println("${ScriptEngine.LOG_PREFIX} Mixin prelaunch setup failed for project '$projectName': ${e.describe()}.")
            emptyList()
        }
    }

    private fun sourceSignature(metadataFile: File, sourceFiles: List<File>, projectName: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val preprocessorVariables = getPreprocessorVariables(projectName).toSortedMap()
        digest.update(metadataFile.readBytes())
        preprocessorVariables.forEach { (name, value) ->
            digest.updateUtf8(name)
            digest.updateUtf8(value.toString())
        }
        sourceFiles.sortedBy { it.absolutePath }.forEach { file ->
            digest.updateUtf8(file.absolutePath)
            digest.updateUtf8(file.lastModified().toString())
            digest.updateUtf8(file.length().toString())
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun unload(projectName: String): Boolean {
        return synchronized(lockFor(projectName)) {
            val loaded = loadedProjects.remove(projectName) ?: return@synchronized false
            try {
                loaded.instance?.onUnload()
            } catch (e: Throwable) {
                System.err.println("${ScriptEngine.LOG_PREFIX} onUnload() threw for project '$projectName': ${e.describe()}.")
            }
            loaded.classLoader.close()
            true
        }
    }

    private fun cleanupOldGenerations(projectName: String, currentGeneration: Int) {
        val projectBuildDir = buildDir(projectName)
        val keepFrom = currentGeneration - 1
        projectBuildDir.listFiles { f ->
            f.isDirectory && (f.name.startsWith("gen-") || f.name.startsWith("preprocessed-gen-") || f.name.startsWith("remapped-gen-"))
        }?.forEach { dir ->
            val gen = when {
                dir.name.startsWith("remapped-gen-") -> dir.name.removePrefix("remapped-gen-").toIntOrNull()
                dir.name.startsWith("preprocessed-gen-") -> dir.name.removePrefix("preprocessed-gen-").toIntOrNull()
                else -> dir.name.removePrefix("gen-").toIntOrNull()
            }
            if (gen != null && gen < keepFrom) {
                dir.deleteRecursively()
            }
        }
    }

    private fun instantiateEntryPoint(
        classLoader: ProjectClassLoader,
        outputDir: File,
        metadata: ProjectMetadata,
    ): Project? {
        val explicitName = metadata.entryPointClass
        if (explicitName != null) {
            val clazz = Class.forName(explicitName, true, classLoader)
            return newProjectInstance(clazz)
        }

        val candidateClassNames = outputDir.walkTopDown()
            .filter { it.isFile && it.extension == "class" }
            .map { classFile ->
                classFile.relativeTo(outputDir).path
                    .removeSuffix(".class")
                    .replace(File.separatorChar, '.')
            }
            .filterNot { it.contains("$") }
            .toList()

        val implementors = candidateClassNames.mapNotNull { name ->
            try {
                val clazz = Class.forName(name, false, classLoader)
                if (Project::class.java.isAssignableFrom(clazz) && !clazz.isInterface) clazz else null
            } catch (e: Throwable) {
                System.err.println("${ScriptEngine.LOG_PREFIX} Failed to instantiate project class '$name': ${e.describe()}.")
                null
            }
        }

        return when (implementors.size) {
            0 -> null
            1 -> newProjectInstance(implementors[0])
            else -> throw IllegalStateException(
                "Multiple classes implement Project (${implementors.joinToString { it.name }}) - add `entryPoint = \"...\"` to metadata.json to specify which one to use."
            )
        }
    }

    private fun newProjectInstance(clazz: Class<*>): Project {
        val instance = clazz.getDeclaredConstructor().newInstance()
        return instance as? Project
            ?: throw IllegalStateException("Entry point class ${clazz.name} does not implement the Project interface.")
    }

    private fun computeFullRuntimeClasspath(): List<File> {
        val seen = LinkedHashMap<String, File>()

        fun addCandidate(path: String) {
            if (path.isBlank()) return
            val file = File(path).canonicalFile
            if (!file.exists() || !file.isFile || !isRelevantRuntimeClasspathEntry(file)) return
            seen[file.absolutePath.lowercase()] = file
        }

        System.getProperty("java.class.path")
            ?.split(File.pathSeparator)
            ?.forEach(::addCandidate)

        var loader: ClassLoader? = baseClassLoader
        while (loader != null) {
            if (loader is java.net.URLClassLoader) {
                loader.urLs.forEach { url ->
                    try {
                        addCandidate(File(url.toURI()).absolutePath)
                    } catch (_: Throwable) {
                        // Ignore errors
                    }
                }
            }
            loader = loader.parent
        }

        val entries = seen.values.toList()
        val remappedVersions = entries
            .map { normalizedPath(it) }
            .filter { it.contains("/.fabric/remappedjars/") }
            .mapNotNull { versionTokenFromPath(it) }
            .toHashSet()

        val filtered = entries.filter { entry ->
            val path = normalizedPath(entry)
            if (!path.contains("/meta/versions/")) return@filter true
            val token = versionTokenFromPath(path) ?: return@filter true
            token !in remappedVersions
        }

        return filtered.sortedBy { it.absolutePath.lowercase() }
    }

    private fun isRelevantRuntimeClasspathEntry(file: File): Boolean {
        val path = normalizedPath(file)

        if (path.contains("/mods/") || path.contains("/processedmods/")) {
            return false
        }

        return path.contains("/meta/libraries/") ||
            path.contains("/meta/versions/") ||
            path.contains("/.fabric/remappedjars/") ||
            path.contains("/fabric/loader/") ||
            path.contains("/fabric-api-")
    }

    private fun versionTokenFromPath(path: String): String? {
        return versionTokenRegex.find(path)
            ?.groupValues
            ?.getOrNull(2)
    }

    private fun normalizedPath(file: File): String = file.absolutePath.replace('\\', '/').lowercase()

    private fun buildDir(projectName: String): File = File(cacheRoot, "build/$projectName")

    private fun buildFile(projectName: String, relativePath: String): File = File(buildDir(projectName), relativePath)
}
