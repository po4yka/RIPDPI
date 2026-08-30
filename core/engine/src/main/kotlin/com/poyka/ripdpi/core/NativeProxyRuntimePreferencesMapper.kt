package com.poyka.ripdpi.core

import com.poyka.ripdpi.data.ProxySettingsSection
import com.poyka.ripdpi.data.SecretString
import com.poyka.ripdpi.proto.AppSettings

internal fun buildListenConfig(proxy: ProxySettingsSection): RipDpiListenConfig {
    // Allow-LAN binds all interfaces (0.0.0.0). The native core rejects a
    // non-loopback listener without an auth token, so allow-LAN only takes
    // effect when a token is present; otherwise it degrades to loopback.
    val lanToken = proxy.lanAuthToken.ifEmpty { null }
    val allowLan = proxy.proxyAllowLan && lanToken != null
    return RipDpiListenConfig(
        ip = if (allowLan) "0.0.0.0" else proxy.proxyIp.ifEmpty { "127.0.0.1" },
        // The mixed inbound mirrors the reference implementation's mixedPort
        // default (2080) so it does not collide with the plain SOCKS default
        // (1080); an explicit user-set port always wins.
        port = proxy.proxyPort.takeIf { it > 0 } ?: if (proxy.mixedInboundEnabled) 2080 else 1080,
        maxConnections = proxy.maxConnections.takeIf { it > 0 } ?: 512,
        bufferSize = proxy.bufferSize.takeIf { it > 0 } ?: 16384,
        tcpFastOpen = proxy.tcpFastOpen,
        defaultTtl = if (proxy.customTtl) proxy.defaultTtl else 0,
        customTtl = proxy.customTtl,
        freezeDetectionEnabled = proxy.freezeDetectionEnabled,
        mixed = proxy.mixedInboundEnabled,
        authToken = if (allowLan) lanToken else null,
    )
}

internal fun buildProtocolConfig(settings: AppSettings): RipDpiProtocolConfig =
    RipDpiProtocolConfig(
        resolveDomains = !settings.noDomain,
        desyncHttp = settings.desyncHttp,
        desyncHttps = settings.desyncHttps,
        desyncUdp = settings.desyncUdp,
        // Unset (proto field absent) means enabled, preserving the historical always-on UDP ASSOCIATE.
        udpAssociateEnabled = !settings.hasUdpAssociateEnabled() || settings.udpAssociateEnabled,
    )

internal fun buildHostsConfig(settings: AppSettings): RipDpiHostsConfig =
    RipDpiHostsConfig(
        mode =
            settings.hostsMode
                .ifEmpty { RipDpiHostsConfig.Mode.Disable.wireName }
                .let(RipDpiHostsConfig.Mode::fromWireName),
        entries =
            when (settings.hostsMode) {
                "blacklist" -> settings.hostsBlacklist
                "whitelist" -> settings.hostsWhitelist
                else -> null
            },
    )

internal fun buildHostAutolearnConfig(
    settings: AppSettings,
    hostAutolearnStorePath: String?,
    networkScopeKey: String?,
): RipDpiHostAutolearnConfig =
    RipDpiHostAutolearnConfig(
        enabled = settings.hostAutolearnEnabled,
        penaltyTtlHours = settings.hostAutolearnPenaltyTtlHours,
        maxHosts = settings.hostAutolearnMaxHosts,
        storePath = hostAutolearnStorePath,
        networkScopeKey = networkScopeKey,
    )

internal fun buildWsTunnelConfig(
    settings: AppSettings,
    workerBearer: String? = null,
): RipDpiWsTunnelConfig {
    val mode =
        settings.wsTunnelMode.ifEmpty {
            if (settings.wsTunnelEnabled) "always" else "off"
        }
    val workerUrl = settings.wsTunnelWorkerUrl.trim().takeIf { it.isNotEmpty() }
    val workerCredentialRef = settings.wsTunnelWorkerCredentialRef.trim().takeIf { it.isNotEmpty() }
    require((workerUrl == null) == (workerCredentialRef == null)) {
        "Cloudflare Worker URL and credential reference must be configured together"
    }
    if (workerUrl != null) {
        require(!workerBearer.isNullOrBlank()) {
            "Cloudflare Worker bearer is unavailable for the configured credential reference"
        }
        require(settings.wsTunnelFakeSni.isBlank()) {
            "Cloudflare Worker transport cannot be combined with WS tunnel fake SNI"
        }
    }
    return RipDpiWsTunnelConfig(
        enabled = mode != "off",
        mode = mode,
        fakeSni = settings.wsTunnelFakeSni.ifEmpty { null },
        allowInsecureSni = settings.wsTunnelAllowInsecureSni,
        cloudflareWorkerUrl = workerUrl,
        cloudflareWorkerCredentialRef = workerCredentialRef,
        cloudflareWorkerBearer = workerBearer?.let(::SecretString),
    )
}
