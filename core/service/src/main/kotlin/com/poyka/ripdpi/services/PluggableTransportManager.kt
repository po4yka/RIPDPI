package com.poyka.ripdpi.services

import android.content.Context
import com.poyka.ripdpi.core.ResolvedRipDpiRelayConfig
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
import java.io.File
import java.net.URI
import javax.inject.Inject
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
class PluggableTransportManager
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val subprocessManager: SubprocessSocksRelayManager,
    ) {
        suspend fun start(config: ResolvedRipDpiRelayConfig) {
            subprocessManager.start(
                config = config,
                spec = launchSpec(config),
            )
        }

        suspend fun waitForExit(): Int = subprocessManager.waitForExit()

        suspend fun pollTelemetry(): NativeRuntimeSnapshot = subprocessManager.pollTelemetry()

        suspend fun stop() = subprocessManager.stop()

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

class PluggableTransportRuntime
    @Inject
    constructor(
        private val manager: PluggableTransportManager,
    ) : RipDpiRelayRuntime {
        override suspend fun start(config: ResolvedRipDpiRelayConfig): Int {
            manager.start(config)
            return manager.waitForExit()
        }

        override suspend fun awaitReady(timeoutMillis: Long) {
            // `start()` blocks until the local SOCKS listener answers a probe.
        }

        override suspend fun stop() {
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
        private val runtime: PluggableTransportRuntime,
    ) : PluggableTransportRuntimeFactory {
        override fun create(): RipDpiRelayRuntime = runtime
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
