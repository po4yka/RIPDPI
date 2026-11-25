package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.serialization.RipDpiTolerantEncodeDefaultsJson
import kotlinx.serialization.Serializable

const val QuicVersionMatrixProfileId = "quic-version-matrix"
const val QuicVersionEvidenceKey = "quicVersionEvidence"
const val QuicVersionMatrixPort = 443

const val QuicVersionMatrixProbeType = "quic_version_matrix"
const val QuicVersionV1ProbeType = "quic_version_v1"
const val QuicVersionV2ProbeType = "quic_version_v2"
const val QuicVersionComparisonProbeType = "quic_version_comparison"
const val QuicVersionResidualProbeType = "quic_version_residual"
val QuicVersionMatrixProbeTypes =
    setOf(
        QuicVersionMatrixProbeType,
        QuicVersionV1ProbeType,
        QuicVersionV2ProbeType,
        QuicVersionComparisonProbeType,
        QuicVersionResidualProbeType,
    )

@Serializable
data class QuicVersionMatrixConfig(
    val host: String = "",
    val connectIp: String? = null,
    val controlSni: String = "",
    val testSni: String = "",
    val residualVersion: Int? = null,
) {
    fun isValid(): Boolean =
        isHttp3Host(host) && (connectIp == null || isIpProbeV4(connectIp) || isIpProbeV6(connectIp)) &&
            isQuicSni(controlSni) && isQuicSni(testSni) && !controlSni.equals(testSni, ignoreCase = true) &&
            (residualVersion == null || residualVersion in 1..2)

    internal fun validate() {
        require(isValid()) { "QUIC version matrix needs an endpoint and two different DNS names" }
    }
}

private fun isQuicSni(value: String): Boolean = isHttp3Host(value) && !isIpProbeV4(value) && !isIpProbeV6(value)

@Serializable
data class QuicVersionMatrixEvidence(
    val status: String,
    val version: String? = null,
    val reason: String? = null,
    val stop: String? = null,
    val limits: List<String> = emptyList(),
    val cells: List<QuicVersionCell> = emptyList(),
    val residual: QuicResidualEvidence? = null,
)

@Serializable
data class QuicVersionCell(
    val sequence: Int,
    val repetition: Int,
    val sni: String,
    val stage: String,
    val bytesSent: Int = 0,
    val bytesReceived: Int = 0,
)

@Serializable
data class QuicResidualEvidence(
    val testStage: String,
    val controlStage: String,
)

fun parseQuicVersionMatrixEvidence(details: List<ProbeDetail>): QuicVersionMatrixEvidence? =
    parseQuicVersionMatrixEvidence(details.singleOrNull { it.key == QuicVersionEvidenceKey }?.value)

fun parseQuicVersionMatrixEvidence(value: String?): QuicVersionMatrixEvidence? =
    value?.takeIf { it.length <= QuicVersionMaxEvidenceCharacters }?.let {
        runCatching { QuicVersionJson.decodeFromString(QuicVersionMatrixEvidence.serializer(), it) }
            .getOrNull()
            ?.takeIf(QuicVersionMatrixEvidence::isConsistent)
    }

private fun QuicVersionMatrixEvidence.isConsistent(): Boolean =
    status in QuicVersionStatuses && (version == null || version in QuicVersions) &&
        (reason == null || reason in QuicVersionReasons) && (stop == null || stop in QuicVersionStops) &&
        limits.all { it in QuicVersionLimits } && cells.size <= QuicVersionMaxCells &&
        cells.all { it.sni in QuicVersionRoles && it.stage in QuicVersionStages && it.sequence >= 0 } &&
        (residual == null || (residual.testStage in QuicVersionStages && residual.controlStage in QuicVersionStages))

internal fun canonicalQuicVersionMatrixEvidence(value: String?): String? =
    parseQuicVersionMatrixEvidence(value)?.let {
        QuicVersionJson.encodeToString(QuicVersionMatrixEvidence.serializer(), it)
    }

internal fun quicVersionWithoutScopeAuthority(
    probeType: String,
    details: List<ProbeDetail>,
): Pair<String, List<ProbeDetail>>? {
    val outcome =
        when (probeType) {
            QuicVersionV1ProbeType, QuicVersionV2ProbeType -> "quic_version_inconclusive"
            QuicVersionComparisonProbeType -> "quic_version_difference_inconclusive"
            QuicVersionResidualProbeType -> "quic_version_residual_not_observed"
            else -> return null
        }
    val evidence =
        parseQuicVersionMatrixEvidence(details)?.let {
            it.copy(
                status = if (probeType == QuicVersionResidualProbeType) "NOT_OBSERVED" else "INCONCLUSIVE",
                limits = (it.limits + "network_changed").distinct(),
            )
        }
    return outcome to
        details.filterNot { it.key == QuicVersionEvidenceKey } +
        listOfNotNull(
            evidence?.let {
                ProbeDetail(
                    QuicVersionEvidenceKey,
                    QuicVersionJson.encodeToString(QuicVersionMatrixEvidence.serializer(), it),
                )
            },
        )
}

internal fun List<ProbeResult>.quicVersionMatrixSummaryLines(): List<String> {
    val rows = filter { it.probeType in QuicVersionMatrixProbeTypes }
    if (rows.isEmpty()) return emptyList()
    val parts =
        rows.mapNotNull { row ->
            parseQuicVersionMatrixEvidence(row.details)?.let {
                "${row.probeType.removePrefix("quic_version_")}=${it.status}"
            }
        }
    val limits = rows.flatMap { parseQuicVersionMatrixEvidence(it.details)?.limits.orEmpty() }.distinct()
    return listOf("quicVersion ${parts.joinToString("; ")}; limits=${limits.joinToString(",")}")
}

internal fun bucketQuicVersionMatrix(outcome: String): DiagnosticsOutcomeBucket =
    when (outcome) {
        "quic_version_test_answered" -> {
            DiagnosticsOutcomeBucket.Healthy
        }

        "quic_version_test_not_answered", "quic_version_difference_observed", "quic_version_residual_suspected" -> {
            DiagnosticsOutcomeBucket.Attention
        }

        else -> {
            DiagnosticsOutcomeBucket.Inconclusive
        }
    }

private val QuicVersionJson = RipDpiTolerantEncodeDefaultsJson
private const val QuicVersionMaxEvidenceCharacters = 4_096
private const val QuicVersionMaxCells = 16
private val QuicVersions = setOf("V1", "V2")
private val QuicVersionRoles = setOf("CONTROL", "TEST")
private val QuicVersionStages = setOf("VALID_INITIAL", "VERSION_NEGOTIATION_ONLY", "OTHER", "TIMEOUT")
private val QuicVersionStops = setOf("CANCELLED", "DEADLINE_EXCEEDED")
private val QuicVersionStatuses =
    setOf(
        "TEST_ANSWERED",
        "TEST_NOT_ANSWERED",
        "TEST_MIXED",
        "UNSUPPORTED_BY_ENDPOINT",
        "INCONCLUSIVE",
        "DIFFERENCE",
        "NO_DIFFERENCE",
        "SUSPECTED",
        "NOT_OBSERVED",
        "UNSUPPORTED",
        "CANCELLED",
    )
private val QuicVersionLimits =
    setOf(
        "initial_stage_only",
        "local_port_separation_only",
        "same_flow_or_endpoint_state_indistinguishable",
        "network_changed",
    )
private val QuicVersionReasons =
    setOf("proxy_path", "invalid_config", "cancelled", "dns_error", "protect", "initial_builder", "transport_error")
