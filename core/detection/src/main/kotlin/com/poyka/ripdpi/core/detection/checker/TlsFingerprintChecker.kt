package com.poyka.ripdpi.core.detection.checker

import com.poyka.ripdpi.core.detection.CategoryResult
import com.poyka.ripdpi.core.detection.CheckExecutionStatus
import com.poyka.ripdpi.core.detection.Finding
import com.poyka.ripdpi.data.AppCoroutineDispatchers
import com.poyka.ripdpi.data.normalizeTlsFingerprintProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

object TlsFingerprintChecker {
    private const val HASH_PREVIEW_LENGTH = 16

    private val BROWSER_REFERENCE_CIPHERS =
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

        val coverage = localCiphers.toSet().count { it in BROWSER_REFERENCE_CIPHERS }
        val referenceSize = BROWSER_REFERENCE_CIPHERS.size
        val hash = computeFingerprint(localCiphers, localProtocols).take(HASH_PREVIEW_LENGTH)
        val configuredProfile = normalizeTlsFingerprintProfile(tlsFingerprintProfile)
        val findings =
            buildList {
                add(Finding("Enabled TLS protocols: ${localProtocols.joinToString(", ")}"))
                add(Finding("Enabled cipher suites: ${localCiphers.size}"))
                add(Finding("Local TLS configuration SHA-256: $hash..."))
                add(Finding("Configured TLS profile: $configuredProfile"))
                add(
                    Finding(
                        "Enabled browser reference cipher suites: $coverage/$referenceSize",
                        needsReview = coverage * 2 < referenceSize,
                    ),
                )
                if ("TLSv1.3" !in localProtocols) {
                    add(Finding("TLS 1.3 is not enabled in the local TLS socket", needsReview = true))
                }
                if (localCiphers.any { it.contains("RC4") || it.contains("DES") || it.contains("MD5") }) {
                    add(Finding("Legacy TLS ciphers enabled (RC4/DES/MD5)", needsReview = true))
                }
            }

        return CategoryResult(
            name = "TLS Fingerprint",
            detected = false,
            findings = findings,
            needsReview = findings.any(Finding::needsReview),
        )
    }

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
