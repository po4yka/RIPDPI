package com.poyka.ripdpi.activities

import androidx.compose.runtime.Immutable
import com.poyka.ripdpi.diagnostics.ProbeResult
import com.poyka.ripdpi.diagnostics.TransferMeasurement
import com.poyka.ripdpi.diagnostics.TransferProgress
import com.poyka.ripdpi.diagnostics.parseTransferEvidence
import com.poyka.ripdpi.diagnostics.toConnectionStageScale
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList

@Immutable
data class DiagnosticsTransferUiModel(
    val target: String,
    val runs: ImmutableList<DiagnosticsTransferRunUiModel>,
    val networkScopeUnverified: Boolean = false,
    val connectionStages: DiagnosticsConnectionStageUiModel? = null,
)

@Immutable
data class DiagnosticsTransferRunUiModel(
    val runIndex: Int,
    val runCount: Int,
    val receivedBodyByteCount: Long,
    val expectedBodyByteCount: Long?,
    val elapsedMs: Long,
    val firstBodyByteMs: Long?,
    val lastBodyProgressMs: Long?,
    val terminationReason: String?,
    val responseComplete: Boolean,
    val windowComplete: Boolean,
    val samples: ImmutableList<DiagnosticsTransferSampleUiModel> = persistentListOf(),
)

@Immutable
data class DiagnosticsTransferSampleUiModel(
    val elapsedMs: Long,
    val bodyByteCount: Long,
)

internal fun TransferProgress.toTransferUiModel(): DiagnosticsTransferUiModel =
    DiagnosticsTransferUiModel(
        target = target,
        runs = persistentListOf(measurement.toTransferRunUiModel()),
        connectionStages = toConnectionStageScale().toConnectionStageUiModel(),
    )

internal fun ProbeResult.toTransferUiModel(): DiagnosticsTransferUiModel? =
    parseTransferEvidence(details)?.let { evidence ->
        DiagnosticsTransferUiModel(
            target = target,
            runs = evidence.runs.map { it.toTransferRunUiModel() }.toImmutableList(),
            networkScopeUnverified = details.any { it.key == "transferNetworkScope" && it.value == "unverified" },
        )
    }

private fun TransferMeasurement.toTransferRunUiModel(): DiagnosticsTransferRunUiModel =
    DiagnosticsTransferRunUiModel(
        runIndex = runIndex,
        runCount = runCount,
        receivedBodyByteCount = receivedBodyByteCount,
        expectedBodyByteCount = expectedBodyByteCount,
        elapsedMs = elapsedMs,
        firstBodyByteMs = firstBodyByteMs,
        lastBodyProgressMs = lastBodyProgressMs,
        terminationReason = terminationReason,
        responseComplete = responseComplete,
        windowComplete = windowComplete,
        samples = samples.map { DiagnosticsTransferSampleUiModel(it.elapsedMs, it.bodyByteCount) }.toImmutableList(),
    )
