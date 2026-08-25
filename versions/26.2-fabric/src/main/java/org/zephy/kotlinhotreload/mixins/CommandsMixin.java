package org.zephy.kotlinhotreload.mixins;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

//#if MC>26.1
import net.minecraft.commands.CommandBuildContext;
//#elseif MC>1.18.2
//$$import net.minecraft.command.CommandRegistryAccess;
//#endif

@Mixin(Commands.class)
public class CommandsMixin {
    @Inject(
        method = "<init>",
        at = @At("TAIL")
    )
    private void onInit(
        //#if MC<=1.15.2
        //Boolean isServer,
        //#elseif MC<=1.18.2
        //$$CommandManager.RegistrationEnvironment environment,
        //#elseif MC<26.1
        //$$CommandManager.RegistrationEnvironment environment,
        //$$CommandRegistryAccess registryAccess,
        //#else
        Commands.CommandSelection environment,
        CommandBuildContext registryAccess,
        //#endif
        CallbackInfo ci
    ) {
        Commands self = (Commands)(Object) this;
        CommandDispatcher<CommandSourceStack> dispatcher = self.getDispatcher();
        org.zephy.kotlinhotreload.Commands.register(dispatcher);
    }
}
