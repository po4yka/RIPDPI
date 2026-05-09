package com.poyka.ripdpi.services

import android.content.Context
import android.os.Build
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
        if (availableAssets.contains("$binaryName.upstream")) {
            context.assets.open("$assetDirectory/$binaryName.upstream").use { input ->
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
