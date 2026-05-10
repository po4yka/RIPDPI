package com.poyka.ripdpi.ui.screens.detection

import com.poyka.ripdpi.data.diagnostics.DetectionResolverConfig
import com.poyka.ripdpi.data.diagnostics.DetectionResolverMode
import com.poyka.ripdpi.data.diagnostics.DnsPreset
import com.poyka.ripdpi.proto.AppSettings
import java.net.URI

internal fun AppSettings.toDetectionResolverConfig(): DetectionResolverConfig {
    val selectedPreset = DetectionDnsPreset.fromWire(detectionCheckDnsPreset)
    val configuredServers = detectionCheckDnsDirectServers.toDnsServerList()
    val dnsPreset = selectedPreset.toDataDnsPreset(configuredServers, detectionCheckDnsDohUrl)
    val fallbackServers = configuredServers.ifEmpty { dnsPreset.servers }

    return when (DetectionDnsResolverMode.fromWire(detectionCheckDnsResolverMode)) {
        DetectionDnsResolverMode.SYSTEM -> {
            DetectionResolverConfig()
        }

        DetectionDnsResolverMode.DIRECT -> {
            DetectionResolverConfig(
                resolverMode = DetectionResolverMode.DIRECT,
                directDnsServers = fallbackServers,
            )
        }

        DetectionDnsResolverMode.DOH -> {
            DetectionResolverConfig(
                resolverMode = DetectionResolverMode.DOH,
                directDnsServers = fallbackServers,
                dohPreset = dnsPreset,
                dohBootstrapIps = fallbackServers,
            )
        }
    }
}

private fun DetectionDnsPreset.toDataDnsPreset(
    configuredServers: List<String>,
    configuredDohUrl: String,
): DnsPreset {
    val preset = DnsPreset.byName(wireValue)
    if (this != DetectionDnsPreset.CUSTOM && preset != null) return preset

    val fallback = preset ?: DnsPreset.CLOUDFLARE
    val dohUrl = configuredDohUrl.ifBlank { fallback.dohUrl }
    return DnsPreset(
        id = wireValue,
        servers = configuredServers.ifEmpty { fallback.servers },
        dohUrl = dohUrl,
        dohHost = dohUrl.toHostOrDefault(fallback.dohHost),
    )
}

private fun String.toDnsServerList(): List<String> =
    split(DNS_SERVER_DELIMITER)
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinct()

private fun String.toHostOrDefault(defaultHost: String): String =
    runCatching { URI(this).host }
        .getOrNull()
        ?.takeIf(String::isNotBlank)
        ?: defaultHost

private val DNS_SERVER_DELIMITER = Regex("""[\s,;]+""")
