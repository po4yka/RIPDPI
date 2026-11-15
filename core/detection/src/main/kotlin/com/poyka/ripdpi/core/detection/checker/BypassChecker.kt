@file:Suppress("MagicNumber", "CyclomaticComplexMethod")

package com.poyka.ripdpi.core.detection.checker

import com.poyka.ripdpi.core.detection.BypassPortRange
import com.poyka.ripdpi.core.detection.BypassResult
import com.poyka.ripdpi.core.detection.BypassScanOptions
import com.poyka.ripdpi.core.detection.EvidenceConfidence
import com.poyka.ripdpi.core.detection.EvidenceItem
import com.poyka.ripdpi.core.detection.EvidenceSource
import com.poyka.ripdpi.core.detection.Finding
import com.poyka.ripdpi.core.detection.probe.IfconfigClient
import com.poyka.ripdpi.core.detection.probe.KnownLocalServices
import com.poyka.ripdpi.core.detection.probe.LocalScanResult
import com.poyka.ripdpi.core.detection.probe.LocalScanStatus
import com.poyka.ripdpi.core.detection.probe.ProxyEndpoint
import com.poyka.ripdpi.core.detection.probe.ProxyScanner
import com.poyka.ripdpi.core.detection.probe.ProxyType
import com.poyka.ripdpi.core.detection.probe.ScanPhase
import com.poyka.ripdpi.core.detection.probe.XrayApiScanResult
import com.poyka.ripdpi.core.detection.probe.XrayApiScanner
import com.poyka.ripdpi.core.detection.vpn.VpnAppCatalog
import com.poyka.ripdpi.data.AppCoroutineDispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

object BypassChecker {
    data class Progress(
        val phase: String,
        val detail: String,
    )

    @Suppress("LongMethod")
    suspend fun check(
        dispatchers: AppCoroutineDispatchers,
        excludePorts: Set<Int> = emptySet(),
        options: BypassScanOptions = BypassScanOptions(),
        onProgress: (suspend (Progress) -> Unit)? = null,
        proxyEndpointProvider: (suspend () -> ProxyEndpoint?)? = null,
        xrayApiScanProvider: (suspend () -> XrayApiScanResult?)? = null,
        directIpProvider: (suspend () -> String?)? = null,
        proxyIpProvider: (suspend (ProxyEndpoint) -> String?)? = null,
        mtProtoProber: MtProtoProber = Socks5MtProtoProber(dispatchers),
        stunClient: Socks5StunClient = Socks5UdpAssociateStunClient(dispatchers),
    ): BypassResult =
        coroutineScope {
            val findings = mutableListOf<Finding>()
            val evidence = mutableListOf<EvidenceItem>()

            val scanRange = options.portRange.toScanRange()
            val excluded = KnownLocalServices.excludedPorts + excludePorts
            val scanner =
                ProxyScanner(
                    dispatchers = dispatchers,
                    excludePorts = excluded,
                    scanRange = scanRange,
                    fullRangeEnabled = options.portRange !is BypassPortRange.Popular,
                )
            val xrayScanner =
                XrayApiScanner(
                    dispatchers = dispatchers,
                    scanPorts = xrayScanPorts(options.portRange, scanRange, excluded),
                )

            val proxyDeferred =
                async {
                    when {
                        !options.proxyScanEnabled -> {
                            LocalScanResult(null, LocalScanStatus.SKIPPED)
                        }

                        proxyEndpointProvider != null -> {
                            providerResult(proxyEndpointProvider.invoke())
                        }

                        else -> {
                            onProgress?.invoke(Progress("Port scanning", "Searching for open proxies on localhost..."))
                            scanner.scan(
                                onProgress = { progress ->
                                    val phaseText =
                                        when (progress.phase) {
                                            ScanPhase.POPULAR_PORTS -> "Popular ports"
                                            ScanPhase.FULL_RANGE -> "Full range scan"
                                        }
                                    val percent =
                                        if (progress.total > 0) (progress.scanned * 100 / progress.total) else 0
                                    onProgress?.invoke(Progress(phaseText, "Port ${progress.currentPort} ($percent%)"))
                                },
                            )
                        }
                    }
                }

            val xrayDeferred =
                async {
                    when {
                        !options.xrayApiScanEnabled -> {
                            LocalScanResult(null, LocalScanStatus.SKIPPED)
                        }

                        xrayApiScanProvider != null -> {
                            providerResult(xrayApiScanProvider.invoke())
                        }

                        else -> {
                            onProgress?.invoke(Progress("Xray API", "Searching for gRPC API on localhost..."))
                            xrayScanner.scan { progress ->
                                val percent = if (progress.total > 0) (progress.scanned * 100 / progress.total) else 0
                                onProgress?.invoke(
                                    Progress("Xray API", "${progress.host}:${progress.currentPort} ($percent%)"),
                                )
                            }
                        }
                    }
                }

            val proxyScan = proxyDeferred.await()
            val xrayScan = xrayDeferred.await()
            val proxyEndpoint = proxyScan.match
            val xrayApiScanResult = xrayScan.match

            reportProxyResult(proxyScan, findings, evidence)
            reportXrayApiResult(xrayScan, findings, evidence)

            var directIp: String? = null
            var proxyIp: String? = null

            if (proxyEndpoint != null && options.networkRequestsEnabled) {
                onProgress?.invoke(Progress("IP check", "Fetching direct IP and IP via proxy..."))

                val directDeferred =
                    async {
                        directIpProvider?.invoke() ?: run {
                            val result = IfconfigClient.fetchDirectIp(dispatchers = dispatchers)
                            result.getOrNull()
                        }
                    }
                val proxyIpDeferred =
                    async {
                        proxyIpProvider?.invoke(proxyEndpoint) ?: run {
                            val result =
                                IfconfigClient.fetchIpViaProxy(
                                    dispatchers = dispatchers,
                                    endpoint = proxyEndpoint,
                                )
                            result.getOrNull()
                        }
                    }
                val mtProtoDeferred =
                    async {
                        if (options.callTransportProbeEnabled && proxyEndpoint.type == ProxyType.SOCKS5) {
                            runCatching { mtProtoProber.canReach(proxyEndpoint) }.getOrDefault(false)
                        } else {
                            false
                        }
                    }
                val stunDeferred =
                    async {
                        if (options.callTransportProbeEnabled && proxyEndpoint.type == ProxyType.SOCKS5) {
                            runCatching { stunClient.reflexiveAddress(proxyEndpoint) }.getOrNull()
                        } else {
                            null
                        }
                    }

                directIp = directDeferred.await()
                proxyIp = proxyIpDeferred.await()
                val mtProtoReachable = mtProtoDeferred.await()
                val stunReflexiveAddresses = listOfNotNull(stunDeferred.await()).distinct()

                findings.add(Finding("Direct IP: ${directIp ?: "failed to fetch"}"))
                findings.add(Finding("IP via proxy: ${proxyIp ?: "failed to fetch"}"))
                reportProxyTransportProbes(mtProtoReachable, stunReflexiveAddresses, findings)

                if (directIp != null && proxyIp != null && directIp != proxyIp) {
                    findings.add(Finding("Direct and proxy IPs differ"))
                } else if (directIp != null && proxyIp != null) {
                    findings.add(Finding("Direct and proxy IPs match"))
                }

                return@coroutineScope BypassResult(
                    proxyEndpoint = proxyEndpoint,
                    directIp = directIp,
                    proxyIp = proxyIp,
                    xrayApiScanResult = xrayApiScanResult,
                    mtProtoReachable = mtProtoReachable,
                    stunReflexiveAddresses = stunReflexiveAddresses,
                    findings = findings,
                    detected = false,
                    needsReview = true,
                    evidence = evidence,
                    proxyScanStatus = proxyScan.status,
                    xrayScanStatus = xrayScan.status,
                )
            }

            BypassResult(
                proxyEndpoint = proxyEndpoint,
                directIp = directIp,
                proxyIp = proxyIp,
                xrayApiScanResult = xrayApiScanResult,
                findings = findings,
                detected = false,
                needsReview = proxyEndpoint != null || xrayApiScanResult != null,
                evidence = evidence,
                proxyScanStatus = proxyScan.status,
                xrayScanStatus = xrayScan.status,
            )
        }

    private fun BypassPortRange.toScanRange(): IntRange =
        when (this) {
            BypassPortRange.Popular -> 1..65535
            BypassPortRange.Extended -> 1024..49151
            BypassPortRange.Full -> 1..65535
            is BypassPortRange.Custom -> ports ?: IntRange.EMPTY
        }

    private fun xrayScanPorts(
        range: BypassPortRange,
        scanRange: IntRange,
        excluded: Set<Int>,
    ): List<Int> =
        if (range == BypassPortRange.Popular) {
            (VpnAppCatalog.localhostProxyPorts + listOf(1081, 7890, 7891, 10085))
                .distinct()
                .sorted()
                .filterNot(excluded::contains)
        } else {
            scanRange.filterNot(excluded::contains)
        }

    private fun reportProxyTransportProbes(
        mtProtoReachable: Boolean,
        stunReflexiveAddresses: List<String>,
        findings: MutableList<Finding>,
    ) {
        if (mtProtoReachable) {
            findings.add(Finding("MTProto reachable via SOCKS5 proxy"))
        }
        for (address in stunReflexiveAddresses) {
            findings.add(Finding("STUN reflexive address via SOCKS5: $address"))
        }
    }

    private fun reportProxyResult(
        scan: LocalScanResult<ProxyEndpoint>,
        findings: MutableList<Finding>,
        evidence: MutableList<EvidenceItem>,
    ) {
        val proxyEndpoint = scan.match
        if (proxyEndpoint == null) {
            findings.add(Finding("Local proxy scan: ${scan.status.description()}", source = EvidenceSource.LOCAL_PROXY))
            return
        }

        val candidateFamilies = VpnAppCatalog.familiesForPort(proxyEndpoint.port)
        val familySuffix = candidateFamilies.takeIf { it.isNotEmpty() }?.joinToString()
        val description =
            buildString {
                append(
                    when (proxyEndpoint.type) {
                        ProxyType.SOCKS5 -> "SOCKS5 no-auth greeting accepted at "
                        ProxyType.HTTP -> "HTTP CONNECT accepted at "
                    },
                )
                append(formatHostPort(proxyEndpoint.host, proxyEndpoint.port))
                if (!familySuffix.isNullOrBlank()) {
                    append(" [possible families: ")
                    append(familySuffix)
                    append("]")
                }
                append(" (local observation)")
            }

        findings.add(
            Finding(
                description = description,
                needsReview = true,
                source = EvidenceSource.LOCAL_PROXY,
                confidence = EvidenceConfidence.MEDIUM,
                family = familySuffix,
            ),
        )
        evidence.add(
            EvidenceItem(
                source = EvidenceSource.LOCAL_PROXY,
                detected = true,
                confidence = EvidenceConfidence.MEDIUM,
                description = description,
                family = familySuffix,
            ),
        )
    }

    private fun reportXrayApiResult(
        scan: LocalScanResult<XrayApiScanResult>,
        findings: MutableList<Finding>,
        evidence: MutableList<EvidenceItem>,
    ) {
        val xrayApiScanResult = scan.match
        if (xrayApiScanResult == null) {
            findings.add(Finding("Xray gRPC API scan: ${scan.status.description()}", source = EvidenceSource.XRAY_API))
            return
        }

        val ep = xrayApiScanResult.endpoint
        val observation =
            "Xray-compatible HandlerService responded to ListOutbounds at " + formatHostPort(ep.host, ep.port)
        findings.add(
            Finding(
                description = observation,
                needsReview = true,
                source = EvidenceSource.XRAY_API,
                confidence = EvidenceConfidence.HIGH,
            ),
        )
        evidence.add(
            EvidenceItem(
                source = EvidenceSource.XRAY_API,
                detected = true,
                confidence = EvidenceConfidence.HIGH,
                description = observation,
            ),
        )
    }

    private fun <T> providerResult(match: T?): LocalScanResult<T> =
        LocalScanResult(match, if (match == null) LocalScanStatus.NO_MATCH else LocalScanStatus.MATCHED)

    private fun LocalScanStatus.description(): String =
        when (this) {
            LocalScanStatus.MATCHED -> "match found"
            LocalScanStatus.NO_MATCH -> "no matching service on scanned ports"
            LocalScanStatus.REJECTED -> "connection or request rejected"
            LocalScanStatus.TIMED_OUT -> "timed out"
            LocalScanStatus.UNAVAILABLE -> "unavailable"
            LocalScanStatus.PARTIAL -> "some attempts did not complete"
            LocalScanStatus.SKIPPED -> "skipped"
        }

    private fun formatHostPort(
        host: String,
        port: Int,
    ): String = if (host.contains(':')) "[$host]:$port" else "$host:$port"
}
