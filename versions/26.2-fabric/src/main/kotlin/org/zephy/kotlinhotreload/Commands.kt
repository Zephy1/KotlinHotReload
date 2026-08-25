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
import org.zephy.kotlinhotreload.internal.ScriptEngine
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.util.function.Supplier

object Commands {
    private val sourceClass: Class<*>? = tryClass(
        "net.minecraft.commands.CommandSourceStack",
        "net.minecraft.server.command.ServerCommandSource",
        "net.minecraft.class_2168",
    )
    private val componentClass: Class<*>? = tryClass(
        "net.minecraft.network.chat.Component",
        "net.minecraft.text.Text",
        "net.minecraft.class_2561",
    )
    private val literalTextClass: Class<*>? = tryClass(
        "net.minecraft.network.chat.TextComponent",
        "net.minecraft.text.LiteralText",
        "net.minecraft.class_2585",
    )

    private fun tryClass(vararg names: String): Class<*>? {
        for (name in names) {
            try {
                return Class.forName(name)
            } catch (_: ReflectiveOperationException) {
                // Ignore errors
            }
        }
        return null
    }

    private fun tryMethod(clazz: Class<*>, paramCount: Int = 1, vararg names: String): Method? {
        for (name in names) {
            try {
                return clazz.methods.firstOrNull {
                    it.name == name && it.parameterCount == paramCount
                }
            } catch (_: ReflectiveOperationException) {
                // Ignore errors
            }
        }
        return null
    }

    // 1.14.4 - 1.18.2
    private val literalTextConstructor: Constructor<*>? = try {
        literalTextClass?.let {
            try {
                it.getConstructor(String::class.java)
            } catch (_: ReflectiveOperationException) {
                null
            }
        }
    } catch (_: ReflectiveOperationException) {
        null
    }

    // 1.19+
    private val componentLiteral: Method? = try {
        componentClass?.let { clazz ->
            clazz.methods.firstOrNull {
                it.name in setOf("literal", "of", "method_30163", "method_43470") && it.parameterTypes.contentEquals(arrayOf(String::class.java))
            }
        }
    } catch (_: ReflectiveOperationException) {
        null
    }

    fun createText(message: String): Component {
        componentLiteral?.let {
            return it.invoke(null, message)!! as Component
        }
        literalTextConstructor?.let {
            return it.newInstance(message) as Component
        }
        error("No Component/Text API found on classpath.")
    }

    fun createMessage(message: String): Supplier<Any> {
        val text = createText(message)
        return Supplier { text }
    }

    private val sendSuccessMethod: Method? = sourceClass?.methods?.firstOrNull {
        (it.name == "sendSuccess" || it.name == "method_9226" || it.name == "method_45068") && it.parameterCount == 2
    }

    private val sendFailureMethod: Method? = sourceClass?.methods?.firstOrNull {
        (it.name == "sendFailure" || it.name == "method_9213") && it.parameterCount == 1
    }

    fun sendSuccess(source: Any, message: String, broadcast: Boolean) {
        val method = sendSuccessMethod ?: error("sendSuccess not found")
        val firstParam = method.parameterTypes[0]
        val arg: Any = if (Supplier::class.java.isAssignableFrom(firstParam)) {
            createMessage(message)
        } else {
            createText(message)
        }
        method.invoke(source, arg, broadcast)
    }

    fun sendFailure(source: Any, message: String) {
        val method = sendFailureMethod ?: error("sendFailure not found")
        method.invoke(source, createText(message))
    }

    @JvmStatic
    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            Commands.literal("script")
                .then(
                    Commands.literal("reload")
                        .then(
                            Commands.argument("name", StringArgumentType.word())
                                .suggests { _, builder ->
                                    ScriptEngine.projectManager.listProjectNames().forEach { builder.suggest(it) }
                                    builder.buildFuture()
                                }
                                .executes { ctx ->
                                    try {
                                        reload(ctx, ScriptEngine.projectManager)
                                    } catch (e: Throwable) {
                                        e.printStackTrace()
                                        0
                                    }
                                }
                        )
                )
                .then(
                    Commands.literal("list")
                        .executes { ctx ->
                            try {
                                list(ctx, ScriptEngine.projectManager)
                            } catch (e: Throwable) {
                                e.printStackTrace()
                                0
                            }
                        }
                )
                .then(
                    Commands.literal("unload")
                        .then(
                            Commands.argument("name", StringArgumentType.word())
                                .executes { ctx ->
                                    try {
                                        unload(ctx, ScriptEngine.projectManager)
                                    } catch (e: Throwable) {
                                        e.printStackTrace()
                                        0
                                    }
                                }
                        )
                )
        )
    }

    private fun reload(ctx: CommandContext<CommandSourceStack>, projectManager: ProjectManager): Int {
        val name = StringArgumentType.getString(ctx, "name")
        val source = ctx.source

        sendSuccess(source, "Compiling '$name'...", false)

        when (val outcome = projectManager.reload(name)) {
            is ReloadOutcome.Success -> {
                sendSuccess(source, "Loaded '$name' (load #${outcome.project.generation})", true)
                outcome.warnings.forEach {
                    if (it.severity == CompileDiagnostic.Severity.ERROR) {
                        sendFailure(source, "  ERROR: $it")
                    } else {
                        sendSuccess(source, "  WARNING: $it", false)
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
        val loaded = projectManager.listLoadedProjects().associateBy { it.projectName }

        if (available.isEmpty()) {
            sendSuccess(source, "No projects found.", false)
            return 1
        }

        available.forEach { name ->
            val status = loaded[name]?.let { "loaded, load #${it.generation}" } ?: "not loaded"
            sendSuccess(source, "$name - $status", false)
        }
        return 1
    }

    private fun unload(ctx: CommandContext<CommandSourceStack>, projectManager: ProjectManager): Int {
        val name = StringArgumentType.getString(ctx, "name")
        val didUnload = projectManager.unload(name)
        sendSuccess(
            ctx.source,
            if (didUnload) "Unloaded '$name'" else "'$name' isn't currently loaded.",
            true,
        )
        return 1
    }
}
