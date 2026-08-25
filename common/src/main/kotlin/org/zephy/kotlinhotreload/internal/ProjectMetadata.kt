package org.zephy.kotlinhotreload.internal

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.google.gson.annotations.JsonAdapter
import com.google.gson.annotations.SerializedName
import java.io.File

data class ProjectMetadata(
    @SerializedName("entryPoint")
    val entryPointClass: String? = null,
    val projectDependencies: List<String> = emptyList(),
    val dependencies: List<String> = emptyList(),
    @JsonAdapter(MixinsDeserializer::class)
    val mixins: List<MixinEntry> = emptyList(),
) {
    companion object {
        private val gson: Gson = Gson()

        private val COORDINATE_REGEX = Regex("^[^:\\s]+:[^:\\s]+:[^:\\s]+$")
        private val QUALIFIED_CLASS_NAME_REGEX = Regex("^[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*$")

        fun default() = ProjectMetadata()

        fun parse(file: File): ProjectMetadata {
            if (!file.exists()) return default()

            val text = file.readText()
            if (text.isBlank()) return default()

            val metadata = try {
                gson.fromJson(text, ProjectMetadata::class.java) ?: default()
            } catch (e: JsonSyntaxException) {
                throw IllegalArgumentException("Malformed metadata.json (${file.absolutePath}): ${e.describe()}.", e)
            } catch (e: com.google.gson.JsonIOException) {
                throw IllegalArgumentException("Could not read metadata.json (${file.absolutePath}): ${e.describe()}.", e)
            }

            metadata.validate(file)
            return metadata
        }
    }

    private fun validate(file: File) {
        val badCoordinates = dependencies.filterNot { COORDINATE_REGEX.matches(it) }
        if (badCoordinates.isNotEmpty()) {
            throw IllegalArgumentException("metadata.json (${file.absolutePath}) has malformed dependency coordinate(s) (expected \"group:artifact:version\"): ${badCoordinates.joinToString()}.")
        }

        if (projectDependencies.any { it.isBlank() }) {
            val blankPositions = projectDependencies.withIndex().filter { it.value.isBlank() }.map { it.index }
            throw IllegalArgumentException(
                "metadata.json (${file.absolutePath}) has blank entr${if (blankPositions.size == 1) "y" else "ies"} " +
                    "in \"projectDependencies\" at index ${blankPositions.joinToString()} - " +
                    "remove the empty string(s) from the list."
            )
        }

        if (entryPointClass != null && entryPointClass.isBlank()) {
            throw IllegalArgumentException("metadata.json (${file.absolutePath}) has a blank \"entryPoint\" value.")
        }

        val badMixinNames = mixins.map { it.name }.filterNot { QUALIFIED_CLASS_NAME_REGEX.matches(it) }
        if (badMixinNames.isNotEmpty()) {
            throw IllegalArgumentException("metadata.json (${file.absolutePath}) has malformed \"mixins\" entry/entries: ${badMixinNames.joinToString()}")
        }
    }
}
