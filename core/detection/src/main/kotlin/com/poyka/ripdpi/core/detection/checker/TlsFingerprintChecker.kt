@file:Suppress("MagicNumber", "LongMethod")

package com.poyka.ripdpi.core.detection.checker

import com.poyka.ripdpi.core.detection.CategoryResult
import com.poyka.ripdpi.core.detection.CheckExecutionStatus
import com.poyka.ripdpi.core.detection.DetectionScope
import com.poyka.ripdpi.core.detection.EvidenceConfidence
import com.poyka.ripdpi.core.detection.EvidenceItem
import com.poyka.ripdpi.core.detection.EvidenceSource
import com.poyka.ripdpi.core.detection.Finding
import com.poyka.ripdpi.data.AppCoroutineDispatchers
import com.poyka.ripdpi.data.TlsFingerprintProfileChromeStable
import com.poyka.ripdpi.data.normalizeTlsFingerprintProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

object TlsFingerprintChecker {
    private val BROWSER_CIPHER_SUITES =
        setOf(
            "TLS_AES_128_GCM_SHA256",
            "TLS_AES_256_GCM_SHA384",
            "TLS_CHACHA20_POLY1305_SHA256",
            "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
            "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
            "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
            "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
            "TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256",
            "TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256",
        )

    suspend fun check(
        dispatchers: AppCoroutineDispatchers,
        tlsFingerprintProfile: String = "chrome_stable",
    ): CategoryResult =
        withContext(dispatchers.io) {
            val configuration = readEnabledConfiguration()
            ensureActive()
            if (configuration == null) {
                unavailable(CheckExecutionStatus.ERROR)
            } else {
                summarizeLocalTls(tlsFingerprintProfile, configuration.ciphers, configuration.protocols)
            }
        }

    internal fun summarizeLocalTls(
        tlsFingerprintProfile: String,
        localCiphers: List<String>,
        localProtocols: List<String>,
    ): CategoryResult {
        if (localCiphers.isEmpty() || localProtocols.isEmpty()) return unavailable(CheckExecutionStatus.INCONCLUSIVE)

        val findings = mutableListOf<Finding>()
        val evidence = mutableListOf<EvidenceItem>()
        var detected = false
        var needsReview = false
        val normalizedProfile = normalizeTlsFingerprintProfile(tlsFingerprintProfile)

        findings.add(Finding("TLS protocols: ${localProtocols.joinToString(", ")}"))
        findings.add(Finding("Cipher suites: ${localCiphers.size} supported"))

        val fingerprintHash = computeFingerprint(localCiphers, localProtocols)
        findings.add(Finding("TLS fingerprint hash: ${fingerprintHash.take(16)}..."))

        findings.add(Finding("Fingerprint profile: $normalizedProfile"))

        val hasTls13 = localProtocols.any { it == "TLSv1.3" }
        if (!hasTls13) {
            needsReview = true
            findings.add(
                Finding(
                    description = "TLS 1.3 not supported - may be distinguishable from modern browsers",
                    needsReview = true,
                    source = EvidenceSource.NETWORK_CAPABILITIES,
                    confidence = EvidenceConfidence.LOW,
                ),
            )
        }

        val browserOverlap = localCiphers.count { it in BROWSER_CIPHER_SUITES }
        val overlapRatio = browserOverlap.toFloat() / BROWSER_CIPHER_SUITES.size

        if (overlapRatio < 0.5f) {
            detected = true
            findings.add(
                Finding(
                    description =
                        "Low browser cipher overlap (${(overlapRatio * 100).toInt()}%) " +
                            "- TLS fingerprint distinguishable from browsers",
                    detected = true,
                    source = EvidenceSource.NETWORK_CAPABILITIES,
                    confidence = EvidenceConfidence.MEDIUM,
                ),
            )
            evidence.add(lowBrowserOverlapEvidence())
        } else {
            findings.add(
                Finding("Browser cipher overlap: ${(overlapRatio * 100).toInt()}% (good)"),
            )
        }

        if (normalizedProfile == TlsFingerprintProfileChromeStable) {
            findings.add(Finding("Using Chrome-stable TLS profile"))
        }

        val hasLegacyCiphers =
            localCiphers.any {
                it.contains("RC4") || it.contains("DES") || it.contains("MD5")
            }
        if (hasLegacyCiphers) {
            findings.add(
                Finding(
                    description = "Legacy ciphers present (RC4/DES/MD5) - unusual for modern clients",
                    needsReview = true,
                    source = EvidenceSource.NETWORK_CAPABILITIES,
                    confidence = EvidenceConfidence.LOW,
                ),
            )
            needsReview = true
        }

        return CategoryResult(
            name = "TLS Fingerprint",
            detected = detected,
            findings = findings,
            needsReview = needsReview,
            evidence = evidence,
        )
    }

    internal fun lowBrowserOverlapEvidence(): EvidenceItem =
        EvidenceItem(
            source = EvidenceSource.NETWORK_CAPABILITIES,
            scope = DetectionScope.NETWORK_OBSERVATION,
            detected = true,
            confidence = EvidenceConfidence.MEDIUM,
            description = "TLS cipher suite set differs significantly from browsers",
        )

    private fun unavailable(status: CheckExecutionStatus): CategoryResult =
        CategoryResult(
            name = "TLS Fingerprint",
            detected = false,
            findings = listOf(Finding("Local TLS configuration is unavailable")),
            executionStatus = status,
        )

    @Suppress("TooGenericExceptionCaught")
    private fun readEnabledConfiguration(): EnabledTlsConfiguration? =
        try {
            val context = SSLContext.getInstance("TLS")
            context.init(null, null, null)
            context.socketFactory.createSocket().use { socket ->
                val tlsSocket = socket as SSLSocket
                EnabledTlsConfiguration(
                    ciphers = tlsSocket.enabledCipherSuites.toList(),
                    protocols = tlsSocket.enabledProtocols.toList(),
                )
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            null
        }

    private data class EnabledTlsConfiguration(
        val ciphers: List<String>,
        val protocols: List<String>,
    )

    private fun computeFingerprint(
        ciphers: List<String>,
        protocols: List<String>,
    ): String {
        val input = (protocols.sorted() + ciphers.sorted()).joinToString(",")
        val digest = MessageDigest.getInstance("SHA-256")
        return digest
            .digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}
