package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.OwnedRelayQuicMigrationConfig
import com.poyka.ripdpi.core.ResolvedRipDpiRelayConfig
import com.poyka.ripdpi.core.RipDpiRelayConfig
import com.poyka.ripdpi.data.AndroidLocalNetworkAccess
import com.poyka.ripdpi.data.RelayKindChainRelay
import com.poyka.ripdpi.data.RelayKindMasque
import com.poyka.ripdpi.data.RelayKindObfs4
import com.poyka.ripdpi.data.RelayKindSnowflake
import com.poyka.ripdpi.data.RelayKindTor
import com.poyka.ripdpi.data.RelayKindWebTunnel
import javax.inject.Inject
import javax.inject.Singleton

private const val AppsScriptHttpsPort = 443

/** Validate the resolved profile, including racing candidates, before starting any relay runtime. */
@Singleton
internal class PermissionCheckedRelayConfigResolver
    @Inject
    constructor(
        private val delegate: DefaultUpstreamRelayRuntimeConfigResolver,
        private val access: AndroidLocalNetworkAccess,
    ) : UpstreamRelayRuntimeConfigResolver,
        LocalNetworkAwareRelayRuntimeConfigResolver {
        override suspend fun resolve(
            config: RipDpiRelayConfig,
            quicMigrationConfig: OwnedRelayQuicMigrationConfig,
        ): ResolvedRipDpiRelayConfig = resolveWithLocalNetworkDependency(config, quicMigrationConfig).config

        override suspend fun resolveWithLocalNetworkDependency(
            config: RipDpiRelayConfig,
            quicMigrationConfig: OwnedRelayQuicMigrationConfig,
        ): LocalNetworkAwareRelayConfigResolution {
            val resolved = delegate.resolve(config, quicMigrationConfig)
            return LocalNetworkAwareRelayConfigResolution(
                config = resolved,
                localNetworkDependent = access.requireRelayEndpoints(resolved),
            )
        }
    }

internal suspend fun AndroidLocalNetworkAccess.requireRelayEndpoints(config: ResolvedRipDpiRelayConfig): Boolean {
    val listenerRequired = requireListener(config.localSocksHost)
    val upstreamRequired =
        when (config.kind) {
            RelayKindChainRelay -> requireDirectEndpoint(config.chainEntryServer, config.chainEntryPort)
            RelayKindGoogleAppsScript -> requireDirectEndpoint(config.appsScriptGoogleIp, AppsScriptHttpsPort)
            RelayKindMasque -> requireMasqueEndpoints(config)
            RelayKindObfs4 -> requireBridgeEndpoint(config.ptBridgeLine)
            RelayKindSnowflake -> requireUrl(config.ptSnowflakeBrokerUrl)
            RelayKindTor -> requireTorBridgeEndpoint(config.ptBridgeLine)
            RelayKindWebTunnel -> requireUrl(config.ptWebTunnelUrl)
            else -> requireDirectEndpoint(config.server, config.serverPort)
        }
    return listenerRequired || upstreamRequired
}

private suspend fun AndroidLocalNetworkAccess.requireMasqueEndpoints(config: ResolvedRipDpiRelayConfig): Boolean {
    val serverRequired = requireUrl(config.masqueUrl)
    val privacyPassRequired =
        config.masquePrivacyPassProviderUrl
            ?.takeIf(String::isNotBlank)
            ?.let { requireUrl(it) }
            ?: false
    return serverRequired || privacyPassRequired
}

private suspend fun AndroidLocalNetworkAccess.requireBridgeEndpoint(bridgeLine: String): Boolean {
    val bridge = parseObfs4BridgeLine(bridgeLine)
    return requireDirectEndpoint(bridge.host, bridge.port)
}

private suspend fun AndroidLocalNetworkAccess.requireTorBridgeEndpoint(bridgeLine: String): Boolean =
    when (torBridgeTransport(bridgeLine)) {
        RelayKindObfs4 -> requireBridgeEndpoint(bridgeLine)
        RelayKindWebTunnel -> requireUrl(torBridgeOption(bridgeLine, "url").orEmpty())
        else -> false
    }

private fun torBridgeTransport(bridgeLine: String): String? {
    val tokens = bridgeLine.trim().split(Regex("\\s+")).filter(String::isNotBlank)
    val transportIndex = if (tokens.firstOrNull().equals("Bridge", ignoreCase = true)) 1 else 0
    return tokens.getOrNull(transportIndex)
}

private fun torBridgeOption(
    bridgeLine: String,
    name: String,
): String? =
    bridgeLine
        .trim()
        .split(Regex("\\s+"))
        .firstOrNull { it.startsWith("$name=") }
        ?.substringAfter('=')
