package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.RipDpiProxyCmdPreferences
import com.poyka.ripdpi.core.RipDpiProxyPreferences
import com.poyka.ripdpi.core.RipDpiWarpConfig
import com.poyka.ripdpi.core.awgConfigOrNull
import com.poyka.ripdpi.core.decodeRipDpiProxyUiPreferences
import com.poyka.ripdpi.core.relayConfigOrNull
import com.poyka.ripdpi.core.warpConfigOrNull
import com.poyka.ripdpi.data.ActiveDnsSettings
import com.poyka.ripdpi.data.AndroidLocalNetworkAccess
import com.poyka.ripdpi.data.WarpEndpointSelectionManual
import java.net.URI

private const val SystemDnsPort = 53
private const val HttpPort = 80
private const val HttpsPort = 443

internal suspend fun AndroidLocalNetworkAccess.requireConnection(policy: ConnectionPolicyResolution): Boolean {
    val preferences = policy.proxyPreferences
    val ui = decodeRipDpiProxyUiPreferences(preferences.toNativeConfigJson())
    val listenerRequired = requireProxyListener(preferences, ui?.listen?.ip)
    val awg = preferences.awgConfigOrNull()
    val awgRequired = awg?.let { requireDirectEndpoint(it.endpointHost, it.endpointPort) } ?: false
    val warp = preferences.warpConfigOrNull()?.takeIf { awg == null }
    val warpRequired = warp?.let { requireWarpEndpoints(it) } ?: false
    // DNS inside a remote egress is not a direct Android LAN socket.
    val remoteEgress = awg != null || warp != null || preferences.relayConfigOrNull() != null
    val dnsRequired =
        if (!policy.activeDns.routeThroughProxy || !remoteEgress) {
            requireDns(policy.activeDns)
        } else {
            false
        }
    return listenerRequired || awgRequired || warpRequired || dnsRequired
}

private suspend fun AndroidLocalNetworkAccess.requireProxyListener(
    preferences: RipDpiProxyPreferences,
    uiListener: String?,
): Boolean =
    if (preferences is RipDpiProxyCmdPreferences) {
        val args = preferences.args
        var listener = "127.0.0.1"
        args.forEachIndexed { index, arg ->
            when {
                arg == "--ip" || arg == "-i" -> listener = args.getOrNull(index + 1) ?: listener
                arg.startsWith("--ip=") -> listener = arg.substringAfter('=')
                arg.startsWith("-i") && !arg.startsWith("--") && arg.length > 2 -> listener = arg.substring(2)
            }
        }
        requireListener(listener)
    } else {
        uiListener?.let { requireListener(it) } ?: false
    }

private suspend fun AndroidLocalNetworkAccess.requireWarpEndpoints(config: RipDpiWarpConfig): Boolean {
    val listenerRequired = requireListener(config.localSocksHost)
    var endpointRequired = false
    if (config.endpointSelectionMode == WarpEndpointSelectionManual) {
        val endpoint = config.manualEndpoint
        listOf(endpoint.host, endpoint.ipv4, endpoint.ipv6).filter(String::isNotBlank).forEach { host ->
            endpointRequired = requireDirectEndpoint(host, endpoint.port) || endpointRequired
        }
    }
    return listenerRequired || endpointRequired
}

private suspend fun AndroidLocalNetworkAccess.requireDns(dns: ActiveDnsSettings): Boolean =
    if (dns.isPlainUdp) {
        requireDirectEndpoint(dns.dnsIp, SystemDnsPort)
    } else if (dns.isEncrypted) {
        if (dns.isOdoh) {
            requireUrl(dns.encryptedDnsOdohProxyUrl)
        } else if (dns.encryptedDnsBootstrapIps.isNotEmpty()) {
            var required = false
            dns.encryptedDnsBootstrapIps.forEach {
                required = requireDirectEndpoint(it, dns.encryptedDnsPort) || required
            }
            required
        } else if (dns.isDoh) {
            requireUrl(dns.encryptedDnsDohUrl)
        } else {
            requireDirectEndpoint(dns.encryptedDnsHost, dns.encryptedDnsPort)
        }
    } else {
        false
    }

internal suspend fun AndroidLocalNetworkAccess.requireUrl(url: String): Boolean {
    val uri = url.takeIf(String::isNotBlank)?.let(::URI)
    val host = uri?.host
    return if (host == null) {
        false
    } else {
        requireDirectEndpoint(
            host,
            uri.port.takeIf { it > 0 } ?: if (uri.scheme == "http") HttpPort else HttpsPort,
        )
    }
}
