package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.diagnostics.replay.ReplayProbeResult
import com.poyka.ripdpi.diagnostics.replay.ReplayVerdict
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.navigation.RipDpiTopAppBar
import com.poyka.ripdpi.ui.components.scaffold.RipDpiScreenScaffold
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIconSizes
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
    RipDpiScreenScaffold(
        modifier = modifier.ripDpiTestTag(RipDpiTestTags.screen(Route.ReplayHistory)),
        topBar = {
            RipDpiTopAppBar(
                title = stringResource(R.string.title_replay_history),
                navigationIcon = RipDpiIcons.Back,
                onNavigationClick = onBack,
                navigationContentDescription = stringResource(R.string.navigation_back),
            )
        },
    ) { innerPadding ->
        ReplayHistoryContent(
            replays = replays,
            onRunScan = onRunScan,
            modifier = Modifier.padding(innerPadding),
        )
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
                    .padding(spacing.lg),
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
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type
    val colors = RipDpiThemeTokens.colors

    RipDpiCard(modifier = modifier) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
            horizontalAlignment = Alignment.Start,
        ) {
            Icon(
                imageVector = RipDpiIcons.NetworkCheck,
                contentDescription = null,
                tint = colors.mutedForeground,
                modifier = Modifier.size(RipDpiIconSizes.Large),
            )
            Text(
                text = stringResource(R.string.vpn_replay_history_empty),
                style = type.bodyEmphasis,
                color = colors.foreground,
            )
            Text(
                text = stringResource(R.string.history_diagnostics_empty_body),
                style = type.body,
                color = colors.mutedForeground,
            )
            RipDpiButton(
                text = stringResource(R.string.diagnostics_overview_run_scan_action),
                onClick = onRunScan,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(top = spacing.xs)
                        .ripDpiTestTag(RipDpiTestTags.ReplayHistoryRunScanAction),
            )
        }
    }
}

@Composable
private fun ReplayHistoryList(
    replays: ImmutableList<ReplayProbeResult>,
    modifier: Modifier = Modifier,
) {
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type
    val colors = RipDpiThemeTokens.colors

    Column(
        modifier = modifier.fillMaxWidth().padding(spacing.lg),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        replays.asReversed().forEach { result ->
            RipDpiCard {
                Text(
                    text = result.request.domain,
                    style = type.bodyEmphasis,
                    color = colors.foreground,
                )
                Spacer(modifier = Modifier.height(spacing.xs))
                Text(
                    text =
                        stringResource(
                            R.string.vpn_replay_history_row_format,
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
