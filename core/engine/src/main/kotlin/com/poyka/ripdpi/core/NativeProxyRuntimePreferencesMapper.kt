package com.poyka.ripdpi.core

import com.poyka.ripdpi.proto.AppSettings

internal fun buildListenConfig(settings: AppSettings): RipDpiListenConfig =
    RipDpiListenConfig(
        ip = settings.proxyIp.ifEmpty { "127.0.0.1" },
        port = settings.proxyPort.takeIf { it > 0 } ?: 1080,
        maxConnections = settings.maxConnections.takeIf { it > 0 } ?: 512,
        bufferSize = settings.bufferSize.takeIf { it > 0 } ?: 16384,
        tcpFastOpen = settings.tcpFastOpen,
        defaultTtl = if (settings.customTtl) settings.defaultTtl else 0,
        customTtl = settings.customTtl,
        freezeDetectionEnabled = settings.freezeDetectionEnabled,
    )

internal fun buildProtocolConfig(settings: AppSettings): RipDpiProtocolConfig =
    RipDpiProtocolConfig(
        resolveDomains = !settings.noDomain,
        desyncHttp = settings.desyncHttp,
        desyncHttps = settings.desyncHttps,
        desyncUdp = settings.desyncUdp,
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

internal fun buildWsTunnelConfig(settings: AppSettings): RipDpiWsTunnelConfig {
    val mode =
        settings.wsTunnelMode.ifEmpty {
            if (settings.wsTunnelEnabled) "always" else "off"
        }
    return RipDpiWsTunnelConfig(
        enabled = mode != "off",
        mode = mode,
    )
}
