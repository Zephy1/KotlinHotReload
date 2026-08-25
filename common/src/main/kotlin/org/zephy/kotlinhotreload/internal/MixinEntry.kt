package org.zephy.kotlinhotreload.internal

import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonSyntaxException
import java.lang.reflect.Type

data class MixinEntry(
    val name: String,
    val minVersion: Int? = null,
    val maxVersion: Int? = null,
) {
    fun appliesTo(mcVersion: Int): Boolean {
        return (minVersion == null || mcVersion >= minVersion) && (maxVersion == null || mcVersion <= maxVersion)
    }
}

class MixinEntryDeserializer {
    fun deserialize(name: String, value: JsonElement): MixinEntry {
        if (value.isJsonNull) return MixinEntry(name = name)
        if (!value.isJsonObject) {
            throw JsonSyntaxException("Mixin entry \"$name\" must be null or a JSON object.")
        }

        val bounds = value.asJsonObject
        val minVersion = bounds.get("minVersion")?.takeUnless { it.isJsonNull }
            ?.let { parseVersion(it, name, "minVersion") }
        val maxVersion = bounds.get("maxVersion")?.takeUnless { it.isJsonNull }
            ?.let { parseVersion(it, name, "maxVersion") }

        if (minVersion != null && maxVersion != null && minVersion > maxVersion) {
            throw JsonSyntaxException("Mixin \"$name\" has \"minVersion\" ($minVersion) greater than \"maxVersion\" ($maxVersion).")
        }

        return MixinEntry(name = name, minVersion = minVersion, maxVersion = maxVersion)
    }

    private fun parseVersion(element: JsonElement, mixinName: String, field: String): Int {
        if (!element.isJsonPrimitive) {
            throw JsonSyntaxException("Mixin \"$mixinName\" has an invalid \"$field\" (expected e.g. 12106 or \"1.21.6\").")
        }

        val primitive = element.asJsonPrimitive
        return when {
            primitive.isNumber -> primitive.asInt
            primitive.isString -> ScriptPreprocessor.parseVersionOrNull(primitive.asString)
                ?: throw JsonSyntaxException("Mixin \"$mixinName\" has a malformed \"$field\" version string: \"${primitive.asString}\" (expected e.g. \"1.21.6\").")
            else -> throw JsonSyntaxException("Mixin \"$mixinName\" has an invalid \"$field\" (expected e.g. 12106 or \"1.21.6\").")
        }
    }
}

fun List<MixinEntry>.applicable(mcVersion: Int): List<String> =
    filter { it.appliesTo(mcVersion) }.map { it.name }

class MixinsDeserializer : JsonDeserializer<List<MixinEntry>> {
    private val entryDeserializer = MixinEntryDeserializer()

    override fun deserialize(json: JsonElement, typeOfT: Type, context: JsonDeserializationContext): List<MixinEntry> {
        if (json.isJsonNull) return emptyList()
        if (!json.isJsonObject) {
            throw JsonSyntaxException("\"mixins\" must be a JSON object.")
        }

        return json.asJsonObject.entrySet().map { (name, value) ->
            entryDeserializer.deserialize(name, value)
        }
    }
}
