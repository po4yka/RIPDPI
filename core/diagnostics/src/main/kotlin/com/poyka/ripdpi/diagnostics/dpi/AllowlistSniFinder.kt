package com.poyka.ripdpi.diagnostics.dpi

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

class AllowlistSniFinder(
    private val probe: Tcp16Probe,
    private val sniList: List<String>,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val batchSize: Int = DefaultBatchSize,
    private val maxCompatiblePerAsn: Int = DefaultMaxCompatiblePerAsn,
) {
    suspend fun find(results: List<Tcp16ProbeResult>): Map<String, AllowlistSniResult> =
        withContext(dispatcher) {
            val flaggedByAsn =
                results
                    .filter { result -> result.verdict == Tcp16Verdict.DETECTED_AT_KB }
                    .groupBy { result -> result.asn }
            flaggedByAsn
                .mapValues { (_, asnResults) ->
                    findForRepresentative(asnResults.first())
                }
        }

    private suspend fun findForRepresentative(result: Tcp16ProbeResult): AllowlistSniResult {
        val baseTarget = result.toTarget(sni = null)
        return try {
            val baseline = probe.runWithRttHint(baseTarget, hintRttMs = null)
            val hintRttMs = baseline.measuredRttMs ?: result.measuredRttMs
            val compatible = mutableListOf<CompatibleSni>()
            var triedCount = 0
            for (batch in sniList.withIndex().chunked(batchSize)) {
                val batchResults =
                    coroutineScope {
                        batch
                            .map { indexed ->
                                async {
                                    val sni = indexed.value
                                    val probeResult =
                                        probe.runWithRttHint(
                                            baseTarget.copy(
                                                id = "${baseTarget.id}:sni:${indexed.index + 1}",
                                                sni = sni,
                                            ),
                                            hintRttMs = hintRttMs,
                                        )
                                    indexed to probeResult
                                }
                            }.awaitAll()
                    }
                triedCount += batchResults.size
                compatible +=
                    batchResults
                        .filter { (_, probeResult) -> probeResult.verdict == Tcp16Verdict.OK }
                        .map { (indexed, _) -> CompatibleSni(indexed.value, indexed.index + 1) }
                if (compatible.size >= maxCompatiblePerAsn) {
                    break
                }
            }
            result.toAllowlistResult(
                compatibleSnis = compatible.take(maxCompatiblePerAsn),
                triedCount = triedCount,
                aborted = false,
            )
        } catch (cancellation: CancellationException) {
            result.toAllowlistResult(
                compatibleSnis = emptyList(),
                triedCount = 0,
                aborted = true,
                abortReason = cancellation.message ?: cancellation::class.java.simpleName,
            )
        }
    }

    private fun Tcp16ProbeResult.toTarget(sni: String?): Tcp16Target =
        Tcp16Target(
            id = targetId,
            asn = asn,
            provider = provider,
            ip = ip,
            port = port,
            sni = sni,
        )

    private fun Tcp16ProbeResult.toAllowlistResult(
        compatibleSnis: List<CompatibleSni>,
        triedCount: Int,
        aborted: Boolean,
        abortReason: String? = null,
    ): AllowlistSniResult =
        AllowlistSniResult(
            asn = asn,
            provider = provider,
            ip = ip,
            compatibleSnis = compatibleSnis,
            triedCount = triedCount,
            aborted = aborted,
            abortReason = abortReason,
        )

    private companion object {
        private const val DefaultBatchSize = 5
        private const val DefaultMaxCompatiblePerAsn = 3
    }
}
