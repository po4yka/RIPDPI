package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.diagnostics.replay.ReplayProbeResult
import com.poyka.ripdpi.diagnostics.replay.ReplayStepEvent
import com.poyka.ripdpi.diagnostics.replay.ReplayVerdict
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.chrome.RipDpiEmptyStateCard
import com.poyka.ripdpi.ui.components.navigation.RipDpiTopAppBar
import com.poyka.ripdpi.ui.components.scaffold.RipDpiContentWidth
import com.poyka.ripdpi.ui.components.scaffold.RipDpiScreenScaffold
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import kotlinx.collections.immutable.ImmutableList

/**
 * Lists the in-memory [ReplayResultStore] ring buffer of past replay
 * runs. Token-only consumer: no MaterialTheme.* reads, no
 * literal Color() / .dp values, all tokens come from RipDpiThemeTokens.
 *
 * Empty state points the user at a fresh Diagnostics scan. Each row surfaces domain + strategy + verdict (colored) +
 * recommendation key + step count.
 */
@Composable
fun ReplayHistoryScreen(
    replays: ImmutableList<ReplayProbeResult>,
    onRunScan: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val topBarScrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    RipDpiScreenScaffold(
        scrollBehavior = topBarScrollBehavior,
        modifier = modifier.ripDpiTestTag(RipDpiTestTags.screen(Route.ReplayHistory)),
        topBar = {
            RipDpiTopAppBar(
                scrollBehavior = topBarScrollBehavior,
                title = stringResource(R.string.title_replay_history),
                navigationIcon = RipDpiIcons.Back,
                onNavigationClick = onBack,
                navigationContentDescription = stringResource(R.string.navigation_back),
            )
        },
    ) { innerPadding ->
        RipDpiContentWidth(Modifier.padding(innerPadding)) {
            ReplayHistoryContent(replays = replays, onRunScan = onRunScan)
        }
    }
}

@Composable
private fun ReplayHistoryContent(
    replays: ImmutableList<ReplayProbeResult>,
    onRunScan: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = RipDpiThemeTokens.spacing
    if (replays.isEmpty()) {
        Box(
            modifier =
                modifier
                    .fillMaxWidth()
                    .padding(horizontal = RipDpiThemeTokens.layout.horizontalPadding, vertical = spacing.lg),
        ) {
            ReplayHistoryEmptyState(
                onRunScan = onRunScan,
                modifier = Modifier.ripDpiTestTag(RipDpiTestTags.ReplayHistoryEmptyState),
            )
        }
        return
    }
    ReplayHistoryList(
        replays = replays,
        modifier = modifier,
    )
}

@Composable
private fun ReplayHistoryEmptyState(
    onRunScan: () -> Unit,
    modifier: Modifier = Modifier,
) {
    RipDpiEmptyStateCard(
        title = stringResource(R.string.vpn_replay_history_empty),
        body = stringResource(R.string.history_diagnostics_empty_body),
        modifier = modifier,
        actionLabel = stringResource(R.string.diagnostics_overview_run_scan_action),
        onAction = onRunScan,
        actionTestTag = RipDpiTestTags.ReplayHistoryRunScanAction,
    )
}

@Composable
private fun ReplayHistoryList(
    replays: ImmutableList<ReplayProbeResult>,
    modifier: Modifier = Modifier,
) {
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type
    val colors = RipDpiThemeTokens.colors

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = RipDpiThemeTokens.layout.horizontalPadding, vertical = spacing.lg),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        items(
            items = replays.asReversed(),
            key = { result -> result.stableHistoryKey() },
        ) { result ->
            RipDpiCard {
                Text(
                    text = result.request.domain,
                    style = type.bodyEmphasis,
                    color = colors.foreground,
                )
                Spacer(modifier = Modifier.height(spacing.xs))
                Text(
                    text =
                        pluralStringResource(
                            R.plurals.vpn_replay_history_row_format,
                            result.events.size,
                            result.request.strategyId,
                            result.events.size,
                        ),
                    style = type.monoSmall,
                    color = colors.mutedForeground,
                )
                Spacer(modifier = Modifier.height(spacing.xs))
                Text(
                    text = verdictLabel(result.verdict),
                    style = type.caption,
                    color = verdictColor(result.verdict),
                )
            }
        }
    }
}

private fun ReplayProbeResult.stableHistoryKey(): String =
    buildString {
        append(request.domain)
        append('|')
        append(request.strategyId)
        append('|')
        append(
            events.filterIsInstance<ReplayStepEvent.StepStarted>().firstOrNull()?.timestampMs
                ?: System.identityHashCode(this@stableHistoryKey),
        )
    }

@Composable
private fun verdictLabel(verdict: ReplayVerdict): String =
    when (verdict) {
        ReplayVerdict.Success -> stringResource(R.string.vpn_replay_history_verdict_success)
        ReplayVerdict.Failure -> stringResource(R.string.vpn_replay_history_verdict_failure)
        ReplayVerdict.Cancelled -> stringResource(R.string.vpn_replay_history_verdict_cancelled)
    }

@Composable
private fun verdictColor(verdict: ReplayVerdict): Color {
    val colors = RipDpiThemeTokens.colors
    return when (verdict) {
        ReplayVerdict.Success -> colors.success
        ReplayVerdict.Failure -> colors.destructive
        ReplayVerdict.Cancelled -> colors.mutedForeground
    }
}
