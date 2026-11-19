package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.poyka.ripdpi.R
import com.poyka.ripdpi.diagnostics.TlsHopAttempt
import com.poyka.ripdpi.diagnostics.TlsHopComparisonConfig
import com.poyka.ripdpi.diagnostics.TlsHopComparisonEvidence
import com.poyka.ripdpi.diagnostics.TlsHopStatus
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.RipDpiCardVariant
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextField
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldBehavior
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldDecoration
import com.poyka.ripdpi.ui.screens.diagnostics.state.DiagnosticsProbeResultUiModel
import com.poyka.ripdpi.ui.screens.diagnostics.state.TlsHopResultUiModel
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import kotlinx.collections.immutable.persistentListOf

@Composable
internal fun TlsHopInputCard(
    input: TlsHopComparisonConfig,
    enabled: Boolean,
    onInputChanged: (TlsHopComparisonConfig) -> Unit,
) {
    // Flag only once every field has a value, so an empty form shows no error.
    val showError =
        listOf(input.host, input.controlSni, input.testSni).all { it.isNotEmpty() } && !input.isValid()
    RipDpiCard(
        variant = RipDpiCardVariant.Tonal,
        modifier = Modifier.ripDpiTestTag(RipDpiTestTags.DiagnosticsTlsHopInput),
    ) {
        TlsHopField(R.string.diagnostics_tls_hop_host, input.host, enabled) { onInputChanged(input.copy(host = it)) }
        TlsHopField(R.string.diagnostics_tls_hop_control_sni, input.controlSni, enabled) {
            onInputChanged(input.copy(controlSni = it))
        }
        TlsHopField(
            R.string.diagnostics_tls_hop_test_sni,
            input.testSni,
            enabled,
            errorText = if (showError) stringResource(R.string.diagnostics_tls_hop_input_error) else null,
        ) { onInputChanged(input.copy(testSni = it)) }
    }
}

@Composable
private fun TlsHopField(
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
            // Hosts and SNIs never contain whitespace.
            val trimmed = edited.trim()
            text.edit(trimmed)
            onValueChange(trimmed)
        },
        modifier = Modifier.fillMaxWidth(),
        decoration = RipDpiTextFieldDecoration(label = stringResource(label), errorText = errorText),
        behavior = RipDpiTextFieldBehavior(enabled = enabled, singleLine = true),
    )
}

/** TLS hop rows belong to [TlsHopResultsCard]; generic probe lists skip them. */
internal fun DiagnosticsProbeResultUiModel.isTlsHopProbe(): Boolean = tlsHop != null

@Composable
internal fun TlsHopResultsCard(result: TlsHopResultUiModel) {
    val evidence = result.evidence
    RipDpiCard(
        variant = RipDpiCardVariant.Tonal,
        modifier = Modifier.ripDpiTestTag(RipDpiTestTags.DiagnosticsTlsHopResults),
    ) {
        TlsHopHeading(stringResource(R.string.diagnostics_tls_hop_title))
        TlsHopHeading(tlsHopVerdict(evidence))
        if (evidence.status == TlsHopStatus.DIVERGENCE) {
            TlsHopText(stringResource(R.string.diagnostics_tls_hop_observation))
        }
        evidence.reason?.let(::tlsHopReasonResource)?.let { TlsHopText(stringResource(it)) }
        evidence.pathLength?.let { TlsHopText(stringResource(R.string.diagnostics_tls_hop_path_length, it)) }
        evidence.limits.mapNotNull(::tlsHopLimitResource).forEach { TlsHopText(stringResource(it)) }
        if (result.controlSni != null && result.testSni != null) {
            TlsHopText("${stringResource(R.string.diagnostics_tls_hop_control_sni)}: ${result.controlSni}")
            TlsHopText("${stringResource(R.string.diagnostics_tls_hop_test_sni)}: ${result.testSni}")
        }
        TlsHopText(
            stringResource(R.string.diagnostics_tls_hop_attempt_count, evidence.controlAttempts, evidence.testAttempts),
        )
        if (result.attempts.isNotEmpty()) {
            var expanded by rememberSaveable { mutableStateOf(false) }
            RipDpiButton(
                text =
                    if (expanded) {
                        stringResource(R.string.diagnostics_tls_hop_attempts_hide)
                    } else {
                        stringResource(R.string.diagnostics_tls_hop_attempts_show, result.attempts.size)
                    },
                onClick = { expanded = !expanded },
                variant = RipDpiButtonVariant.Secondary,
                wrapLabel = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (expanded) result.attempts.forEach { TlsHopText(tlsHopAttemptRow(it)) }
        }
    }
}

@Composable
private fun tlsHopVerdict(evidence: TlsHopComparisonEvidence): String =
    when (evidence.status) {
        TlsHopStatus.DIVERGENCE -> {
            stringResource(R.string.diagnostics_tls_hop_divergence, evidence.firstHop ?: 0, evidence.lastHop ?: 0)
        }

        TlsHopStatus.INCONCLUSIVE -> {
            stringResource(R.string.diagnostics_tls_hop_inconclusive)
        }

        TlsHopStatus.UNSUPPORTED -> {
            stringResource(R.string.diagnostics_tls_hop_unsupported)
        }

        TlsHopStatus.CANCELLED -> {
            stringResource(R.string.diagnostics_tls_hop_cancelled)
        }
    }

@Composable
private fun tlsHopAttemptRow(attempt: TlsHopAttempt): String =
    stringResource(
        R.string.diagnostics_tls_hop_attempt_row,
        attempt.ttl,
        stringResource(
            if (attempt.role ==
                "TEST"
            ) {
                R.string.diagnostics_tls_hop_test_sni
            } else {
                R.string.diagnostics_tls_hop_control_sni
            },
        ),
        tlsHopOutcomeResource(attempt.outcome)?.let { stringResource(it) } ?: attempt.outcome,
        attempt.elapsedMs,
    )

@StringRes
private fun tlsHopReasonResource(reason: String): Int? =
    when (reason) {
        "proxy_path" -> R.string.diagnostics_tls_hop_reason_direct_path
        "ttl_setter" -> R.string.diagnostics_tls_hop_reason_ttl
        "protect", "linger" -> R.string.diagnostics_tls_hop_reason_socket
        "dns_error" -> R.string.diagnostics_tls_hop_reason_dns
        "invalid_config", "invalid_sni" -> R.string.diagnostics_tls_hop_input_error
        else -> null
    }

@StringRes
private fun tlsHopLimitResource(limit: String): Int? =
    when (limit) {
        "control_never_reached_server" -> R.string.diagnostics_tls_hop_limit_control_never_reached_server
        "control_reacts_like_test" -> R.string.diagnostics_tls_hop_limit_control_reacts_like_test
        "server_hello_near_client" -> R.string.diagnostics_tls_hop_limit_server_hello_near_client
        "network_changed" -> R.string.diagnostics_tls_hop_limit_network_changed
        "inconsistent_repetitions" -> R.string.diagnostics_tls_hop_limit_inconsistent_repetitions
        else -> null
    }

@StringRes
private fun tlsHopOutcomeResource(outcome: String): Int? =
    when (outcome) {
        "RST" -> R.string.diagnostics_tls_hop_outcome_rst
        "FIN" -> R.string.diagnostics_tls_hop_outcome_fin
        "TLS_ALERT" -> R.string.diagnostics_tls_hop_outcome_tls_alert
        "SERVER_HELLO" -> R.string.diagnostics_tls_hop_outcome_server_hello
        "OTHER" -> R.string.diagnostics_tls_hop_outcome_other
        "SILENCE" -> R.string.diagnostics_tls_hop_outcome_silence
        "HANDSHAKE_FAILED" -> R.string.diagnostics_tls_hop_outcome_handshake_failed
        "LOCAL_ERROR" -> R.string.diagnostics_tls_hop_outcome_local_error
        else -> null
    }

@Composable
private fun TlsHopHeading(text: String) {
    Text(
        text = text,
        modifier = Modifier.fillMaxWidth(),
        style = RipDpiThemeTokens.type.bodyEmphasis,
        color = RipDpiThemeTokens.colors.foreground,
    )
}

@Composable
private fun TlsHopText(text: String) {
    Text(
        text = text,
        modifier = Modifier.fillMaxWidth(),
        style = RipDpiThemeTokens.type.secondaryBody,
        color = RipDpiThemeTokens.colors.mutedForeground,
    )
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun TlsHopResultsPreview() {
    RipDpiComponentPreview {
        TlsHopResultsCard(
            TlsHopResultUiModel(
                evidence =
                    TlsHopComparisonEvidence(
                        status = TlsHopStatus.DIVERGENCE,
                        firstHop = 5,
                        lastHop = 5,
                        pathLength = 9,
                        controlAttempts = 18,
                        testAttempts = 18,
                    ),
                controlSni = "control.example",
                testSni = "test.example",
                attempts = persistentListOf(TlsHopAttempt(5, "TEST", 0, "RST", 41)),
            ),
        )
    }
}
