package com.poyka.ripdpi.activities

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

@Immutable
data class SelectiveMatrixTargetUiModel(
    val label: String,
    val url: String,
    val cohort: String,
    val infrastructure: String,
    val source: String,
    val sourceDate: String,
    val verifiedAt: String?,
)

@Immutable
data class SelectiveMatrixAttemptUiModel(
    val target: String,
    val cohort: String,
    val attempt: String,
    val stages: ImmutableList<DiagnosticsFieldUiModel>,
    val bodyBytes: String,
    val bodyComplete: Boolean,
    val httpCode: String,
    val failureStage: String,
    val elapsedMs: String,
    val infrastructure: String,
    val source: String,
    val sourceDate: String,
    val verifiedAt: String?,
)

internal fun DiagnosticsProbeResultUiModel.toSelectiveMatrixAttempt(): SelectiveMatrixAttemptUiModel? {
    if (probeType != "selective_availability") return null
    val values = details.associate { it.label to it.value }
    return SelectiveMatrixAttemptUiModel(
        target = target,
        cohort = values["cohort"].orEmpty(),
        attempt = values["attempt"].orEmpty(),
        stages =
            listOf("dns", "tcp", "tls", "http", "body")
                .map { stage ->
                    DiagnosticsFieldUiModel(stage, values["${stage}Status"] ?: "not_run")
                }.toImmutableList(),
        bodyBytes = values["bodyByteCount"] ?: "0",
        bodyComplete = values["bodyComplete"] == "true",
        httpCode = values["httpStatusCode"] ?: "—",
        failureStage = values["failureStage"].orEmpty().takeUnless { it.equals("none", ignoreCase = true) }.orEmpty(),
        elapsedMs = values["elapsedMs"] ?: "—",
        infrastructure = values["infrastructureGroup"].orEmpty(),
        source = values["sourceUrl"].orEmpty(),
        sourceDate = values["sourceDate"].orEmpty(),
        verifiedAt = values["lastVerifiedAt"].verifiedMatrixTimestamp(),
    )
}

internal fun String?.verifiedMatrixTimestamp(): String? =
    this?.takeUnless {
        it.isBlank() || it.equals("unknown", ignoreCase = true) || it.equals("null", ignoreCase = true)
    }
