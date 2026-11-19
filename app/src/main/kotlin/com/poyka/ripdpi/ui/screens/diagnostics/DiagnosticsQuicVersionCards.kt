package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.poyka.ripdpi.R
import com.poyka.ripdpi.diagnostics.QuicVersionComparisonProbeType
import com.poyka.ripdpi.diagnostics.QuicVersionMatrixConfig
import com.poyka.ripdpi.diagnostics.QuicVersionMatrixEvidence
import com.poyka.ripdpi.diagnostics.QuicVersionResidualProbeType
import com.poyka.ripdpi.diagnostics.QuicVersionV1ProbeType
import com.poyka.ripdpi.diagnostics.QuicVersionV2ProbeType
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.RipDpiCardVariant
import com.poyka.ripdpi.ui.components.cards.SettingsRow
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextField
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldBehavior
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldDecoration
import com.poyka.ripdpi.ui.screens.diagnostics.state.DiagnosticsProbeResultUiModel
import com.poyka.ripdpi.ui.screens.diagnostics.state.QuicVersionRowUiModel
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

@Composable
internal fun QuicVersionInputCard(
    input: QuicVersionMatrixConfig,
    enabled: Boolean,
    onInputChanged: (QuicVersionMatrixConfig) -> Unit,
) {
    // Flag only once every required field has a value, so an empty form shows no error.
    val showError =
        listOf(input.host, input.controlSni, input.testSni).all { it.isNotEmpty() } && !input.isValid()
    RipDpiCard(
        variant = RipDpiCardVariant.Tonal,
        modifier = Modifier.ripDpiTestTag(RipDpiTestTags.DiagnosticsQuicVersionInput),
    ) {
        QuicVersionField(R.string.diagnostics_quic_version_host, input.host, enabled) {
            onInputChanged(input.copy(host = it))
        }
        QuicVersionField(R.string.diagnostics_quic_version_connect_ip, input.connectIp.orEmpty(), enabled) {
            onInputChanged(input.copy(connectIp = it.ifEmpty { null }))
        }
        QuicVersionField(R.string.diagnostics_quic_version_control_sni, input.controlSni, enabled) {
            onInputChanged(input.copy(controlSni = it))
        }
        QuicVersionField(
            R.string.diagnostics_quic_version_test_sni,
            input.testSni,
            enabled,
            errorText = if (showError) stringResource(R.string.diagnostics_quic_version_input_error) else null,
        ) { onInputChanged(input.copy(testSni = it)) }
        SettingsRow(
            title = stringResource(R.string.diagnostics_quic_version_residual),
            checked = input.residualVersion != null,
            onCheckedChange = { onInputChanged(input.copy(residualVersion = if (it) 1 else null)) },
            enabled = enabled,
        )
    }
}

@Composable
private fun QuicVersionField(
    @StringRes label: Int,
    value: String,
    enabled: Boolean,
    errorText: String? = null,
    onValueChange: (String) -> Unit,
) {
    val text = remember { LaggingInputText(value) }
    SideEffect { text.sync(value) }
    RipDpiTextField(
        value = text.text,
        onValueChange = { edited ->
            // Hosts, IPs and SNIs never contain whitespace.
            val trimmed = edited.trim()
            text.edit(trimmed)
            onValueChange(trimmed)
        },
        modifier = Modifier.fillMaxWidth(),
        decoration = RipDpiTextFieldDecoration(label = stringResource(label), errorText = errorText),
        behavior = RipDpiTextFieldBehavior(enabled = enabled, singleLine = true),
    )
}

/** QUIC version rows belong to [QuicVersionResultsCard]; generic probe lists skip them. */
internal fun DiagnosticsProbeResultUiModel.isQuicVersionProbe(): Boolean = quicVersion != null

@Composable
internal fun QuicVersionResultsCard(rows: List<QuicVersionRowUiModel>) {
    RipDpiCard(
        variant = RipDpiCardVariant.Tonal,
        modifier = Modifier.ripDpiTestTag(RipDpiTestTags.DiagnosticsQuicVersionResults),
    ) {
        QuicVersionHeading(stringResource(R.string.diagnostics_quic_version_title))
        rows.forEach { row -> QuicVersionText(quicVersionRowText(row)) }
        rows
            .flatMap { it.evidence.limits }
            .distinct()
            .mapNotNull(::quicVersionLimitResource)
            .forEach { QuicVersionText(stringResource(it)) }
    }
}

@Composable
private fun quicVersionRowText(row: QuicVersionRowUiModel): String {
    val evidence = row.evidence
    val status = stringResource(quicVersionStatusResource(row.probeType, evidence))
    val text =
        when (row.probeType) {
            QuicVersionV1ProbeType -> stringResource(R.string.diagnostics_quic_version_row, 1, status)
            QuicVersionV2ProbeType -> stringResource(R.string.diagnostics_quic_version_row, 2, status)
            else -> status
        }
    val reason = evidence.reason?.let(::quicVersionReasonResource)?.let { stringResource(it) }
    val stopped = evidence.stop?.let { stringResource(R.string.diagnostics_quic_version_stopped) }
    return listOfNotNull(text, reason, stopped).joinToString(" · ")
}

@StringRes
private fun quicVersionStatusResource(
    probeType: String,
    evidence: QuicVersionMatrixEvidence,
): Int =
    when (evidence.status) {
        "TEST_ANSWERED" -> {
            R.string.diagnostics_quic_version_test_answered
        }

        "TEST_NOT_ANSWERED" -> {
            R.string.diagnostics_quic_version_test_not_answered
        }

        "TEST_MIXED" -> {
            R.string.diagnostics_quic_version_test_mixed
        }

        "UNSUPPORTED_BY_ENDPOINT" -> {
            R.string.diagnostics_quic_version_unsupported_by_endpoint
        }

        "DIFFERENCE" -> {
            R.string.diagnostics_quic_version_difference
        }

        "NO_DIFFERENCE" -> {
            R.string.diagnostics_quic_version_no_difference
        }

        "SUSPECTED" -> {
            R.string.diagnostics_quic_version_residual_suspected
        }

        "NOT_OBSERVED" -> {
            R.string.diagnostics_quic_version_residual_not_observed
        }

        "UNSUPPORTED" -> {
            R.string.diagnostics_quic_version_unsupported
        }

        "CANCELLED" -> {
            R.string.diagnostics_quic_version_cancelled
        }

        else -> {
            when (probeType) {
                QuicVersionComparisonProbeType -> R.string.diagnostics_quic_version_difference_inconclusive
                QuicVersionResidualProbeType -> R.string.diagnostics_quic_version_residual_not_observed
                else -> R.string.diagnostics_quic_version_inconclusive
            }
        }
    }

@StringRes
private fun quicVersionReasonResource(reason: String): Int? =
    when (reason) {
        "proxy_path" -> R.string.diagnostics_quic_version_reason_direct_path
        "protect", "transport_error", "initial_builder" -> R.string.diagnostics_quic_version_reason_socket
        "dns_error" -> R.string.diagnostics_quic_version_reason_dns
        "invalid_config" -> R.string.diagnostics_quic_version_input_error
        else -> null
    }

@StringRes
private fun quicVersionLimitResource(limit: String): Int? =
    when (limit) {
        "initial_stage_only" -> R.string.diagnostics_quic_version_limit_initial_stage
        "local_port_separation_only" -> R.string.diagnostics_quic_version_limit_local_port
        "same_flow_or_endpoint_state_indistinguishable" -> R.string.diagnostics_quic_version_limit_residual
        "network_changed" -> R.string.diagnostics_quic_version_limit_network_changed
        else -> null
    }

@Composable
private fun QuicVersionHeading(text: String) {
    Text(
        text = text,
        modifier = Modifier.fillMaxWidth(),
        style = RipDpiThemeTokens.type.bodyEmphasis,
        color = RipDpiThemeTokens.colors.foreground,
    )
}

@Composable
private fun QuicVersionText(text: String) {
    Text(
        text = text,
        modifier = Modifier.fillMaxWidth(),
        style = RipDpiThemeTokens.type.secondaryBody,
        color = RipDpiThemeTokens.colors.mutedForeground,
    )
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun QuicVersionResultsPreview() {
    val limits = listOf("initial_stage_only", "local_port_separation_only")
    RipDpiComponentPreview {
        QuicVersionResultsCard(
            listOf(
                QuicVersionRowUiModel(
                    QuicVersionV1ProbeType,
                    QuicVersionMatrixEvidence("TEST_NOT_ANSWERED", "V1", limits = limits),
                ),
                QuicVersionRowUiModel(
                    QuicVersionV2ProbeType,
                    QuicVersionMatrixEvidence("TEST_ANSWERED", "V2", limits = limits),
                ),
                QuicVersionRowUiModel(
                    QuicVersionComparisonProbeType,
                    QuicVersionMatrixEvidence("DIFFERENCE", limits = limits),
                ),
            ),
        )
    }
}
