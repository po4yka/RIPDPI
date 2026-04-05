package com.poyka.ripdpi.services

import android.content.Context
import android.os.Build
import co.touchlab.kermit.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject

/**
 * Manages the lifecycle of the root helper binary.
 *
 * When root mode is enabled, extracts the helper binary from APK assets,
 * starts it via `su`, and monitors its Unix socket for readiness.
 */
class RootHelperManager
    @Inject
    constructor() {
        private var helperProcess: Process? = null
        private var activeSocketPath: String? = null

        private companion object {
            private val log = Logger.withTag("RootHelperManager")
            private const val HELPER_BINARY_NAME = "ripdpi-root-helper"
            private const val SOCKET_NAME = "root_helper.sock"
            private const val READY_POLL_INTERVAL_MS = 100L
            private const val READY_TIMEOUT_MS = 3000L
        }

        val socketPath: String?
            get() = activeSocketPath

        /**
         * Extract, start, and verify the root helper process.
         *
         * @return the Unix socket path on success, or `null` on failure.
         */
        suspend fun start(context: Context): String? =
            withContext(Dispatchers.IO) {
                try {
                    val binary = extractBinary(context)
                    val socket = File(context.filesDir, SOCKET_NAME)

                    // Remove stale socket.
                    if (socket.exists()) {
                        socket.delete()
                    }

                    val cmd =
                        arrayOf(
                            "su",
                            "-c",
                            "${binary.absolutePath} --socket ${socket.absolutePath}",
                        )
                    log.i { "starting root helper: ${cmd.joinToString(" ")}" }
                    val process = Runtime.getRuntime().exec(cmd)
                    helperProcess = process

                    // Wait for the socket to appear.
                    val deadline = System.currentTimeMillis() + READY_TIMEOUT_MS
                    while (!socket.exists() && System.currentTimeMillis() < deadline) {
                        delay(READY_POLL_INTERVAL_MS)
                    }

                    if (!socket.exists()) {
                        log.e { "root helper socket did not appear within ${READY_TIMEOUT_MS}ms" }
                        stop()
                        return@withContext null
                    }

                    activeSocketPath = socket.absolutePath
                    log.i { "root helper started, socket: ${socket.absolutePath}" }
                    socket.absolutePath
                } catch (e: IOException) {
                    log.e(e) { "failed to start root helper" }
                    stop()
                    null
                }
            }

        /** Stop the root helper process and clean up. */
        fun stop() {
            val process = helperProcess ?: return
            helperProcess = null
            activeSocketPath = null

            try {
                process.destroy()
                val exited =
                    runCatching {
                        process.waitFor()
                        true
                    }.getOrDefault(false)

                if (!exited) {
                    process.destroyForcibly()
                }
            } catch (e: Exception) {
                log.w(e) { "error stopping root helper" }
            }
            log.i { "root helper stopped" }
        }

        fun isRunning(): Boolean {
            val process = helperProcess ?: return false
            return runCatching {
                process.exitValue()
                false
            }.getOrDefault(true)
        }

        private fun extractBinary(context: Context): File {
            val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
            val assetPath = "bin/$abi/$HELPER_BINARY_NAME"
            val targetFile = File(context.filesDir, HELPER_BINARY_NAME)

            context.assets.open(assetPath).use { input ->
                targetFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            targetFile.setExecutable(true, true)
            log.d { "extracted root helper binary: $assetPath -> ${targetFile.absolutePath}" }
            return targetFile
        }
    }
