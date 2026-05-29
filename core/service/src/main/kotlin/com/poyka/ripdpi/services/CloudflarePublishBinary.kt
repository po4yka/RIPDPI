package com.poyka.ripdpi.services

import android.content.Context
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject

internal const val CloudflaredBinaryName = "ripdpi-cloudflared"
internal const val CloudflareOriginBinaryName = "ripdpi-cloudflare-origin"

internal open class CloudflarePublishBinaryExtractor
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) {
        open fun extract(binaryName: String): File {
            val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
            val assetDirectory = "bin/$abi"
            // TODO(cloudflare-removal): cloudflare-runtime/ holds ripdpi-cloudflared +
            // ripdpi-cloudflare-origin (primary, publish mode). Blocks subscription delivery
            // when publish mode is the active relay. Gate on cloudflare_publish flag.
            val targetDir = File(context.filesDir, "cloudflare-runtime/$abi").apply { mkdirs() }
            val availableAssets =
                context.assets
                    .list(assetDirectory)
                    ?.toSet()
                    .orEmpty()
            if (availableAssets.contains("$binaryName.upstream")) {
                installIfChanged(File(targetDir, "$binaryName.upstream")) {
                    context.assets.open("$assetDirectory/$binaryName.upstream")
                }
            }
            val target = File(targetDir, binaryName)
            installIfChanged(target) { context.assets.open("$assetDirectory/$binaryName") }
            return target
        }

        /**
         * Installs the asset bytes into [target] only when they differ from the previously
         * installed copy, avoiding redundant flash writes on every start.
         *
         * The copy is keyed by `(ABI, asset content hash)`: [target]'s directory is the per-ABI
         * `cloudflare-runtime/<abi>` dir, and a companion `<name>.sha256` marker records the
         * SHA-256 of the asset bytes. A subsequent start re-hashes the asset (cheap mmap read
         * from the APK) and skips the copy when the marker matches; an asset version change
         * (a new binary shipped by an app update) produces a different hash and re-installs.
         *
         * @return true if a copy was performed, false if the existing install was up to date.
         */
        internal open fun installIfChanged(
            target: File,
            openAsset: () -> InputStream,
        ): Boolean {
            val marker = File(target.parentFile, "${target.name}.sha256")
            val assetHash = openAsset().use(::sha256Hex)
            if (target.exists() && marker.exists() && marker.readText().trim() == assetHash) {
                if (!target.canExecute()) target.setExecutable(true, true)
                return false
            }
            openAsset().use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            target.setExecutable(true, true)
            marker.writeText(assetHash)
            return true
        }

        private fun sha256Hex(input: InputStream): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(HashBufferBytes)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
            return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
        }

        private companion object {
            private const val HashBufferBytes = 8 * 1024
        }
    }

internal open class CloudflarePublishVersionProbe
    @Inject
    constructor() {
        open fun probe(
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
