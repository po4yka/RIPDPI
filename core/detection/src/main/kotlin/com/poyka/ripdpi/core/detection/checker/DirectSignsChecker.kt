@file:Suppress("ReturnCount")

package com.poyka.ripdpi.core.detection.checker

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Proxy
import com.poyka.ripdpi.core.detection.CategoryResult
import com.poyka.ripdpi.core.detection.CheckExecutionStatus
import com.poyka.ripdpi.core.detection.EvidenceConfidence
import com.poyka.ripdpi.core.detection.EvidenceItem
import com.poyka.ripdpi.core.detection.EvidenceSource
import com.poyka.ripdpi.core.detection.Finding
import com.poyka.ripdpi.core.detection.vpn.InstalledVpnAppDetector
import kotlinx.coroutines.CancellationException

object DirectSignsChecker {
    private val KNOWN_PROXY_PORTS =
        setOf(
            80,
            443,
            1080,
            3127,
            3128,
            4080,
            5555,
            7000,
            7044,
            8000,
            8080,
            8081,
            8082,
            8888,
            9000,
            9050,
            9051,
            9150,
            12345,
        )
    private val KNOWN_PROXY_PORT_RANGES = listOf(16000..16100)

    suspend fun check(
        context: Context,
        excludePackage: String? = null,
    ): CategoryResult {
        val findings = mutableListOf<Finding>()
        val evidence = mutableListOf<EvidenceItem>()

        val vpnTransport = checkVpnTransport(context, findings, evidence)
        val proxyNeedsReview = checkSystemProxy(findings, evidence)
        val appDetection = InstalledVpnAppDetector.detect(context, excludePackage)
        findings += appDetection.findings
        evidence += appDetection.evidence

        return CategoryResult(
            name = "Direct signs",
            detected = vpnTransport == true,
            findings = findings,
            needsReview = proxyNeedsReview || appDetection.needsReview,
            evidence = evidence,
            matchedApps = appDetection.matchedApps,
            executionStatus =
                when {
                    vpnTransport != null && appDetection.executionStatus == CheckExecutionStatus.COMPLETED -> {
                        CheckExecutionStatus.COMPLETED
                    }

                    vpnTransport == null && appDetection.executionStatus == CheckExecutionStatus.ERROR &&
                        evidence.isEmpty() -> {
                        CheckExecutionStatus.ERROR
                    }

                    else -> {
                        CheckExecutionStatus.INCONCLUSIVE
                    }
                },
        )
    }

    private fun checkVpnTransport(
        context: Context,
        findings: MutableList<Finding>,
        evidence: MutableList<EvidenceItem>,
    ): Boolean? {
        val caps = defaultNetworkCapabilities(context)
        if (caps == null) {
            findings.add(Finding("Default network VPN transport could not be checked"))
            return null
        }

        val hasVpnTransport = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        findings.add(
            Finding(
                description = "TRANSPORT_VPN: ${if (hasVpnTransport) "detected" else "not detected"}",
                detected = hasVpnTransport,
                source = EvidenceSource.NETWORK_CAPABILITIES,
                confidence = hasVpnTransport.takeIf { it }?.let { EvidenceConfidence.HIGH },
            ),
        )
        if (hasVpnTransport) {
            evidence.add(
                EvidenceItem(
                    source = EvidenceSource.NETWORK_CAPABILITIES,
                    detected = true,
                    confidence = EvidenceConfidence.HIGH,
                    description = "Active network reports TRANSPORT_VPN",
                ),
            )
        }
        return hasVpnTransport
    }

    // ROADMAP-architecture-refactor Android can rethrow Binder failures as RuntimeException.
    @Suppress("TooGenericExceptionCaught")
    private fun defaultNetworkCapabilities(context: Context): NetworkCapabilities? =
        try {
            context.getSystemService(ConnectivityManager::class.java)?.let { cm ->
                cm.activeNetwork?.let(cm::getNetworkCapabilities)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: RuntimeException) {
            null
        }

    @Suppress("DEPRECATION")
    private fun checkSystemProxy(
        findings: MutableList<Finding>,
        evidence: MutableList<EvidenceItem>,
    ): Boolean {
        val httpHost = System.getProperty("http.proxyHost") ?: Proxy.getDefaultHost()
        val httpPort =
            System.getProperty("http.proxyPort")
                ?: Proxy.getDefaultPort().takeIf { it > 0 }?.toString()
        val socksHost = System.getProperty("socksProxyHost")
        val socksPort = System.getProperty("socksProxyPort")
        var needsReview = false

        needsReview = addProxyFinding(
            type = "HTTP proxy",
            host = httpHost,
            port = httpPort,
            findings = findings,
            evidence = evidence,
        ) || needsReview

        needsReview = addProxyFinding(
            type = "SOCKS proxy",
            host = socksHost,
            port = socksPort,
            findings = findings,
            evidence = evidence,
        ) || needsReview

        return needsReview
    }

    private fun addProxyFinding(
        type: String,
        host: String?,
        port: String?,
        findings: MutableList<Finding>,
        evidence: MutableList<EvidenceItem>,
    ): Boolean {
        if (host.isNullOrBlank()) {
            findings.add(Finding("$type: not configured"))
            return false
        }

        val knownPort = isKnownProxyPort(port)
        val confidence = if (knownPort) EvidenceConfidence.MEDIUM else EvidenceConfidence.LOW
        val description = "$type: $host:${port ?: "N/A"}"

        findings.add(
            Finding(
                description = description,
                needsReview = true,
                source = EvidenceSource.SYSTEM_PROXY,
                confidence = confidence,
            ),
        )
        evidence.add(
            EvidenceItem(
                source = EvidenceSource.SYSTEM_PROXY,
                detected = true,
                confidence = confidence,
                description = description,
            ),
        )

        if (knownPort) {
            findings.add(
                Finding(
                    description = "$type uses known proxy port $port",
                    needsReview = true,
                    source = EvidenceSource.SYSTEM_PROXY,
                    confidence = EvidenceConfidence.MEDIUM,
                ),
            )
            evidence.add(
                EvidenceItem(
                    source = EvidenceSource.SYSTEM_PROXY,
                    detected = true,
                    confidence = EvidenceConfidence.MEDIUM,
                    description = "$type uses known proxy port $port",
                ),
            )
        }

        return true
    }

    internal fun isKnownProxyPort(port: String?): Boolean {
        val value = port?.toIntOrNull() ?: return false
        return value in KNOWN_PROXY_PORTS || KNOWN_PROXY_PORT_RANGES.any { value in it }
    }
}
