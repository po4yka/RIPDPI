package com.poyka.ripdpi.core

import com.poyka.ripdpi.data.awg.AwgActivationRequest
import com.poyka.ripdpi.utility.shellSplit

sealed interface RipDpiProxyPreferences {
    fun toNativeConfigJson(): String

    val localAuthToken: String? get() = null
}

data class OwnedRelayQuicMigrationConfig(
    val bindLowPort: Boolean = false,
    val migrateAfterHandshake: Boolean = false,
)

fun stripRipDpiRuntimeContext(configJson: String): String = RipDpiProxyJsonCodec.stripRuntimeContext(configJson)

fun decodeRipDpiProxyUiPreferences(configJson: String): RipDpiProxyUIPreferences? =
    RipDpiProxyJsonCodec.decodeUiPreferences(configJson)

fun RipDpiProxyPreferences.warpConfigOrNull(): RipDpiWarpConfig? =
    when (this) {
        is RipDpiProxyUIPreferences -> {
            warp.takeIf { it.enabled }
        }

        is RipDpiProxyJsonPreferences -> {
            decodeRipDpiProxyUiPreferences(toNativeConfigJson())?.warp?.takeIf { it.enabled }
        }

        is RipDpiProxyCmdPreferences -> {
            null
        }
    }

/**
 * Returns the AmneziaWG activation request when AWG is the configured VPN-mode egress,
 * or null otherwise. A non-null value means AWG is active; null means it is not configured.
 * AWG and WARP are mutually exclusive WireGuard egress transports: when AWG is present
 * it takes precedence and WARP must not be started simultaneously. Mirrors [warpConfigOrNull].
 */
fun RipDpiProxyPreferences.awgConfigOrNull(): AwgActivationRequest? =
    when (this) {
        is RipDpiProxyUIPreferences -> {
            awg
        }

        is RipDpiProxyJsonPreferences -> {
            decodeRipDpiProxyUiPreferences(toNativeConfigJson())?.awg
        }

        is RipDpiProxyCmdPreferences -> {
            null
        }
    }

fun RipDpiProxyPreferences.relayConfigOrNull(): RipDpiRelayConfig? =
    when (this) {
        is RipDpiProxyUIPreferences -> {
            relay.takeIf { it.enabled }
        }

        is RipDpiProxyJsonPreferences -> {
            decodeRipDpiProxyUiPreferences(toNativeConfigJson())?.relay?.takeIf { it.enabled }
        }

        is RipDpiProxyCmdPreferences -> {
            null
        }
    }

fun RipDpiProxyPreferences.ownedRelayQuicMigrationConfig(): OwnedRelayQuicMigrationConfig =
    when (this) {
        is RipDpiProxyUIPreferences -> {
            OwnedRelayQuicMigrationConfig(
                bindLowPort = fakePackets.quicBindLowPort,
                migrateAfterHandshake = fakePackets.quicMigrateAfterHandshake,
            )
        }

        is RipDpiProxyJsonPreferences -> {
            decodeRipDpiProxyUiPreferences(toNativeConfigJson())
                ?.let { preferences ->
                    OwnedRelayQuicMigrationConfig(
                        bindLowPort = preferences.fakePackets.quicBindLowPort,
                        migrateAfterHandshake = preferences.fakePackets.quicMigrateAfterHandshake,
                    )
                }
                ?: OwnedRelayQuicMigrationConfig()
        }

        is RipDpiProxyCmdPreferences -> {
            OwnedRelayQuicMigrationConfig()
        }
    }

/**
 * Returns a copy of these preferences with the WARP section configured to point the
 * proxy upstream at [port] on loopback (`enabled = true`). When AmneziaWG is the active
 * VPN-mode egress, the proxy core's existing WARP-upstream mechanism is reused to dial
 * the AWG loopback SOCKS inbound, so no new native config section is required. Non-UI
 * preferences are returned unchanged.
 */
fun RipDpiProxyPreferences.withAwgEgressPort(port: Int): RipDpiProxyPreferences =
    when (this) {
        is RipDpiProxyUIPreferences -> {
            RipDpiProxyUIPreferences(
                listen = listen,
                protocols = protocols,
                chains = chains,
                fakePackets = fakePackets,
                parserEvasions = parserEvasions,
                adaptiveFallback = adaptiveFallback,
                quic = quic,
                hosts = hosts,
                relay = relay,
                warp =
                    RipDpiWarpConfig(
                        enabled = true,
                        localSocksHost = "127.0.0.1",
                        localSocksPort = port,
                    ),
                hostAutolearn = hostAutolearn,
                wsTunnel = wsTunnel,
                nativeLogLevel = nativeLogLevel,
                runtimeContext = runtimeContext,
                logContext = logContext,
                rootMode = rootMode,
                rootHelperSocketPath = rootHelperSocketPath,
                geoipDbPath = geoipDbPath,
                geositeDbPath = geositeDbPath,
                environmentKind = environmentKind,
                awg = awg,
            )
        }

        is RipDpiProxyJsonPreferences,
        is RipDpiProxyCmdPreferences,
        -> {
            this
        }
    }

class RipDpiProxyJsonPreferences(
    private val configJson: String,
    private val hostAutolearnStorePath: String? = null,
    private val networkScopeKey: String? = null,
    private val runtimeContext: RipDpiRuntimeContext? = null,
    private val logContext: RipDpiLogContext? = null,
    private val rootMode: Boolean = false,
    private val rootHelperSocketPath: String? = null,
    private val geoipDbPath: String? = null,
    private val geositeDbPath: String? = null,
    private val localListenPortOverride: Int? = null,
    override val localAuthToken: String? = null,
    private val environmentKind: com.poyka.ripdpi.data.EnvironmentKind = com.poyka.ripdpi.data.EnvironmentKind.Unknown,
) : RipDpiProxyPreferences {
    override fun toNativeConfigJson(): String =
        RipDpiProxyJsonCodec.rewriteJson(
            configJson = configJson,
            hostAutolearnStorePath = hostAutolearnStorePath,
            networkScopeKey = networkScopeKey,
            runtimeContext = runtimeContext,
            logContext = logContext,
            rootMode = rootMode,
            rootHelperSocketPath = rootHelperSocketPath,
            geoipDbPath = geoipDbPath,
            geositeDbPath = geositeDbPath,
            localListenPortOverride = localListenPortOverride,
            localAuthToken = localAuthToken,
            environmentKind = environmentKind,
        )
}

class RipDpiProxyCmdPreferences(
    val args: Array<String>,
    private val hostAutolearnStorePath: String? = null,
    val runtimeContext: RipDpiRuntimeContext? = null,
    val logContext: RipDpiLogContext? = null,
) : RipDpiProxyPreferences {
    constructor(cmd: String) : this(cmdToArgs(cmd))

    constructor(
        cmd: String,
        hostAutolearnStorePath: String?,
        runtimeContext: RipDpiRuntimeContext?,
        logContext: RipDpiLogContext? = null,
    ) : this(cmdToArgs(cmd), hostAutolearnStorePath, runtimeContext, logContext)

    companion object {
        private fun cmdToArgs(cmd: String): Array<String> {
            val firstArgIndex = cmd.indexOf("-")
            val argsStr = (if (firstArgIndex > 0) cmd.substring(firstArgIndex) else cmd).trim()
            return arrayOf("ripdpi") + shellSplit(argsStr)
        }
    }

    override fun toNativeConfigJson(): String =
        RipDpiProxyJsonCodec.encodeCommandLinePreferences(
            args = args.toList(),
            hostAutolearnStorePath = hostAutolearnStorePath,
            runtimeContext = runtimeContext,
            logContext = logContext,
        )
}
