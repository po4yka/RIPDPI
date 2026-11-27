package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.diagnostics.export.redactDiagnosticsArchiveText

internal fun bucketSelectiveMatrix(outcome: String): DiagnosticsOutcomeBucket =
    when (outcome) {
        "matrix_available", "matrix_target_available" -> DiagnosticsOutcomeBucket.Healthy
        "matrix_selective", "matrix_mixed", "matrix_target_transport_failed" -> DiagnosticsOutcomeBucket.Attention
        "matrix_unavailable" -> DiagnosticsOutcomeBucket.Failed
        else -> DiagnosticsOutcomeBucket.Inconclusive
    }

internal fun List<ProbeResult>.selectiveMatrixSummaryLines(): List<String> =
    matrixResultLines() + targetGroupSummaryLines()

internal fun List<Diagnosis>.mobileAllowlistSummaryLines(): List<String> =
    filter { it.code == MobileAllowlistDiagnosis }
        .flatMap { diagnosis ->
            diagnosis.evidence.map { "mobileAllowlist $it" } +
                listOfNotNull(diagnosis.recommendation?.let { "mobileAllowlist reason=$it" })
        }.map(::redactDiagnosticsArchiveText)

const val MobileAllowlistDiagnosis = "mobile_allowlist_mode"

private fun List<ProbeResult>.matrixResultLines(): List<String> =
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
        "targetClass",
        "destinationAsn",
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
        "repetitionsPerTarget",
        "targetStability",
        "unstableTargets",
        "reason",
    )
