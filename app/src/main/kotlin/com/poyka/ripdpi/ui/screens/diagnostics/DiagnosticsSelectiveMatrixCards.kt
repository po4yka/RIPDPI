package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.DiagnosticsFieldUiModel
import com.poyka.ripdpi.activities.DiagnosticsProbeResultUiModel
import com.poyka.ripdpi.activities.DiagnosticsTone
import com.poyka.ripdpi.activities.SelectiveMatrixAttemptUiModel
import com.poyka.ripdpi.activities.SelectiveMatrixTargetUiModel
import com.poyka.ripdpi.activities.toSelectiveMatrixAttempt
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.RipDpiCardVariant
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextField
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldBehavior
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldDecoration
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import kotlinx.collections.immutable.toImmutableList

@Composable
internal fun SelectiveMatrixInputCard(
    input: String,
    targets: List<SelectiveMatrixTargetUiModel>,
    valid: Boolean,
    enabled: Boolean,
    onInputChanged: (String) -> Unit,
) {
    RipDpiCard(modifier = Modifier.ripDpiTestTag(RipDpiTestTags.DiagnosticsSelectiveMatrixInput)) {
        MatrixHeading(stringResource(R.string.diagnostics_matrix_title))
        MatrixText(stringResource(R.string.diagnostics_matrix_manual))
        RipDpiTextField(
            value = input,
            onValueChange = onInputChanged,
            modifier = Modifier.fillMaxWidth(),
            decoration =
                RipDpiTextFieldDecoration(
                    label = stringResource(R.string.diagnostics_matrix_hosts),
                    helperText = stringResource(R.string.diagnostics_matrix_hosts_hint),
                    errorText = if (valid) null else stringResource(R.string.diagnostics_matrix_hosts_error),
                    testTag = RipDpiTestTags.DiagnosticsSelectiveMatrixHosts,
                ),
            behavior = RipDpiTextFieldBehavior(enabled = enabled, singleLine = false),
        )
        var catalogExpanded by rememberSaveable { mutableStateOf(false) }
        RipDpiButton(
            text =
                stringResource(
                    if (catalogExpanded) {
                        R.string.diagnostics_matrix_catalog_hide
                    } else {
                        R.string.diagnostics_matrix_catalog_show
                    },
                    targets.size,
                ),
            onClick = { catalogExpanded = !catalogExpanded },
            variant = RipDpiButtonVariant.Secondary,
            wrapLabel = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (catalogExpanded) {
            targets.forEach { target ->
                Column(verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.xs)) {
                    MatrixHeading("${cohortLabel(target.cohort)} · ${target.label}")
                    MatrixText(target.url)
                    MatrixText(stringResource(R.string.diagnostics_matrix_infrastructure, target.infrastructure))
                    MatrixProvenance(target.source, target.sourceDate, target.verifiedAt)
                }
            }
        }
    }
}

@Composable
internal fun SelectiveMatrixResultsCard(results: List<DiagnosticsProbeResultUiModel>) {
    val attempts = remember(results) { results.mapNotNull { it.toSelectiveMatrixAttempt() } }
    val summary = results.lastOrNull { it.probeType == "selective_availability_summary" }
    val coverage = summary?.details?.associate { it.label to it.value }.orEmpty()
    val groups = remember(attempts) { attempts.groupBy { it.cohort } }
    RipDpiCard(modifier = Modifier.ripDpiTestTag(RipDpiTestTags.DiagnosticsSelectiveMatrixResults)) {
        MatrixHeading(stringResource(R.string.diagnostics_matrix_title))
        MatrixHeading(stringResource(matrixConclusionResource(summary?.outcome)))
        MatrixText(stringResource(R.string.diagnostics_matrix_caution))
        if (coverage["reason"] == "network_scope_unverified") {
            MatrixText(stringResource(R.string.diagnostics_matrix_scope_unverified))
        }
        MatrixText(
            stringResource(
                R.string.diagnostics_matrix_attempt_count,
                coverage["completedAttempts"] ?: attempts.size.toString(),
                coverage["expectedAttempts"] ?: "—",
            ),
        )
        coverage["catalogVersion"]?.let { version ->
            MatrixText(stringResource(R.string.diagnostics_matrix_catalog, version))
        }
        groups.forEach { (cohort, rows) ->
            MatrixHeading(cohortLabel(cohort))
            MatrixText(
                stringResource(
                    R.string.diagnostics_matrix_cohort_coverage,
                    rows.count {
                        it.bodyComplete
                    },
                    rows.size,
                    rows.map { it.target }.distinct().size,
                ),
            )
            rows.groupBy { it.target }.forEach { (target, attempts) -> MatrixTargetResults(target, attempts) }
        }
    }
}

@Composable
private fun MatrixTargetResults(
    target: String,
    attempts: List<SelectiveMatrixAttemptUiModel>,
) {
    var expanded by rememberSaveable(target) { mutableStateOf(false) }
    RipDpiCard(variant = RipDpiCardVariant.Tonal) {
        MatrixHeading(target)
        MatrixText(
            stringResource(
                R.string.diagnostics_matrix_cohort_coverage,
                attempts.count {
                    it.bodyComplete
                },
                attempts.size,
                1,
            ),
        )
        RipDpiButton(
            text =
                stringResource(
                    if (expanded) {
                        R.string.diagnostics_matrix_attempts_hide
                    } else {
                        R.string.diagnostics_matrix_attempts_show
                    },
                    attempts.size,
                ),
            onClick = { expanded = !expanded },
            variant = RipDpiButtonVariant.Secondary,
            wrapLabel = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (expanded) attempts.forEach { MatrixAttemptCard(it) }
    }
}

@Composable
private fun MatrixAttemptCard(attempt: SelectiveMatrixAttemptUiModel) {
    RipDpiCard(variant = RipDpiCardVariant.Tonal) {
        MatrixHeading(attempt.target)
        MatrixText(stringResource(R.string.diagnostics_matrix_attempt, attempt.attempt, attempt.elapsedMs))
        // Vertical stage rows keep status text readable with large fonts and RTL layouts.
        attempt.stages.forEach { stage ->
            MatrixText("${stageLabel(stage.label)}: ${stageStatus(stage.value)}")
        }
        MatrixText(stringResource(R.string.diagnostics_matrix_http_code, attempt.httpCode))
        MatrixText(
            stringResource(
                if (attempt.bodyComplete) {
                    R.string.diagnostics_matrix_body_complete
                } else {
                    R.string.diagnostics_matrix_body_partial
                },
                attempt.bodyBytes,
            ),
        )
        if (attempt.failureStage.isNotBlank()) {
            MatrixText(stringResource(R.string.diagnostics_matrix_failure_stage, stageLabel(attempt.failureStage)))
        }
        MatrixText(stringResource(R.string.diagnostics_matrix_infrastructure, attempt.infrastructure))
        MatrixProvenance(attempt.source, attempt.sourceDate, attempt.verifiedAt)
    }
}

@Composable
private fun MatrixProvenance(
    source: String,
    date: String,
    verifiedAt: String?,
) {
    if (source.isNotBlank()) {
        MatrixText(stringResource(R.string.diagnostics_matrix_source, source, date))
    }
    MatrixText(
        if (verifiedAt.isNullOrBlank()) {
            stringResource(R.string.diagnostics_matrix_not_verified)
        } else {
            stringResource(R.string.diagnostics_matrix_verified, verifiedAt)
        },
    )
}

internal fun matrixConclusionResource(outcome: String?): Int =
    when (outcome) {
        "matrix_available" -> R.string.diagnostics_matrix_available
        "matrix_selective" -> R.string.diagnostics_matrix_selective
        "matrix_unavailable" -> R.string.diagnostics_matrix_unavailable
        "matrix_mixed" -> R.string.diagnostics_matrix_mixed
        else -> R.string.diagnostics_matrix_inconclusive
    }

@Composable
private fun cohortLabel(cohort: String): String =
    stringResource(
        when (cohort) {
            "declared_available" -> R.string.diagnostics_matrix_declared
            "domestic" -> R.string.diagnostics_matrix_domestic
            "global" -> R.string.diagnostics_matrix_global
            "user" -> R.string.diagnostics_matrix_user
            else -> R.string.diagnostics_matrix_unknown
        },
    )

@Composable
private fun stageLabel(stage: String): String =
    if (stage == "body") stringResource(R.string.diagnostics_matrix_body) else stage.uppercase()

@Composable
private fun stageStatus(status: String): String =
    stringResource(
        when (status) {
            "ok" -> R.string.diagnostics_matrix_stage_ok
            "failed" -> R.string.diagnostics_matrix_stage_failed
            "admitted_addresses" -> R.string.diagnostics_matrix_stage_admitted
            "proxy_resolved_unknown" -> R.string.diagnostics_matrix_stage_proxy
            else -> R.string.diagnostics_matrix_stage_not_run
        },
    )

@Composable
private fun MatrixHeading(text: String) {
    Text(
        text = text,
        modifier = Modifier.fillMaxWidth(),
        style = RipDpiThemeTokens.type.bodyEmphasis,
        color = RipDpiThemeTokens.colors.foreground,
    )
}

@Composable
private fun MatrixText(text: String) {
    Text(
        text = text,
        modifier = Modifier.fillMaxWidth(),
        style = RipDpiThemeTokens.type.secondaryBody,
        color = RipDpiThemeTokens.colors.mutedForeground,
    )
}

@Preview(showBackground = true, widthDp = 360)
@Preview(showBackground = true, widthDp = 360, locale = "ru", fontScale = 1.5f)
@Composable
private fun SelectiveMatrixInputPreview() {
    RipDpiComponentPreview {
        SelectiveMatrixInputCard(
            input = "example.org",
            targets =
                listOf(
                    SelectiveMatrixTargetUiModel(
                        label = "Example",
                        url = "https://example.org/",
                        cohort = "global",
                        infrastructure = "example",
                        source = "https://example.org/",
                        sourceDate = "2026-10-09",
                        verifiedAt = null,
                    ),
                ),
            valid = true,
            enabled = true,
            onInputChanged = {},
        )
    }
}

@Preview(showBackground = true, widthDp = 360)
@Preview(showBackground = true, widthDp = 360, locale = "ru", fontScale = 1.5f)
@Preview(showBackground = true, widthDp = 360, locale = "ar")
@Composable
private fun SelectiveMatrixResultsPreview() {
    val attempts =
        listOf(
            matrixPreviewAttempt("domestic.example", "declared_available", 1, success = true),
            matrixPreviewAttempt("global.example", "global", 1, success = false),
            matrixPreviewAttempt("domestic.example", "declared_available", 2, success = true),
            matrixPreviewAttempt("global.example", "global", 2, success = true),
        )
    val summary =
        DiagnosticsProbeResultUiModel(
            id = "matrix-summary",
            probeType = "selective_availability_summary",
            target = "matrix",
            outcome = "matrix_mixed",
            tone = DiagnosticsTone.Warning,
            details =
                listOf(
                    DiagnosticsFieldUiModel("completedAttempts", "4"),
                    DiagnosticsFieldUiModel("expectedAttempts", "4"),
                    DiagnosticsFieldUiModel("catalogVersion", "preview-v1"),
                ).toImmutableList(),
        )
    RipDpiComponentPreview { SelectiveMatrixResultsCard(attempts + summary) }
}

private fun matrixPreviewAttempt(
    target: String,
    cohort: String,
    attempt: Int,
    success: Boolean,
): DiagnosticsProbeResultUiModel =
    DiagnosticsProbeResultUiModel(
        id = "$target-$attempt",
        probeType = "selective_availability",
        target = target,
        outcome = if (success) "matrix_target_available" else "matrix_target_transport_failed",
        tone = if (success) DiagnosticsTone.Positive else DiagnosticsTone.Warning,
        details =
            listOf(
                "cohort" to cohort,
                "attempt" to attempt.toString(),
                "dnsStatus" to "admitted_addresses",
                "tcpStatus" to "ok",
                "tlsStatus" to if (success) "ok" else "failed",
                "httpStatus" to if (success) "ok" else "not_run",
                "bodyStatus" to if (success) "ok" else "not_run",
                "bodyByteCount" to if (success) "1024" else "0",
                "bodyComplete" to success.toString(),
                "httpStatusCode" to if (success) "200" else "—",
                "failureStage" to if (success) "none" else "tls",
                "elapsedMs" to if (success) "450" else "5000",
                "infrastructureGroup" to target,
                "sourceUrl" to "https://example.org/catalog",
                "sourceDate" to "2026-10-09",
                "lastVerifiedAt" to "unknown",
            ).map { (key, value) -> DiagnosticsFieldUiModel(key, value) }.toImmutableList(),
    )
