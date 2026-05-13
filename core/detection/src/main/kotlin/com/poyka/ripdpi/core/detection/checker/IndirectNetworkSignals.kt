@file:Suppress(
    "TooGenericExceptionCaught",
    "ReturnCount",
    "LoopWithTooManyJumpStatements",
    "MagicNumber",
    "NestedBlockDepth",
)

package com.poyka.ripdpi.core.detection.checker

import android.content.Context
import android.net.ConnectivityManager
import com.poyka.ripdpi.core.detection.EvidenceConfidence
import com.poyka.ripdpi.core.detection.EvidenceItem
import com.poyka.ripdpi.core.detection.EvidenceSource
import com.poyka.ripdpi.core.detection.Finding
import kotlinx.coroutines.CancellationException
import java.net.NetworkInterface

internal object IndirectNetworkSignals {
    private val VPN_INTERFACE_PATTERNS =
        listOf(
            Regex("^tun\\d+"),
            Regex("^tap\\d+"),
            Regex("^wg\\d+"),
            Regex("^ppp\\d+"),
            Regex("^ipsec.*"),
        )

    private val STANDARD_INTERFACES =
        listOf(
            Regex("^wlan.*"),
            Regex("^rmnet.*"),
            Regex("^eth.*"),
            Regex("^lo$"),
        )

    fun checkNotVpnCapability(
        context: Context,
        findings: MutableList<Finding>,
        evidence: MutableList<EvidenceItem>,
    ): IndirectSignalOutcome {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = cm.activeNetwork ?: return IndirectSignalOutcome()
        val caps = cm.getNetworkCapabilities(activeNetwork) ?: return IndirectSignalOutcome()

        val capsString = caps.toString()
        val hasNotVpn = capsString.contains("NOT_VPN")
        findings.add(
            Finding(
                description = "NOT_VPN capability: ${if (hasNotVpn) "present" else "absent (suspicious)"}",
                detected = !hasNotVpn,
                source = EvidenceSource.NETWORK_CAPABILITIES,
                confidence = (!hasNotVpn).takeIf { it }?.let { EvidenceConfidence.MEDIUM },
            ),
        )
        if (!hasNotVpn) {
            evidence.add(
                EvidenceItem(
                    source = EvidenceSource.NETWORK_CAPABILITIES,
                    detected = true,
                    confidence = EvidenceConfidence.MEDIUM,
                    description = "Active network does not expose NOT_VPN capability",
                ),
            )
        }
        return IndirectSignalOutcome(detected = !hasNotVpn)
    }

    fun checkNetworkInterfaces(
        findings: MutableList<Finding>,
        evidence: MutableList<EvidenceItem>,
    ): Boolean =
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList() ?: emptyList()
            val vpnInterfaces =
                interfaces.filter { iface ->
                    iface.isUp && VPN_INTERFACE_PATTERNS.any { pattern -> pattern.matches(iface.name) }
                }

            if (vpnInterfaces.isEmpty()) {
                findings.add(Finding("VPN interfaces (tun/tap/wg/ppp/ipsec): not detected"))
                false
            } else {
                for (iface in vpnInterfaces) {
                    findings.add(
                        Finding(
                            description = "VPN interface detected: ${iface.name}",
                            detected = true,
                            source = EvidenceSource.NETWORK_INTERFACE,
                            confidence = EvidenceConfidence.MEDIUM,
                        ),
                    )
                    evidence.add(
                        EvidenceItem(
                            source = EvidenceSource.NETWORK_INTERFACE,
                            detected = true,
                            confidence = EvidenceConfidence.MEDIUM,
                            description = "Active VPN-like interface ${iface.name}",
                        ),
                    )
                }
                true
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            findings.add(Finding("Error checking interfaces: ${e.message}"))
            false
        }

    @Suppress("NestedBlockDepth")
    fun checkMtu(
        findings: MutableList<Finding>,
        evidence: MutableList<EvidenceItem>,
    ): Boolean =
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList() ?: emptyList()
            var detected = false
            for (iface in interfaces) {
                if (!iface.isUp) continue
                val isVpnLike = VPN_INTERFACE_PATTERNS.any { it.matches(iface.name) }
                if (!isVpnLike) continue

                val mtu = iface.mtu
                if (mtu !in 1..1499) continue

                findings.add(
                    Finding(
                        description = "MTU anomaly: ${iface.name} MTU=$mtu (< 1500)",
                        detected = true,
                        source = EvidenceSource.NETWORK_INTERFACE,
                        confidence = EvidenceConfidence.MEDIUM,
                    ),
                )
                evidence.add(
                    EvidenceItem(
                        source = EvidenceSource.NETWORK_INTERFACE,
                        detected = true,
                        confidence = EvidenceConfidence.MEDIUM,
                        description = "VPN-like interface ${iface.name} uses low MTU $mtu",
                    ),
                )
                detected = true
            }

            val activeInterfaces = interfaces.filter { it.isUp && it.mtu in 1..1499 }
            val nonVpnLowMtu =
                activeInterfaces.filter { iface ->
                    !VPN_INTERFACE_PATTERNS.any { it.matches(iface.name) } &&
                        !STANDARD_INTERFACES.any { it.matches(iface.name) }
                }
            for (iface in nonVpnLowMtu) {
                findings.add(
                    Finding(
                        description = "MTU anomaly: non-standard interface ${iface.name} MTU=${iface.mtu}",
                        detected = true,
                        source = EvidenceSource.NETWORK_INTERFACE,
                        confidence = EvidenceConfidence.LOW,
                    ),
                )
                evidence.add(
                    EvidenceItem(
                        source = EvidenceSource.NETWORK_INTERFACE,
                        detected = true,
                        confidence = EvidenceConfidence.LOW,
                        description = "Non-standard interface ${iface.name} uses low MTU ${iface.mtu}",
                    ),
                )
                detected = true
            }

            if (!detected) {
                findings.add(Finding("MTU: no anomalies detected"))
            }

            detected
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            findings.add(Finding("Error checking MTU: ${e.message}"))
            false
        }

    fun checkRoutingTable(
        context: Context,
        findings: MutableList<Finding>,
        evidence: MutableList<EvidenceItem>,
    ): Boolean =
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork
            if (network == null) {
                findings.add(Finding("Default route: no active network"))
                false
            } else {
                val linkProps = cm.getLinkProperties(network)
                val routes = linkProps?.routes.orEmpty()
                val defaultRoutes = routes.filter { it.isDefaultRoute }

                if (defaultRoutes.isEmpty()) {
                    findings.add(Finding("Default route: not found"))
                    false
                } else {
                    var detected = false
                    for (route in defaultRoutes) {
                        val iface = route.`interface` ?: continue
                        val isStandard = STANDARD_INTERFACES.any { it.matches(iface) }
                        if (isStandard) {
                            findings.add(Finding("Default route: $iface (standard)"))
                            continue
                        }

                        findings.add(
                            Finding(
                                description = "Default route through non-standard interface: $iface",
                                detected = true,
                                source = EvidenceSource.ROUTING,
                                confidence = EvidenceConfidence.MEDIUM,
                            ),
                        )
                        evidence.add(
                            EvidenceItem(
                                source = EvidenceSource.ROUTING,
                                detected = true,
                                confidence = EvidenceConfidence.MEDIUM,
                                description = "Default route points to non-standard interface $iface",
                            ),
                        )
                        detected = true
                    }
                    detected
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            findings.add(Finding("Error checking routes: ${e.message}"))
            false
        }
}
