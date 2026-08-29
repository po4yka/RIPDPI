package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.AndroidLocalNetworkAccess
import com.poyka.ripdpi.data.LocalNetworkAccessRequiredException
import com.poyka.ripdpi.data.LocalNetworkPermission
import com.poyka.ripdpi.diagnostics.contract.engine.EngineProbeTaskFamily
import com.poyka.ripdpi.diagnostics.contract.engine.EngineScanRequestWire
import java.net.URI

private const val ProxyPort = 1080
private const val HttpsPort = 443
private const val HttpPort = 80
private const val DnsPort = 53

internal data class LocalNetworkScanAdmission(
    val request: EngineScanRequestWire,
    val deferred: List<ProbeResult>,
)

internal suspend fun AndroidLocalNetworkAccess.prepareScanEndpoints(
    request: EngineScanRequestWire,
): LocalNetworkScanAdmission {
    val granted = hasAccess()
    if (granted || request.pathMode == ScanPathMode.IN_PATH) {
        if (!granted) request.proxyHost?.let { requireDirectEndpoint(it, request.proxyPort ?: ProxyPort) }
        return LocalNetworkScanAdmission(request, emptyList())
    }
    val filter = LocalNetworkScanFilter(this)
    val admitted = filter.admit(request)
    return LocalNetworkScanAdmission(
        admitted.copy(probeTasks = admitted.probeTasks.filterNot { (it.family to it.targetId) in filter.deniedTasks }),
        filter.deferred,
    )
}

private class LocalNetworkScanFilter(
    private val access: AndroidLocalNetworkAccess,
) {
    val deferred = mutableListOf<ProbeResult>()
    val deniedTasks = mutableSetOf<Pair<EngineProbeTaskFamily, String>>()

    suspend fun admit(request: EngineScanRequestWire): EngineScanRequestWire =
        request.copy(
            tcpTargets =
                allowed(request.tcpTargets, EngineProbeTaskFamily.TCP, "tcp_connect", { it.id }) {
                    requireDirectEndpoint(it.ip, it.port)
                },
            domainTargets =
                allowed(request.domainTargets, EngineProbeTaskFamily.WEB, "domain_reachability", { it.host }) {
                    requireHosts(it.connectIps, it.connectIp, it.host, it.httpsPort ?: HttpsPort)
                    requireHosts(it.connectIps, it.connectIp, it.host, it.httpPort ?: HttpPort)
                },
            quicTargets =
                allowed(request.quicTargets, EngineProbeTaskFamily.QUIC, "quic_reachability", { it.host }) {
                    requireHosts(it.connectIps, it.connectIp, it.host, it.port)
                },
            dnsTargets =
                allowed(request.dnsTargets, EngineProbeTaskFamily.DNS, "dns_integrity", { it.domain }) {
                    requireDnsTarget(it)
                },
            serviceTargets =
                allowed(
                    request.serviceTargets,
                    EngineProbeTaskFamily.SERVICE,
                    "service_reachability",
                    { it.id },
                ) {
                    requireServiceTarget(it)
                },
            circumventionTargets =
                allowed(
                    request.circumventionTargets,
                    EngineProbeTaskFamily.CIRCUMVENTION,
                    "circumvention_reachability",
                    { it.id },
                ) {
                    it.bootstrapUrl?.let { url -> requireProbeUrl(url) }
                    (it.handshakeIp ?: it.handshakeHost)?.let { host -> requireDirectEndpoint(host, it.handshakePort) }
                },
            throughputTargets =
                allowed(
                    request.throughputTargets,
                    EngineProbeTaskFamily.THROUGHPUT,
                    "throughput_window",
                    { it.id },
                ) {
                    val uri = URI(it.url)
                    requireHosts(it.connectIps, it.connectIp, uri.host.orEmpty(), it.port ?: uri.networkPort())
                },
            telegramTarget =
                allowed(
                    listOfNotNull(request.telegramTarget),
                    EngineProbeTaskFamily.TELEGRAM,
                    "telegram_availability",
                    { "telegram" },
                ) {
                    requireProbeUrl(it.mediaUrl)
                    requireDirectEndpoint(it.uploadIp, it.uploadPort)
                    it.dcEndpoints.forEach { endpoint -> requireDirectEndpoint(endpoint.ip, endpoint.port) }
                }.singleOrNull(),
        )

    private suspend fun <T> allowed(
        targets: List<T>,
        family: EngineProbeTaskFamily,
        probeType: String,
        id: (T) -> String,
        check: suspend AndroidLocalNetworkAccess.(T) -> Unit,
    ): List<T> =
        targets.filter { target ->
            try {
                access.check(target)
                true
            } catch (_: LocalNetworkAccessRequiredException) {
                deniedTasks += family to id(target)
                deferred +=
                    ProbeResult(
                        probeType = probeType,
                        target = id(target),
                        outcome = "capability_skipped",
                        details =
                            listOf(
                                ProbeDetail("permission", LocalNetworkPermission),
                                ProbeDetail("reason", "local_network_permission_required"),
                            ),
                    )
                false
            }
        }
}

private suspend fun AndroidLocalNetworkAccess.requireDnsTarget(target: DnsTarget) {
    target.udpServer?.let { requireDirectEndpoint(it, DnsPort) }
    if (target.encryptedBootstrapIps.isNotEmpty()) {
        target.encryptedBootstrapIps.forEach { requireDirectEndpoint(it, target.encryptedPort ?: HttpsPort) }
    } else {
        target.encryptedHost?.let { requireDirectEndpoint(it, target.encryptedPort ?: HttpsPort) }
        target.encryptedDohUrl?.let { requireProbeUrl(it) }
    }
}

private suspend fun AndroidLocalNetworkAccess.requireServiceTarget(target: ServiceTarget) {
    target.bootstrapUrl?.let { requireProbeUrl(it) }
    target.mediaUrl?.let { requireProbeUrl(it) }
    (target.tcpEndpointIp ?: target.tcpEndpointHost)?.let { requireDirectEndpoint(it, target.tcpEndpointPort) }
    (target.quicConnectIp ?: target.quicHost)?.let { requireDirectEndpoint(it, target.quicPort) }
}

private suspend fun AndroidLocalNetworkAccess.requireHosts(
    ips: List<String>,
    ip: String?,
    host: String,
    port: Int,
) {
    // Mirror native ordered_connect_targets: legacy address, preferred edges, then hostname fallback.
    val peers = (listOfNotNull(ip) + ips + host).filter(String::isNotBlank).distinct()
    peers.forEach { requireDirectEndpoint(it, port) }
}

private suspend fun AndroidLocalNetworkAccess.requireProbeUrl(url: String) {
    val uri = URI(url)
    uri.host?.let { requireDirectEndpoint(it, uri.networkPort()) }
}

private fun URI.networkPort(): Int = port.takeIf { it > 0 } ?: if (scheme == "http") HttpPort else HttpsPort
