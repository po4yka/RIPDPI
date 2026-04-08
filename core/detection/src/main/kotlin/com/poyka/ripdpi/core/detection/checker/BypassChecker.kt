package com.poyka.ripdpi.core.detection.checker

import com.poyka.ripdpi.core.detection.BypassResult
import com.poyka.ripdpi.core.detection.EvidenceConfidence
import com.poyka.ripdpi.core.detection.EvidenceItem
import com.poyka.ripdpi.core.detection.EvidenceSource
import com.poyka.ripdpi.core.detection.Finding
import com.poyka.ripdpi.core.detection.probe.IfconfigClient
import com.poyka.ripdpi.core.detection.probe.ProxyEndpoint
import com.poyka.ripdpi.core.detection.probe.ProxyScanner
import com.poyka.ripdpi.core.detection.probe.ScanMode
import com.poyka.ripdpi.core.detection.probe.ScanPhase
import com.poyka.ripdpi.core.detection.vpn.VpnAppCatalog
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

object BypassChecker {
    data class Progress(
        val phase: String,
        val detail: String,
    )

    @Suppress("LongMethod")
    suspend fun check(
        excludePorts: Set<Int> = emptySet(),
        onProgress: (suspend (Progress) -> Unit)? = null,
    ): BypassResult =
        coroutineScope {
            val findings = mutableListOf<Finding>()
            val evidence = mutableListOf<EvidenceItem>()

            val scanner = ProxyScanner(excludePorts = excludePorts)

            val proxyDeferred =
                async {
                    onProgress?.invoke(Progress("Port scanning", "Searching for open proxies on localhost..."))
                    scanner.findOpenProxyEndpoint(
                        mode = ScanMode.AUTO,
                        manualPort = null,
                        onProgress = { progress ->
                            val phaseText =
                                when (progress.phase) {
                                    ScanPhase.POPULAR_PORTS -> "Popular ports"
                                    ScanPhase.FULL_RANGE -> "Full range scan"
                                }
                            val percent = if (progress.total > 0) (progress.scanned * 100 / progress.total) else 0
                            onProgress?.invoke(Progress(phaseText, "Port ${progress.currentPort} ($percent%)"))
                        },
                    )
                }

            val proxyEndpoint = proxyDeferred.await()

            reportProxyResult(proxyEndpoint, findings, evidence)

            var directIp: String? = null
            var proxyIp: String? = null
            var confirmedBypass = false

            if (proxyEndpoint != null) {
                onProgress?.invoke(Progress("IP check", "Fetching direct IP and IP via proxy..."))

                val directDeferred = async { IfconfigClient.fetchDirectIp() }
                val proxyIpDeferred = async { IfconfigClient.fetchIpViaProxy(proxyEndpoint) }

                directIp = directDeferred.await().getOrNull()
                proxyIp = proxyIpDeferred.await().getOrNull()

                findings.add(Finding("Direct IP: ${directIp ?: "failed to fetch"}"))
                findings.add(Finding("IP via proxy: ${proxyIp ?: "failed to fetch"}"))

                if (directIp != null && proxyIp != null && directIp != proxyIp) {
                    confirmedBypass = true
                    findings.add(
                        Finding(
                            description = "Per-app split bypass: confirmed (IPs differ)",
                            detected = true,
                            source = EvidenceSource.SPLIT_TUNNEL_BYPASS,
                            confidence = EvidenceConfidence.HIGH,
                        ),
                    )
                    evidence.add(
                        EvidenceItem(
                            source = EvidenceSource.SPLIT_TUNNEL_BYPASS,
                            detected = true,
                            confidence = EvidenceConfidence.HIGH,
                            description = "Direct IP differs from proxy IP",
                        ),
                    )
                } else if (directIp != null && proxyIp != null) {
                    findings.add(Finding("Per-app split disabled: IPs match"))
                }
            }

            val detected = confirmedBypass
            val needsReview = !detected && proxyEndpoint != null

            BypassResult(
                proxyEndpoint = proxyEndpoint,
                directIp = directIp,
                proxyIp = proxyIp,
                xrayApiScanResult = null,
                findings = findings,
                detected = detected,
                needsReview = needsReview,
                evidence = evidence,
            )
        }

    private fun reportProxyResult(
        proxyEndpoint: ProxyEndpoint?,
        findings: MutableList<Finding>,
        evidence: MutableList<EvidenceItem>,
    ) {
        if (proxyEndpoint == null) {
            findings.add(Finding("Open proxies on localhost: not detected"))
            return
        }

        val candidateFamilies = VpnAppCatalog.familiesForPort(proxyEndpoint.port)
        val familySuffix = candidateFamilies.takeIf { it.isNotEmpty() }?.joinToString()
        val description =
            buildString {
                append("Open ")
                append(proxyEndpoint.type.name)
                append(" proxy: ")
                append(formatHostPort(proxyEndpoint.host, proxyEndpoint.port))
                if (!familySuffix.isNullOrBlank()) {
                    append(" [")
                    append(familySuffix)
                    append("]")
                }
                append(" (needs bypass confirmation)")
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
                description =
                    "Detected open ${proxyEndpoint.type.name} proxy at " +
                        formatHostPort(proxyEndpoint.host, proxyEndpoint.port),
                family = familySuffix,
            ),
        )
    }

    private fun formatHostPort(
        host: String,
        port: Int,
    ): String = if (host.contains(':')) "[$host]:$port" else "$host:$port"
}
