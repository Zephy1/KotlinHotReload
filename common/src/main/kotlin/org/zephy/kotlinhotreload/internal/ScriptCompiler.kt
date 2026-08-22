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
                    } catch (e: Throwable) {
                        // Ignore URLs that can't be converted to files
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

    private fun classpathEntryFor(clazz: Class<*>): File {
        val location = clazz.protectionDomain?.codeSource?.location
            ?: error(
                "Could not determine the classpath location for ${clazz.name} - it may have been loaded from somewhere other than a regular jar file."
            )
        return File(location.toURI())
    }

    private val javaBinary: File by lazy {
        val binName = if (System.getProperty("os.name").contains("windows", ignoreCase = true)) "java.exe" else "java"
        File(File(System.getProperty("java.home"), "bin"), binName)
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
            "-jvm-target", "25",
            "-Xsuppress-version-warnings",
        )
        allArgs += sourceFiles.map { it.absolutePath }

        val argfile = File.createTempFile("${ScriptEngine.MOD_ID}-compile-", ".args")
        argfile.deleteOnExit()
        argfile.writeText(
            allArgs.joinToString("\n") { arg ->
                if (arg.any { it.isWhitespace() }) "\"${arg.replace("\\", "\\\\").replace("\"", "\\\"")}\"" else arg
            },
            StandardCharsets.UTF_8,
        )

        val command = listOf(javaBinary.absolutePath, "@${argfile.absolutePath}")

        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()

        activeProcesses.add(process)
        lockFile.writeText(process.pid().toString())

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
                parseDiagnostics(output, exitCode = 1) + CompileDiagnostic(
                    severity = CompileDiagnostic.Severity.ERROR,
                    message = "Compile timed out after $COMPILE_TIMEOUT_MINUTES minute(s) and was killed.",
                    filePath = null, line = null, column = null,
                )
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
            argfile.delete()
            if (process.isAlive) process.destroyForcibly()
        }
    }

    private fun reapStaleProcess(lockFile: File) {
        if (!lockFile.isFile) return
        val pid = lockFile.readText().trim().toLongOrNull()
        if (pid != null) {
            val handle = ProcessHandle.of(pid).orElse(null)
            if (handle != null && handle.isAlive && handle.info().command().orElse(null) == javaBinary.absolutePath) {
                System.err.println(
                    "${ScriptEngine.LOG_PREFIX} Found a leftover compiler process (pid $pid) that never " +
                        "exited cleanly, likely from a previous crash - terminating it."
                )
                handle.destroyForcibly()
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
                } else {
                    CompileDiagnostic(
                        severity = if (exitCode != 0) CompileDiagnostic.Severity.ERROR else CompileDiagnostic.Severity.WARNING,
                        message = line,
                        filePath = null, line = null, column = null,
                    )
                }
            }
            .toList()

        if (exitCode != 0 && parsed.none { it.severity == CompileDiagnostic.Severity.ERROR }) {
            return parsed + CompileDiagnostic(
                severity = CompileDiagnostic.Severity.ERROR,
                message = "Compiler process exited with code $exitCode and produced no diagnostic output" +
                    if (output.isBlank()) "" else " (raw output: ${output.trim()})",
                filePath = null, line = null, column = null,
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
