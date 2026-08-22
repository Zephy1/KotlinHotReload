package org.zephy.kotlinhotreload.internal

import org.zephy.kotlinhotreload.api.ScriptEngineAPI
import java.io.File

object ScriptEngine {
    const val MOD_ID = "kotlin-hot-reload"
    const val MOD_NAME = "Kotlin Hot Reload"
    const val LOG_PREFIX = "[$MOD_ID]"

    lateinit var CONFIG_DIR: File
    lateinit var PROJECTS_DIR: File
    lateinit var CACHE_DIR: File

    lateinit var projectManager: ProjectManager
        private set

    fun onInitialize(baseConfigDir: File): ProjectManager {
        CONFIG_DIR = baseConfigDir.resolve(MOD_NAME).apply { mkdirs() }
        PROJECTS_DIR = CONFIG_DIR.resolve("projects").apply { mkdirs() }
        CACHE_DIR = CONFIG_DIR.resolve(".cache").apply { mkdirs() }

        val baseClassLoader = ScriptEngine::class.java.classLoader

        projectManager = ProjectManager(
            projectsRoot = PROJECTS_DIR,
            cacheRoot = CACHE_DIR,
            baseClassLoader = baseClassLoader,
        )

        ScriptEngineAPI.bind(projectManager)

        println("$LOG_PREFIX Ready. Projects directory: ${PROJECTS_DIR.absolutePath}.")

        return projectManager
    }
}
