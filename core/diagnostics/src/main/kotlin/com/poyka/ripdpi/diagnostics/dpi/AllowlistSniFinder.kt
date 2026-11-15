package com.poyka.ripdpi.diagnostics.dpi

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class AllowlistSniFinder(
    private val probe: Tcp16Probe,
    private val sniList: List<String>,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun find(results: List<Tcp16ProbeResult>): Map<String, AllowlistSniResult> =
        withContext(dispatcher) {
            results
                .filter { it.verdict == Tcp16Verdict.PADDED_TRAIN_INTERRUPTED }
                .groupBy { it.asn }
                .mapValues { (_, peers) -> findForPeer(peers.firstOrNull { it.canCompareSni } ?: peers.first()) }
        }

    private suspend fun findForPeer(result: Tcp16ProbeResult): AllowlistSniResult {
        if (!result.canCompareSni) return result.finding(failure = AllowlistSniFailure.NO_TLS_BASELINE)

        val baseTarget =
            Tcp16Target(
                id = result.targetId,
                asn = result.asn,
                provider = result.provider,
                ip = result.ip,
                port = result.port,
                sni = result.tlsServerName,
                transport = result.transport,
                httpAuthority = result.httpAuthority,
            )
        val compatible = mutableListOf<CompatibleSni>()
        var triedCount = 0
        return withTimeoutOrNull(SearchBudgetMs) {
            val baseline = probe.runTarget(baseTarget, result.meanResponseMs)
            if (!baseline.canCompareSni) {
                return@withTimeoutOrNull result.finding(failure = AllowlistSniFailure.BASELINE_NOT_REPRODUCED)
            }

            for (batch in candidates(baseTarget.sni).chunked(BatchSize)) {
                val successful = probeBatch(baseTarget, batch, result.meanResponseMs)
                triedCount += batch.size
                compatible += successful
                if (compatible.size >= MaxCompatiblePerPeer) break
            }
            result.finding(compatible.take(MaxCompatiblePerPeer), triedCount)
        } ?: result.finding(compatible.take(MaxCompatiblePerPeer), triedCount, AllowlistSniFailure.SEARCH_TIMED_OUT)
    }

    private fun candidates(baselineName: String?): Sequence<IndexedValue<String>> =
        sniList
            .asSequence()
            .withIndex()
            .filter { it.value.isTcp16ServerName() && !it.value.equals(baselineName, ignoreCase = true) }
            .distinctBy { it.value.lowercase() }

    private suspend fun probeBatch(
        baseline: Tcp16Target,
        candidates: List<IndexedValue<String>>,
        responseTimeHintMs: Long?,
    ): List<CompatibleSni> =
        coroutineScope {
            candidates
                .map { (index, sni) ->
                    async {
                        val verified = probe.runTarget(baseline.copy(sni = sni), responseTimeHintMs)
                        if (verified.verdict == Tcp16Verdict.OK) CompatibleSni(sni, index + 1) else null
                    }
                }.awaitAll()
                .filterNotNull()
        }

    private fun Tcp16ProbeResult.finding(
        compatibleSnis: List<CompatibleSni> = emptyList(),
        triedCount: Int = 0,
        failure: AllowlistSniFailure? = null,
    ): AllowlistSniResult =
        AllowlistSniResult(
            asn = asn,
            provider = provider,
            ip = ip,
            port = port,
            compatibleSnis = compatibleSnis,
            triedCount = triedCount,
            failure = failure,
        )

    private companion object {
        private const val BatchSize = 5
        private const val MaxCompatiblePerPeer = 3
        private const val SearchBudgetMs = 60_000L
    }
}
