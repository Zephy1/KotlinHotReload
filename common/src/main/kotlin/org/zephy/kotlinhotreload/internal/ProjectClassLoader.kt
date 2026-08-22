package org.zephy.kotlinhotreload.internal

import java.io.File
import java.net.URLClassLoader

class ProjectClassLoader(
    val projectName: String,
    outputDir: File,
    parent: ClassLoader,
) : URLClassLoader(
    arrayOf(outputDir.toURI().toURL()),
    parent,
) {
    var generation: Int = 0
        internal set

    override fun toString(): String = "ProjectClassLoader(project=$projectName, gen=$generation)"
}
