package org.zephy.kotlinhotreload

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import org.zephy.kotlinhotreload.internal.CompileDiagnostic
import org.zephy.kotlinhotreload.internal.ProjectManager
import org.zephy.kotlinhotreload.internal.ReloadOutcome

//#if MC<=1.15.2
//$$import net.minecraft.text.LiteralText
//#endif

//#if MC>1.19.4
import java.util.function.Supplier
//#endif

object Commands {
    private fun createText(message: String) :
        //#if MC<=1.19.4
        //$$Text
        //#else
        Component
        //#endif
    {
        //#if MC<=1.15.2
        //$$return LiteralText(message)
        //#elseif MC<=1.18.2
        //$$return Text
        //#elseif MC<=1.19.4
        //$$return Text
        //#else
        return Component
        //#endif

        //#if MC>1.15.2
            //#if MC<=1.18.2
            //$$    .of(message)
            //#else
                .literal(message)
            //#endif
        //#endif
    }

    private fun createMessage(message: String) :
        //#if MC<=1.19.4
        //$$Text
        //#else
        Supplier<Component>
        //#endif
    {
        val text = createText(message)
        //#if MC<=1.19.4
        //$$return text
        //#else
        return {
            text
        }
        //#endif
    }

    fun register(dispatcher: CommandDispatcher<CommandSourceStack>, projectManager: ProjectManager) {
        dispatcher.register(
            Commands.literal("script")
                .then(
                    Commands.literal("reload")
                        .then(
                            Commands.argument("name", StringArgumentType.word())
                                .suggests { _, builder ->
                                    projectManager.listProjectNames().forEach { builder.suggest(it) }
                                    builder.buildFuture()
                                }
                                .executes { ctx -> reload(ctx, projectManager) }
                        )
                )
                .then(
                    Commands.literal("list")
                        .executes { ctx -> list(ctx, projectManager) }
                )
                .then(
                    Commands.literal("unload")
                        .then(
                            Commands.argument("name", StringArgumentType.word())
                                .executes { ctx -> unload(ctx, projectManager) }
                        )
                )
        )
    }

    private fun reload(ctx: CommandContext<CommandSourceStack>, projectManager: ProjectManager): Int {
        val name = StringArgumentType.getString(ctx, "name")
        val source = ctx.source

        source.sendSuccess(createMessage("Compiling '$name'..."), false)

        when (val outcome = projectManager.reload(name)) {
            is ReloadOutcome.Success -> {
                source.sendSuccess(
                    createMessage("Loaded '$name' (load #${outcome.project.generation})"),
                    true,
                )
                outcome.warnings.forEach {
                    if (it.severity == CompileDiagnostic.Severity.ERROR) {
                        source.sendFailure(createText("  ERROR: $it"))
                    } else {
                        source.sendSuccess(createMessage("  WARNING: $it"), false)
                    }
                }
            }
            is ReloadOutcome.CompileFailure -> {
                val count = outcome.errors.size
                source.sendFailure(createText("Compile failed for '$name' ($count error${if (count == 1) "" else "s"}):"))
                outcome.errors.forEach { source.sendFailure(createText("  $it")) }
            }
            is ReloadOutcome.ProjectError -> {
                source.sendFailure(createText("Failed to load '$name': ${outcome.message}"))
            }
        }
        return 1
    }

    private fun list(ctx: CommandContext<CommandSourceStack>, projectManager: ProjectManager): Int {
        val source = ctx.source
        val available = projectManager.listProjectNames()
        val loaded = projectManager.listLoadedProjects().associateBy { it.name }

        if (available.isEmpty()) {
            source.sendSuccess(createMessage("No projects found."), false)
            return 1
        }

        available.forEach { name ->
            val status = loaded[name]?.let { "loaded, load #${it.generation}" } ?: "not loaded"
            source.sendSuccess(createMessage("$name - $status"), false)
        }
        return 1
    }

    private fun unload(ctx: CommandContext<CommandSourceStack>, projectManager: ProjectManager): Int {
        val name = StringArgumentType.getString(ctx, "name")
        val didUnload = projectManager.unload(name)
        ctx.source.sendSuccess(
            createMessage(if (didUnload) "Unloaded '$name'" else "'$name' isn't currently loaded."),
            true,
        )
        return 1
    }
}
