package org.zephy.kotlinhotreload.internal

import org.zephy.kotlinhotreload.api.Project
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

data class LoadedProject(
    val name: String,
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
    private val compiler = ScriptCompiler()
    private val dependencyResolver = MavenDependencyResolver(localRepoDir = File(cacheRoot, "maven"))
    private val loadedProjects = ConcurrentHashMap<String, LoadedProject>()

    private val projectLocks = ConcurrentHashMap<String, Any>()
    private fun lockFor(name: String): Any = projectLocks.computeIfAbsent(name) { Any() }

    private val engineOwnClasspath: List<File> by lazy {
        listOf(
            classpathEntryFor(Project::class.java),
            classpathEntryFor(kotlin.jvm.internal.Intrinsics::class.java),
        )
    }
    private val cachedRuntimeClasspath: List<File> by lazy { computeFullRuntimeClasspath() }
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

    private fun fullClasspathFor(name: String, metadata: ProjectMetadata): List<File> {
        val resolvedDependencyJars = dependencyResolver.resolve(
            metadata.dependencies,
            cacheKeyDir = File(cacheRoot, "deps/$name"),
        )
        val projectDependencyJars = ClasspathProjectIDRegistry.resolve(metadata.projectDependencies)
        return engineOwnClasspath + cachedRuntimeClasspath + projectDependencyJars + resolvedDependencyJars
    }

    fun listProjectNames(): List<String> =
        projectsRoot.listFiles { f -> f.isDirectory }?.map { it.name }?.sorted() ?: emptyList()

    fun projectsDirectory(): File = projectsRoot

    fun listLoadedProjects(): List<LoadedProject> = loadedProjects.values.toList()

    fun getLoaded(name: String): LoadedProject? = loadedProjects[name]

    fun reload(name: String): ReloadOutcome {
        if (!VALID_PROJECT_NAME.matches(name)) {
            return ReloadOutcome.ProjectError(
                "Invalid project name '$name' - only letters, digits, '-' and '_' are allowed."
            )
        }
        return synchronized(lockFor(name)) { reloadLocked(name) }
    }

    private fun reloadLocked(name: String): ReloadOutcome {
        val projectDir = File(projectsRoot, name)
        if (!projectDir.isDirectory) {
            return ReloadOutcome.ProjectError(
                "No project directory found at ${projectDir.absolutePath} - check the spelling, or run /script list to see available projects."
            )
        }

        val sourceFiles = sourceFilesIn(projectDir)
        if (sourceFiles.isEmpty()) {
            return ReloadOutcome.ProjectError(
                "Project '$name' has no .kt source files in ${projectDir.absolutePath}."
            )
        }

        val metadata = try {
            ProjectMetadata.parse(File(projectDir, "metadata.json"))
        } catch (e: Exception) {
            return ReloadOutcome.ProjectError(e.message ?: "Failed to parse metadata.json.")
        }

        val unknownProjectIDs = ClasspathProjectIDRegistry.unknownProjectIDs(metadata.projectDependencies)

        val fullClasspath = try {
            fullClasspathFor(name, metadata)
        } catch (e: Exception) {
            return ReloadOutcome.ProjectError("Dependency resolution failed: ${e.describe()}.")
        }

        val nextGeneration = (loadedProjects[name]?.generation ?: 0) + 1
        val outputDir = File(cacheRoot, "build/$name/gen-$nextGeneration")
        if (outputDir.exists()) outputDir.deleteRecursively()


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
                listOf(
                    CompileDiagnostic(
                        severity = CompileDiagnostic.Severity.WARNING,
                        message = "Unknown classpath projectID(s) requested: ${unknownProjectIDs.joinToString()} - is the project that registers them installed?",
                        filePath = null, line = null, column = null,
                    )
                )
            } else emptyList()
            return ReloadOutcome.CompileFailure(compileResult.errors + extra)
        }

        val newMixins = metadata.mixins.filterNot { it in MixinStaging.registeredMixins(name) }
        val mixinDiagnostics = if (newMixins.isNotEmpty()) {
            val message = "New mixin(s) found: ${newMixins.joinToString()} - restart the game to register ${if (newMixins.size == 1) "it" else "them"}."
            System.err.println("${ScriptEngine.LOG_PREFIX} $message")
            listOf(
                CompileDiagnostic(
                    severity = CompileDiagnostic.Severity.ERROR,
                    message = message,
                    filePath = null, line = null, column = null,
                )
            )
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
            instance?.onLoad(name)
        } catch (e: Throwable) {
            newClassLoader.close()
            return ReloadOutcome.ProjectError("Entry point's onLoad() threw: ${e.describe()}.")
        }

        val previous = loadedProjects[name]
        if (previous?.instance != null) {
            try {
                previous.instance.onUnload()
            } catch (e: Throwable) {
                System.err.println("${ScriptEngine.LOG_PREFIX} onUnload() threw for project '$name': ${e.describe()}.")
            }
        }
        previous?.classLoader?.close()

        val loaded = LoadedProject(name, newClassLoader, instance, nextGeneration)
        loadedProjects[name] = loaded

        cleanupOldGenerations(name, nextGeneration)

        return ReloadOutcome.Success(loaded, compileResult.warnings + mixinDiagnostics, newMixins)
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
                            System.err.println(
                                "${ScriptEngine.LOG_PREFIX} Mixin prelaunch setup crashed for project '$name': ${e.describe()}."
                            )
                            emptyList()
                        }
                    })
                }
                .flatMap { it.get() }
        } finally {
            pool.shutdown()
        }
    }

    private fun stagePrelaunchMixinsForLocked(name: String): List<String> {
        val projectDir = File(projectsRoot, name)
        if (!projectDir.isDirectory) return emptyList()

        val sourceFiles = sourceFilesIn(projectDir)
        if (sourceFiles.isEmpty()) return emptyList()

        val metadataFile = File(projectDir, "metadata.json")
        val metadata = try {
            ProjectMetadata.parse(metadataFile)
        } catch (e: Exception) {
            System.err.println(
                "${ScriptEngine.LOG_PREFIX} Skipping mixin prelaunch setup for '$name' - failed to parse metadata.json: ${e.describe()}."
            )
            return emptyList()
        }
        if (metadata.mixins.isEmpty()) return emptyList()

        val outputDir = File(cacheRoot, "build/$name/prelaunch")
        val signatureFile = File(cacheRoot, "build/$name/prelaunch.sig")
        val signature = sourceSignature(metadataFile, sourceFiles)

        if (outputDir.isDirectory && signatureFile.isFile && signatureFile.readText() == signature) {
            return try {
                MixinStaging.stage(name, outputDir, cacheRoot, metadata.mixins)
            } catch (e: IllegalArgumentException) {
                System.err.println("${ScriptEngine.LOG_PREFIX} Mixin prelaunch setup failed for project '$name': ${e.describe()}.")
                emptyList()
            }
        }

        System.err.println("${ScriptEngine.LOG_PREFIX} Compiling mixins for project '$name'...")

        val depStart = System.currentTimeMillis()
        val fullClasspath = try {
            fullClasspathFor(name, metadata)
        } catch (e: Exception) {
            System.err.println(
                "${ScriptEngine.LOG_PREFIX} Skipping mixin prelaunch setup for '$name' - dependency resolution failed: ${e.describe()}."
            )
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
            System.err.println("${ScriptEngine.LOG_PREFIX} Mixin prelaunch setup failed for project '$name': ${e.describe()}.")
            emptyList()
        }
    }

    private fun sourceSignature(metadataFile: File, sourceFiles: List<File>): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        digest.update(metadataFile.readBytes())
        sourceFiles.sortedBy { it.absolutePath }.forEach { file ->
            digest.update(file.absolutePath.toByteArray())
            digest.update(file.lastModified().toString().toByteArray())
            digest.update(file.length().toString().toByteArray())
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun unload(name: String): Boolean {
        return synchronized(lockFor(name)) {
            val loaded = loadedProjects.remove(name) ?: return@synchronized false
            try {
                loaded.instance?.onUnload()
            } catch (e: Throwable) {
                System.err.println("${ScriptEngine.LOG_PREFIX} onUnload() threw for project '$name': ${e.describe()}.")
            }
            loaded.classLoader.close()
            true
        }
    }

    private fun cleanupOldGenerations(name: String, currentGeneration: Int) {
        val projectBuildDir = File(cacheRoot, "build/$name")
        val keepFrom = currentGeneration - 1
        projectBuildDir.listFiles { f -> f.isDirectory && f.name.startsWith("gen-") }?.forEach { dir ->
            val gen = dir.name.removePrefix("gen-").toIntOrNull()
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

    fun classpathEntryFor(clazz: Class<*>): File {
        val location = clazz.protectionDomain?.codeSource?.location
            ?: error("Could not determine the classpath location for ${clazz.name} - it may have been loaded from somewhere other than a regular jar file.")
        return File(location.toURI())
    }

    private fun computeFullRuntimeClasspath(): List<File> {
        return System.getProperty("java.class.path")
            .split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .map { File(it) }
            .filter { it.exists() }
    }
}
