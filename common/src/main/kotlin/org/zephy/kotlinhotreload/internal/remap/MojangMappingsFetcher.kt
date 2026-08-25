package org.zephy.kotlinhotreload.internal.remap

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.zephy.kotlinhotreload.internal.Http
import java.io.File
import java.security.MessageDigest

object MojangMappingsFetcher {
    private const val VERSION_MANIFEST_URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"

    fun fetchClientMappings(mcVersionName: String, cacheDir: File): File {
        val cached = File(cacheDir, "$mcVersionName-client.txt")
        if (cached.isFile && cached.length() > 0) return cached

        cacheDir.mkdirs()

        val manifest = parseJsonObject(Http.getText(VERSION_MANIFEST_URL))
        val versionUrl = manifest.getAsJsonArray("versions")
            .asSequence()
            .map { it.asJsonObject }
            .firstOrNull { it.get("id").asString == mcVersionName }
            ?.get("url")?.asString
            ?: error("Minecraft version '$mcVersionName' not found in Mojang's version manifest.")

        val versionMeta = parseJsonObject(Http.getText(versionUrl))
        val downloads = versionMeta.getAsJsonObject("downloads")
            ?: error("Version metadata for '$mcVersionName' has no 'downloads' section.")
        val clientMappingsInfo = downloads.getAsJsonObject("client_mappings")
            ?: error("Minecraft version '$mcVersionName' has no published client mappings.")

        val url = clientMappingsInfo.get("url").asString
        val expectedSha1 = clientMappingsInfo.get("sha1").asString

        val bytes = Http.getBytes(url)

        val actualSha1 = MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }
        check(actualSha1 == expectedSha1) {
            "Downloaded client mappings for $mcVersionName failed checksum verification (expected $expectedSha1, got $actualSha1)."
        }

        val tmp = File.createTempFile("client-mappings-", ".txt", cacheDir)
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(cached)) {
            cached.writeBytes(bytes)
            tmp.delete()
        }
        return cached
    }

    @Suppress("DEPRECATION")
    private fun parseJsonObject(text: String): JsonObject = JsonParser().parse(text).asJsonObject
}
