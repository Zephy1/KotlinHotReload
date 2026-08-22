package org.zephy.kotlinhotreload.internal

import java.io.File
import java.util.concurrent.ConcurrentHashMap

object ClasspathProjectIDRegistry {
    private val projectIDs = ConcurrentHashMap<String, List<File>>()

    fun registerProjectID(projectName: String, jars: List<File>) {
        projectIDs[projectName] = jars.toList()
    }

    fun registerProjectID(projectName: String, jar: File) = registerProjectID(projectName, listOf(jar))

    fun unregisterProjectID(projectName: String) {
        projectIDs.remove(projectName)
    }

    fun isRegistered(projectName: String): Boolean = projectIDs.containsKey(projectName)

    fun registeredProjectIDs(): Set<String> = projectIDs.keys.toSet()

    fun resolve(_projectIDs: List<String>): List<File> {
        return _projectIDs
            .mapNotNull { projectIDs[it] }
            .flatten()
            .distinct()
    }

    fun unknownProjectIDs(_projectIDs: List<String>): List<String> {
        return _projectIDs.filterNot { projectIDs.containsKey(it) }
    }
}
