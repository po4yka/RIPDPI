package com.poyka.ripdpi.core

import com.poyka.ripdpi.core.routing.DestinationRoutingPolicy
import com.poyka.ripdpi.data.WarpRouteModeRules
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

        is RipDpiProxyUiSessionPreferences -> {
            preferences.warp.takeIf { it.enabled }
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

        is RipDpiProxyUiSessionPreferences -> {
            preferences.awg
        }

        is RipDpiProxyJsonPreferences -> {
            awg ?: decodeRipDpiProxyUiPreferences(toNativeConfigJson())?.awg
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

        is RipDpiProxyUiSessionPreferences -> {
            preferences.relay.takeIf { it.enabled }
        }

        is RipDpiProxyJsonPreferences -> {
            decodeRipDpiProxyUiPreferences(toNativeConfigJson())?.relay?.takeIf { it.enabled }
        }

        is RipDpiProxyCmdPreferences -> {
            null
        }
    }

/** Returns the effective SOCKS5 UDP ASSOCIATE switch, preserving the default-on wire semantics. */
fun RipDpiProxyPreferences.isUdpAssociateEnabled(): Boolean =
    when (this) {
        is RipDpiProxyUIPreferences -> {
            protocols.udpAssociateEnabled
        }

        is RipDpiProxyUiSessionPreferences -> {
            preferences.protocols.udpAssociateEnabled
        }

        is RipDpiProxyJsonPreferences -> {
            decodeRipDpiProxyUiPreferences(toNativeConfigJson())?.protocols?.udpAssociateEnabled ?: true
        }

        is RipDpiProxyCmdPreferences -> {
            true
        }
    }

/** Applies a session-local SOCKS5 UDP ASSOCIATE override without mutating stored settings. */
fun RipDpiProxyPreferences.withUdpAssociateEnabled(enabled: Boolean): RipDpiProxyPreferences =
    when (this) {
        is RipDpiProxyUIPreferences -> {
            withProtocolConfig(protocols.copy(udpAssociateEnabled = enabled))
        }

        is RipDpiProxyUiSessionPreferences -> {
            val protocols = preferences.protocols.copy(udpAssociateEnabled = enabled)
            copy(preferences = preferences.withProtocolConfig(protocols))
        }

        is RipDpiProxyJsonPreferences -> {
            withUdpAssociateEnabled(enabled)
        }

        is RipDpiProxyCmdPreferences -> {
            require(enabled) { "Command-line proxy preferences do not support a session UDP override" }
            this
        }
    }

fun RipDpiProxyPreferences.withRelayRuntimeSelection(
    selectedConfig: RipDpiRelayConfig,
    localSocksHost: String,
    localSocksPort: Int,
): RipDpiProxyPreferences {
    val relay =
        selectedConfig.copy(
            enabled = true,
            localSocksHost = localSocksHost,
            localSocksPort = localSocksPort,
        )
    return when (this) {
        is RipDpiProxyUIPreferences -> withRelayConfig(relay)
        is RipDpiProxyUiSessionPreferences -> copy(preferences = preferences.withRelayConfig(relay))
        is RipDpiProxyJsonPreferences -> withRelayRuntimeSelection(relay)
        is RipDpiProxyCmdPreferences -> this
    }
}

private fun RipDpiProxyUIPreferences.withRelayConfig(relayConfig: RipDpiRelayConfig): RipDpiProxyUIPreferences =
    withRuntimeConfig(protocols, relayConfig)

private fun RipDpiProxyUIPreferences.withProtocolConfig(
    protocolConfig: RipDpiProtocolConfig,
): RipDpiProxyUIPreferences = withRuntimeConfig(protocolConfig, relay)

private fun RipDpiProxyUIPreferences.withRuntimeConfig(
    protocolConfig: RipDpiProtocolConfig,
    relayConfig: RipDpiRelayConfig,
): RipDpiProxyUIPreferences =
    RipDpiProxyUIPreferences(
        listen = listen,
        protocols = protocolConfig,
        chains = chains,
        fakePackets = fakePackets,
        parserEvasions = parserEvasions,
        adaptiveFallback = adaptiveFallback,
        quic = quic,
        hosts = hosts,
        relay = relayConfig,
        warp = warp,
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
        destinationRouting = destinationRouting,
        awg = awg,
    )

fun RipDpiProxyPreferences.ownedRelayQuicMigrationConfig(): OwnedRelayQuicMigrationConfig =
    when (this) {
        is RipDpiProxyUIPreferences -> {
            OwnedRelayQuicMigrationConfig(
                bindLowPort = fakePackets.quicBindLowPort,
                migrateAfterHandshake = fakePackets.quicMigrateAfterHandshake,
            )
        }

        is RipDpiProxyUiSessionPreferences -> {
            OwnedRelayQuicMigrationConfig(
                bindLowPort = preferences.fakePackets.quicBindLowPort,
                migrateAfterHandshake = preferences.fakePackets.quicMigrateAfterHandshake,
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
                relay = RipDpiRelayConfig(),
                warp =
                    RipDpiWarpConfig(
                        enabled = true,
                        routeMode = WarpRouteModeRules,
                        routeHosts = AmneziaWgAllTrafficRouteHosts,
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
                destinationRouting = destinationRouting,
                awg = null,
            )
        }

        is RipDpiProxyUiSessionPreferences -> {
            copy(preferences = preferences.withAwgEgressPort(port) as RipDpiProxyUIPreferences)
        }

        is RipDpiProxyJsonPreferences -> {
            withAwgEgressPort(port)
        }

        is RipDpiProxyCmdPreferences,
        -> {
            this
        }
    }

fun RipDpiProxyPreferences.withLocalProxySessionOverrides(
    listenPortOverride: Int? = null,
    authToken: String? = null,
): RipDpiProxyPreferences =
    when (this) {
        is RipDpiProxyUIPreferences -> {
            RipDpiProxyUiSessionPreferences(
                preferences = this,
                localListenPortOverride = listenPortOverride,
                localAuthToken = authToken,
            )
        }

        is RipDpiProxyUiSessionPreferences -> {
            copy(
                localListenPortOverride = listenPortOverride ?: localListenPortOverride,
                localAuthToken = authToken ?: localAuthToken,
            )
        }

        is RipDpiProxyJsonPreferences -> {
            RipDpiProxyJsonPreferences(
                configJson = toNativeConfigJson(),
                localListenPortOverride = listenPortOverride,
                localAuthToken = authToken,
                awg = awg,
            )
        }

        is RipDpiProxyCmdPreferences -> {
            RipDpiProxyJsonPreferences(
                configJson = toNativeConfigJson(),
                localListenPortOverride = listenPortOverride,
                localAuthToken = authToken,
            )
        }
    }

fun RipDpiProxyPreferences.withProxyLogContext(logContext: RipDpiLogContext?): RipDpiProxyPreferences =
    if (logContext == null) {
        this
    } else {
        when (this) {
            is RipDpiProxyUIPreferences -> {
                withSessionOverrides(logContext = logContext)
            }

            is RipDpiProxyUiSessionPreferences -> {
                copy(preferences = preferences.withSessionOverrides(logContext = logContext))
            }

            is RipDpiProxyJsonPreferences -> {
                RipDpiProxyJsonPreferences(
                    configJson = toNativeConfigJson(),
                    logContext = logContext,
                    awg = awg,
                )
            }

            is RipDpiProxyCmdPreferences -> {
                RipDpiProxyJsonPreferences(
                    configJson = toNativeConfigJson(),
                    logContext = logContext,
                )
            }
        }
    }

internal const val AmneziaWgAllTrafficRouteHosts = "__ripdpi_awg_all_traffic__"

private data class RipDpiProxyUiSessionPreferences(
    val preferences: RipDpiProxyUIPreferences,
    val localListenPortOverride: Int? = null,
    override val localAuthToken: String? = null,
) : RipDpiProxyPreferences {
    override fun toNativeConfigJson(): String =
        RipDpiProxyJsonCodec.encodeUiPreferences(
            NativeProxyCreateRequest(
                preferences = preferences,
                rootMode = preferences.rootMode,
                rootHelperSocketPath = preferences.rootHelperSocketPath,
                geoipDbPath = preferences.geoipDbPath,
                geositeDbPath = preferences.geositeDbPath,
                localListenPortOverride = localListenPortOverride,
                localAuthToken = localAuthToken,
                environmentKind = preferences.environmentKind,
            ),
        )
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
    private val relayRuntimeSelection: RipDpiRelayConfig? = null,
    internal val awg: AwgActivationRequest? = null,
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
            relayRuntimeSelection = relayRuntimeSelection,
        )

    internal fun withRelayRuntimeSelection(relay: RipDpiRelayConfig): RipDpiProxyPreferences {
        if (decodeRipDpiProxyUiPreferences(configJson) == null) {
            return this
        }
        return RipDpiProxyJsonPreferences(
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
            relayRuntimeSelection = relay,
            awg = awg,
        )
    }

    internal fun withUdpAssociateEnabled(enabled: Boolean): RipDpiProxyPreferences =
        RipDpiProxyJsonPreferences(
            configJson = RipDpiProxyJsonCodec.rewriteUdpAssociateEnabled(configJson, enabled),
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
            relayRuntimeSelection = relayRuntimeSelection,
            awg = awg,
        )

    internal fun withAwgEgressPort(port: Int): RipDpiProxyPreferences {
        val routed =
            decodeRipDpiProxyUiPreferences(toNativeConfigJson())
                ?.withAwgEgressPort(port)
                ?: return this
        val withSessionOverrides =
            if (localListenPortOverride != null || localAuthToken != null) {
                routed.withLocalProxySessionOverrides(
                    listenPortOverride = localListenPortOverride,
                    authToken = localAuthToken,
                )
            } else {
                routed
            }
        return withSessionOverrides.withProxyLogContext(logContext)
    }
}

class RipDpiProxyCmdPreferences(
    val args: Array<String>,
    private val hostAutolearnStorePath: String? = null,
    val destinationRouting: DestinationRoutingPolicy = DestinationRoutingPolicy(canonicalDigest = ""),
    val geoipDbPath: String? = null,
    val geositeDbPath: String? = null,
    val runtimeContext: RipDpiRuntimeContext? = null,
    val logContext: RipDpiLogContext? = null,
) : RipDpiProxyPreferences {
    constructor(cmd: String) : this(cmdToArgs(cmd))

    constructor(
        cmd: String,
        hostAutolearnStorePath: String?,
        runtimeContext: RipDpiRuntimeContext?,
        logContext: RipDpiLogContext? = null,
        destinationRouting: DestinationRoutingPolicy = DestinationRoutingPolicy(canonicalDigest = ""),
        geoipDbPath: String? = null,
        geositeDbPath: String? = null,
    ) : this(
        args = cmdToArgs(cmd),
        hostAutolearnStorePath = hostAutolearnStorePath,
        destinationRouting = destinationRouting,
        geoipDbPath = geoipDbPath,
        geositeDbPath = geositeDbPath,
        runtimeContext = runtimeContext,
        logContext = logContext,
    )

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
            destinationRouting = destinationRouting,
            geoipDbPath = geoipDbPath,
            geositeDbPath = geositeDbPath,
            runtimeContext = runtimeContext,
            logContext = logContext,
        )
}
