package com.poyka.ripdpi.services

import android.content.Context
import android.os.Build
import com.poyka.ripdpi.core.ResolvedRipDpiRelayConfig
import com.poyka.ripdpi.core.RipDpiRelayFactory
import com.poyka.ripdpi.core.RipDpiRelayRuntime
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.net.URL
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.thread

private const val CloudflaredBinaryName = "ripdpi-cloudflared"
private const val CloudflareOriginBinaryName = "ripdpi-cloudflare-origin"
private const val CloudflarePublishMetricsReadyPath = "/ready"
private const val CloudflarePublishReadyPollIntervalMs = 100L
private const val CloudflarePublishReadyTimeoutMs = 7_500L
private const val CloudflareOriginReadyPrefix = "RIPDPI-READY|cloudflare-origin|"
private const val CloudflareOriginErrorPrefix = "RIPDPI-ERROR|cloudflare-origin|"
private const val CloudflarePublishRuntimeKind = "cloudflared"

internal data class CloudflareLocalOriginSpec(
    val rawUrl: String,
    val host: String,
    val port: Int,
)

internal data class CloudflareNamedTunnelSpec(
    val tunnelId: String,
    val credentialsJson: String,
)

internal fun parseCloudflareLocalOriginSpec(rawUrl: String): CloudflareLocalOriginSpec {
    val uri = URI(rawUrl.trim())
    require(uri.scheme.equals("http", ignoreCase = true)) {
        "Cloudflare publish origin must use http:// loopback"
    }
    val host = uri.host ?: error("Cloudflare publish origin must include a host")
    require(host == "127.0.0.1" || host == "localhost" || host == "::1") {
        "Cloudflare publish origin must bind to loopback only"
    }
    require(uri.port > 0) {
        "Cloudflare publish origin must include an explicit port"
    }
    require(uri.rawPath.isNullOrBlank() || uri.rawPath == "/") {
        "Cloudflare publish origin URL must not include a path"
    }
    require(uri.rawQuery.isNullOrBlank() && uri.rawFragment.isNullOrBlank()) {
        "Cloudflare publish origin URL must not include query or fragment parameters"
    }
    return CloudflareLocalOriginSpec(
        rawUrl = "http://${if (host == "::1") "[::1]" else host}:${uri.port}",
        host = host,
        port = uri.port,
    )
}

internal fun extractCloudflareNamedTunnelSpec(credentialsJson: String): CloudflareNamedTunnelSpec {
    val parsed =
        Json.parseToJsonElement(credentialsJson).let { element ->
            require(element is JsonObject) { "Cloudflare named-tunnel credentials must be a JSON object" }
            element
        }
    val tunnelId =
        listOf("TunnelID", "tunnelID", "tunnelId", "tunnel_id")
            .firstNotNullOfOrNull { key ->
                parsed[key]
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?.trim()
                    ?.takeIf(String::isNotBlank)
            } ?: error("Cloudflare named-tunnel credentials are missing TunnelID")
    return CloudflareNamedTunnelSpec(
        tunnelId = tunnelId,
        credentialsJson = credentialsJson,
    )
}

internal fun buildCloudflaredConfigYaml(
    tunnelId: String,
    credentialsFilePath: String,
    metricsAddress: String,
    hostname: String,
    serviceUrl: String,
): String =
    """
    |tunnel: $tunnelId
    |credentials-file: $credentialsFilePath
    |metrics: $metricsAddress
    |ingress:
    |  - hostname: $hostname
    |    service: $serviceUrl
    |  - service: http_status:404
    """.trimMargin()

private data class ManagedCloudflareProcess(
    val process: Process,
    val version: String?,
    val outputThread: Thread,
)

private data class RunningCloudflarePublish(
    val originProcess: ManagedCloudflareProcess,
    val cloudflaredProcess: ManagedCloudflareProcess,
    val metricsAddress: String,
    val localOriginUrl: String,
    val originReadySignal: CompletableDeferred<String>,
    @Volatile var lastError: String? = null,
    @Volatile var lastFailureClass: String? = null,
    @Volatile var originReady: Boolean = false,
    @Volatile var cloudflaredReady: Boolean = false,
    @Volatile var originListenerAddress: String? = null,
)

@Singleton
class CloudflarePublishManager
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) {
        @Volatile private var running: RunningCloudflarePublish? = null

        suspend fun start(config: ResolvedRipDpiRelayConfig) {
            require(config.kind == com.poyka.ripdpi.data.RelayKindCloudflareTunnel) {
                "Cloudflare publish runtime only supports Cloudflare Tunnel profiles"
            }
            val originSpec = parseCloudflareLocalOriginSpec(config.cloudflarePublishLocalOriginUrl)
            val metricsPort = findLoopbackPort()
            val metricsAddress = "127.0.0.1:$metricsPort"
            val stateDir =
                File(
                    context.filesDir,
                    "cloudflare-publish/${sanitizeSegment(config.profileId)}",
                ).apply { mkdirs() }
            val originReadySignal = CompletableDeferred<String>()
            var runningState: RunningCloudflarePublish? = null
            var pendingLastError: String? = null
            var pendingFailureClass: String? = null
            val originProcess =
                launchOriginProcess(
                    config = config,
                    originSpec = originSpec,
                    stateDir = stateDir,
                    readySignal = originReadySignal,
                    onError = { message, failureClass ->
                        runningState?.lastError = message
                        runningState?.lastFailureClass = failureClass
                        if (runningState == null) {
                            pendingLastError = message
                            pendingFailureClass = failureClass
                        }
                    },
                )
            runningState =
                RunningCloudflarePublish(
                    originProcess = originProcess,
                    cloudflaredProcess =
                        launchCloudflaredProcess(
                            config = config,
                            originSpec = originSpec,
                            metricsAddress = metricsAddress,
                            stateDir = stateDir,
                            lastErrorSink = { message, failureClass ->
                                runningState?.lastError = message
                                runningState?.lastFailureClass = failureClass
                            },
                        ),
                    metricsAddress = metricsAddress,
                    localOriginUrl = originSpec.rawUrl,
                    originReadySignal = originReadySignal,
                    originReady = false,
                    cloudflaredReady = false,
                )
            val state = requireNotNull(runningState)
            pendingLastError?.let { state.lastError = it }
            pendingFailureClass?.let { state.lastFailureClass = it }
            running = state
            try {
                waitForOriginReady(state)
                state.originReady = true
                waitForCloudflaredReady(state)
                state.cloudflaredReady = true
            } catch (error: Exception) {
                runCatching { stop() }
                throw error
            }
        }

        suspend fun waitForUnexpectedExit(): Int =
            coroutineScope {
                val active = running ?: return@coroutineScope 0
                val originExit = async(Dispatchers.IO) { active.originProcess.process.waitFor() to "origin" }
                val cloudflaredExit =
                    async(Dispatchers.IO) {
                        active.cloudflaredProcess.process.waitFor() to "cloudflared"
                    }
                val (exitCode, source) =
                    select<Pair<Int, String>> {
                        originExit.onAwait { it }
                        cloudflaredExit.onAwait { it }
                    }
                if (exitCode != 0) {
                    active.lastFailureClass = "helper_exit"
                    active.lastError = "Cloudflare publish $source exited with code $exitCode"
                }
                exitCode
            }

        suspend fun stop() {
            withContext(Dispatchers.IO) {
                val active = running
                running = null
                if (active == null) {
                    return@withContext
                }
                stopManagedProcess(active.cloudflaredProcess)
                stopManagedProcess(active.originProcess)
            }
        }

        fun pollTelemetry(relayTelemetry: NativeRuntimeSnapshot): NativeRuntimeSnapshot {
            val active = running
            if (active == null) {
                return relayTelemetry
            }
            val helpersRunning = isAlive(active.originProcess.process) && isAlive(active.cloudflaredProcess.process)
            return relayTelemetry.copy(
                ptRuntimeKind = CloudflarePublishRuntimeKind,
                ptRuntimeState =
                    when {
                        helpersRunning && active.originReady && active.cloudflaredReady -> "running"
                        active.lastError != null -> "failed"
                        else -> "starting"
                    },
                ptRuntimeVersion =
                    buildList {
                        active.cloudflaredProcess.version?.let { add(it) }
                        active.originProcess.version?.let { add("origin=$it") }
                    }.joinToString(" | ").ifBlank { null },
                listenerAddress = relayTelemetry.listenerAddress ?: active.originListenerAddress,
                lastError = relayTelemetry.lastError ?: active.lastError,
                lastFailureClass = relayTelemetry.lastFailureClass ?: active.lastFailureClass,
            )
        }

        private fun launchOriginProcess(
            config: ResolvedRipDpiRelayConfig,
            originSpec: CloudflareLocalOriginSpec,
            stateDir: File,
            readySignal: CompletableDeferred<String>,
            onError: (String, String) -> Unit,
        ): ManagedCloudflareProcess {
            val binary = extractBinary(CloudflareOriginBinaryName)
            val version = probeVersion(binary, listOf("--version"))
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
                startOutputThread(process, redacted) { line ->
                    when {
                        line.startsWith(CloudflareOriginReadyPrefix) -> {
                            val parts = line.split('|', limit = 4)
                            readySignal.complete(parts.getOrNull(3).orEmpty())
                        }

                        line.startsWith(CloudflareOriginErrorPrefix) -> {
                            val parts = line.split('|', limit = 4)
                            onError(parts.getOrNull(3).orEmpty(), parts.getOrNull(2).orEmpty())
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

        private fun launchCloudflaredProcess(
            config: ResolvedRipDpiRelayConfig,
            originSpec: CloudflareLocalOriginSpec,
            metricsAddress: String,
            stateDir: File,
            lastErrorSink: (String, String) -> Unit,
        ): ManagedCloudflareProcess {
            val binary = extractBinary(CloudflaredBinaryName)
            val version = probeVersion(binary, listOf("--version"))
            val launchPlan =
                buildCloudflaredLaunchPlan(
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
                startOutputThread(process, launchPlan.redactedValues) { line ->
                    when {
                        line.contains("ERR", ignoreCase = true) || line.contains("error", ignoreCase = true) -> {
                            lastErrorSink(line, "cloudflared")
                        }

                        line.contains("Registered tunnel connection", ignoreCase = true) -> {
                            running?.cloudflaredReady = true
                        }
                    }
                }
            return ManagedCloudflareProcess(
                process = process,
                version = version,
                outputThread = outputThread,
            )
        }

        private suspend fun waitForOriginReady(state: RunningCloudflarePublish) {
            val deadline = System.currentTimeMillis() + CloudflarePublishReadyTimeoutMs
            while (System.currentTimeMillis() < deadline) {
                if (!isAlive(state.originProcess.process)) {
                    throw IOException(state.lastError ?: "Cloudflare origin helper exited before readiness")
                }
                if (state.originReadySignal.isCompleted) {
                    state.originListenerAddress = state.originReadySignal.await()
                    return
                }
                delay(CloudflarePublishReadyPollIntervalMs)
            }
            throw IOException("Cloudflare origin helper readiness timed out")
        }

        private suspend fun waitForCloudflaredReady(state: RunningCloudflarePublish) {
            val deadline = System.currentTimeMillis() + CloudflarePublishReadyTimeoutMs
            while (System.currentTimeMillis() < deadline) {
                if (!isAlive(state.cloudflaredProcess.process)) {
                    throw IOException(state.lastError ?: "cloudflared exited before readiness")
                }
                if (cloudflaredReady(state.metricsAddress)) {
                    return
                }
                delay(CloudflarePublishReadyPollIntervalMs)
            }
            throw IOException("cloudflared readiness timed out")
        }

        private fun cloudflaredReady(metricsAddress: String): Boolean =
            runCatching {
                val connection =
                    (
                        URL("http://$metricsAddress$CloudflarePublishMetricsReadyPath")
                            .openConnection() as HttpURLConnection
                    )
                connection.connectTimeout = 300
                connection.readTimeout = 300
                connection.requestMethod = "GET"
                connection.connect()
                (connection.responseCode in 200..299).also {
                    connection.disconnect()
                }
            }.getOrDefault(false)

        private fun extractBinary(binaryName: String): File {
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

        private fun probeVersion(
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

        private fun startOutputThread(
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

        private fun stopManagedProcess(process: ManagedCloudflareProcess) {
            process.outputThread.interrupt()
            process.process.destroy()
            if (!process.process.waitFor(1_500, TimeUnit.MILLISECONDS)) {
                process.process.destroyForcibly()
                process.process.waitFor(1_500, TimeUnit.MILLISECONDS)
            }
        }

        private fun buildCloudflaredLaunchPlan(
            config: ResolvedRipDpiRelayConfig,
            originSpec: CloudflareLocalOriginSpec,
            metricsAddress: String,
            stateDir: File,
        ): CloudflaredLaunchPlan {
            val credentialsJson = config.cloudflareTunnelCredentialsJson?.trim().orEmpty()
            if (credentialsJson.isNotEmpty()) {
                val namedTunnel = extractCloudflareNamedTunnelSpec(credentialsJson)
                val credentialsFile =
                    File(stateDir, "cloudflared-credentials.json").apply {
                        writeText(namedTunnel.credentialsJson)
                    }
                val configFile =
                    File(stateDir, "cloudflared-config.yml").apply {
                        writeText(
                            buildCloudflaredConfigYaml(
                                tunnelId = namedTunnel.tunnelId,
                                credentialsFilePath = credentialsFile.absolutePath,
                                metricsAddress = metricsAddress,
                                hostname = config.server,
                                serviceUrl = originSpec.rawUrl,
                            ),
                        )
                    }
                return CloudflaredLaunchPlan(
                    arguments =
                        listOf(
                            "tunnel",
                            "--no-autoupdate",
                            "--config",
                            configFile.absolutePath,
                            "run",
                        ),
                    environment = emptyMap(),
                    redactedValues = emptyList(),
                )
            }
            val token = config.cloudflareTunnelToken?.trim().orEmpty()
            require(token.isNotEmpty()) {
                "Cloudflare publish mode requires a tunnel token or named-tunnel credentials JSON"
            }
            return CloudflaredLaunchPlan(
                arguments =
                    listOf(
                        "tunnel",
                        "--no-autoupdate",
                        "--metrics",
                        metricsAddress,
                        "run",
                        "--token",
                        token,
                    ),
                environment = emptyMap(),
                redactedValues = listOf(token),
            )
        }

        private fun findLoopbackPort(): Int =
            ServerSocket(0, 0, InetAddress.getByName("127.0.0.1")).use { socket ->
                socket.localPort
            }

        private fun sanitizeSegment(raw: String): String = raw.replace(Regex("[^a-zA-Z0-9._-]"), "_")

        private fun isAlive(process: Process): Boolean =
            runCatching {
                process.exitValue()
                false
            }.getOrDefault(true)
    }

private data class CloudflaredLaunchPlan(
    val arguments: List<String>,
    val environment: Map<String, String>,
    val redactedValues: List<String>,
)

class CloudflarePublishRuntime
    @Inject
    constructor(
        private val relayFactory: RipDpiRelayFactory,
        private val publishManager: CloudflarePublishManager,
    ) : RipDpiRelayRuntime {
        @Volatile private var stopping = false

        @Volatile private var relayRuntime: RipDpiRelayRuntime? = null

        @Volatile private var activeConfig: ResolvedRipDpiRelayConfig? = null
        private var relayStartSignal = CompletableDeferred<RipDpiRelayRuntime>()

        override suspend fun start(config: ResolvedRipDpiRelayConfig): Int =
            coroutineScope {
                stopping = false
                activeConfig = config
                relayStartSignal = CompletableDeferred()
                publishManager.start(config)
                val relay = relayFactory.create()
                relayRuntime = relay
                relayStartSignal.complete(relay)
                val relayExit = async { relay.start(config) }
                val helperExit = async { publishManager.waitForUnexpectedExit() }
                val exitCode =
                    select<Int> {
                        relayExit.onAwait { code ->
                            publishManager.stop()
                            code
                        }
                        helperExit.onAwait { code ->
                            if (!stopping) {
                                relay.stop()
                            }
                            code
                        }
                    }
                relayRuntime = null
                activeConfig = null
                exitCode
            }

        override suspend fun awaitReady(timeoutMillis: Long) {
            withTimeout(timeoutMillis) {
                val relay = relayStartSignal.await()
                relay.awaitReady(timeoutMillis)
            }
        }

        override suspend fun stop() {
            stopping = true
            runCatching { relayRuntime?.stop() }
            publishManager.stop()
            relayRuntime = null
            activeConfig = null
        }

        override suspend fun pollTelemetry(): NativeRuntimeSnapshot {
            val relayTelemetry = relayRuntime?.pollTelemetry() ?: NativeRuntimeSnapshot(source = "relay")
            return activeConfig?.let {
                publishManager.pollTelemetry(
                    relayTelemetry =
                        relayTelemetry.copy(
                            upstreamAddress = relayTelemetry.upstreamAddress ?: it.server.takeIf(String::isNotBlank),
                        ),
                )
            } ?: relayTelemetry
        }
    }

interface CloudflarePublishRuntimeFactory {
    fun create(): RipDpiRelayRuntime
}

@Singleton
class DefaultCloudflarePublishRuntimeFactory
    @Inject
    constructor(
        private val runtime: CloudflarePublishRuntime,
    ) : CloudflarePublishRuntimeFactory {
        override fun create(): RipDpiRelayRuntime = runtime
    }

@Module
@InstallIn(SingletonComponent::class)
abstract class CloudflarePublishRuntimeFactoryModule {
    @Binds
    @Singleton
    abstract fun bindCloudflarePublishRuntimeFactory(
        factory: DefaultCloudflarePublishRuntimeFactory,
    ): CloudflarePublishRuntimeFactory
}
