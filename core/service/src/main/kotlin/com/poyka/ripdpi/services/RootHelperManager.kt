package com.poyka.ripdpi.services

import android.content.Context
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Build
import co.touchlab.kermit.Logger
import com.poyka.ripdpi.data.RootSettingsSection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the lifecycle of the root helper binary.
 *
 * When root mode is enabled, extracts the helper binary from APK assets,
 * starts it via `su`, and monitors its Unix socket for readiness.
 */
@Singleton
open class RootHelperManager
    @Inject
    constructor() {
        internal constructor(
            binaryExtractor: (Context) -> File,
            processLaunchAttempts: (File, File, File) -> List<RootHelperLaunchAttempt>,
            readinessProbe: suspend (File, Long, Long) -> Boolean,
            shutdownRequester: ((String, String?) -> Unit)? = null,
            rootProcessTerminator: (() -> Unit)? = null,
        ) : this() {
            this.binaryExtractor = binaryExtractor
            this.processLaunchAttempts = processLaunchAttempts
            this.readinessProbe = readinessProbe
            this.shutdownRequester = shutdownRequester ?: ::requestHelperShutdown
            this.rootProcessTerminator = rootProcessTerminator ?: ::terminateRootHelperProcesses
        }

        private var helperProcess: Process? = null
        private var activeSocketPath: String? = null
        private var activeNoncePath: String? = null
        private var binaryExtractor: (Context) -> File = { context -> extractBinary(context) }
        private var processLaunchAttempts: (File, File, File) -> List<RootHelperLaunchAttempt> =
            { binary, socket, nonceFile -> buildRootHelperLaunchAttempts(binary, socket, nonceFile) }
        private var readinessProbe: suspend (File, Long, Long) -> Boolean = { socket, timeoutMs, pollIntervalMs ->
            awaitSocketReady(socket, timeoutMs, pollIntervalMs)
        }
        private var shutdownRequester: (String, String?) -> Unit = ::requestHelperShutdown
        private var rootProcessTerminator: () -> Unit = ::terminateRootHelperProcesses

        private companion object {
            private val log = Logger.withTag("RootHelperManager")
            private const val HELPER_BINARY_NAME = "ripdpi-root-helper"
            private const val SOCKET_NAME = "root_helper.sock"
            private const val NONCE_FILE_NAME = "$SOCKET_NAME.nonce"
            private const val ROOT_HELPER_PROTOCOL_VERSION = 3
            private const val SHUTDOWN_COMMAND = "v3/shutdown"
            private const val SHUTDOWN_READ_TIMEOUT_MS = 500
            private const val ROOT_TERMINATION_TIMEOUT_MS = 1000L
            private const val SESSION_NONCE_BYTES = 32
            private const val READY_POLL_INTERVAL_MS = 100L
            private const val READY_TIMEOUT_MS = 3000L
            private const val STOP_TIMEOUT_MS = 1000L
            private val SU_COMMAND_CANDIDATES = listOf("su", "/system/xbin/su", "/system/bin/su")
            private val secureRandom = SecureRandom()
        }

        /** Absolute path of the live helper Unix socket, or `null` when the helper is not running. */
        open val socketPath: String?
            get() = activeSocketPath

        /**
         * Reconcile the helper process with the [RootSettingsSection].
         *
         * When [root]'s `rootModeEnabled` is `false` the helper is stopped and
         * `null` is returned — preserving the non-root baseline. When `true`,
         * the helper is started if it is not already running.
         *
         * @return the helper Unix socket path while the helper is running, or
         *   `null` when root mode is off or the helper could not be started. A
         *   `null` result is the signal for the native runtime to fall back to
         *   non-privileged code paths.
         */
        open suspend fun syncRootMode(
            context: Context,
            root: RootSettingsSection,
        ): String? {
            if (!root.rootModeEnabled) {
                stop()
                return null
            }
            return ensureStarted(context)
        }

        /**
         * Start the helper if it is not already running, idempotently.
         *
         * @return the live helper socket path, or `null` when the helper could
         *   not be started — callers must then degrade to non-root behavior.
         */
        open suspend fun ensureStarted(context: Context): String? {
            val currentPath = activeSocketPath
            if (currentPath != null && isRunning()) {
                return currentPath
            }
            stop()
            return start(context)
        }

        /**
         * Extract, start, and verify the root helper process.
         *
         * @return the Unix socket path on success, or `null` on failure.
         */
        open suspend fun start(context: Context): String? =
            withContext(Dispatchers.IO) {
                try {
                    val binary = binaryExtractor(context)
                    val socket = File(context.filesDir, SOCKET_NAME)
                    val nonceFile = File(context.filesDir, NONCE_FILE_NAME)

                    removeStaleIpcFiles(socket, nonceFile)
                    writeSessionNonce(nonceFile, generateSessionNonce())

                    for (attempt in processLaunchAttempts(binary, socket, nonceFile)) {
                        removeStaleSocket(socket)

                        log.i { "starting root helper: ${attempt.description}" }
                        val process =
                            try {
                                attempt.launch()
                            } catch (e: IOException) {
                                log.w(e) { "failed to launch root helper via ${attempt.description}" }
                                null
                            } catch (e: SecurityException) {
                                log.w(e) { "failed to launch root helper via ${attempt.description}" }
                                null
                            }
                        if (process == null) {
                            continue
                        }
                        helperProcess = process

                        if (readinessProbe(socket, READY_TIMEOUT_MS, READY_POLL_INTERVAL_MS)) {
                            activeSocketPath = socket.absolutePath
                            activeNoncePath = nonceFile.absolutePath
                            log.i { "root helper started, socket: ${socket.absolutePath}" }
                            return@withContext socket.absolutePath
                        }

                        log.w { "root helper socket was not connectable via ${attempt.description}" }
                        stop()
                    }

                    log.e { "root helper socket was not connectable within ${READY_TIMEOUT_MS}ms" }
                    removeStaleIpcFiles(socket, nonceFile)
                    null
                } catch (e: IOException) {
                    log.e(e) { "failed to start root helper" }
                    stop()
                    null
                }
            }

        /** Stop the root helper process and clean up. */
        open fun stop() {
            val process = helperProcess
            val socketPath = activeSocketPath
            val noncePath = activeNoncePath
            helperProcess = null
            activeSocketPath = null
            activeNoncePath = null
            if (socketPath != null) {
                runCatching {
                    shutdownRequester(socketPath, noncePath)
                }.onFailure { error ->
                    log.w(error) { "failed to request root helper shutdown" }
                }
            }
            if (process == null) {
                removeIpcFiles(socketPath, noncePath)
                return
            }

            try {
                process.destroy()
                val exited =
                    runCatching {
                        process.waitFor(STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    }.getOrDefault(false)

                if (!exited) {
                    process.destroyForcibly()
                }
            } catch (e: IOException) {
                log.w(e) { "error stopping root helper" }
            } catch (e: SecurityException) {
                log.w(e) { "error stopping root helper" }
            } finally {
                if (socketPath != null) {
                    runCatching {
                        rootProcessTerminator()
                    }.onFailure { error ->
                        log.w(error) { "failed to terminate detached root helper process" }
                    }
                }
                removeIpcFiles(socketPath, noncePath)
            }
            log.i { "root helper stopped" }
        }

        /** Returns `true` while the launched helper process is still alive. */
        open fun isRunning(): Boolean {
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

        private fun buildRootHelperLaunchAttempts(
            binary: File,
            socket: File,
            nonceFile: File,
        ): List<RootHelperLaunchAttempt> {
            val helperCommand =
                "${shellQuote(binary.absolutePath)} --socket ${shellQuote(socket.absolutePath)} " +
                    "--session-nonce-file ${shellQuote(nonceFile.absolutePath)}"
            return launchableSuCommands().flatMap { suCommand ->
                listOf(
                    RootHelperLaunchAttempt("$suCommand -c exec $helperCommand") {
                        Runtime
                            .getRuntime()
                            .exec(arrayOf(suCommand, "-c", "exec $helperCommand"))
                    },
                    RootHelperLaunchAttempt("$suCommand 0 sh -c exec $helperCommand") {
                        Runtime
                            .getRuntime()
                            .exec(arrayOf(suCommand, "0", "sh", "-c", "exec $helperCommand"))
                    },
                )
            }
        }

        private fun launchableSuCommands(): List<String> =
            SU_COMMAND_CANDIDATES.filter { suCommand ->
                !suCommand.startsWith(File.separator) || File(suCommand).canExecute()
            }

        private fun shellQuote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"

        private fun generateSessionNonce(): String {
            val bytes = ByteArray(SESSION_NONCE_BYTES)
            secureRandom.nextBytes(bytes)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }

        private fun writeSessionNonce(
            nonceFile: File,
            nonce: String,
        ) {
            nonceFile.writeText(nonce, Charsets.US_ASCII)
            nonceFile.setReadable(false, false)
            nonceFile.setWritable(false, false)
            nonceFile.setReadable(true, true)
            nonceFile.setWritable(true, true)
        }

        private fun removeStaleIpcFiles(
            socket: File,
            nonceFile: File,
        ) {
            removeStaleSocket(socket)
            if (nonceFile.exists()) {
                nonceFile.delete()
            }
        }

        private fun removeStaleSocket(socket: File) {
            if (socket.exists()) {
                socket.delete()
            }
        }

        private fun removeIpcFiles(
            socketPath: String?,
            noncePath: String?,
        ) {
            socketPath?.let { runCatching { File(it).delete() } }
            noncePath?.let { runCatching { File(it).delete() } }
        }

        private fun requestHelperShutdown(
            socketPath: String,
            noncePath: String?,
        ) {
            val nonce =
                noncePath
                    ?.let(::File)
                    ?.takeIf(File::exists)
                    ?.readText(Charsets.US_ASCII)
                    ?.trim()
                    .orEmpty()
            if (nonce.isEmpty()) {
                return
            }
            val payload =
                """{"protocol_version":$ROOT_HELPER_PROTOCOL_VERSION,"command":"$SHUTDOWN_COMMAND",""" +
                    """"session_nonce":"$nonce"}""" +
                    "\n"
            LocalSocket().use { socket ->
                socket.connect(LocalSocketAddress(socketPath, LocalSocketAddress.Namespace.FILESYSTEM))
                socket.soTimeout = SHUTDOWN_READ_TIMEOUT_MS
                socket.outputStream.write(payload.toByteArray(Charsets.UTF_8))
                socket.outputStream.flush()
                runCatching {
                    socket.inputStream.bufferedReader(Charsets.UTF_8).readLine()
                }
            }
        }

        private fun terminateRootHelperProcesses() {
            val command =
                "killall -TERM $HELPER_BINARY_NAME 2>/dev/null || true; " +
                    "killall -KILL $HELPER_BINARY_NAME 2>/dev/null || true"
            launchableSuCommands().firstOrNull { suCommand ->
                runCatching {
                    val process = Runtime.getRuntime().exec(arrayOf(suCommand, "-c", command))
                    val exited = process.waitFor(ROOT_TERMINATION_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    if (!exited) {
                        process.destroyForcibly()
                    }
                    exited
                }.getOrDefault(false)
            }
        }

        private suspend fun awaitSocketReady(
            socket: File,
            timeoutMs: Long,
            pollIntervalMs: Long,
        ): Boolean {
            var ready = false
            withTimeoutOrNull<Unit>(timeoutMs) {
                while (!ready) {
                    ready = (socket.exists() && canConnect(socket))
                    if (!ready) {
                        delay(pollIntervalMs)
                    }
                }
            }
            return ready || (socket.exists() && canConnect(socket))
        }

        private fun canConnect(socket: File): Boolean {
            val localSocket = LocalSocket()
            return try {
                localSocket.connect(
                    LocalSocketAddress(
                        socket.absolutePath,
                        LocalSocketAddress.Namespace.FILESYSTEM,
                    ),
                )
                true
            } catch (_: IOException) {
                false
            } finally {
                runCatching { localSocket.close() }
            }
        }
    }

internal class RootHelperLaunchAttempt(
    val description: String,
    val launch: () -> Process,
)
