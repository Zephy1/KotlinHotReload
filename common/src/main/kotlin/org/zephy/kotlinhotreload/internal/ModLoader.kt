package org.zephy.kotlinhotreload.internal

import java.io.File
import java.nio.file.Path

enum class ModLoaderType {
    FABRIC,
    NEOFORGE,
    FORGE,
    ;
}

interface ModLoader {
    val loaderType: ModLoaderType
    fun getConfigDir(): File
    fun addToClasspath(path: Path)
    fun getMcVersionString(): String
    fun getMcVersionInt(): Int
}

object ModLoaderHolder {
    lateinit var instance: ModLoader
}
