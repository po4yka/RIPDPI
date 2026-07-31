package com.poyka.ripdpi.services

import android.content.Context
import android.os.Build
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

internal class SubprocessRelayBinaryExtractor(
    private val context: Context,
) {
    fun extract(binaryName: String): File {
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
        val assetPath = "bin/$abi/$binaryName"
        val assetDirectory = "bin/$abi"
        val targetDir = File(context.filesDir, "subprocess-relays/$abi").apply { mkdirs() }
        val availableAssets =
            context.assets
                .list(assetDirectory)
                ?.toSet()
                .orEmpty()
        val manifestPayload =
            runCatching {
                context.assets
                    .open("metadata/pluggable-transports.json")
                    .bufferedReader()
                    .use { it.readText() }
            }.getOrNull()
        val upstreamAsset =
            try {
                resolvePluggableTransportUpstreamAsset(
                    manifestPayload = manifestPayload,
                    abi = abi,
                    binaryName = binaryName,
                    availableAssets = availableAssets,
                )
            } catch (error: IllegalArgumentException) {
                removeStalePluggableTransportFile(File(targetDir, binaryName))
                removeStalePluggableTransportFile(File(targetDir, "$binaryName.upstream"))
                throw IllegalStateException("Invalid pluggable transport manifest for $binaryName/$abi", error)
            }
        val upstreamTarget = File(targetDir, "$binaryName.upstream")
        if (upstreamAsset != null) {
            context.assets.open("$assetDirectory/$upstreamAsset").use { input ->
                upstreamTarget.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            upstreamTarget.setExecutable(true, true)
        } else {
            removeStalePluggableTransportFile(upstreamTarget)
        }
        val target = File(targetDir, binaryName)
        context.assets.open(assetPath).use { input ->
            target.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        target.setExecutable(true, true)
        return target
    }
}

internal fun resolvePluggableTransportUpstreamAsset(
    manifestPayload: String?,
    abi: String,
    binaryName: String,
    availableAssets: Set<String>,
): String? {
    val legacyUpstream = "$binaryName.upstream"
    val payload = requireNotNull(manifestPayload) { "Pluggable transport manifest is unavailable" }

    val artifact =
        requireNotNull(
            requireNotNull(Json.parseToJsonElement(payload).jsonObject["artifacts"]) {
                "Pluggable transport manifest has no artifacts array"
            }.jsonArray
                .asSequence()
                .map { it.jsonObject }
                .singleOrNull { candidate ->
                    candidate["abi"]?.jsonPrimitive?.content == abi &&
                        candidate["outputName"]?.jsonPrimitive?.content == binaryName
                },
        ) {
            "Manifest has no unique artifact for $binaryName/$abi"
        }

    val upstreamElement = artifact["upstreamBinary"]
    if (upstreamElement == null || upstreamElement is JsonNull) {
        return null
    }
    val manifestUpstream = upstreamElement.jsonPrimitive.content
    require(manifestUpstream.isNotBlank() && File(manifestUpstream).name == manifestUpstream) {
        "Unsafe upstream asset name for $binaryName/$abi"
    }
    return requireNotNull(
        listOf(manifestUpstream, legacyUpstream)
            .distinct()
            .firstOrNull(availableAssets::contains),
    ) {
        "Upstream asset is missing for $binaryName/$abi"
    }
}

internal fun removeStalePluggableTransportFile(file: File) {
    check(!file.exists() || file.delete()) { "Unable to remove stale pluggable transport file: $file" }
}
