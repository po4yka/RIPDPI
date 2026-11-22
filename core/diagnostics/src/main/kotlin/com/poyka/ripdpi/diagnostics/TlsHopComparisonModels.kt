package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.serialization.RipDpiTolerantEncodeDefaultsJson
import kotlinx.serialization.Serializable

const val TlsHopComparisonProfileId = "tls-hop-comparison"
const val TlsHopEvidenceKey = "tlsHopEvidence"
const val TlsHopComparisonPort = 443

/** Local-history detail with the SNIs and per-attempt timings. The ordinary export drops it. */
const val TlsHopLocalKey = "tlsHopLocal"

/** One user-selected endpoint (port 443) probed with a control SNI and a test SNI. */
@Serializable
data class TlsHopComparisonConfig(
    val host: String = "",
    val controlSni: String = "",
    val testSni: String = "",
) {
    fun isValid(): Boolean =
        isHttp3Host(host) && isTlsHopSni(controlSni) && isTlsHopSni(testSni) &&
            !controlSni.equals(testSni, ignoreCase = true)

    internal fun validate() {
        require(isValid()) { "TLS hop comparison needs an endpoint and two different DNS names" }
    }
}

// An IP literal sends no SNI, so both names must be DNS names.
private fun isTlsHopSni(value: String): Boolean = isHttp3Host(value) && !isIpProbeV4(value) && !isIpProbeV6(value)

@Serializable
enum class TlsHopStatus { DIVERGENCE, INCONCLUSIVE, UNSUPPORTED, CANCELLED }

@Serializable
data class TlsHopComparisonEvidence(
    val status: TlsHopStatus,
    val reason: String? = null,
    val firstHop: Int? = null,
    val lastHop: Int? = null,
    val pathLength: Int? = null,
    val limits: List<String> = emptyList(),
    val stop: String? = null,
    val controlAttempts: Int = 0,
    val testAttempts: Int = 0,
) {
    val controlValidated: Boolean?
        get() =
            when {
                stop != "COMPLETED" || reason != null || controlAttempts == 0 || "network_changed" in limits -> null
                status != TlsHopStatus.DIVERGENCE && status != TlsHopStatus.INCONCLUSIVE -> null
                pathLength != null -> true
                "control_never_reached_server" in limits -> false
                else -> null
            }
}

/** Local history only: the SNIs and per-attempt timings. */
@Serializable
data class TlsHopComparisonLocal(
    val controlSni: String = "",
    val testSni: String = "",
    val attempts: List<TlsHopAttempt> = emptyList(),
)

@Serializable
data class TlsHopAttempt(
    val ttl: Int,
    val role: String,
    val repetition: Int,
    val outcome: String,
    val elapsedMs: Long,
)

fun parseTlsHopComparisonLocal(details: List<ProbeDetail>): TlsHopComparisonLocal? =
    details
        .singleOrNull { it.key == TlsHopLocalKey }
        ?.value
        ?.takeIf { it.length <= TlsHopMaxLocalCharacters }
        ?.let { runCatching { TlsHopJson.decodeFromString(TlsHopComparisonLocal.serializer(), it) }.getOrNull() }

fun parseTlsHopComparisonEvidence(details: List<ProbeDetail>): TlsHopComparisonEvidence? =
    parseTlsHopComparisonEvidence(details.singleOrNull { it.key == TlsHopEvidenceKey }?.value)

fun parseTlsHopComparisonEvidence(value: String?): TlsHopComparisonEvidence? =
    value?.takeIf { it.length <= TlsHopMaxEvidenceCharacters }?.let {
        runCatching { TlsHopJson.decodeFromString(TlsHopComparisonEvidence.serializer(), it) }
            .getOrNull()
            ?.takeIf(TlsHopComparisonEvidence::isConsistent)
    }

private fun TlsHopComparisonEvidence.isConsistent(): Boolean {
    val hops = listOfNotNull(firstHop, lastHop, pathLength)
    val interval =
        firstHop != null && lastHop != null && pathLength != null && firstHop <= lastHop &&
            lastHop < pathLength
    return hops.all { it in 1..TlsHopMaxTtl } && limits.all { it in TlsHopLimits } &&
        controlAttempts in 0..TlsHopMaxAttempts && testAttempts in 0..TlsHopMaxAttempts &&
        (reason == null || reason in TlsHopReasons) &&
        if (status == TlsHopStatus.DIVERGENCE) interval else firstHop == null && lastHop == null
}

/** Ordinary export keeps the summary only; [TlsHopLocalKey] is dropped by the archive redactor. */
internal fun canonicalTlsHopComparisonEvidence(value: String?): String? =
    parseTlsHopComparisonEvidence(value)?.let {
        TlsHopJson.encodeToString(TlsHopComparisonEvidence.serializer(), it)
    }

/** A network-scope change withdraws the interval and keeps the attempts. */
internal fun List<ProbeDetail>.withoutTlsHopScopeAuthority(): List<ProbeDetail> {
    val evidence =
        parseTlsHopComparisonEvidence(this)?.let {
            it.copy(
                status = TlsHopStatus.INCONCLUSIVE,
                reason = "network_scope_unverified",
                firstHop = null,
                lastHop = null,
                pathLength = null,
                limits = (it.limits + "network_changed").distinct(),
            )
        }
    return filterNot { it.key == TlsHopEvidenceKey } +
        listOfNotNull(
            evidence?.let {
                ProbeDetail(TlsHopEvidenceKey, TlsHopJson.encodeToString(TlsHopComparisonEvidence.serializer(), it))
            },
        )
}

internal fun List<ProbeResult>.tlsHopComparisonSummaryLines(): List<String> =
    asSequence()
        .filter { it.probeType == TlsHopComparisonProbeType }
        .take(1)
        .mapNotNull { result ->
            parseTlsHopComparisonEvidence(result.details)?.let {
                "tlsHop status=${it.status}; firstHop=${it.firstHop ?: "none"}; lastHop=${it.lastHop ?: "none"}; " +
                    "pathLength=${it.pathLength ?: "unknown"}; limits=${it.limits.joinToString(",")}; " +
                    "controlAttempts=${it.controlAttempts}; testAttempts=${it.testAttempts}; " +
                    "controlStatus=${it.controlValidated.controlStatusText()}"
            }
        }.toList()

internal fun bucketTlsHopComparison(outcome: String): DiagnosticsOutcomeBucket =
    if (outcome == "tls_hop_divergence") DiagnosticsOutcomeBucket.Attention else DiagnosticsOutcomeBucket.Inconclusive

const val TlsHopComparisonProbeType = "tls_hop_comparison"
private val TlsHopJson = RipDpiTolerantEncodeDefaultsJson
private const val TlsHopMaxEvidenceCharacters = 4_096
private const val TlsHopMaxLocalCharacters = 32_768
private const val TlsHopMaxTtl = 32
private const val TlsHopMaxAttempts = 64
private val TlsHopLimits =
    setOf(
        "control_never_reached_server",
        "control_reacts_like_test",
        "server_hello_near_client",
        "network_changed",
        "inconsistent_repetitions",
    )
private val TlsHopReasons =
    setOf(
        "proxy_path",
        "invalid_config",
        "invalid_sni",
        "cancelled",
        "dns_error",
        "protect",
        "ttl_setter",
        "linger",
        "network_scope_unverified",
    )
