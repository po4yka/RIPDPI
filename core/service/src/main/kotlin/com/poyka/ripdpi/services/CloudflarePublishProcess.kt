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

internal class CloudflarePublishProcessSupervisor
    @Inject
    constructor(
        private val binaryExtractor: CloudflarePublishBinaryExtractor,
        private val versionProbe: CloudflarePublishVersionProbe,
        private val launchPlanBuilder: CloudflaredLaunchPlanBuilder,
        private val outputReader: CloudflarePublishProcessOutputReader,
    ) {
        fun launchOriginProcess(
            config: ResolvedRipDpiRelayConfig,
            originSpec: CloudflareLocalOriginSpec,
            stateDir: File,
            readySignal: CompletableDeferred<String>,
            onError: (String, String) -> Unit,
        ): ManagedCloudflareProcess {
            val binary = binaryExtractor.extract(CloudflareOriginBinaryName)
            val version = versionProbe.probe(binary, listOf("--version"))
            val redacted = listOfNotNull(config.vlessUuid)
            val process =
                ProcessBuilder(
                    listOf(
                        binary.absolutePath,
                        "--listen",
                        "${originSpec.host}:${originSpec.port}",
                        "--path",
                        config.xhttpPath.ifBlank { "/" },
                        "--uuid",
                        config.vlessUuid.orEmpty(),
                    ),
                ).redirectErrorStream(true)
                    .directory(stateDir)
                    .start()
            val outputThread =
                outputReader.start(process, redacted) { line ->
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
            return ManagedCloudflareProcess(
                process = process,
                version = version,
                outputThread = outputThread,
            )
        }

        fun launchCloudflaredProcess(
            config: ResolvedRipDpiRelayConfig,
            originSpec: CloudflareLocalOriginSpec,
            metricsAddress: String,
            stateDir: File,
            lastErrorSink: (String, String) -> Unit,
            onRegisteredTunnelConnection: () -> Unit,
        ): ManagedCloudflareProcess {
            val binary = binaryExtractor.extract(CloudflaredBinaryName)
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
            processBuilder.environment().putAll(launchPlan.environment)
            val process = processBuilder.start()
            val outputThread =
                outputReader.start(process, launchPlan.redactedValues) { line ->
                    when {
                        line.contains("ERR", ignoreCase = true) || line.contains("error", ignoreCase = true) -> {
                            lastErrorSink(line, "cloudflared")
                        }

                        line.contains("Registered tunnel connection", ignoreCase = true) -> {
                            onRegisteredTunnelConnection()
                        }
                    }
                }
            return ManagedCloudflareProcess(
                process = process,
                version = version,
                outputThread = outputThread,
            )
        }

        fun stop(process: ManagedCloudflareProcess) {
            process.outputThread.interrupt()
            process.process.destroy()
            if (!process.process.waitFor(CloudflareProcessStopTimeoutMs, TimeUnit.MILLISECONDS)) {
                process.process.destroyForcibly()
                process.process.waitFor(CloudflareProcessStopTimeoutMs, TimeUnit.MILLISECONDS)
            }
        }
    }

internal class CloudflarePublishProcessOutputReader
    @Inject
    constructor() {
        fun start(
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
