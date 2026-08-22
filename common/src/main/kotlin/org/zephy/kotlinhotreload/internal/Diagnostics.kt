package org.zephy.kotlinhotreload.internal

data class CompileDiagnostic(
    val severity: Severity,
    val message: String,
    val filePath: String?,
    val line: Int?,
    val column: Int?,
) {
    enum class Severity {
        ERROR,
        WARNING,
        INFO,
        LOGGING,
        ;
    }

    override fun toString(): String {
        val loc = if (filePath != null) {
            if (line != null) {
                "$filePath:$line:${column ?: 0}"
            } else filePath
        } else "<unknown>"
        return "[$severity] $loc: $message"
    }
}

data class CompileResult(
    val success: Boolean,
    val diagnostics: List<CompileDiagnostic>,
    val outputDir: java.io.File?,
) {
    val errors: List<CompileDiagnostic> get() = diagnostics.filter {
        it.severity == CompileDiagnostic.Severity.ERROR
    }
    val warnings: List<CompileDiagnostic> get() = diagnostics.filter {
        it.severity == CompileDiagnostic.Severity.WARNING
    }
}
