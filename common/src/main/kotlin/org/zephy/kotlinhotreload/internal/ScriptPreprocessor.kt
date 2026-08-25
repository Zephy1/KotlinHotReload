package org.zephy.kotlinhotreload.internal

import java.io.File
import java.nio.charset.StandardCharsets

object ScriptPreprocessor {
    private const val IF = "//#if"
    private const val IFDEF = "//#ifdef"
    private const val ELSEIF = "//#elseif"
    private const val ELSE = "//#else"
    private const val ENDIF = "//#endif"

    class PreprocessException(message: String) : RuntimeException(message)

    private data class Frame(var currentValue: Boolean, var elseFound: Boolean = false, var trueFound: Boolean = false)

    fun preprocess(source: String, vars: Map<String, Int>, fileName: String = "<script>"): String {
        val stack = mutableListOf<Frame>()
        var active = true
        var lineNo = 0

        fun evalCondition(raw: String): Boolean {
            if (!raw.startsWith(" ")) {
                throw PreprocessException("Expected a space before the condition on line $lineNo of $fileName")
            }
            return evalExpr(raw.trim(), vars, lineNo, fileName)
        }

        val outLines = source.lines().map { line ->
            lineNo++
            val trimmed = line.trim()
            when {
                trimmed.startsWith(IF) -> {
                    val result = evalCondition(trimmed.substring(IF.length))
                    stack.add(Frame(result, trueFound = result))
                    active = active && result
                    line
                }
                trimmed.startsWith(IFDEF) -> {
                    val result = vars.containsKey(trimmed.substring(IFDEF.length).trim())
                    stack.add(Frame(result, trueFound = result))
                    active = active && result
                    line
                }
                trimmed.startsWith(ELSEIF) -> {
                    if (stack.isEmpty()) throw PreprocessException("Unexpected //#elseif on line $lineNo of $fileName")
                    val frame = stack.last()
                    if (frame.elseFound) throw PreprocessException("//#elseif after //#else on line $lineNo of $fileName")
                    active = if (frame.trueFound) {
                        frame.currentValue = false
                        false
                    } else {
                        val result = evalCondition(trimmed.substring(ELSEIF.length))
                        frame.currentValue = result
                        frame.trueFound = result
                        stack.all { it.currentValue }
                    }
                    line
                }
                trimmed.startsWith(ELSE) -> {
                    if (stack.isEmpty()) throw PreprocessException("Unexpected //#else on line $lineNo of $fileName")
                    val frame = stack.last()
                    if (frame.elseFound) throw PreprocessException("Duplicate //#else on line $lineNo of $fileName")
                    frame.elseFound = true
                    frame.currentValue = !frame.trueFound
                    active = stack.all { it.currentValue }
                    line
                }
                trimmed.startsWith(ENDIF) -> {
                    if (stack.isEmpty()) throw PreprocessException("Unexpected //#endif on line $lineNo of $fileName")
                    stack.removeAt(stack.lastIndex)
                    active = stack.all { it.currentValue }
                    line
                }
                else -> if (active) uncomment(line, trimmed) else ""
            }
        }

        if (stack.isNotEmpty()) throw PreprocessException("Missing //#endif in $fileName")

        return outLines.joinToString("\n")
    }

    private fun uncomment(line: String, trimmed: String): String {
        if (!trimmed.startsWith("//$$")) return line
        val markerIndex = line.indexOf("//$$")
        return line.substring(0, markerIndex) + trimmed.removePrefix("//$$")
    }

    fun stage(sourceFiles: List<File>, sourceRoot: File, stagingDir: File, vars: Map<String, Int>): List<File> {
        stagingDir.deleteRecursively()
        stagingDir.mkdirs()

        return sourceFiles.map { file ->
            val rel = file.relativeTo(sourceRoot)
            val staged = File(stagingDir, rel.path)
            staged.parentFile.mkdirs()
            if (file.extension == "kt") {
                val processed = preprocess(file.readText(StandardCharsets.UTF_8), vars, rel.path)
                staged.writeText(processed, StandardCharsets.UTF_8)
            } else {
                file.copyTo(staged, overwrite = true)
            }
            staged
        }
    }

    private val EXPR = Regex("""(.+?)(==|!=|<=|>=|<|>)(.+)""")

    private fun evalExpr(expr: String, vars: Map<String, Int>, lineNo: Int, fileName: String): Boolean {
        expr.split("||").let { if (it.size > 1) return it.any { part -> evalExpr(part.trim(), vars, lineNo, fileName) } }
        expr.split("&&").let { if (it.size > 1) return it.all { part -> evalExpr(part.trim(), vars, lineNo, fileName) } }
        if (expr.startsWith("!")) return !evalExpr(expr.substring(1), vars, lineNo, fileName)

        vars[expr]?.let { return it != 0 }

        val match = EXPR.matchEntire(expr)
            ?: throw PreprocessException("Invalid expression \"$expr\" on line $lineNo of $fileName")

        val lhs = evalVar(match.groupValues[1].trim(), vars, lineNo, fileName)
        val rhs = evalVar(match.groupValues[3].trim(), vars, lineNo, fileName)
        return when (match.groupValues[2]) {
            "==" -> lhs == rhs
            "!=" -> lhs != rhs
            ">=" -> lhs >= rhs
            "<=" -> lhs <= rhs
            ">" -> lhs > rhs
            "<" -> lhs < rhs
            else -> throw PreprocessException("Invalid operator in \"$expr\" on line $lineNo of $fileName")
        }
    }

    private fun evalVar(token: String, vars: Map<String, Int>, lineNo: Int, fileName: String): Int {
        vars[token]?.let { return it }
        token.toIntOrNull()?.let { return it }
        parseVersionOrNull(token)?.let { return it }
        throw PreprocessException("Unknown variable \"$token\" in expression on line $lineNo of $fileName")
    }

    fun parseVersionOrNull(raw: String): Int? {
        val parts = raw.trim().split(".")
        if (parts.size !in 1..3) return null

        val nums = IntArray(3)
        for (i in parts.indices) {
            val segment = parts[i]
            if (segment.isEmpty() || segment.any { !it.isDigit() }) return null
            val value = segment.toIntOrNull() ?: return null
            if (i == 0) {
                if (value !in 0..Int.MAX_VALUE / 10000) return null
            } else if (value !in 0..99) {
                return null
            }
            nums[i] = value
        }

        return nums[0] * 10_000 + nums[1] * 100 + nums[2]
    }
}
