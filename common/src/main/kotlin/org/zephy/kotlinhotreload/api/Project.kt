package org.zephy.kotlinhotreload.api

import org.zephy.kotlinhotreload.internal.ScriptEngine

abstract class Project {
    lateinit var projectName: String
        private set

    fun onLoad(projectName: String) {
        this.projectName = projectName
        onLoad()
    }

    open fun onLoad() { }
    open fun onUnload() { }

    fun registerPreprocessorVariable(name: String, value: Int) {
        ScriptEngine.projectManager.registerPreprocessorVariable(projectName, name, value)
    }
}
