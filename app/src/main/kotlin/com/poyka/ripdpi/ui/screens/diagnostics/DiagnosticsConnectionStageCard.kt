package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.DiagnosticsConnectionLaneUiModel
import com.poyka.ripdpi.activities.DiagnosticsConnectionMeasurementUiModel
import com.poyka.ripdpi.activities.DiagnosticsConnectionStageUiModel
import com.poyka.ripdpi.diagnostics.ConnectionLaneKind
import com.poyka.ripdpi.diagnostics.ConnectionStage
import com.poyka.ripdpi.diagnostics.ConnectionStageState
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

@Composable
internal fun DiagnosticsConnectionStageCard(
    scale: DiagnosticsConnectionStageUiModel,
    modifier: Modifier = Modifier,
    initiallyExpanded: Boolean = false,
) {
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    Column(
        modifier = modifier.fillMaxWidth().ripDpiTestTag(RipDpiTestTags.DiagnosticsConnectionStages),
        verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.sm),
    ) {
        RipDpiButton(
            text =
                stringResource(
                    if (expanded) R.string.diagnostics_stages_hide else R.string.diagnostics_stages_show,
                    scale.lanes.size,
                ),
            onClick = { expanded = !expanded },
            variant = RipDpiButtonVariant.Secondary,
            wrapLabel = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (expanded) {
            Column(
                modifier = Modifier.fillMaxWidth().ripDpiTestTag(RipDpiTestTags.DiagnosticsConnectionStageLanes),
                verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.md),
            ) {
                StageText(stringResource(R.string.diagnostics_stages_legend))
                scale.lanes.forEach { lane -> ConnectionLane(lane) }
            }
        }
    }
}

@Composable
private fun ConnectionLane(lane: DiagnosticsConnectionLaneUiModel) {
    Column(verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.xs)) {
        val name = stringResource(lane.kind.labelResource())
        StageText(
            lane.attempt?.let { stringResource(R.string.diagnostics_stages_attempt, name, it) } ?: name,
            emphasized = true,
        )
        if (lane.networkScopeUnverified) {
            StageText(stringResource(R.string.diagnostics_transfer_scope_unverified))
        }
        lane.stages.forEach { stage -> ConnectionMeasurement(stage) }
    }
}

@Composable
private fun ConnectionMeasurement(stage: DiagnosticsConnectionMeasurementUiModel) {
    Column(verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.xs)) {
        StageText(
            stringResource(
                R.string.diagnostics_stages_state,
                stringResource(stage.stage.labelResource()),
                stringResource(stage.state.labelResource()),
            ),
        )
        stage.durationMs?.let { StageText(stringResource(R.string.diagnostics_stages_duration, it)) }
        stage.elapsedMs?.let { StageText(stringResource(R.string.diagnostics_stages_elapsed, it)) }
        stage.byteCount?.let { StageText(stringResource(R.string.diagnostics_stages_bytes, it)) }
        stage.httpStatusCode?.let { StageText(stringResource(R.string.diagnostics_stages_http_status, it)) }
    }
}

@Composable
private fun StageText(
    text: String,
    emphasized: Boolean = false,
) {
    Text(
        text = text,
        modifier = Modifier.fillMaxWidth(),
        style = if (emphasized) RipDpiThemeTokens.type.bodyEmphasis else RipDpiThemeTokens.type.secondaryBody,
        color = RipDpiThemeTokens.colors.foreground,
    )
}

@StringRes
private fun ConnectionStage.labelResource(): Int =
    when (this) {
        ConnectionStage.DNS -> R.string.diagnostics_stages_dns
        ConnectionStage.TCP -> R.string.diagnostics_stages_tcp
        ConnectionStage.PROXY -> R.string.diagnostics_stages_proxy
        ConnectionStage.TLS -> R.string.diagnostics_stages_tls
        ConnectionStage.HTTP_HEADERS -> R.string.diagnostics_stages_headers
        ConnectionStage.FIRST_BODY_BYTE -> R.string.diagnostics_stages_first_byte
        ConnectionStage.BODY -> R.string.diagnostics_stages_body
        ConnectionStage.QUIC_RESPONSE -> R.string.diagnostics_stages_quic_response
    }

@StringRes
private fun ConnectionStageState.labelResource(): Int =
    when (this) {
        ConnectionStageState.SUCCEEDED -> R.string.diagnostics_stages_succeeded
        ConnectionStageState.OBSERVED -> R.string.diagnostics_stages_observed
        ConnectionStageState.FAILED -> R.string.diagnostics_stages_failed
        ConnectionStageState.PARTIAL -> R.string.diagnostics_stages_partial
        ConnectionStageState.RUNNING -> R.string.diagnostics_stages_running
        ConnectionStageState.NOT_REACHED -> R.string.diagnostics_stages_not_reached
        ConnectionStageState.NOT_APPLICABLE -> R.string.diagnostics_stages_not_applicable
        ConnectionStageState.UNKNOWN -> R.string.diagnostics_stages_unknown
        ConnectionStageState.CANCELLED -> R.string.diagnostics_stages_cancelled
        ConnectionStageState.TIMED_OUT -> R.string.diagnostics_stages_timed_out
    }

@StringRes
private fun ConnectionLaneKind.labelResource(): Int =
    when (this) {
        ConnectionLaneKind.MATRIX -> R.string.diagnostics_stages_lane_matrix
        ConnectionLaneKind.TRANSFER -> R.string.diagnostics_stages_lane_transfer
        ConnectionLaneKind.TLS13 -> R.string.diagnostics_stages_lane_tls13
        ConnectionLaneKind.TLS12 -> R.string.diagnostics_stages_lane_tls12
        ConnectionLaneKind.TLS_ECH -> R.string.diagnostics_stages_lane_ech
        ConnectionLaneKind.HTTP -> R.string.diagnostics_stages_lane_http
        ConnectionLaneKind.QUIC -> R.string.diagnostics_stages_lane_quic
        ConnectionLaneKind.DNS_SYSTEM -> R.string.diagnostics_stages_lane_dns_system
        ConnectionLaneKind.DNS_ENCRYPTED -> R.string.diagnostics_stages_lane_dns_encrypted
        ConnectionLaneKind.BOOTSTRAP -> R.string.diagnostics_stages_lane_bootstrap
        ConnectionLaneKind.MEDIA -> R.string.diagnostics_stages_lane_media
        ConnectionLaneKind.GATEWAY -> R.string.diagnostics_stages_lane_gateway
        ConnectionLaneKind.HANDSHAKE -> R.string.diagnostics_stages_lane_handshake
        ConnectionLaneKind.TCP -> R.string.diagnostics_stages_lane_tcp
    }
