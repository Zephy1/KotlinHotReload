package org.zephy.kotlinhotreload

import net.fabricmc.api.ModInitializer
import net.fabricmc.loader.api.FabricLoader
import org.zephy.kotlinhotreload.internal.ScriptEngine

class Entrypoint : ModInitializer {
    override fun onInitialize() {
        ScriptEngine.onInitialize(FabricLoader.getInstance().configDir.toFile())
    }
}
