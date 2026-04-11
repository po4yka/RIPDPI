package com.poyka.ripdpi.services

import android.content.Context
import android.os.Build
import com.poyka.ripdpi.core.ResolvedRipDpiRelayConfig
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.RelayKindNaiveProxy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.thread

private const val RelayReadyPollIntervalMs = 100L
private const val RelayReadyTimeoutMs = 5_000L

data class SubprocessSocksRelayLaunchSpec(
    val binaryName: String,
    val commandArguments: List<String>,
    val runtimeKind: String,
    val upstreamAddress: String? = null,
    val environment: Map<String, String> = emptyMap(),
    val managedClientBridge: ManagedClientSocksBridgeSpec? = null,
)

@Singleton
class SubprocessSocksRelayManager
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) {
        @Volatile private var process: Process? = null

        @Volatile private var config: ResolvedRipDpiRelayConfig? = null

        @Volatile private var launchSpec: SubprocessSocksRelayLaunchSpec? = null

        @Volatile private var lastError: String? = null

        @Volatile private var processOutputThread: Thread? = null

        @Volatile private var managedClientListener: InetSocketAddress? = null

        @Volatile private var managedClientBridge: ManagedClientSocksBridge? = null

        suspend fun start(
            config: ResolvedRipDpiRelayConfig,
            spec: SubprocessSocksRelayLaunchSpec,
        ) {
            withContext(Dispatchers.IO) {
                stopInternal()
                val binary = extractBinary(spec.binaryName)
                val processBuilder =
                    ProcessBuilder(
                        buildList {
                            add(binary.absolutePath)
                            addAll(spec.commandArguments)
                        },
                    )
                processBuilder.redirectErrorStream(true)
                processBuilder.environment().putAll(spec.environment)
                process = processBuilder.start()
                this@SubprocessSocksRelayManager.config = config
                this@SubprocessSocksRelayManager.launchSpec = spec
                lastError = null
                managedClientListener = null
                managedClientBridge = null
                processOutputThread = startProcessOutputThread(process!!, spec)
            }
            if (spec.managedClientBridge != null) {
                val listener = waitForManagedClientListener(spec)
                withContext(Dispatchers.IO) {
                    managedClientBridge =
                        ManagedClientSocksBridge(
                            listenHost = config.localSocksHost,
                            listenPort = config.localSocksPort,
                            upstreamListener = listener,
                            bridgeSpec = spec.managedClientBridge,
                        ).also { bridge ->
                            bridge.start()
                        }
                }
            }
            waitUntilReady(config)
        }

        suspend fun waitForExit(): Int =
            withContext(Dispatchers.IO) {
                process?.waitFor() ?: 0
            }

        suspend fun pollTelemetry(): NativeRuntimeSnapshot =
            withContext(Dispatchers.IO) {
                val activeConfig = config
                val activeLaunchSpec = launchSpec
                val running = isRunning()
                NativeRuntimeSnapshot(
                    source = "relay",
                    state = if (running) "running" else "idle",
                    health = if (running) "running" else "idle",
                    listenerAddress = activeConfig?.let { "${it.localSocksHost}:${it.localSocksPort}" },
                    upstreamAddress = activeLaunchSpec?.upstreamAddress,
                    profileId = activeConfig?.profileId,
                    protocolKind = activeConfig?.kind,
                    tcpCapable = true,
                    udpCapable = false,
                    lastError = lastError,
                    ptRuntimeKind =
                        activeLaunchSpec?.runtimeKind?.takeUnless { it == RelayKindNaiveProxy },
                    ptRuntimeState =
                        activeLaunchSpec
                            ?.runtimeKind
                            ?.takeUnless { it == RelayKindNaiveProxy }
                            ?.let {
                                when {
                                    running -> "running"
                                    lastError != null -> "failed"
                                    else -> "idle"
                                }
                            },
                )
            }

        suspend fun stop() {
            withContext(Dispatchers.IO) {
                stopInternal()
            }
        }

        private fun stopInternal() {
            managedClientBridge?.close()
            managedClientBridge = null
            managedClientListener = null
            processOutputThread?.interrupt()
            processOutputThread = null
            val activeProcess = process
            process = null
            config = null
            launchSpec = null
            if (activeProcess == null) {
                return
            }
            try {
                activeProcess.destroy()
                runCatching { activeProcess.waitFor() }
                if (runCatching { activeProcess.exitValue() }.isFailure) {
                    activeProcess.destroyForcibly()
                }
            } catch (error: IOException) {
                lastError = error.message
            } catch (error: SecurityException) {
                lastError = error.message
            }
        }

        private fun isRunning(): Boolean {
            val activeProcess = process ?: return false
            return runCatching {
                activeProcess.exitValue()
                false
            }.getOrDefault(true)
        }

        private suspend fun waitUntilReady(config: ResolvedRipDpiRelayConfig) {
            val deadline = System.currentTimeMillis() + RelayReadyTimeoutMs
            while (System.currentTimeMillis() < deadline) {
                if (!isRunning()) {
                    lastError = "Subprocess relay exited before readiness"
                    error(lastError ?: "Subprocess relay exited")
                }
                if (canConnect(config.localSocksHost, config.localSocksPort)) {
                    return
                }
                delay(RelayReadyPollIntervalMs)
            }
            lastError = "Subprocess relay readiness timed out"
            error(lastError ?: "Subprocess relay readiness timed out")
        }

        private fun canConnect(
            host: String,
            port: Int,
        ): Boolean =
            runCatching {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, port), 250)
                    socket.soTimeout = 250
                    socket.getOutputStream().write(byteArrayOf(0x05, 0x01, 0x00))
                    val response = socket.getInputStream().readNBytes(2)
                    if (!response.contentEquals(byteArrayOf(0x05, 0x00))) {
                        error("unexpected SOCKS5 readiness response")
                    }
                }
                true
            }.getOrDefault(false)

        private suspend fun waitForManagedClientListener(spec: SubprocessSocksRelayLaunchSpec): InetSocketAddress {
            val bridgeSpec = requireNotNull(spec.managedClientBridge)
            val deadline = System.currentTimeMillis() + RelayReadyTimeoutMs
            while (System.currentTimeMillis() < deadline) {
                managedClientListener?.let { return it }
                if (!isRunning()) {
                    lastError = lastError ?: "Managed PT exited before advertising a SOCKS listener"
                    error(lastError ?: "Managed PT exited")
                }
                delay(RelayReadyPollIntervalMs)
            }
            lastError = "Managed PT listener readiness timed out for ${bridgeSpec.methodName}"
            error(lastError ?: "Managed PT listener readiness timed out")
        }

        private fun startProcessOutputThread(
            activeProcess: Process,
            spec: SubprocessSocksRelayLaunchSpec,
        ): Thread =
            thread(
                name = "subprocess-relay-output-${spec.runtimeKind}",
                isDaemon = true,
            ) {
                activeProcess.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        handleProcessOutputLine(line, spec)
                    }
                }
            }

        private fun handleProcessOutputLine(
            line: String,
            spec: SubprocessSocksRelayLaunchSpec,
        ) {
            val trimmed = line.trim()
            if (trimmed.isBlank()) {
                return
            }
            spec.managedClientBridge
                ?.let { bridgeSpec ->
                    parseManagedClientListenerLine(trimmed, bridgeSpec.methodName)
                }?.let { listener ->
                    managedClientListener = listener
                    return
                }
            if (
                trimmed.startsWith("ENV-ERROR") ||
                trimmed.startsWith("VERSION-ERROR") ||
                trimmed.startsWith("PROXY-ERROR") ||
                trimmed.startsWith("CMETHOD-ERROR") ||
                trimmed.startsWith("SMETHOD-ERROR") ||
                trimmed.contains("[ERROR]") ||
                trimmed.contains(" error", ignoreCase = true)
            ) {
                lastError = trimmed
            }
        }

        private fun extractBinary(binaryName: String): File {
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
