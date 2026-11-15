package com.poyka.ripdpi.diagnostics.dpi

import com.poyka.ripdpi.diagnostics.DnsResponseOutcome

enum class DoqBaselineLane {
    JSON,
    WIRE,
}

data class DoqBaselineObservation(
    val lane: DoqBaselineLane,
    val answer: DnsProbeAnswer,
    val sameResolverPolicy: Boolean = false,
) {
    val isUsableForComparison: Boolean
        get() = sameResolverPolicy && answer.comparableDnsAnswer() != null

    internal fun forProvider(provider: DoqProvider): DoqBaselineObservation =
        copy(
            sameResolverPolicy =
                answer.dohProfile != null && answer.dohProfile == provider.dohProfile &&
                    answer.resolver.equals(provider.tlsServerName, ignoreCase = true),
        )
}

internal fun compareDoqAnswer(
    answer: DnsProbeAnswer,
    baseline: List<DoqBaselineObservation>,
): DoqVerdict {
    val observed = answer.comparableDnsAnswer() ?: return DoqVerdict.DOQ_INTEGRITY_INCONCLUSIVE
    val usable = baseline.filter { it.sameResolverPolicy }.mapNotNull { it.answer.comparableDnsAnswer() }.distinct()
    val reference = usable.firstOrNull()
    return when {
        reference == null -> DoqVerdict.DOQ_INTEGRITY_INCONCLUSIVE
        usable.size > 1 -> DoqVerdict.DOQ_BASELINE_CONFLICT
        observed == reference && observed.outcome == DnsResponseOutcome.ANSWER -> DoqVerdict.DOQ_OK
        observed == reference -> DoqVerdict.DOQ_INTEGRITY_INCONCLUSIVE
        dnsAnswersOverlap(observed.ips, reference.ips) -> DoqVerdict.DOQ_INTEGRITY_PARTIAL
        else -> DoqVerdict.DOQ_INTEGRITY_DIVERGENT
    }
}

private fun DnsProbeAnswer.comparableDnsAnswer(): ComparableDnsAnswer? {
    val semantics = response?.takeIf { it.queryType == "A" && it.truncated != true } ?: return null
    val ips = addresses.filter(::isDnsIpv4).toSet()
    return when {
        semantics.outcome == DnsResponseOutcome.ANSWER && ips.isNotEmpty() -> {
            ComparableDnsAnswer(
                semantics.outcome,
                ips,
            )
        }

        semantics.outcome.isNegativeDnsAnswer() && ips.isEmpty() -> {
            ComparableDnsAnswer(semantics.outcome, ips)
        }

        else -> {
            null
        }
    }
}

private data class ComparableDnsAnswer(
    val outcome: DnsResponseOutcome,
    val ips: Set<String>,
)
