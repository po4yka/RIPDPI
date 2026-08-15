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
            runCatching {
                val certificate = fetcher.fetch(ip, port, timeoutMs)
                CertHostnameDiscovery(
                    ip = ip,
                    port = port,
                    commonName = certificate.extractCommonName(),
                    subjectAltNames = certificate.extractDnsSubjectAltNames(),
                )
            }.getOrElse { error ->
                error.throwIfCancellation()
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

private fun List<*>.toDnsNameOrNull(): String? =
    (getOrNull(0) as? Int)
        ?.takeIf { type -> type == GeneralNameDns }
        ?.let { getOrNull(1) as? String }

private fun X509Certificate.extractCommonName(): String? =
    subjectX500Principal
        ?.getName(X500Principal.RFC2253)
        ?.let(::extractCommonNameFromRfc2253)

private fun extractCommonNameFromRfc2253(subject: String): String? {
    var index = 0
    var commonName: String? = null
    while (index < subject.length && commonName == null) {
        val attribute = subject.readRfc2253Attribute(index)
        if (attribute == null) {
            index = subject.length
        } else {
            commonName = attribute.commonNameValue()
            index = attribute.nextIndex
        }
    }
    return commonName
}

private fun String.readRfc2253Attribute(startIndex: Int): Rfc2253Attribute? {
    var index = startIndex
    while (index < length && this[index] != '=') index += 1
    return if (index >= length) {
        null
    } else {
        val key = substring(startIndex, index).trim()
        index += 1
        val valueStart = index
        index = findRfc2253ValueEnd(index)
        val nextIndex = if (index < length && this[index] == ',') index + 1 else index
        Rfc2253Attribute(
            key = key,
            value =
                substring(valueStart, index)
                    .replace("\\,", ",")
                    .trim(),
            nextIndex = nextIndex,
        )
    }
}

private fun String.findRfc2253ValueEnd(startIndex: Int): Int {
    var index = startIndex
    var escaped = false
    while (index < length) {
        val char = this[index]
        when {
            escaped -> escaped = false
            char == '\\' -> escaped = true
            char == ',' -> break
        }
        index += 1
    }
    return index
}

private data class Rfc2253Attribute(
    val key: String,
    val value: String,
    val nextIndex: Int,
) {
    fun commonNameValue(): String? =
        value
            .takeIf { key.equals("CN", ignoreCase = true) }
            ?.ifBlank { null }
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

private fun Throwable.throwIfCancellation() {
    if (this is CancellationException) {
        throw this
    }
}

private const val GeneralNameDns = 2
