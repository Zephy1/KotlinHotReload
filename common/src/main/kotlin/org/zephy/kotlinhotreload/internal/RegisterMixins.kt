package org.zephy.kotlinhotreload.internal

import com.google.gson.Gson
import org.spongepowered.asm.mixin.Mixins
import java.io.File

object RegisterMixins {
    private val gson = Gson()

    fun run(modLoader: ModLoader) {
        val engineRoot = File(modLoader.getConfigDir(), ScriptEngine.MOD_NAME)
        val projectsRoot = File(engineRoot, "projects")
        val cacheRoot = File(engineRoot, ".cache")

        val projectDirs = projectsRoot.listFiles { f -> f.isDirectory } ?: emptyArray()
        for (projectDir in projectDirs) {
            registerStagedMixins(modLoader, cacheRoot, projectDir.name)
        }

        Thread({
            try {
                val projectManager = ProjectManager(
                    projectsRoot = projectsRoot,
                    cacheRoot = cacheRoot,
                    baseClassLoader = javaClass.classLoader,
                )
                projectManager.stagePrelaunchMixins()
                System.err.println("${ScriptEngine.LOG_PREFIX} Background mixin compilation finished - restart the game to add any new mixins.")
            } catch (e: Throwable) {
                System.err.println("${ScriptEngine.LOG_PREFIX} Background mixin compilation failed: ${e.describe()}.")
            }
        }, "${ScriptEngine.MOD_ID}-mixin-precompile").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
            start()
        }
    }

    private fun registerStagedMixins(modLoader: ModLoader, cacheRoot: File, projectName: String) {
        val classesDir = File(cacheRoot, "build/$projectName/mixins/classes")
        if (!classesDir.isDirectory) return

        val configFiles = classesDir.listFiles { f ->
            f.isFile && f.name.startsWith("mixins.") && f.extension == "json"
        }
        if (configFiles.isNullOrEmpty()) return

        try {
            modLoader.addToClasspath(classesDir.toPath())
        } catch (e: Throwable) {
            System.err.println("${ScriptEngine.LOG_PREFIX} Failed to add staged mixin classes for project '$projectName' to the launch classpath: ${e.describe()}.")
            return
        }

        val aliasesByRealName = readAliases(classesDir)

        val registeredMixinNames = mutableListOf<String>()
        val registrationFailures = mutableMapOf<String, String>()
        for (configFile in configFiles) {
            try {
                Mixins.addConfiguration(configFile.name)
                val realNames = mixinClassNamesIn(configFile)
                registeredMixinNames += realNames
                realNames.forEach { real -> aliasesByRealName[real]?.let { registeredMixinNames += it } }
            } catch (e: Throwable) {
                val message = e.describe()
                System.err.println("${ScriptEngine.LOG_PREFIX} Failed to register mixin config '${configFile.name}' from project '$projectName': $message.")
                val realNames = mixinClassNamesIn(configFile)
                realNames.forEach { real ->
                    registrationFailures[real] = message
                    aliasesByRealName[real]?.forEach { alias -> registrationFailures[alias] = message }
                }
            }
        }

        if (registeredMixinNames.isNotEmpty()) {
            MixinStaging.markRegistered(projectName, registeredMixinNames)
        }
        if (registrationFailures.isNotEmpty()) {
            MixinStaging.markRegistrationErrors(projectName, registrationFailures)
        }
    }

    private fun readAliases(classesDir: File): Map<String, List<String>> {
        val aliasFile = File(classesDir, "aliases.json")
        if (!aliasFile.isFile) return emptyMap()

        return try {
            val type = com.google.gson.reflect.TypeToken.getParameterized(
                Map::class.java, String::class.java, String::class.java
            ).type
            val aliases: Map<String, String> = gson.fromJson(aliasFile.readText(), type) ?: emptyMap()
            aliases.entries.groupBy({ it.value }, { it.key })
        } catch (_: Throwable) {
            emptyMap()
        }
    }

    private fun mixinClassNamesIn(configFile: File): List<String> {
        val config = try {
            gson.fromJson(configFile.readText(), MixinConfigData::class.java) ?: return emptyList()
        } catch (_: Throwable) {
            return emptyList()
        }
        val prefix = if (config.`package`.isEmpty()) "" else "${config.`package`}."
        return config.mixins.map { "$prefix$it" }
    }
}
