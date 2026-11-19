package com.poyka.ripdpi.ui.screens.history

import android.content.Intent
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.diagnostics.SelectiveMatrixComparison
import com.poyka.ripdpi.diagnostics.SelectiveMatrixLegSummary
import com.poyka.ripdpi.diagnostics.SelectiveMatrixPairMismatch
import com.poyka.ripdpi.diagnostics.SelectiveMatrixPairVerdict
import com.poyka.ripdpi.diagnostics.SelectiveMatrixTargetLegOutcome
import com.poyka.ripdpi.diagnostics.SelectiveMatrixTargetStage
import com.poyka.ripdpi.diagnostics.export.encodePairExport
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.RipDpiCardVariant
import com.poyka.ripdpi.ui.screens.diagnostics.MatrixHeading
import com.poyka.ripdpi.ui.screens.diagnostics.MatrixText
import com.poyka.ripdpi.ui.screens.diagnostics.stageLabel
import java.text.DateFormat
import java.util.Date

@Composable
internal fun SelectiveMatrixPairCard(
    pairing: SelectiveMatrixPairingUiModel,
    onCompare: (String) -> Unit,
) {
    RipDpiCard(variant = RipDpiCardVariant.Tonal) {
        MatrixHeading(stringResource(R.string.diagnostics_matrix_pair_title))
        if (pairing.candidates.isEmpty()) {
            MatrixText(stringResource(R.string.diagnostics_matrix_pair_no_candidates))
        }
        pairing.candidates.forEach { candidate ->
            RipDpiButton(
                text = "${transportLabel(candidate.transport)} · ${candidate.startedAtLabel}",
                onClick = { onCompare(candidate.sessionId) },
                variant =
                    if (candidate.sessionId == pairing.pairedSessionId) {
                        RipDpiButtonVariant.Primary
                    } else {
                        RipDpiButtonVariant.Secondary
                    },
                wrapLabel = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        when (val comparison = pairing.comparison) {
            null -> {}

            is SelectiveMatrixComparison.Unavailable -> {
                ComparisonUnavailable(comparison)
            }

            is SelectiveMatrixComparison.Compared -> {
                ComparisonResult(comparison, pairing.targetLabels)
            }
        }
        pairing.comparison?.let { comparison ->
            val context = LocalContext.current
            RipDpiButton(
                text = stringResource(R.string.diagnostics_matrix_pair_share),
                onClick = {
                    val send =
                        Intent(Intent.ACTION_SEND)
                            .setType("text/plain")
                            .putExtra(Intent.EXTRA_TEXT, comparison.encodePairExport())
                    context.startActivity(Intent.createChooser(send, null))
                },
                variant = RipDpiButtonVariant.Outline,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ComparisonUnavailable(comparison: SelectiveMatrixComparison.Unavailable) {
    MatrixHeading(stringResource(R.string.diagnostics_matrix_pair_unavailable))
    comparison.mismatches.forEach { MatrixText(stringResource(mismatchResource(it))) }
}

@Composable
private fun ComparisonResult(
    comparison: SelectiveMatrixComparison.Compared,
    targetLabels: Map<String, String>,
) {
    MatrixHeading(
        stringResource(
            R.string.diagnostics_matrix_pair_count,
            comparison.pathSpecificCount,
            comparison.comparableCount,
        ),
    )
    listOf(comparison.first, comparison.second).forEach { leg -> LegSummary(leg) }
    comparison.rows.forEach { row ->
        RipDpiCard(variant = RipDpiCardVariant.Tonal) {
            val target = targetLabels[row.targetId] ?: row.targetId
            MatrixHeading("$target · ${stringResource(verdictResource(row.verdict))}")
            LegOutcome(comparison.first.transport, row.first)
            LegOutcome(comparison.second.transport, row.second)
        }
    }
}

@Composable
private fun LegSummary(leg: SelectiveMatrixLegSummary) {
    val locales = LocalConfiguration.current.locales
    val startedAt = remember(locales) { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, locales[0]) }
    MatrixText("${transportLabel(leg.transport)} · ${startedAt.format(Date(leg.startedAt))}")
    if (!leg.valid) MatrixText(stringResource(R.string.diagnostics_matrix_pair_leg_invalid))
    if (!leg.controlsValid) MatrixText(stringResource(R.string.diagnostics_matrix_pair_leg_controls_failed))
}

@Composable
private fun LegOutcome(
    transport: String,
    outcome: SelectiveMatrixTargetLegOutcome,
) {
    val network = transportLabel(transport)
    MatrixText(
        when (outcome.stage) {
            SelectiveMatrixTargetStage.AVAILABLE -> {
                stringResource(
                    R.string.diagnostics_matrix_pair_leg_available,
                    network,
                    outcome.availableAttempts,
                    outcome.observedAttempts,
                )
            }

            SelectiveMatrixTargetStage.FAILED -> {
                stringResource(
                    R.string.diagnostics_matrix_pair_leg_failed,
                    network,
                    stageLabel(outcome.failureStage.orEmpty()),
                    outcome.availableAttempts,
                    outcome.observedAttempts,
                )
            }

            SelectiveMatrixTargetStage.UNMEASURED -> {
                stringResource(R.string.diagnostics_matrix_pair_leg_unmeasured, network, outcome.observedAttempts)
            }
        },
    )
}

@Composable
private fun transportLabel(transport: String?): String =
    when (transport) {
        "wifi" -> stringResource(R.string.remembered_networks_transport_wifi)
        "cellular" -> stringResource(R.string.remembered_networks_transport_cellular)
        else -> transport ?: stringResource(R.string.diagnostics_matrix_unknown)
    }

private fun mismatchResource(mismatch: SelectiveMatrixPairMismatch): Int =
    when (mismatch) {
        SelectiveMatrixPairMismatch.REVISIONS_MISSING -> R.string.diagnostics_matrix_pair_revisions_missing
        SelectiveMatrixPairMismatch.TARGET_SET -> R.string.diagnostics_matrix_pair_target_set
        SelectiveMatrixPairMismatch.PROBE_PARAMETERS -> R.string.diagnostics_matrix_pair_probe_parameters
        SelectiveMatrixPairMismatch.APP_CONFIGURATION -> R.string.diagnostics_matrix_pair_app_configuration
        SelectiveMatrixPairMismatch.TRANSPORT_UNKNOWN -> R.string.diagnostics_matrix_pair_transport_unknown
        SelectiveMatrixPairMismatch.SAME_TRANSPORT -> R.string.diagnostics_matrix_pair_same_transport
        SelectiveMatrixPairMismatch.AGE_LIMIT -> R.string.diagnostics_matrix_pair_age_limit
    }

private fun verdictResource(verdict: SelectiveMatrixPairVerdict): Int =
    when (verdict) {
        SelectiveMatrixPairVerdict.PATH_SPECIFIC -> R.string.diagnostics_matrix_pair_path_specific
        SelectiveMatrixPairVerdict.AVAILABLE_ON_BOTH -> R.string.diagnostics_matrix_pair_available_on_both
        SelectiveMatrixPairVerdict.CONTROL -> R.string.diagnostics_matrix_pair_control
        SelectiveMatrixPairVerdict.INCONCLUSIVE_LEG_INVALID -> R.string.diagnostics_matrix_pair_inconclusive_leg_invalid
        SelectiveMatrixPairVerdict.INCONCLUSIVE_CONTROL_FAILED -> R.string.diagnostics_matrix_pair_inconclusive_control
        SelectiveMatrixPairVerdict.INCONCLUSIVE_FAILED_ON_BOTH -> R.string.diagnostics_matrix_pair_inconclusive_both
        SelectiveMatrixPairVerdict.INCONCLUSIVE_UNMEASURED -> R.string.diagnostics_matrix_pair_inconclusive_unmeasured
    }
