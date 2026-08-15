package com.poyka.ripdpi.ui.screens.tuner

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.poyka.ripdpi.R
import com.poyka.ripdpi.diagnostics.RankedStrategyProbeResult
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.RipDpiCardVariant
import com.poyka.ripdpi.ui.components.indicators.RipDpiProgressBar
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextField
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldBehavior
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldDecoration
import com.poyka.ripdpi.ui.components.scaffold.RipDpiContentScreenScaffold
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import kotlinx.collections.immutable.toImmutableList

private const val PreviewWidthDp = 420
private const val PreviewHeightDp = 900
private const val PreviewFastLatencyMs = 118L
private const val PreviewSlowLatencyMs = 420L
private const val PreviewDomainCount = 3
private const val PreviewPerfectRate = 1.0
private const val PreviewPartialSuccesses = 1
private const val PreviewPartialRate = 0.33
private const val PreviewFastAverageLatencyMs = 124L
private const val PreviewSlowAverageLatencyMs = 268L
private const val PreviewDnsTamperedCount = 0
private const val PreviewTotalExpectedResults = 12

@Composable
fun StrategyTunerRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: StrategyTunerViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    StrategyTunerScreen(
        state = state,
        onBack = onBack,
        onDomainsChanged = viewModel::updateDomainsText,
        onRun = viewModel::start,
        onCancel = viewModel::cancel,
        onApply = viewModel::apply,
        modifier = modifier,
    )
}

@Composable
fun StrategyTunerScreen(
    state: StrategyTunerUiState,
    onBack: () -> Unit,
    onDomainsChanged: (String) -> Unit,
    onRun: () -> Unit,
    onCancel: () -> Unit,
    onApply: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = RipDpiThemeTokens.spacing
    RipDpiContentScreenScaffold(
        title = stringResource(R.string.title_strategy_tuner),
        navigationIcon = RipDpiIcons.Back,
        onNavigationClick = onBack,
        modifier = modifier.ripDpiTestTag(RipDpiTestTags.screen(Route.StrategyTuner)),
    ) {
        StrategyTunerControls(
            state = state,
            onDomainsChanged = onDomainsChanged,
            onRun = onRun,
            onCancel = onCancel,
        )
        StrategyTunerBudgetCard(budget = state.budget)
        if (state.isRunning) {
            RipDpiProgressBar(progress = state.progress)
        }
        val resolvedMessage = state.messageRes?.let { stringResource(it) } ?: state.message
        resolvedMessage?.let { message ->
            RipDpiCard(variant = RipDpiCardVariant.Tonal) {
                Text(
                    text = message,
                    style = RipDpiThemeTokens.type.body,
                    color = RipDpiThemeTokens.colors.foreground,
                )
            }
        }
        RipDpiCard {
            Text(
                text = stringResource(R.string.strategy_tuner_leaderboard_title),
                style = RipDpiThemeTokens.type.sectionTitle,
                color = RipDpiThemeTokens.colors.foreground,
            )
            if (state.rankedStrategies.isEmpty()) {
                Text(
                    text = stringResource(R.string.strategy_tuner_empty_results),
                    style = RipDpiThemeTokens.type.body,
                    color = RipDpiThemeTokens.colors.mutedForeground,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    state.rankedStrategies.forEachIndexed { index, result ->
                        StrategyTunerRankedRow(
                            result = result,
                            isBest = index == 0,
                            isApplied = result.strategyId == state.appliedStrategyId,
                            applyEnabled = !state.isRunning,
                            onApply = { onApply(result.strategyId) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StrategyTunerControls(
    state: StrategyTunerUiState,
    onDomainsChanged: (String) -> Unit,
    onRun: () -> Unit,
    onCancel: () -> Unit,
) {
    val spacing = RipDpiThemeTokens.spacing
    RipDpiCard {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text =
                        stringResource(
                            R.string.strategy_tuner_status_format,
                            state.results.size,
                            state.totalExpectedResults,
                        ),
                    style = RipDpiThemeTokens.type.bodyEmphasis,
                    color = RipDpiThemeTokens.colors.foreground,
                    modifier = Modifier.weight(1f),
                )
                RipDpiButton(
                    text =
                        if (state.isRunning) {
                            stringResource(R.string.strategy_tuner_cancel)
                        } else {
                            stringResource(R.string.strategy_tuner_run)
                        },
                    onClick = if (state.isRunning) onCancel else onRun,
                    variant = if (state.isRunning) RipDpiButtonVariant.Outline else RipDpiButtonVariant.Primary,
                )
            }
            RipDpiTextField(
                value = state.domainsText,
                onValueChange = onDomainsChanged,
                decoration = RipDpiTextFieldDecoration(label = stringResource(R.string.strategy_tuner_domains_label)),
                behavior = RipDpiTextFieldBehavior(singleLine = false, minHeight = 96.dp),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun StrategyTunerBudgetCard(budget: StrategyTunerBudget) {
    RipDpiCard(variant = RipDpiCardVariant.Tonal) {
        Text(
            text = stringResource(R.string.strategy_tuner_budget_title),
            style = RipDpiThemeTokens.type.bodyEmphasis,
            color = RipDpiThemeTokens.colors.foreground,
        )
        Text(
            text =
                stringResource(
                    R.string.strategy_tuner_budget_format,
                    budget.maxStrategies,
                    budget.maxDomains,
                    budget.maxTotalProbes,
                    budget.probeIntervalMs,
                ),
            style = RipDpiThemeTokens.type.body,
            color = RipDpiThemeTokens.colors.mutedForeground,
        )
    }
}

@Preview(showBackground = true, widthDp = PreviewWidthDp, heightDp = PreviewHeightDp)
@Composable
private fun StrategyTunerScreenPreview() {
    RipDpiTheme {
        StrategyTunerScreen(
            state = previewStrategyTunerUiState(),
            onBack = {},
            onDomainsChanged = {},
            onRun = {},
            onCancel = {},
            onApply = {},
        )
    }
}

fun previewStrategyTunerUiState(): StrategyTunerUiState =
    StrategyTunerUiState(
        runState = StrategyTunerRunState.Complete,
        results =
            listOf(
                strategyTunerProbeResult(
                    "tls_rec_split",
                    "TLS record split",
                    "www.youtube.com",
                    true,
                    PreviewFastLatencyMs,
                ),
                strategyTunerProbeResult("split", "Split", "www.youtube.com", false, PreviewSlowLatencyMs),
            ).toImmutableList(),
        rankedStrategies =
            listOf(
                RankedStrategyProbeResult(
                    "tls_rec_split",
                    "TLS record split",
                    PreviewDomainCount,
                    PreviewDomainCount,
                    PreviewPerfectRate,
                    PreviewFastAverageLatencyMs,
                    PreviewDnsTamperedCount,
                ),
                RankedStrategyProbeResult(
                    "split",
                    "Split",
                    PreviewDomainCount,
                    PreviewPartialSuccesses,
                    PreviewPartialRate,
                    PreviewSlowAverageLatencyMs,
                    PreviewDnsTamperedCount,
                ),
            ).toImmutableList(),
        totalExpectedResults = PreviewTotalExpectedResults,
    )

private fun strategyTunerProbeResult(
    strategyId: String,
    strategyLabel: String,
    domain: String,
    success: Boolean,
    latencyMs: Long,
) = com.poyka.ripdpi.diagnostics.StrategyProbeResult(
    strategyId = strategyId,
    strategyLabel = strategyLabel,
    domain = domain,
    success = success,
    latencyMs = latencyMs,
)
