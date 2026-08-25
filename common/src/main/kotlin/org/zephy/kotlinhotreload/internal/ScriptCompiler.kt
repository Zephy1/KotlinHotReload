package org.zephy.kotlinhotreload.internal

import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class ScriptCompiler {
    private val compilerJar: File by lazy {
        val location = K2JVMCompiler::class.java.protectionDomain?.codeSource?.location
            ?: error("Could not determine the Kotlin compiler's own jar location.")
        File(location.toURI())
    }

    private val compilerLaunchClasspath: List<File> by lazy {
        val entries = LinkedHashSet<File>()

        var loader: ClassLoader? = K2JVMCompiler::class.java.classLoader
        while (loader != null) {
            if (loader is java.net.URLClassLoader) {
                loader.urLs.forEach { url ->
                    try {
                        entries += File(url.toURI())
                    } catch (_: Throwable) {
                        // Ignore errors
                    }
                }
            }
            loader = loader.parent
        }

        System.getProperty("java.class.path")
            ?.split(File.pathSeparator)
            ?.filter { it.isNotBlank() }
            ?.forEach { entries += File(it) }

        entries += compilerJar
        entries += classpathEntryFor(kotlin.jvm.internal.Intrinsics::class.java)

        entries.filter { it.exists() }
    }

    private val isWindows: Boolean by lazy {
        System.getProperty("os.name").contains("windows", ignoreCase = true)
    }

    private val javaBinary: File by lazy {
        val binName = if (isWindows) "java.exe" else "java"
        File(File(System.getProperty("java.home"), "bin"), binName)
    }

    object JavaVersion {
        val jvmVersion: String by lazy {
            System.getProperty("java.specification.version") ?: "25"
        }
        val isJava9OrLater: Boolean by lazy {
            val major = jvmVersion.substringBefore('.').toIntOrNull() ?: 0
            major >= 9
        }
    }

    private fun processHandle(pid: Long): Any? = try {
        val processHandleClass = Class.forName("java.lang.ProcessHandle")
        val of = processHandleClass.getMethod("of", java.lang.Long.TYPE)
        @Suppress("UNCHECKED_CAST")
        (of.invoke(null, pid) as java.util.Optional<Any>).orElse(null)
    } catch (_: Throwable) {
        null
    }

    private fun getPidCompat(process: Process): Long? {
        if (JavaVersion.isJava9OrLater) {
            return try {
                process.javaClass.getMethod("pid").invoke(process) as? Long
            } catch (_: Throwable) {
                null
            }
        }

        return try {
            val pidField = process.javaClass.getDeclaredField("pid")
            pidField.isAccessible = true
            when (val value = pidField.get(process)) {
                is Long -> value
                is Int -> value.toLong()
                else -> null
            }
        } catch (_: NoSuchFieldException) {
            try {
                val handleField = process.javaClass.getDeclaredField("handle")
                handleField.isAccessible = true
                val handle = handleField.getLong(process)
                val getProcessId0 = process.javaClass.getDeclaredMethod("getProcessId0", Long::class.javaPrimitiveType)
                getProcessId0.isAccessible = true
                (getProcessId0.invoke(null, handle) as? Int)?.toLong()
            } catch (_: Throwable) {
                null
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun isPidAliveCompat(pid: Long): Boolean {
        if (!JavaVersion.isJava9OrLater) return isPidAlive(pid)
        val handle = processHandle(pid) ?: return false
        return try {
            handle.javaClass.getMethod("isAlive").invoke(handle) as Boolean
        } catch (_: Throwable) {
            false
        }
    }
    private fun isPidAlive(pid: Long): Boolean {
        return try {
            if (isWindows) {
                val p = ProcessBuilder("tasklist", "/FI", "PID eq $pid").redirectErrorStream(true).start()
                val output = p.inputStream.bufferedReader(StandardCharsets.UTF_8).readText()
                p.waitFor(3, TimeUnit.SECONDS)
                output.contains(pid.toString())
            } else {
                val p = ProcessBuilder("kill", "-0", pid.toString()).redirectErrorStream(true).start()
                val finished = p.waitFor(3, TimeUnit.SECONDS)
                finished && p.exitValue() == 0
            }
        } catch (_: Throwable) {
            false
        }
    }

    private fun queryPidCommand(pid: Long): String? {
        return try {
            val p = if (isWindows) {
                ProcessBuilder("wmic", "process", "where", "ProcessId=$pid", "get", "ExecutablePath")
                    .redirectErrorStream(true).start()
            } else {
                ProcessBuilder("ps", "-o", "comm=", "-p", pid.toString())
                    .redirectErrorStream(true).start()
            }
            val output = p.inputStream.bufferedReader(StandardCharsets.UTF_8).readText()
            p.waitFor(3, TimeUnit.SECONDS)
            output.trim().takeIf { it.isNotBlank() }
        } catch (_: Throwable) {
            null
        }
    }

    private fun killPidForciblyCompat(pid: Long) {
        if (!JavaVersion.isJava9OrLater) {
            killPidForcibly(pid)
            return
        }
        val handle = processHandle(pid) ?: return
        try {
            handle.javaClass.getMethod("destroyForcibly").invoke(handle)
        } catch (_: Throwable) {
            // Ignore errors
        }
    }
    private fun killPidForcibly(pid: Long) {
        try {
            val command = if (isWindows) {
                listOf("taskkill", "/F", "/PID", pid.toString())
            } else {
                listOf("kill", "-9", pid.toString())
            }
            ProcessBuilder(command).redirectErrorStream(true).start().waitFor(5, TimeUnit.SECONDS)
        } catch (_: Throwable) {
            // Ignore errors
        }
    }

    fun compile(
        sourceFiles: List<File>,
        classpathEntries: List<File>,
        outputDir: File,
    ): CompileResult {
        outputDir.mkdirs()

        val lockFile = File(outputDir, ".compile.lock")
        reapStaleProcess(lockFile)

        val classpathString = classpathEntries.joinToString(File.pathSeparator) { it.absolutePath }
        val allArgs = mutableListOf(
            "-Dfile.encoding=UTF-8",
            "-Dstdout.encoding=UTF-8",
            "-Dstderr.encoding=UTF-8",
            "-XX:+IgnoreUnrecognizedVMOptions",
            "--sun-misc-unsafe-memory-access=allow",
            "-cp", compilerLaunchClasspath.joinToString(File.pathSeparator) { it.absolutePath },
            "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
            "-cp", classpathString,
            "-d", outputDir.absolutePath,
            "-no-stdlib",
            "-no-reflect",
            "-jvm-target", JavaVersion.jvmVersion,
            "-Xsuppress-version-warnings",
        )
        allArgs += sourceFiles.map { it.absolutePath }

        val argfile: File? = if (JavaVersion.isJava9OrLater) {
            File.createTempFile("${ScriptEngine.MOD_ID}-compile-", ".args").also {
                it.deleteOnExit()
                it.writeText(
                    allArgs.joinToString("\n") { arg ->
                        if (arg.any { it.isWhitespace() }) "\"${arg.replace("\\", "\\\\").replace("\"", "\\\"")}\"" else arg
                    },
                    StandardCharsets.UTF_8,
                )
            }
        } else null

        val command = if (argfile != null) {
            listOf(javaBinary.absolutePath, "@${argfile.absolutePath}")
        } else {
            listOf(javaBinary.absolutePath) + allArgs
        }

        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()

        activeProcesses.add(process)
        val pid = getPidCompat(process)
        if (pid != null) {
            lockFile.writeText(pid.toString())
        }

        try {
            val outputBuilder = StringBuilder()
            val readerThread = Thread({
                process.inputStream.bufferedReader(StandardCharsets.UTF_8).forEachLine {
                    outputBuilder.append(it).append('\n')
                }
            }, "${ScriptEngine.MOD_ID}-compiler-output-reader").apply {
                isDaemon = true
                start()
            }

            val finishedInTime = process.waitFor(COMPILE_TIMEOUT_MINUTES, TimeUnit.MINUTES)
            if (!finishedInTime) {
                process.destroyForcibly()
                process.waitFor(10, TimeUnit.SECONDS)
            }
            readerThread.join(5_000)

            val timedOut = !finishedInTime
            val exitCode = if (process.isAlive) -1 else process.exitValue()
            val output = outputBuilder.toString()

            val diagnostics = if (timedOut) {
                parseDiagnostics(output, exitCode = 1) + errorDiagnostic("Compile timed out after $COMPILE_TIMEOUT_MINUTES minute(s) and was killed.")
            } else {
                parseDiagnostics(output, exitCode)
            }

            val success = !timedOut && exitCode == 0 && diagnostics.none { it.severity == CompileDiagnostic.Severity.ERROR }

            return CompileResult(
                success = success,
                diagnostics = diagnostics,
                outputDir = if (success) outputDir else null,
            )
        } catch (e: InterruptedException) {
            process.destroyForcibly()
            Thread.currentThread().interrupt()
            throw e
        } finally {
            activeProcesses.remove(process)
            lockFile.delete()
            argfile?.delete()
            if (process.isAlive) process.destroyForcibly()
        }
    }

    private fun reapStaleProcess(lockFile: File) {
        if (!lockFile.isFile) return
        val pid = lockFile.readText().trim().toLongOrNull()
        if (pid != null && isPidAliveCompat(pid)) {
            val command = queryPidCommand(pid)
            val looksLikeJava = command?.contains("java", ignoreCase = true) == true
            if (looksLikeJava) {
                System.err.println("${ScriptEngine.LOG_PREFIX} Found a leftover compiler process (pid $pid) that never exited cleanly - terminating it.")
                killPidForciblyCompat(pid)
            }
        }
        lockFile.delete()
    }

    private val diagnosticLine = Regex("""^(.+?):(\d+):(\d+):\s*(error|warning):\s*(.*)$""")

    private val jvmNoiseLine = Regex("""^WARNING: .*$""")

    private fun parseDiagnostics(output: String, exitCode: Int): List<CompileDiagnostic> {
        val parsed = output.lineSequence()
            .filter { it.isNotBlank() && !jvmNoiseLine.matches(it) }
            .map { line ->
                val match = diagnosticLine.find(line)
                if (match != null) {
                    val g = match.groupValues
                    CompileDiagnostic(
                        severity = if (g[4] == "error") CompileDiagnostic.Severity.ERROR else CompileDiagnostic.Severity.WARNING,
                        message = g[5],
                        filePath = g[1],
                        line = g[2].toIntOrNull(),
                        column = g[3].toIntOrNull(),
                    )
                } else if (exitCode != 0) {
                    errorDiagnostic(line)
                } else {
                    warningDiagnostic(line)
                }
            }
            .toList()

        if (exitCode != 0 && parsed.none { it.severity == CompileDiagnostic.Severity.ERROR }) {
            return parsed + errorDiagnostic(
                message = "Compiler process exited with code $exitCode and produced no diagnostic output" +
                    if (output.isBlank()) "" else " (raw output: ${output.trim()})",
            )
        }

        return parsed
    }

    companion object {
        private const val COMPILE_TIMEOUT_MINUTES = 2L

        private val activeProcesses = ConcurrentHashMap.newKeySet<Process>()

        init {
            Runtime.getRuntime().addShutdownHook(
                Thread({
                    activeProcesses.forEach { it.destroyForcibly() }
                }, "${ScriptEngine.MOD_ID}-compiler-shutdown-hook")
            )
        }
    }
}
