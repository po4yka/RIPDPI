package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.diagnostics.export.redactDiagnosticsArchiveText

internal fun bucketSelectiveMatrix(outcome: String): DiagnosticsOutcomeBucket =
    when (outcome) {
        "matrix_available", "matrix_target_available" -> DiagnosticsOutcomeBucket.Healthy
        "matrix_selective", "matrix_mixed", "matrix_target_transport_failed" -> DiagnosticsOutcomeBucket.Attention
        "matrix_unavailable" -> DiagnosticsOutcomeBucket.Failed
        else -> DiagnosticsOutcomeBucket.Inconclusive
    }

/** Target identifiers stay opaque; archive text redaction removes user hostnames and source URLs. */
internal fun List<ProbeResult>.selectiveMatrixSummaryLines(): List<String> =
    filter { it.probeType in MatrixProbeTypes }.take(MatrixMaximumResultRows).flatMap { result ->
        buildList {
            val details = result.details.associate { it.key to it.value }
            add("${result.probeType}=${result.outcome}")
            if (result.probeType == "selective_availability_summary") {
                add("scope=Observed HTTPS availability only; provider cause and remote endpoint health are unknown.")
            }
            MatrixSummaryFields.forEach { key -> details[key]?.let { add("$key=$it") } }
        }.map(::redactDiagnosticsArchiveText)
    }

private const val MatrixMaximumResultRows = 31
private val MatrixProbeTypes = setOf("selective_availability", "selective_availability_summary")
private val MatrixSummaryFields =
    listOf(
        "targetId",
        "cohort",
        "infrastructureGroup",
        "attempt",
        "catalogVersion",
        "sourceUrl",
        "sourceDate",
        "lastVerifiedAt",
        "dnsStatus",
        "tcpStatus",
        "tlsStatus",
        "httpStatus",
        "httpStatusCode",
        "bodyStatus",
        "bodyByteCount",
        "bodyComplete",
        "failureStage",
        "elapsedMs",
        "completedAttempts",
        "expectedAttempts",
        "reason",
    )
