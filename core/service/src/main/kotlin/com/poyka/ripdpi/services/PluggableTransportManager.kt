@file:Suppress("MagicNumber")

package com.poyka.ripdpi.services

import android.content.Context
import com.poyka.ripdpi.core.ResolvedRipDpiRelayConfig
import com.poyka.ripdpi.core.ResolvedTorPluggableTransportConfig
import com.poyka.ripdpi.core.RipDpiRelayConfig
import com.poyka.ripdpi.core.RipDpiRelayRuntime
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.RelayKindObfs4
import com.poyka.ripdpi.data.RelayKindSnowflake
import com.poyka.ripdpi.data.RelayKindWebTunnel
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import java.io.File
import java.net.URI
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

private const val SnowflakeBinaryName = "ripdpi-snowflake"
private const val WebTunnelBinaryName = "ripdpi-webtunnel"
private const val Obfs4BinaryName = "ripdpi-obfs4"
private const val ManagedTransportVersion = "1"
private const val SnowflakeDummyTargetHost = "192.0.2.1"
private const val SnowflakeDummyTargetPort = 1

internal data class ParsedObfs4BridgeLine(
    val host: String,
    val port: Int,
    val cert: String,
    val iatMode: Int,
)

internal fun parseObfs4BridgeLine(rawBridgeLine: String): ParsedObfs4BridgeLine {
    val tokens = rawBridgeLine.trim().split(Regex("\\s+")).filter(String::isNotBlank)
    require(tokens.size >= 5) { "Invalid obfs4 bridge line" }
    val transportIndex =
        when {
            tokens[0].equals("Bridge", ignoreCase = true) -> 1
            else -> 0
        }
    require(tokens.getOrNull(transportIndex) == RelayKindObfs4) {
        "Bridge line must declare obfs4 transport"
    }
    val endpointToken = tokens.getOrNull(transportIndex + 1) ?: error("Bridge line is missing endpoint")
    val endpointDelimiter = endpointToken.lastIndexOf(':')
    require(endpointDelimiter > 0 && endpointDelimiter < endpointToken.lastIndex) {
        "Bridge line endpoint must include host:port"
    }
    val options =
        tokens.drop(transportIndex + 3).associate { token ->
            val delimiter = token.indexOf('=')
            require(delimiter > 0) { "Bridge line option is malformed: $token" }
            token.substring(0, delimiter) to token.substring(delimiter + 1)
        }
    val cert = options["cert"].orEmpty()
    require(cert.isNotBlank()) { "Bridge line must include cert=<base64>" }
    val iatMode = options["iat-mode"]?.toIntOrNull() ?: 0
    return ParsedObfs4BridgeLine(
        host = endpointToken.substring(0, endpointDelimiter),
        port = endpointToken.substring(endpointDelimiter + 1).toInt(),
        cert = cert,
        iatMode = iatMode,
    )
}

internal fun encodePtArguments(vararg entries: Pair<String, String?>): String =
    entries
        .filter { (_, value) -> !value.isNullOrBlank() }
        .joinToString(separator = ";") { (key, value) ->
            "${escapePtArgument(key)}=${escapePtArgument(requireNotNull(value))}"
        }

private fun escapePtArgument(value: String): String =
    buildString(value.length) {
        value.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                ';' -> append("\\;")
                else -> append(character)
            }
        }
    }

internal fun splitUrlTarget(rawUrl: String): Pair<String, Int> {
    val uri = URI(rawUrl)
    val host = uri.host ?: error("URL must include a host")
    val port =
        if (uri.port > 0) {
            uri.port
        } else {
            when (uri.scheme.lowercase()) {
                "http" -> 80
                "https" -> 443
                else -> error("Unsupported PT URL scheme: ${uri.scheme}")
            }
        }
    return host to port
}

@Singleton
open class PluggableTransportManager
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val subprocessManager: SubprocessSocksRelayManager,
    ) {
        open suspend fun start(config: ResolvedRipDpiRelayConfig) {
            subprocessManager.start(
                config = config,
                spec = launchSpec(config),
            )
        }

        open suspend fun waitForExit(): Int = subprocessManager.waitForExit()

        open suspend fun pollTelemetry(): NativeRuntimeSnapshot = subprocessManager.pollTelemetry()

        open suspend fun stop() = subprocessManager.stop()

        fun torManagedTransports(config: RipDpiRelayConfig): List<ResolvedTorPluggableTransportConfig> =
            buildList {
                val bridgeTransport = config.ptBridgeLine.bridgeTransportName()
                if (bridgeTransport == RelayKindObfs4) {
                    add(
                        ResolvedTorPluggableTransportConfig(
                            protocols = listOf(RelayKindObfs4),
                            binaryPath = subprocessManager.extractBinary(Obfs4BinaryName).absolutePath,
                            arguments = emptyList(),
                            runOnStartup = false,
                        ),
                    )
                }
                if (bridgeTransport == RelayKindWebTunnel || config.ptWebTunnelUrl.isNotBlank()) {
                    add(
                        ResolvedTorPluggableTransportConfig(
                            protocols = listOf(RelayKindWebTunnel),
                            binaryPath = subprocessManager.extractBinary(WebTunnelBinaryName).absolutePath,
                            arguments = emptyList(),
                            runOnStartup = false,
                        ),
                    )
                }
            }

        private fun launchSpec(config: ResolvedRipDpiRelayConfig): SubprocessSocksRelayLaunchSpec =
            when (config.kind) {
                RelayKindSnowflake -> {
                    SubprocessSocksRelayLaunchSpec(
                        binaryName = SnowflakeBinaryName,
                        runtimeKind = config.kind,
                        upstreamAddress = config.ptSnowflakeBrokerUrl,
                        commandArguments = emptyList(),
                        environment = managedTransportEnvironment(config, methodName = RelayKindSnowflake),
                        managedClientBridge =
                            ManagedClientSocksBridgeSpec(
                                methodName = RelayKindSnowflake,
                                targetHost = SnowflakeDummyTargetHost,
                                targetPort = SnowflakeDummyTargetPort,
                                ptArguments =
                                    encodePtArguments(
                                        "url" to config.ptSnowflakeBrokerUrl,
                                        "front" to config.ptSnowflakeFrontDomain,
                                        "utls-imitate" to "hellochrome_auto",
                                        "covertdtls-config" to "mimic",
                                    ),
                            ),
                    )
                }

                RelayKindWebTunnel -> {
                    val (targetHost, targetPort) = splitUrlTarget(config.ptWebTunnelUrl)
                    SubprocessSocksRelayLaunchSpec(
                        binaryName = WebTunnelBinaryName,
                        runtimeKind = config.kind,
                        protectionCapability = SubprocessSocketProtectionCapability.ProtectSocketPath,
                        upstreamAddress = config.ptWebTunnelUrl,
                        commandArguments = emptyList(),
                        environment = managedTransportEnvironment(config, methodName = RelayKindWebTunnel),
                        managedClientBridge =
                            ManagedClientSocksBridgeSpec(
                                methodName = RelayKindWebTunnel,
                                targetHost = targetHost,
                                targetPort = targetPort,
                                ptArguments =
                                    encodePtArguments(
                                        "url" to config.ptWebTunnelUrl,
                                        "utls" to "hellochrome_auto",
                                    ),
                            ),
                    )
                }

                RelayKindObfs4 -> {
                    val bridgeLine = parseObfs4BridgeLine(config.ptBridgeLine)
                    SubprocessSocksRelayLaunchSpec(
                        binaryName = Obfs4BinaryName,
                        runtimeKind = config.kind,
                        upstreamAddress = "${bridgeLine.host}:${bridgeLine.port}",
                        redactedValues = listOf(bridgeLine.cert),
                        commandArguments = emptyList(),
                        environment = managedTransportEnvironment(config, methodName = RelayKindObfs4),
                        managedClientBridge =
                            ManagedClientSocksBridgeSpec(
                                methodName = RelayKindObfs4,
                                targetHost = bridgeLine.host,
                                targetPort = bridgeLine.port,
                                ptArguments =
                                    encodePtArguments(
                                        "cert" to bridgeLine.cert,
                                        "iat-mode" to bridgeLine.iatMode.toString(),
                                    ),
                            ),
                    )
                }

                else -> {
                    error("Unsupported pluggable transport kind: ${config.kind}")
                }
            }

        private fun managedTransportEnvironment(
            config: ResolvedRipDpiRelayConfig,
            methodName: String,
        ): Map<String, String> {
            val stateDir =
                File(context.filesDir, "pluggable-transports/${sanitizeStateDirSegment(config.profileId)}-$methodName")
            stateDir.mkdirs()
            return mapOf(
                "TOR_PT_MANAGED_TRANSPORT_VER" to ManagedTransportVersion,
                "TOR_PT_STATE_LOCATION" to stateDir.absolutePath,
                "TOR_PT_CLIENT_TRANSPORTS" to methodName,
                "TOR_PT_EXIT_ON_STDIN_CLOSE" to "1",
            )
        }

        private fun sanitizeStateDirSegment(value: String): String = value.replace(Regex("[^a-zA-Z0-9._-]"), "_")
    }

private fun String.bridgeTransportName(): String? {
    val tokens = trim().split(Regex("\\s+")).filter(String::isNotBlank)
    return when {
        tokens.isEmpty() -> null
        tokens[0].equals("Bridge", ignoreCase = true) -> tokens.getOrNull(1)
        else -> tokens[0]
    }
}

class PluggableTransportRuntime
    @Inject
    constructor(
        private val manager: PluggableTransportManager,
    ) : RipDpiRelayRuntime {
        // Completed once the subprocess launch passes its readiness probe, or completed
        // exceptionally if the launch fails. `awaitReady()` blocks on this so the supervisor
        // only reports Connected after the local SOCKS listener answers a probe.
        @Volatile private var readySignal = CompletableDeferred<Unit>()

        override suspend fun start(config: ResolvedRipDpiRelayConfig): Int {
            val signal = CompletableDeferred<Unit>()
            readySignal = signal
            try {
                manager.start(config)
            } catch (
                @Suppress("TooGenericExceptionCaught") readinessError: Exception,
            ) {
                signal.completeExceptionally(readinessError)
                throw readinessError
            }
            signal.complete(Unit)
            return manager.waitForExit()
        }

        override suspend fun awaitReady(timeoutMillis: Long) {
            // Block until the subprocess launch passes its readiness probe (set in `start()`);
            // a readiness failure is rethrown here so the supervisor fails honestly.
            readySignal.await()
        }

        override suspend fun stop() {
            readySignal.cancel(CancellationException("Pluggable transport runtime stopped before readiness"))
            manager.stop()
        }

        override suspend fun pollTelemetry(): NativeRuntimeSnapshot = manager.pollTelemetry()
    }

interface PluggableTransportRuntimeFactory {
    fun create(): RipDpiRelayRuntime
}

@Singleton
class DefaultPluggableTransportRuntimeFactory
    @Inject
    constructor(
        private val runtimeProvider: Provider<PluggableTransportRuntime>,
    ) : PluggableTransportRuntimeFactory {
        override fun create(): RipDpiRelayRuntime = runtimeProvider.get()
    }

@Module
@InstallIn(SingletonComponent::class)
abstract class PluggableTransportRuntimeFactoryModule {
    @Binds
    @Singleton
    abstract fun bindPluggableTransportRuntimeFactory(
        factory: DefaultPluggableTransportRuntimeFactory,
    ): PluggableTransportRuntimeFactory
}
