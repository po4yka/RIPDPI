package com.poyka.ripdpi.ui.components.inputs

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.screens.status.SchemeChunk
import com.poyka.ripdpi.ui.screens.status.SchemeSampleHost
import com.poyka.ripdpi.ui.screens.status.SchemeVerdict
import com.poyka.ripdpi.ui.screens.status.StrategyPassageScene
import com.poyka.ripdpi.ui.screens.status.schemeReadout
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

private val ChipRadius = 4.dp
private val ChipPaddingX = 6.dp
private val ChipPaddingY = 1.dp
private const val ChipTint = 0.14f

@StringRes
private fun SchemeVerdict.labelRes(): Int =
    when (this) {
        SchemeVerdict.NoMatch -> R.string.status_strategy_verdict_no_match
        SchemeVerdict.StopsLooking -> R.string.status_strategy_verdict_stops
        SchemeVerdict.WholeName -> R.string.status_strategy_verdict_whole_name
    }

/** The scheme in words: what the filter reads, what the server gets, and where the plan comes from. */
@Composable
internal fun RipDpiStrategyReadout(
    scene: StrategyPassageScene,
    modifier: Modifier = Modifier,
) {
    val lane = scene.tcp ?: scene.udp ?: return
    val readout = remember(lane) { schemeReadout(lane) }
    val colors = RipDpiThemeTokens.colors
    val type = RipDpiThemeTokens.type
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.sm),
    ) {
        ReadoutLine(
            label = stringResource(R.string.status_strategy_dpi_reads),
            verdict = stringResource(readout.verdict.labelRes()),
            chunks = readout.chunks,
        )
        ReadoutLine(
            label = stringResource(R.string.status_strategy_server_gets),
            verdict = stringResource(R.string.status_strategy_server_whole),
            chunks = listOf(SchemeChunk(SchemeSampleHost, decoy = false)),
            note = stringResource(R.string.status_strategy_sample),
        )
        if (scene.lowered) {
            Text(
                text = stringResource(R.string.status_strategy_lowered),
                style = type.secondaryBody,
                color = colors.foreground,
            )
        }
        Text(
            text = stringResource(R.string.status_strategy_source),
            style = type.caption,
            color = colors.mutedForeground,
        )
    }
}

@Composable
private fun ReadoutLine(
    label: String,
    verdict: String,
    chunks: List<SchemeChunk>,
    note: String? = null,
) {
    val colors = RipDpiThemeTokens.colors
    val type = RipDpiThemeTokens.type
    val spacing = RipDpiThemeTokens.spacing
    // TalkBack reads one line as one item: the label, what is read, and the verdict.
    Column(
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
            val muted = colors.mutedForeground
            Text(text = label, modifier = Modifier.weight(1f), style = type.secondaryBody, color = muted)
            Text(text = verdict, style = type.smallLabel, color = colors.foreground)
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            chunks.forEach { chunk ->
                val tint = if (chunk.decoy) colors.warning else colors.foreground
                Text(
                    text = chunk.text,
                    modifier =
                        Modifier
                            .background(tint.copy(alpha = ChipTint), RoundedCornerShape(ChipRadius))
                            .padding(horizontal = ChipPaddingX, vertical = ChipPaddingY),
                    style = type.monoSmall,
                    color = colors.foreground,
                )
            }
            note?.let { Text(text = it, style = type.caption, color = colors.mutedForeground) }
        }
    }
}
