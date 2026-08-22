package org.zephy.kotlinhotreload.api

abstract class Project {
    lateinit var projectName: String
        private set

    fun onLoad(projectName: String) {
        this.projectName = projectName
        onLoad()
    }

    open fun onLoad() { }
    open fun onUnload() { }
}
