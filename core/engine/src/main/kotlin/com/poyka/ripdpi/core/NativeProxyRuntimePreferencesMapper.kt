package com.poyka.ripdpi.core

import com.poyka.ripdpi.data.ProxySettingsSection
import com.poyka.ripdpi.proto.AppSettings

internal fun buildListenConfig(proxy: ProxySettingsSection): RipDpiListenConfig =
    RipDpiListenConfig(
        ip = proxy.proxyIp.ifEmpty { "127.0.0.1" },
        port = proxy.proxyPort.takeIf { it > 0 } ?: 1080,
        maxConnections = proxy.maxConnections.takeIf { it > 0 } ?: 512,
        bufferSize = proxy.bufferSize.takeIf { it > 0 } ?: 16384,
        tcpFastOpen = proxy.tcpFastOpen,
        defaultTtl = if (proxy.customTtl) proxy.defaultTtl else 0,
        customTtl = proxy.customTtl,
        freezeDetectionEnabled = proxy.freezeDetectionEnabled,
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
