package org.zephy.kotlinhotreload

import net.fabricmc.api.ModInitializer
import net.fabricmc.loader.api.FabricLoader
import org.zephy.kotlinhotreload.internal.ScriptEngine

//#if MC<=1.18.2
//$$import net.fabricmc.fabric.api.command.v1.CommandRegistrationCallback
//#else
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
//#endif

class FabricEntrypoint : ModInitializer {
    override fun onInitialize() {
        val projectManager = ScriptEngine.onInitialize(FabricLoader.getInstance().configDir.toFile())
        //#if MC<=1.18.2
        //$$CommandRegistrationCallback.EVENT.register { dispatcher, _ ->
        //#else
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
        //#endif
            Commands.register(dispatcher, projectManager)
        }
    }
}
