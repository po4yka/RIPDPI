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
import java.io.IOException
import kotlin.time.TimeMark
import kotlin.time.TimeSource

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
        return transactionId to requireNotNull(query.frameDoqMessage())
    }

    fun parseResponse(
        response: ByteArray,
        query: ByteArray,
    ): DnsProbeAnswer {
        val wireQuery = query.unframeDoqMessage() ?: return dnsFailure(DnsResponseOutcome.MALFORMED)
        return response.unframeDoqMessage()?.let { wireResponse ->
            DnsWireResponseParser.parse(wireResponse, wireQuery, allowQuestionlessError = true)
        } ?: dnsFailure(DnsResponseOutcome.MALFORMED)
    }
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
            val work =
                providers
                    .asSequence()
                    .flatMap { provider -> domains.asSequence().map { domain -> provider to domain } }
            val results = mutableListOf<DoqProbeResult>()
            for (batch in work.chunked(concurrency.coerceIn(1, DefaultDoqConcurrency))) {
                results +=
                    batch
                        .map { (provider, domain) ->
                            async {
                                activeExchanges.withPermit {
                                    probe(provider, domain, dohResults[domain].orEmpty())
                                }
                            }
                        }.awaitAll()
                        .filterNotNull()
            }
            results
        }

    private suspend fun probe(
        provider: DoqProvider,
        domain: String,
        baseline: List<DoqBaselineObservation>,
    ): DoqProbeResult? {
        val startedAt = TimeSource.Monotonic.markNow()
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
                }
            provider.resultFromAnswer(domain, answer, startedAt, evidence)
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

    private fun DoqProvider.resultFromAnswer(
        domain: String,
        answer: DnsProbeAnswer?,
        startedAt: TimeMark,
        evidence: List<DoqBaselineObservation>,
    ): DoqProbeResult {
        val response = answer?.response
        return when {
            answer == null -> {
                result(
                    domain,
                    DoqVerdict.DOQ_TIMEOUT,
                    emptyList(),
                    startedAt,
                    failureKind = DoqFailureKind.TIMEOUT,
                    baseline = evidence,
                )
            }

            response?.outcome == DnsResponseOutcome.MALFORMED || response?.truncated == true -> {
                val detail = if (response.truncated == true) "Truncated DoQ response" else "Malformed DoQ response"
                result(
                    domain,
                    DoqVerdict.DOQ_EXCHANGE_FAILED,
                    emptyList(),
                    startedAt,
                    errorDetail = detail,
                    response = response,
                    failureKind = DoqFailureKind.WIRE,
                    baseline = evidence,
                )
            }

            else -> {
                val ips = answer.addresses.filter(::isDnsIpv4)
                val verdict = compareDoqAnswer(answer, evidence)
                result(
                    domain,
                    verdict,
                    ips,
                    startedAt,
                    response = response,
                    baseline = evidence,
                )
            }
        }
    }

    private fun DoqProvider.result(
        domain: String,
        verdict: DoqVerdict,
        resolvedIps: List<String>,
        startedAt: TimeMark,
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
            latencyMs = startedAt.elapsedNow().inWholeMilliseconds,
            errorDetail = errorDetail,
            response = response,
            failureKind = failureKind,
            baseline = baseline,
        )

    companion object {
        const val DefaultDoqPort = 853
        const val DefaultDoqTimeoutMs = 6_000L
        const val DefaultDoqConcurrency = 8
        private val activeExchanges = Semaphore(DefaultDoqConcurrency)
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
