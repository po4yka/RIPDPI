package com.poyka.ripdpi.activities

import androidx.compose.runtime.Immutable
import com.poyka.ripdpi.diagnostics.ConnectionLaneKind
import com.poyka.ripdpi.diagnostics.ConnectionStage
import com.poyka.ripdpi.diagnostics.ConnectionStageScale
import com.poyka.ripdpi.diagnostics.ConnectionStageState
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

@Immutable
data class DiagnosticsConnectionStageUiModel(
    val lanes: ImmutableList<DiagnosticsConnectionLaneUiModel>,
)

@Immutable
data class DiagnosticsConnectionLaneUiModel(
    val kind: ConnectionLaneKind,
    val attempt: Int?,
    val stages: ImmutableList<DiagnosticsConnectionMeasurementUiModel>,
    val networkScopeUnverified: Boolean,
)

@Immutable
data class DiagnosticsConnectionMeasurementUiModel(
    val stage: ConnectionStage,
    val state: ConnectionStageState,
    val durationMs: Long?,
    val elapsedMs: Long?,
    val byteCount: Long?,
    val httpStatusCode: Int?,
)

internal fun ConnectionStageScale.toConnectionStageUiModel(
    networkScopeUnverified: Boolean = false,
): DiagnosticsConnectionStageUiModel =
    DiagnosticsConnectionStageUiModel(
        lanes
            .map { lane ->
                DiagnosticsConnectionLaneUiModel(
                    lane.kind,
                    lane.attempt,
                    lane.stages
                        .map { stage ->
                            DiagnosticsConnectionMeasurementUiModel(
                                stage.stage,
                                stage.state,
                                stage.durationMs,
                                stage.elapsedMs,
                                stage.byteCount,
                                stage.httpStatusCode,
                            )
                        }.toImmutableList(),
                    lane.networkScopeUnverified || networkScopeUnverified,
                )
            }.toImmutableList(),
    )
