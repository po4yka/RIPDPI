package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.ActiveDnsSettings
import com.poyka.ripdpi.data.activeDnsSettings
import com.poyka.ripdpi.proto.AppSettings

internal data class NetworkFingerprint(
    val transportLabel: String,
    val interfaceName: String?,
    val dnsServers: List<String>,
)

internal data class EffectiveDnsResolution(
    val activeDns: ActiveDnsSettings,
    val override: TemporaryResolverOverride?,
    val shouldClearOverride: Boolean,
)

internal data class ResolverRefreshPlan(
    val resolution: EffectiveDnsResolution,
    val signature: String,
    val requiresTunnelRebuild: Boolean,
)

internal fun resolveEffectiveDns(
    settings: AppSettings,
    override: TemporaryResolverOverride?,
): EffectiveDnsResolution {
    val persistedDns = settings.activeDnsSettings()
    if (override == null) {
        return EffectiveDnsResolution(
            activeDns = persistedDns,
            override = null,
            shouldClearOverride = false,
        )
    }
    return if (override.matches(persistedDns)) {
        EffectiveDnsResolution(
            activeDns = persistedDns,
            override = null,
            shouldClearOverride = true,
        )
    } else {
        EffectiveDnsResolution(
            activeDns = override.toActiveDnsSettings(),
            override = override,
            shouldClearOverride = false,
        )
    }
}

internal fun planResolverRefresh(
    settings: AppSettings,
    override: TemporaryResolverOverride?,
    currentSignature: String?,
    tunnelRunning: Boolean,
): ResolverRefreshPlan {
    val resolution = resolveEffectiveDns(settings, override)
    val signature = dnsSignature(resolution.activeDns, resolution.override?.reason)
    return ResolverRefreshPlan(
        resolution = resolution,
        signature = signature,
        requiresTunnelRebuild = tunnelRunning && currentSignature != signature,
    )
}

internal fun dnsSignature(
    activeDns: ActiveDnsSettings,
    overrideReason: String?,
): String =
    listOf(
        activeDns.mode,
        activeDns.providerId,
        activeDns.dnsIp,
        activeDns.encryptedDnsProtocol,
        activeDns.encryptedDnsHost,
        activeDns.encryptedDnsPort.toString(),
        activeDns.encryptedDnsTlsServerName,
        activeDns.encryptedDnsBootstrapIps.joinToString(","),
        activeDns.encryptedDnsDohUrl,
        activeDns.encryptedDnsDnscryptProviderName,
        activeDns.encryptedDnsDnscryptPublicKey,
        overrideReason.orEmpty(),
    ).joinToString("|")

internal fun classifyNetworkHandover(
    previous: NetworkFingerprint?,
    current: NetworkFingerprint?,
): String? =
    when {
        previous == null || previous == current -> null
        current == null -> "connectivity_loss"
        previous.transportLabel != current.transportLabel -> "transport_switch"
        previous != current -> "link_refresh"
        else -> null
    }
