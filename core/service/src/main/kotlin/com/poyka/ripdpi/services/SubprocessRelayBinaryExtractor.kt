package com.poyka.ripdpi.services

import android.content.Context
import android.os.Build
import kotlinx.serialization.json.Json
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
            resolvePluggableTransportUpstreamAsset(
                manifestPayload = manifestPayload,
                abi = abi,
                binaryName = binaryName,
                availableAssets = availableAssets,
            )
        if (upstreamAsset != null) {
            context.assets.open("$assetDirectory/$upstreamAsset").use { input ->
                File(targetDir, "$binaryName.upstream").outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            File(targetDir, "$binaryName.upstream").setExecutable(true, true)
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
    val manifestUpstream =
        manifestPayload?.let { payload ->
            runCatching {
                Json
                    .parseToJsonElement(payload)
                    .jsonObject
                    .getValue("artifacts")
                    .jsonArray
                    .asSequence()
                    .map { it.jsonObject }
                    .singleOrNull { artifact ->
                        artifact["abi"]?.jsonPrimitive?.content == abi &&
                            artifact["outputName"]?.jsonPrimitive?.content == binaryName
                    }?.get("upstreamBinary")
                    ?.jsonPrimitive
                    ?.content
                    ?.takeIf { File(it).name == it }
            }.getOrNull()
        }
    return listOfNotNull(manifestUpstream, "$binaryName.upstream")
        .distinct()
        .firstOrNull(availableAssets::contains)
}
