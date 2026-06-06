package com.poyka.ripdpi.data

import java.util.Locale

data class RelayTrustDomain(
    val jurisdiction: String = "",
    val operatorName: String = "",
)

/**
 * Trust + latency caveat for a composed N-hop relay chain.
 *
 * [sharedJurisdiction] / [sharedOperatorName] surface the first pair of hops
 * that collapse the chain's anonymity by sharing a jurisdiction or operator —
 * a shared trust domain anywhere in the chain defeats the multi-hop guarantee,
 * so the detection scans every hop pair, not just entry↔exit.
 *
 * [missingEntryTrustDomain] / [missingExitTrustDomain] flag whether the first
 * and last hops carry a complete trust domain (kept for the existing two-hop
 * UI surface). [cumulativeLatencyHops] is the number of sequential hops the
 * traffic traverses — the chain's cumulative latency caveat: each added hop
 * compounds a full round-trip, so a 4-hop chain warns more loudly than a 2-hop
 * one. It is `0` when no warning is raised.
 */
data class RelayTrustDomainWarning(
    val sharedJurisdiction: String? = null,
    val sharedOperatorName: String? = null,
    val missingEntryTrustDomain: Boolean = false,
    val missingExitTrustDomain: Boolean = false,
    val cumulativeLatencyHops: Int = 0,
)

fun RelayProfileRecord.toRelayTrustDomain(): RelayTrustDomain =
    RelayTrustDomain(
        jurisdiction = jurisdiction,
        operatorName = operatorName,
    )

/**
 * Two-hop convenience overload kept for the legacy entry/exit call sites; it
 * delegates to the ordered N-hop [detectRelayChainTrustWarning] below.
 */
fun detectRelayChainTrustWarning(
    entry: RelayTrustDomain,
    exit: RelayTrustDomain,
): RelayTrustDomainWarning? = detectRelayChainTrustWarning(listOf(entry, exit))

/**
 * Resolve the trust + cumulative-latency caveat across an ordered hop list.
 *
 * Returns `null` only when the chain has at least two hops, no hop pair shares
 * a trust domain, and every hop carries a complete trust domain. Otherwise the
 * returned warning reports the first shared jurisdiction/operator found across
 * any hop pair, whether the entry (first) or exit (last) hop is missing a trust
 * domain, and the cumulative latency caveat (the hop count).
 */
fun detectRelayChainTrustWarning(hops: List<RelayTrustDomain>): RelayTrustDomainWarning? {
    val hasEnoughHops = hops.size >= 2
    val sharedJurisdiction = firstSharedTrustDomainValue(hops) { it.jurisdiction }
    val sharedOperator = firstSharedTrustDomainValue(hops) { it.operatorName }
    val missingEntryTrustDomain = hops.firstOrNull()?.hasIncompleteTrustDomain() == true
    val missingExitTrustDomain = hops.lastOrNull()?.hasIncompleteTrustDomain() == true
    val anyHopMissingTrustDomain = hops.any { it.hasIncompleteTrustDomain() }
    val hasSharedTrustDomain = sharedJurisdiction != null || sharedOperator != null
    val shouldWarn = hasEnoughHops && (hasSharedTrustDomain || anyHopMissingTrustDomain)
    return RelayTrustDomainWarning(
        sharedJurisdiction = sharedJurisdiction,
        sharedOperatorName = sharedOperator,
        missingEntryTrustDomain = missingEntryTrustDomain,
        missingExitTrustDomain = missingExitTrustDomain,
        cumulativeLatencyHops = hops.size,
    ).takeIf { shouldWarn }
}

/**
 * Return the first non-empty value that two distinct hops share (case- and
 * whitespace-insensitive), scanning every ordered hop pair. The returned value
 * preserves the earlier hop's original (trimmed) casing for display.
 */
private inline fun firstSharedTrustDomainValue(
    hops: List<RelayTrustDomain>,
    selector: (RelayTrustDomain) -> String,
): String? {
    for (i in hops.indices) {
        for (j in i + 1 until hops.size) {
            val shared = sharedTrustDomainValue(selector(hops[i]), selector(hops[j]))
            if (shared != null) {
                return shared
            }
        }
    }
    return null
}

private fun sharedTrustDomainValue(
    entryValue: String,
    exitValue: String,
): String? {
    val normalizedEntry = entryValue.normalizedTrustDomain()
    if (normalizedEntry.isEmpty() || normalizedEntry != exitValue.normalizedTrustDomain()) {
        return null
    }
    return entryValue.trim()
}

private fun RelayTrustDomain.hasIncompleteTrustDomain(): Boolean =
    jurisdiction.trim().isEmpty() || operatorName.trim().isEmpty()

private fun String.normalizedTrustDomain(): String = trim().lowercase(Locale.ROOT)
