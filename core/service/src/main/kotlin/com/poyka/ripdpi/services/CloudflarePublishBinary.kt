package com.poyka.ripdpi.services

import android.content.Context
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject

internal const val CloudflaredBinaryName = "ripdpi-cloudflared"
internal const val CloudflareOriginBinaryName = "ripdpi-cloudflare-origin"

internal class CloudflarePublishBinaryExtractor
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) {
        fun extract(binaryName: String): File {
            val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
            val assetPath = "bin/$abi/$binaryName"
            val assetDirectory = "bin/$abi"
            val targetDir = File(context.filesDir, "cloudflare-runtime/$abi").apply { mkdirs() }
            val availableAssets =
                context.assets
                    .list(assetDirectory)
                    ?.toSet()
                    .orEmpty()
            if (availableAssets.contains("$binaryName.upstream")) {
                context.assets.open("$assetDirectory/$binaryName.upstream").use { input ->
                    File(targetDir, "$binaryName.upstream").outputStream().use { output -> input.copyTo(output) }
                }
                File(targetDir, "$binaryName.upstream").setExecutable(true, true)
            }
            val target = File(targetDir, binaryName)
            context.assets.open(assetPath).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            target.setExecutable(true, true)
            return target
        }
    }

internal class CloudflarePublishVersionProbe
    @Inject
    constructor() {
        fun probe(
            binary: File,
            versionArguments: List<String>,
        ): String? =
            runCatching {
                val process =
                    ProcessBuilder(
                        buildList {
                            add(binary.absolutePath)
                            addAll(versionArguments)
                        },
                    ).redirectErrorStream(true)
                        .start()
                val output =
                    process.inputStream
                        .bufferedReader()
                        .readText()
                        .trim()
                process.waitFor(2, TimeUnit.SECONDS)
                output.ifBlank { null }
            }.getOrNull()
    }
