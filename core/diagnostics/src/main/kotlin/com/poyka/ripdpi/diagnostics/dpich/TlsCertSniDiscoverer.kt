package com.poyka.ripdpi.diagnostics.dpich

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import javax.security.auth.x500.X500Principal

fun interface TlsPeerCertificateFetcher {
    fun fetch(
        ip: String,
        port: Int,
        timeoutMs: Long,
    ): X509Certificate
}

class TlsCertSniDiscoverer(
    private val fetcher: TlsPeerCertificateFetcher = NoSniTlsPeerCertificateFetcher(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun discover(
        ip: String,
        port: Int = HttpsPort,
        timeoutMs: Long = DefaultTimeoutMs,
    ): CertHostnameDiscovery =
        withContext(dispatcher) {
            try {
                val certificate = fetcher.fetch(ip, port, timeoutMs)
                CertHostnameDiscovery(
                    ip = ip,
                    port = port,
                    commonName = certificate.extractCommonName(),
                    subjectAltNames = certificate.extractDnsSubjectAltNames(),
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                CertHostnameDiscovery(
                    ip = ip,
                    port = port,
                    commonName = null,
                    subjectAltNames = emptyList(),
                    error = error.message ?: error.javaClass.simpleName,
                )
            }
        }

    suspend fun discoverBatch(
        ips: List<String>,
        workers: Int = DefaultWorkers,
        port: Int = HttpsPort,
        timeoutMs: Long = DefaultTimeoutMs,
    ): List<CertHostnameDiscovery> {
        if (ips.isEmpty()) return emptyList()
        val semaphore = Semaphore(workers.coerceAtLeast(1))
        return coroutineScope {
            ips
                .map { ip ->
                    async {
                        semaphore.withPermit {
                            discover(ip = ip, port = port, timeoutMs = timeoutMs)
                        }
                    }
                }.awaitAll()
        }
    }

    companion object {
        private const val HttpsPort = 443
        private const val DefaultTimeoutMs = 3_000L
        private const val DefaultWorkers = 8
    }
}

class NoSniTlsPeerCertificateFetcher(
    private val socketFactory: SSLSocketFactory = trustAllSocketFactory(),
) : TlsPeerCertificateFetcher {
    override fun fetch(
        ip: String,
        port: Int,
        timeoutMs: Long,
    ): X509Certificate {
        val socket = socketFactory.createSocket() as SSLSocket
        socket.use { sslSocket ->
            sslSocket.soTimeout = timeoutMs.toInt()
            sslSocket.sslParameters =
                sslSocket.sslParameters.apply {
                    endpointIdentificationAlgorithm = null
                    serverNames = emptyList()
                }
            sslSocket.connect(InetSocketAddress(ip, port), timeoutMs.toInt())
            sslSocket.startHandshake()
            return sslSocket.session.peerCertificates.first() as X509Certificate
        }
    }
}

private fun X509Certificate.extractDnsSubjectAltNames(): List<String> =
    subjectAlternativeNames
        .orEmpty()
        .asSequence()
        .mapNotNull { entry -> entry.toDnsNameOrNull() }
        .distinctBy { hostname -> hostname.lowercase() }
        .toList()

private fun List<*>.toDnsNameOrNull(): String? {
    val type = getOrNull(0) as? Int ?: return null
    if (type != GeneralNameDns) return null
    return getOrNull(1) as? String
}

private fun X509Certificate.extractCommonName(): String? =
    subjectX500Principal
        ?.getName(X500Principal.RFC2253)
        ?.let(::extractCommonNameFromRfc2253)

private fun extractCommonNameFromRfc2253(subject: String): String? {
    var index = 0
    while (index < subject.length) {
        val attributeStart = index
        while (index < subject.length && subject[index] != '=') index += 1
        if (index >= subject.length) return null
        val key = subject.substring(attributeStart, index).trim()
        index += 1
        val valueStart = index
        var escaped = false
        while (index < subject.length) {
            val char = subject[index]
            when {
                escaped -> escaped = false
                char == '\\' -> escaped = true
                char == ',' -> break
            }
            index += 1
        }
        if (key.equals("CN", ignoreCase = true)) {
            return subject
                .substring(valueStart, index)
                .replace("\\,", ",")
                .trim()
                .ifBlank { null }
        }
        if (index < subject.length && subject[index] == ',') index += 1
    }
    return null
}

private fun trustAllSocketFactory(): SSLSocketFactory {
    val context = SSLContext.getInstance("TLS")
    context.init(null, arrayOf(TrustAllManager), SecureRandom())
    return context.socketFactory
}

private object TrustAllManager : X509TrustManager {
    override fun checkClientTrusted(
        chain: Array<out X509Certificate>?,
        authType: String?,
    ) = Unit

    override fun checkServerTrusted(
        chain: Array<out X509Certificate>?,
        authType: String?,
    ) = Unit

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

private const val GeneralNameDns = 2
