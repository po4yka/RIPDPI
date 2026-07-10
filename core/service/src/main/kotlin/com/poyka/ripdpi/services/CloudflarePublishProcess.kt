@file:Suppress("TooGenericExceptionCaught")

package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.ResolvedRipDpiRelayConfig
import kotlinx.coroutines.CompletableDeferred
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.concurrent.thread

private const val CloudflareOriginReadyPrefix = "RIPDPI-READY|cloudflare-origin|"
private const val CloudflareOriginErrorPrefix = "RIPDPI-ERROR|cloudflare-origin|"
private const val CloudflareOriginSignalPartsLimit = 4
private const val CloudflareOriginFailureClassIndex = 2
private const val CloudflareOriginMessageIndex = 3
private const val CloudflareProcessStopTimeoutMs = 1_500L
private const val CloudflareOriginConfigStdinFlag = "--config-stdin"
private val CloudflareInheritedConfigEnvironment =
    setOf(
        "HOME",
        "NO_AUTOUPDATE",
        "DIAL_EDGE_TIMEOUT",
        "STDIN_CONTROL",
        "NO_TLS_VERIFY",
        "LOCAL_SSH_PORT",
        "SSH_IDLE_TIMEOUT",
        "SSH_MAX_TIMEOUT",
        "BUCKET_ID",
        "REGION_ID",
        "SECRET_ID",
        "ACCESS_CLIENT_ID",
        "SESSION_TOKEN_ID",
        "S3_URL",
        "HOST_KEY_PATH",
    )

internal fun MutableMap<String, String>.scrubCloudflareHelperEnvironment() {
    keys.removeAll { key -> key.startsWith("TUNNEL_") || key in CloudflareInheritedConfigEnvironment }
}

internal data class ManagedCloudflareProcess(
    val process: Process,
    val version: String?,
    val outputThread: Thread,
)

internal data class RunningCloudflarePublish(
    val originProcess: ManagedCloudflareProcess,
    val cloudflaredProcess: ManagedCloudflareProcess,
    val metricsAddress: String,
    val originReadySignal: CompletableDeferred<String>,
    @Volatile var lastError: String? = null,
    @Volatile var lastFailureClass: String? = null,
    @Volatile var originReady: Boolean = false,
    @Volatile var cloudflaredReady: Boolean = false,
    @Volatile var originListenerAddress: String? = null,
)

internal open class CloudflarePublishProcessSupervisor
    @Inject
    constructor(
        private val binaryExtractor: CloudflarePublishBinaryExtractor,
        private val versionProbe: CloudflarePublishVersionProbe,
        private val launchPlanBuilder: CloudflaredLaunchPlanBuilder,
        private val outputReader: CloudflarePublishProcessOutputReader,
        private val protectPathProvider: ActiveProtectSocketPathProvider,
    ) {
        private val outstandingProcesses = mutableListOf<OutstandingCloudflareProcess>()

        open fun launchOriginProcess(
            config: ResolvedRipDpiRelayConfig,
            originSpec: CloudflareLocalOriginSpec,
            stateDir: File,
            readySignal: CompletableDeferred<String>,
            onError: (String, String) -> Unit,
        ): ManagedCloudflareProcess {
            val binary = binaryExtractor.extract(CloudflareOriginBinaryName, config.cloudflareTunnelMode)
            val version = versionProbe.probe(binary, listOf("--version"))
            val startupConfig =
                encodeCloudflareOriginStartupConfig(
                    originSpec = originSpec,
                    xhttpPath = config.xhttpPath,
                    vlessUuid = config.vlessUuid,
                )
            val redacted = cloudflarePublishRedactedValues(config, stateDir)
            val processBuilder =
                ProcessBuilder(
                    listOf(
                        binary.absolutePath,
                        CloudflareOriginConfigStdinFlag,
                    ),
                ).redirectErrorStream(true)
                    .directory(stateDir)
            processBuilder.environment().scrubCloudflareHelperEnvironment()
            protectPathProvider.current()?.let {
                processBuilder.environment()[ActiveProtectSocketPathProvider.ENV_VAR] = it
            }
            return startManagedProcess(
                processBuilder = processBuilder,
                version = version,
                redactedValues = redacted,
                stdinPayload = startupConfig,
            ) { line ->
                when {
                    line.startsWith(CloudflareOriginReadyPrefix) -> {
                        val parts = line.split('|', limit = CloudflareOriginSignalPartsLimit)
                        readySignal.complete(parts.getOrNull(CloudflareOriginMessageIndex).orEmpty())
                    }

                    line.startsWith(CloudflareOriginErrorPrefix) -> {
                        val parts = line.split('|', limit = CloudflareOriginSignalPartsLimit)
                        onError(
                            parts.getOrNull(CloudflareOriginMessageIndex).orEmpty(),
                            parts.getOrNull(CloudflareOriginFailureClassIndex).orEmpty(),
                        )
                    }

                    line.contains("error", ignoreCase = true) -> {
                        onError(line, "origin")
                    }
                }
            }
        }

        open fun launchCloudflaredProcess(
            config: ResolvedRipDpiRelayConfig,
            originSpec: CloudflareLocalOriginSpec,
            metricsAddress: String,
            stateDir: File,
            lastErrorSink: (String, String) -> Unit,
            onRegisteredTunnelConnection: () -> Unit,
        ): ManagedCloudflareProcess {
            val binary = binaryExtractor.extract(CloudflaredBinaryName, config.cloudflareTunnelMode)
            val version = versionProbe.probe(binary, listOf("--version"))
            val launchPlan =
                launchPlanBuilder.build(
                    config = config,
                    originSpec = originSpec,
                    metricsAddress = metricsAddress,
                    stateDir = stateDir,
                )
            val processBuilder =
                ProcessBuilder(
                    buildList {
                        add(binary.absolutePath)
                        addAll(launchPlan.arguments)
                    },
                ).redirectErrorStream(true)
                    .directory(stateDir)
            processBuilder.environment().apply {
                scrubCloudflareHelperEnvironment()
                putAll(launchPlan.environment)
            }
            return startManagedProcess(
                processBuilder = processBuilder,
                version = version,
                redactedValues = launchPlan.redactedValues,
                stdinPayload = null,
            ) { line ->
                when {
                    line.contains("ERR", ignoreCase = true) || line.contains("error", ignoreCase = true) -> {
                        lastErrorSink(line, "cloudflared")
                    }

                    line.contains("Registered tunnel connection", ignoreCase = true) -> {
                        onRegisteredTunnelConnection()
                    }
                }
            }
        }

        open fun stop(process: ManagedCloudflareProcess) {
            terminateProcess(process.process, process.outputThread)
        }

        open fun stopOutstandingProcesses() {
            val snapshot = synchronized(outstandingProcesses) { outstandingProcesses.toList() }
            var stopFailure: Throwable? = null
            snapshot.forEach { outstanding ->
                runCatching { terminateProcess(outstanding.process, outstanding.outputThread) }
                    .onSuccess {
                        synchronized(outstandingProcesses) { outstandingProcesses.remove(outstanding) }
                    }.onFailure { error ->
                        if (stopFailure == null) stopFailure = error
                    }
            }
            stopFailure?.let { throw it }
        }

        internal open fun startProcess(processBuilder: ProcessBuilder): Process = processBuilder.start()

        private fun startManagedProcess(
            processBuilder: ProcessBuilder,
            version: String?,
            redactedValues: List<String>,
            stdinPayload: ByteArray?,
            onLine: (String) -> Unit,
        ): ManagedCloudflareProcess {
            var process: Process? = null
            var outputThread: Thread? = null
            try {
                process = startProcess(processBuilder)
                val outstanding = OutstandingCloudflareProcess(process, null)
                synchronized(outstandingProcesses) { outstandingProcesses += outstanding }
                process.outputStream.use { input ->
                    stdinPayload?.let(input::write)
                    input.flush()
                }
                outputThread = outputReader.start(process, redactedValues, onLine)
                synchronized(outstandingProcesses) { outstandingProcesses.remove(outstanding) }
                return ManagedCloudflareProcess(
                    process = process,
                    version = version,
                    outputThread = outputThread,
                )
            } catch (
                @Suppress("TooGenericExceptionCaught") error: Exception,
            ) {
                process?.let { started ->
                    val cleanupError = runCatching { terminateProcess(started, outputThread) }.exceptionOrNull()
                    if (cleanupError == null) {
                        synchronized(outstandingProcesses) {
                            outstandingProcesses.removeAll { it.process === started }
                        }
                    } else {
                        synchronized(outstandingProcesses) {
                            outstandingProcesses
                                .firstOrNull { it.process === started }
                                ?.outputThread = outputThread
                        }
                        error.addSuppressed(cleanupError)
                    }
                }
                throw error
            }
        }

        private fun terminateProcess(
            process: Process,
            outputThread: Thread?,
        ) {
            outputThread?.interrupt()
            runCatching { process.outputStream.close() }
            runCatching { process.destroy() }
            var terminated =
                runCatching {
                    process.waitFor(CloudflareProcessStopTimeoutMs, TimeUnit.MILLISECONDS)
                }.getOrDefault(false)
            if (!terminated) {
                runCatching { process.destroyForcibly() }
                terminated =
                    runCatching {
                        process.waitFor(CloudflareProcessStopTimeoutMs, TimeUnit.MILLISECONDS)
                    }.getOrDefault(false)
            }
            outputThread?.join(CloudflareProcessStopTimeoutMs)
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
            check(terminated && !isCloudflareProcessAlive(process)) {
                "Cloudflare helper could not be terminated and reaped"
            }
        }

        private class OutstandingCloudflareProcess(
            val process: Process,
            var outputThread: Thread?,
        )
    }

internal open class CloudflarePublishProcessOutputReader
    @Inject
    constructor() {
        open fun start(
            process: Process,
            redactedValues: List<String>,
            onLine: (String) -> Unit,
        ): Thread =
            thread(
                name = "cloudflare-publish-output",
                isDaemon = true,
            ) {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { rawLine ->
                        val line =
                            redactedValues
                                .filter(String::isNotBlank)
                                .fold(rawLine.trim()) { message, secret ->
                                    message.replace(secret, "<redacted>")
                                }
                        if (line.isNotBlank()) {
                            onLine(line)
                        }
                    }
                }
            }
    }

internal fun isCloudflareProcessAlive(process: Process): Boolean =
    runCatching {
        process.exitValue()
        false
    }.getOrDefault(true)
