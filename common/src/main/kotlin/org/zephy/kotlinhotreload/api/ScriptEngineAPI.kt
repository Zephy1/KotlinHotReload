package org.zephy.kotlinhotreload.api

import org.zephy.kotlinhotreload.internal.ClasspathProjectIDRegistry
import org.zephy.kotlinhotreload.internal.ProjectManager
import org.zephy.kotlinhotreload.internal.ScriptEngine

object ScriptEngineAPI {
    private var projectManager: ProjectManager? = null

    internal fun bind(_projectManager: ProjectManager) {
        projectManager = _projectManager
    }

    fun registerProjectID(projectName: String, jars: List<java.io.File>) = ClasspathProjectIDRegistry.registerProjectID(projectName, jars)

    fun projectManager(): ProjectManager {
        return projectManager ?: error("ScriptEngineAPI was accessed before ${ScriptEngine.MOD_ID} finished initializing.")
    }
}
