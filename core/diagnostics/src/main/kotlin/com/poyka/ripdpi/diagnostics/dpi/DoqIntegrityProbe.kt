package com.poyka.ripdpi.diagnostics.dpi

import com.poyka.ripdpi.diagnostics.DnsResponseOutcome
import com.poyka.ripdpi.diagnostics.DnsResponseSemantics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.IOException

enum class DoqVerdict {
    DOQ_OK,
    DOQ_EXCHANGE_FAILED,
    DOQ_TLS_FAILED,
    DOQ_INTEGRITY_DIVERGENT,
    DOQ_TIMEOUT,
    DOQ_INTEGRITY_INCONCLUSIVE,
    DOQ_INTEGRITY_PARTIAL,
    DOQ_BASELINE_CONFLICT,
}

data class DoqProvider(
    val name: String,
    val endpoint: String,
    val port: Int = DoqDefaultPort,
    val tlsServerName: String = endpoint,
    val dohProfile: DohResolverProfile? = null,
)

data class DoqProbeResult(
    val provider: String,
    val endpoint: String,
    val domain: String,
    val verdict: DoqVerdict,
    val resolvedIps: List<String>,
    val latencyMs: Long?,
    val errorDetail: String? = null,
    val response: DnsResponseSemantics? = null,
    val failureKind: DoqFailureKind? = null,
    val baseline: List<DoqBaselineObservation> = emptyList(),
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
        query: ByteArray,
    ): DnsProbeAnswer {
        val length = response.doqPayloadLengthOrNull()
        if (length == null || query.doqPayloadLengthOrNull() == null) {
            return dnsFailure(DnsResponseOutcome.MALFORMED)
        }
        return DnsWireResponseParser.parse(
            response.copyOfRange(DoqLengthPrefixBytes, DoqLengthPrefixBytes + length),
            query.copyOfRange(DoqLengthPrefixBytes, query.size),
            allowQuestionlessError = true,
        )
    }

    private fun ByteArray.doqPayloadLengthOrNull(): Int? {
        if (size < DoqLengthPrefixBytes) return null
        val length = ((this[0].toInt() and ByteMask) shl Byte.SIZE_BITS) or (this[1].toInt() and ByteMask)
        return length.takeIf { it > 0 && size == DoqLengthPrefixBytes + it }
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
    internal fun expectedChecks(domains: List<String>): Int = providers.size * domains.size

    suspend fun run(
        domains: List<String>,
        dohResults: Map<String, List<DoqBaselineObservation>>,
    ): List<DoqProbeResult> =
        coroutineScope {
            val semaphore = Semaphore(concurrency.coerceAtLeast(1))
            providers
                .flatMap { provider -> domains.map { domain -> provider to domain } }
                .map { (provider, domain) ->
                    async {
                        semaphore.withPermit {
                            probe(provider, domain, dohResults[domain].orEmpty())
                        }
                    }
                }.awaitAll()
                .filterNotNull()
        }

    /** Returns null when [domain] is not a DNS name, so it has no DoQ query to send. */
    private suspend fun probe(
        provider: DoqProvider,
        domain: String,
        baseline: List<DoqBaselineObservation>,
    ): DoqProbeResult? {
        val startedAt = System.currentTimeMillis()
        val (_, query) =
            try {
                DoqWireCodec.buildQuery(domain)
            } catch (_: IllegalArgumentException) {
                return null
            }
        val evidence = baseline.map { it.forProvider(provider) }
        return try {
            val answer =
                withTimeoutOrNull(timeoutMs) {
                    val response =
                        client.exchange(
                            endpoint = provider.endpoint,
                            port = provider.port,
                            tlsServerName = provider.tlsServerName,
                            query = query,
                            timeoutMs = timeoutMs,
                        )
                    DoqWireCodec.parseResponse(response, query)
                } ?: return provider.result(
                    domain,
                    DoqVerdict.DOQ_TIMEOUT,
                    emptyList(),
                    startedAt,
                    failureKind = DoqFailureKind.TIMEOUT,
                    baseline = evidence,
                )
            val response = answer.response
            if (response?.outcome == DnsResponseOutcome.MALFORMED || response?.truncated == true) {
                return provider.result(
                    domain,
                    DoqVerdict.DOQ_EXCHANGE_FAILED,
                    emptyList(),
                    startedAt,
                    errorDetail =
                        if (response?.truncated ==
                            true
                        ) {
                            "Truncated DoQ response"
                        } else {
                            "Malformed DoQ response"
                        },
                    response = response,
                    failureKind = DoqFailureKind.WIRE,
                    baseline = evidence,
                )
            }
            val ips = answer.addresses.filter(::isDnsIpv4)
            val verdict = compareDoqAnswer(answer, evidence)
            provider.result(domain, verdict, ips, startedAt, response = response, baseline = evidence)
        } catch (error: DoqExchangeException) {
            val verdict =
                when (error.kind) {
                    DoqFailureKind.TIMEOUT -> DoqVerdict.DOQ_TIMEOUT
                    DoqFailureKind.TLS -> DoqVerdict.DOQ_TLS_FAILED
                    else -> DoqVerdict.DOQ_EXCHANGE_FAILED
                }
            provider.result(
                domain,
                verdict,
                emptyList(),
                startedAt,
                errorDetail = error.message,
                failureKind = error.kind,
                baseline = evidence,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: IOException) {
            provider.result(
                domain = domain,
                verdict = DoqVerdict.DOQ_EXCHANGE_FAILED,
                resolvedIps = emptyList(),
                startedAt = startedAt,
                errorDetail = error.message ?: error.javaClass.simpleName,
                baseline = evidence,
            )
        }
    }

    private fun DoqProvider.result(
        domain: String,
        verdict: DoqVerdict,
        resolvedIps: List<String>,
        startedAt: Long,
        errorDetail: String? = null,
        response: DnsResponseSemantics? = null,
        failureKind: DoqFailureKind? = null,
        baseline: List<DoqBaselineObservation> = emptyList(),
    ): DoqProbeResult =
        DoqProbeResult(
            provider = name,
            endpoint = endpoint,
            domain = domain,
            verdict = verdict,
            resolvedIps = resolvedIps,
            latencyMs = System.currentTimeMillis() - startedAt,
            errorDetail = errorDetail,
            response = response,
            failureKind = failureKind,
            baseline = baseline,
        )

    companion object {
        const val DefaultDoqPort = 853
        const val DefaultDoqTimeoutMs = 6_000L
        const val DefaultDoqConcurrency = 8
        val DefaultDoqProviders =
            listOf(
                DoqProvider("AdGuard", "dns.adguard-dns.com", dohProfile = DohResolverProfile.ADGUARD_DEFAULT),
                DoqProvider(
                    "Cloudflare",
                    "1.1.1.1",
                    tlsServerName = "cloudflare-dns.com",
                    dohProfile = DohResolverProfile.CLOUDFLARE_STANDARD,
                ),
                DoqProvider("Google", "dns.google", dohProfile = DohResolverProfile.GOOGLE_PUBLIC),
                DoqProvider("NextDNS", "dns.nextdns.io"),
            )
    }
}

private const val DoqDefaultPort = 853
