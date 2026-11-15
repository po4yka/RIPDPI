package com.poyka.ripdpi.ui.screens.diagnostics.state

import androidx.compose.runtime.Immutable
import com.poyka.ripdpi.diagnostics.ProbeResult
import com.poyka.ripdpi.diagnostics.TransferMeasurement
import com.poyka.ripdpi.diagnostics.TransferProgress
import com.poyka.ripdpi.diagnostics.TransferWindowReadings
import com.poyka.ripdpi.diagnostics.parseTransferEvidence
import com.poyka.ripdpi.diagnostics.parseTransferWindowReadings
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
    val comparisonUnavailable: Boolean = false,
    val windowReadings: TransferWindowReadings? = null,
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

internal fun ProbeResult.toTransferUiModel(
    reportResults: List<ProbeResult> = emptyList(),
    reportScopeUnverified: Boolean = false,
): DiagnosticsTransferUiModel? {
    val evidence = parseTransferEvidence(details)
    val runs = evidence?.runs.orEmpty()
    val windows = parseTransferWindowReadings(details, runs)
    if (evidence == null && windows == null) return null
    val scope = comparisonScope(windows).takeUnless { reportScopeUnverified }
    return DiagnosticsTransferUiModel(
        target = target,
        runs = runs.map { it.toTransferRunUiModel() }.toImmutableList(),
        networkScopeUnverified =
            reportScopeUnverified || details.any { it.key == "transferNetworkScope" && it.value == "unverified" },
        comparisonUnavailable =
            details.any { it.key == "isControl" && it.value == "true" } &&
                (
                    scope == null ||
                        reportResults.none {
                            it.details.any { detail -> detail.key == "isControl" && detail.value == "false" } &&
                                scope == it.comparisonScope(parseTransferWindowReadings(it.details))
                        }
                ),
        windowReadings = windows,
    )
}

private data class TransferComparisonScope(
    val windowBytes: Long,
    val scheme: String,
    val addressFamily: String,
)

private fun ProbeResult.comparisonScope(windows: TransferWindowReadings?): TransferComparisonScope? {
    if (probeType != "throughput_window" || outcome != "throughput_measured" ||
        windows == null || windows.plannedRuns < 2 || windows.acceptedRuns != windows.plannedRuns ||
        windows.bodyGoodputRatesBps.any { it == null } ||
        details.any { it.key == "transferNetworkScope" && it.value == "unverified" }
    ) {
        return null
    }

    fun value(key: String): String? = details.singleOrNull { it.key == key }?.value
    val window = value("windowBytes")?.toLongOrNull()?.takeIf { it > 0 } ?: return null
    val scheme = value("url")?.substringBefore("://", "")?.takeIf { it == "http" || it == "https" } ?: return null
    val families = value("addressFamilyReadings")?.split('|').orEmpty()
    val family = families.firstOrNull()?.takeIf { it == "ipv4" || it == "ipv6" } ?: return null
    return TransferComparisonScope(window, scheme, family)
        .takeIf { families.size == windows.plannedRuns && families.all { it == family } }
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
