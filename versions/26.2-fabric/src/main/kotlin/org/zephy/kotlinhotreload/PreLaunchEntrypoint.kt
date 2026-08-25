package org.zephy.kotlinhotreload

import net.fabricmc.loader.api.FabricLoader
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint
import net.fabricmc.loader.impl.launch.FabricLauncherBase
import org.zephy.kotlinhotreload.internal.ModLoader
import org.zephy.kotlinhotreload.internal.ModLoaderHolder
import org.zephy.kotlinhotreload.internal.ModLoaderType
import org.zephy.kotlinhotreload.internal.RegisterMixins
import org.zephy.kotlinhotreload.internal.ScriptPreprocessor
import java.io.File
import java.nio.file.Path

class FabricModLoader : ModLoader {
    override val loaderType: ModLoaderType
        get() = ModLoaderType.FABRIC
    override fun getConfigDir(): File = FabricLoader.getInstance().configDir.toFile()
    override fun addToClasspath(path: Path) = FabricLauncherBase.getLauncher().addToClassPath(path)
    override fun getMcVersionString(): String =
        FabricLoader.getInstance()
            .getModContainer("minecraft").get()
            .metadata.version.friendlyString
    override fun getMcVersionInt(): Int =
        ScriptPreprocessor.parseVersionOrNull(getMcVersionString())!!
}

class PreLaunchEntrypoint : PreLaunchEntrypoint {
    override fun onPreLaunch() {
        ModLoaderHolder.instance = FabricModLoader()
        RegisterMixins.run(ModLoaderHolder.instance)
    }
}
