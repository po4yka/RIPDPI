package com.poyka.ripdpi.services

import android.content.Context
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Build
import co.touchlab.kermit.Logger
import com.poyka.ripdpi.data.RootSettingsSection
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
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
    constructor(
        private val activeProtectSocketPathProvider: ActiveProtectSocketPathProvider,
    ) {
        constructor() : this(ActiveProtectSocketPathProvider())

        internal constructor(
            binaryExtractor: (Context) -> File,
            processLaunchAttempts: (File, File, File) -> List<RootHelperLaunchAttempt>,
            readinessProbe: suspend (File, Long, Long) -> Boolean,
            shutdownRequester: ((String, String?) -> Unit)? = null,
            rootProcessTerminator: (() -> Unit)? = null,
            nfqwsRequester: ((String, String, String, JsonObject) -> JsonObject)? = null,
            nfqwsExtractor: ((Context) -> File)? = null,
            nfqwsDispatcher: CoroutineDispatcher = Dispatchers.IO,
        ) : this() {
            this.binaryExtractor = binaryExtractor
            this.processLaunchAttempts = processLaunchAttempts
            this.readinessProbe = readinessProbe
            this.shutdownRequester = shutdownRequester ?: RootHelperIpc::shutdown
            this.rootProcessTerminator = rootProcessTerminator ?: ::terminateRootHelperProcesses
            this.nfqwsRequester = nfqwsRequester ?: RootHelperIpc::request
            this.nfqwsExtractor = nfqwsExtractor ?: NfqwsBackendAssets::extract
            this.nfqwsDispatcher = nfqwsDispatcher
        }

        private val backendSession = AtomicReference<NfqwsBackendSession?>(null)
        private var backendMonitor: Job? = null
        private var nfqwsRequester: (String, String, String, JsonObject) -> JsonObject = RootHelperIpc::request
        private var nfqwsExtractor: (Context) -> File = NfqwsBackendAssets::extract
        private var nfqwsDispatcher: CoroutineDispatcher = Dispatchers.IO
        private var helperProcess: Process? = null
        private var activeSocketPath: String? = null
        private var activeNoncePath: String? = null
        private var binaryExtractor: (Context) -> File = { context -> extractBinary(context) }
        private var processLaunchAttempts: (File, File, File) -> List<RootHelperLaunchAttempt> =
            { binary, socket, nonceFile -> buildRootHelperLaunchAttempts(binary, socket, nonceFile) }
        private var readinessProbe: suspend (File, Long, Long) -> Boolean = { socket, timeoutMs, pollIntervalMs ->
            awaitSocketReady(socket, timeoutMs, pollIntervalMs)
        }
        private var shutdownRequester: (String, String?) -> Unit = RootHelperIpc::shutdown
        private var rootProcessTerminator: () -> Unit = ::terminateRootHelperProcesses

        private companion object {
            private val log = Logger.withTag("RootHelperManager")
            private const val HELPER_BINARY_NAME = "ripdpi-root-helper"
            private const val SOCKET_NAME = "root_helper.sock"
            private const val NONCE_FILE_NAME = "$SOCKET_NAME.nonce"
            private const val ROOT_TERMINATION_TIMEOUT_MS = 1000L
            private const val SESSION_NONCE_BYTES = 32
            private const val READY_POLL_INTERVAL_MS = 100L
            private const val READY_TIMEOUT_MS = 3000L
            private const val STOP_TIMEOUT_MS = 1000L
            private const val BACKEND_POLL_INTERVAL_MS = 1000L
            private val SU_COMMAND_CANDIDATES = listOf("su", "/system/xbin/su", "/system/bin/su")
            private val secureRandom = SecureRandom()
        }

        /** Absolute path of the live helper Unix socket, or `null` when the helper is not running. */
        open val socketPath: String?
            get() = activeSocketPath

        /** True only after NFQUEUE startup and rule installation succeed. */
        open val nfqwsActive: Boolean
            get() = backendSession.get()?.running == true && isRunning()

        open suspend fun syncNfqws(
            context: Context,
            settings: com.poyka.ripdpi.proto.AppSettings,
        ) {
            val requested = nfqwsArguments(settings)
            val socket = activeSocketPath
            val nonce = activeNoncePath
            if (requested == null) {
                val pending = backendSession.updateAndGet { it?.copy(running = false) }
                backendMonitor?.cancel()
                if (pending != null && socket != null && nonce != null) {
                    withContext(nfqwsDispatcher) {
                        nfqwsRequester(socket, nonce, "v3/stop_nfqws2", buildJsonObject {})
                    }
                }
                backendSession.compareAndSet(pending, null)
                return
            }
            if (socket == null || nonce == null) throw IOException("nfqws2 root helper is unavailable")
            val protectPath = activeProtectSocketPathProvider.current()
            withContext(nfqwsDispatcher) {
                val prior = backendSession.get()
                if (prior?.args == requested && prior.protectPath == protectPath && prior.running) {
                    val status = nfqwsRequester(socket, nonce, "v3/nfqws2_status", buildJsonObject {})
                    if (status["running"]?.jsonPrimitive?.booleanOrNull == true) return@withContext
                }
                backendMonitor?.cancel()
                val pending = NfqwsBackendSession(requested, protectPath, nonce, running = false)
                backendSession.set(pending)
                val directory = nfqwsExtractor(context)
                val params =
                    buildJsonObject {
                        put("binary_path", File(directory, "nfqws2").absolutePath)
                        put("working_directory", directory.absolutePath)
                        put("owner_pid", android.os.Process.myPid())
                        protectPath?.let { put("protect_path", it) }
                        put(
                            "args",
                            buildJsonArray {
                                (NfqwsBackendAssets.initializers() + requested).forEach { add(it) }
                            },
                        )
                    }
                val status = nfqwsRequester(socket, nonce, "v3/start_nfqws2", params)
                check(status["running"]?.jsonPrimitive?.booleanOrNull == true) { "nfqws2 did not activate" }
                if (!backendSession.compareAndSet(pending, pending.copy(running = true))) {
                    throw RuntimeCleanupPendingException()
                }
            }
        }

        open fun monitorNfqws(
            scope: CoroutineScope,
            onFailure: suspend (IOException) -> Unit,
        ) {
            backendMonitor?.cancel()
            val observed = backendSession.get()
            val socket = activeSocketPath
            if (observed?.running != true || socket == null || !isRunning()) return
            val nonce = observed.noncePath
            backendMonitor =
                scope.launch(nfqwsDispatcher) {
                    while (isActive && backendSession.get() === observed) {
                        delay(BACKEND_POLL_INTERVAL_MS)
                        val failure =
                            try {
                                val status =
                                    nfqwsRequester(
                                        socket,
                                        nonce,
                                        "v3/nfqws2_status",
                                        buildJsonObject {},
                                    )
                                if (status["running"]?.jsonPrimitive?.booleanOrNull == true) {
                                    null
                                } else {
                                    IOException("nfqws2 exited unexpectedly")
                                }
                            } catch (error: IOException) {
                                error
                            }
                        if (!isActive) return@launch
                        if (failure != null && backendSession.compareAndSet(observed, observed.copy(running = false))) {
                            onFailure(failure)
                            return@launch
                        }
                    }
                }
        }

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
            if (activeSocketPath != null) throw IOException("Root-helper cleanup is pending")
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
            val ownedBackend = backendSession.getAndUpdate { it?.copy(running = false) }
            backendMonitor?.cancel()
            backendMonitor = null
            val process = helperProcess
            val socketPath = activeSocketPath
            val noncePath = activeNoncePath
            if (socketPath != null) {
                val shutdownFailure = runCatching { shutdownRequester(socketPath, noncePath) }.exceptionOrNull()
                if (shutdownFailure != null) {
                    log.w(shutdownFailure) { "failed to request root helper shutdown" }
                    if (ownedBackend != null) throw RuntimeCleanupPendingException(shutdownFailure)
                }
            }
            helperProcess = null
            activeSocketPath = null
            activeNoncePath = null
            backendSession.set(null)
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
                if (socketPath != null && ownedBackend == null) {
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

/** Finish local service destruction while the helper retains pending cleanup. */
internal fun RootHelperManager.stopOnDestroy() {
    try {
        stop()
    } catch (error: RuntimeCleanupPendingException) {
        Logger.withTag("RootHelperManager").w(error) { "root helper retains pending cleanup after service destruction" }
    }
}
