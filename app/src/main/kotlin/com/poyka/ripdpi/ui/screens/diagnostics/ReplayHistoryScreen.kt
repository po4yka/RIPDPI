package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.diagnostics.replay.ReplayProbeResult
import com.poyka.ripdpi.diagnostics.replay.ReplayVerdict
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import kotlinx.collections.immutable.ImmutableList

/**
 * G010 P4.8. Lists the in-memory [ReplayResultStore] ring buffer of
 * past replay runs. Token-only consumer: no MaterialTheme.* reads, no
 * literal Color() / .dp values, all tokens come from RipDpiThemeTokens.
 *
 * Empty state shows a hint pointing the user at the Replay Failure
 * screen. Each row surfaces domain + strategy + verdict (colored) +
 * recommendation key + step count.
 */
@Composable
fun ReplayHistoryScreen(replays: ImmutableList<ReplayProbeResult>) {
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type
    val colors = RipDpiThemeTokens.colors
    if (replays.isEmpty()) {
        Column(modifier = Modifier.fillMaxWidth().padding(spacing.lg)) {
            Text(
                text = stringResource(R.string.vpn_replay_history_empty),
                style = type.body,
                color = colors.mutedForeground,
            )
        }
        return
    }
    Column(
        modifier = Modifier.fillMaxWidth().padding(spacing.lg),
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
