package com.poyka.ripdpi.diagnostics.dpi

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.IOException

enum class DoqVerdict {
    DOQ_OK,
    DOQ_BLOCKED_QUIC,
    DOQ_DPI_REJECT,
    DOQ_INTEGRITY_DIVERGENT,
    DOQ_TIMEOUT,
}

data class DoqProvider(
    val name: String,
    val endpoint: String,
    val port: Int = DoqDefaultPort,
    val tlsServerName: String = endpoint,
)

data class DoqProbeResult(
    val provider: String,
    val endpoint: String,
    val domain: String,
    val verdict: DoqVerdict,
    val resolvedIps: List<String>,
    val latencyMs: Long?,
    val errorDetail: String? = null,
)

interface DoqQuicClient {
    suspend fun exchange(
        endpoint: String,
        port: Int,
        tlsServerName: String,
        query: ByteArray,
        timeoutMs: Long,
    ): ByteArray
}

class DoqQuicUnavailableException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

class DoqTlsRejectException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

object DoqWireCodec {
    fun buildQuery(domain: String): Pair<Int, ByteArray> {
        val transactionId = DnsWireBuilder.randomTransactionId()
        val query = DnsWireBuilder.buildQuery(domain, transactionId)
        val framed =
            ByteArrayOutputStream(query.size + DoqLengthPrefixBytes)
                .apply {
                    write((query.size shr Byte.SIZE_BITS) and ByteMask)
                    write(query.size and ByteMask)
                    write(query)
                }.toByteArray()
        return transactionId to framed
    }

    fun parseResponse(
        response: ByteArray,
        transactionId: Int,
    ): List<String> =
        when (val length = response.doqPayloadLengthOrNull()) {
            null -> {
                listOf(DnsWireBuilder.PARSE_ERR)
            }

            else -> {
                DnsWireBuilder.parseResponse(
                    response.copyOfRange(DoqLengthPrefixBytes, DoqLengthPrefixBytes + length),
                    transactionId,
                )
            }
        }

    private fun ByteArray.doqPayloadLengthOrNull(): Int? {
        if (size < DoqLengthPrefixBytes) return null
        val length = ((this[0].toInt() and ByteMask) shl Byte.SIZE_BITS) or (this[1].toInt() and ByteMask)
        return length.takeIf { it > 0 && size >= DoqLengthPrefixBytes + it }
    }

    private const val DoqLengthPrefixBytes = 2
    private const val ByteMask = 0xFF
}

class DoqIntegrityProbe(
    private val client: DoqQuicClient,
    private val providers: List<DoqProvider> = DefaultDoqProviders,
    private val timeoutMs: Long = DefaultDoqTimeoutMs,
    private val concurrency: Int = DefaultDoqConcurrency,
) {
    suspend fun run(
        domains: List<String>,
        dohResults: Map<String, List<String>>,
    ): List<DoqProbeResult> =
        coroutineScope {
            val semaphore = Semaphore(concurrency.coerceAtLeast(1))
            providers
                .flatMap { provider -> domains.map { domain -> provider to domain } }
                .map { (provider, domain) ->
                    async {
                        semaphore.withPermit {
                            probe(provider, domain, dohResults[domain].orEmpty().toSet())
                        }
                    }
                }.awaitAll()
        }

    private suspend fun probe(
        provider: DoqProvider,
        domain: String,
        dohIps: Set<String>,
    ): DoqProbeResult {
        val startedAt = System.currentTimeMillis()
        val (transactionId, query) = DoqWireCodec.buildQuery(domain)
        return try {
            val records =
                withTimeout(timeoutMs) {
                    val response =
                        client.exchange(
                            endpoint = provider.endpoint,
                            port = provider.port,
                            tlsServerName = provider.tlsServerName,
                            query = query,
                            timeoutMs = timeoutMs,
                        )
                    DoqWireCodec.parseResponse(response, transactionId)
                }
            val ips = records.filter(::isDoqIpv4Literal)
            val verdict =
                if (ips.isNotEmpty() && dohIps.isNotEmpty() && ips.intersect(dohIps).isEmpty()) {
                    DoqVerdict.DOQ_INTEGRITY_DIVERGENT
                } else {
                    DoqVerdict.DOQ_OK
                }
            provider.result(domain, verdict, ips, startedAt)
        } catch (error: TimeoutCancellationException) {
            provider.result(domain, DoqVerdict.DOQ_TIMEOUT, emptyList(), startedAt, error.message ?: "timeout")
        } catch (error: DoqTimeoutException) {
            provider.result(domain, DoqVerdict.DOQ_TIMEOUT, emptyList(), startedAt, error.message)
        } catch (error: DoqTlsRejectException) {
            provider.result(domain, DoqVerdict.DOQ_DPI_REJECT, emptyList(), startedAt, error.message)
        } catch (error: CancellationException) {
            throw error
        } catch (error: IOException) {
            provider.result(
                domain = domain,
                verdict = DoqVerdict.DOQ_BLOCKED_QUIC,
                resolvedIps = emptyList(),
                startedAt = startedAt,
                errorDetail = error.message ?: error.javaClass.simpleName,
            )
        }
    }

    private fun DoqProvider.result(
        domain: String,
        verdict: DoqVerdict,
        resolvedIps: List<String>,
        startedAt: Long,
        errorDetail: String? = null,
    ): DoqProbeResult =
        DoqProbeResult(
            provider = name,
            endpoint = endpoint,
            domain = domain,
            verdict = verdict,
            resolvedIps = resolvedIps,
            latencyMs = System.currentTimeMillis() - startedAt,
            errorDetail = errorDetail,
        )

    companion object {
        const val DefaultDoqPort = 853
        const val DefaultDoqTimeoutMs = 6_000L
        const val DefaultDoqConcurrency = 8
        val DefaultDoqProviders =
            listOf(
                DoqProvider("AdGuard", "dns.adguard-dns.com"),
                DoqProvider("Cloudflare", "1.1.1.1", tlsServerName = "cloudflare-dns.com"),
                DoqProvider("Google", "dns.google"),
                DoqProvider("NextDNS", "dns.nextdns.io"),
            )
    }
}

private fun isDoqIpv4Literal(value: String): Boolean {
    val octets = value.split('.')
    return octets.size == Ipv4Octets && octets.all { octet -> octet.toIntOrNull() in Ipv4OctetRange }
}

private const val DoqDefaultPort = 853
private const val Ipv4Octets = 4
private val Ipv4OctetRange = 0..255
