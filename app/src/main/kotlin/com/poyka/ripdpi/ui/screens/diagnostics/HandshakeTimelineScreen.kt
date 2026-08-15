package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.style.TextAlign
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.RipDpiCardVariant
import com.poyka.ripdpi.ui.theme.RipDpiStroke
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import kotlinx.collections.immutable.ImmutableList

// Label column width = section spacing × this factor (matches spec card layout).
private const val LabelColumnSectionMultiplier = 4

/**
 * Per-stage Gantt-style timeline of the connection handshake.
 * Matches `docs/design/rds/preview/vpn-handshake-timeline.html` —
 * label column, bar track at fractional position/width, duration
 * column, plus a NOW overlay line and a footer with the slowest-stage
 * callout.
 *
 * The component is presentation-only: it consumes
 * [HandshakeTimelineState] (caller computes fractions from real
 * handshake events) and renders tokens from `RipDpiThemeTokens`. No
 * Material3 color reads, no literal sp/dp/Color values, no animation
 * spec literals.
 */
@Composable
fun HandshakeTimelineScreen(
    state: HandshakeTimelineState,
    modifier: Modifier = Modifier,
) {
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type
    val colors = RipDpiThemeTokens.colors
    RipDpiCard(modifier = modifier.fillMaxWidth(), variant = RipDpiCardVariant.Outlined) {
        HandshakeHeader(state)
        Spacer(modifier = Modifier.height(spacing.md))
        HandshakeAxisRow(state.axisTickLabels)
        Spacer(modifier = Modifier.height(spacing.sm))
        HandshakeRows(state)
        Spacer(modifier = Modifier.height(spacing.md))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = state.footerSlowest,
                style = type.monoSmall,
                color = colors.mutedForeground,
            )
            Text(
                text = state.footerBudget,
                style = type.monoSmall,
                color = colors.mutedForeground,
            )
        }
    }
}

@Composable
private fun HandshakeHeader(state: HandshakeTimelineState) {
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type
    val colors = RipDpiThemeTokens.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom,
    ) {
        Column {
            Text(text = state.title, style = type.bodyEmphasis, color = colors.foreground)
            Spacer(modifier = Modifier.height(spacing.xs))
            Text(text = state.subtitle, style = type.monoSmall, color = colors.mutedForeground)
        }
        Column(horizontalAlignment = Alignment.End) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(text = state.totalValue, style = type.monoValue, color = colors.foreground)
                Spacer(modifier = Modifier.width(spacing.xs))
                Text(text = state.totalUnit, style = type.monoSmall, color = colors.mutedForeground)
            }
            Text(
                text = state.totalLabel.uppercase(),
                style = type.smallLabel,
                color = colors.mutedForeground,
            )
        }
    }
}

@Composable
private fun HandshakeAxisRow(tickLabels: ImmutableList<String>) {
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type
    val colors = RipDpiThemeTokens.colors
    val labelColumnWidth = spacing.section * LabelColumnSectionMultiplier
    val durationColumnWidth = spacing.section + spacing.lg
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(modifier = Modifier.width(labelColumnWidth))
        Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceBetween) {
            tickLabels.forEach { label ->
                Text(
                    text = label,
                    style = type.monoSmall,
                    color = colors.mutedForeground,
                    textAlign = TextAlign.Center,
                )
            }
        }
        Spacer(modifier = Modifier.width(durationColumnWidth))
    }
}

@Composable
private fun HandshakeRows(state: HandshakeTimelineState) {
    val spacing = RipDpiThemeTokens.spacing
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
        state.stages.forEach { stage ->
            HandshakeStageRow(stage, nowPercent = state.nowPercent)
        }
    }
}

@Composable
private fun HandshakeStageRow(
    stage: HandshakeStageRow,
    nowPercent: Float,
) {
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type
    val colors = RipDpiThemeTokens.colors
    val labelColumnWidth = spacing.section * LabelColumnSectionMultiplier
    val durationColumnWidth = spacing.section + spacing.lg
    val barTrackHeight = spacing.lg
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.width(labelColumnWidth)) {
            Text(text = stage.name, style = type.monoValue, color = colors.foreground)
            Text(text = stage.sub, style = type.monoSmall, color = colors.mutedForeground)
        }
        Box(modifier = Modifier.weight(1f).height(barTrackHeight)) {
            HandshakeBarTrack(stage = stage, nowPercent = nowPercent)
        }
        Text(
            text = stage.durationLabel,
            style = type.monoValue,
            color =
                when (stage.tone) {
                    HandshakeStageTone.Warn -> colors.warning
                    HandshakeStageTone.Bad -> colors.destructive
                    HandshakeStageTone.Queued -> colors.mutedForeground
                    else -> colors.foreground
                },
            modifier = Modifier.width(durationColumnWidth),
            textAlign = TextAlign.End,
        )
    }
}

@Composable
private fun HandshakeBarTrack(
    stage: HandshakeStageRow,
    nowPercent: Float,
) {
    val colors = RipDpiThemeTokens.colors
    val shapes = RipDpiThemeTokens.shapes
    val trackColor = colors.inputBackground
    val barColor =
        when (stage.tone) {
            HandshakeStageTone.Ok -> colors.success
            HandshakeStageTone.Info -> colors.info
            HandshakeStageTone.Warn -> colors.warning
            HandshakeStageTone.Bad -> colors.destructive
            HandshakeStageTone.Queued -> colors.border
        }
    val nowColor = colors.foreground
    val nowStroke = RipDpiStroke.Thin

    Box(modifier = Modifier.fillMaxSize().clip(shapes.xs)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(color = trackColor)
            val startPx = size.width * stage.startPercent.coerceIn(0f, 1f)
            val widthPx = size.width * stage.widthPercent.coerceIn(0f, 1f - stage.startPercent.coerceIn(0f, 1f))
            drawRect(
                color = barColor,
                topLeft = Offset(startPx, 0f),
                size = Size(widthPx, size.height),
            )
            val nowPx = size.width * nowPercent.coerceIn(0f, 1f)
            drawLine(
                color = nowColor,
                start = Offset(nowPx, 0f),
                end = Offset(nowPx, size.height),
                strokeWidth = nowStroke.toPx(),
            )
        }
    }
}

enum class HandshakeStageTone {
    Ok,
    Info,
    Warn,
    Bad,
    Queued,
}

data class HandshakeStageRow(
    val name: String,
    val sub: String,
    val startPercent: Float,
    val widthPercent: Float,
    val durationLabel: String,
    val tone: HandshakeStageTone,
)

data class HandshakeTimelineState(
    val title: String,
    val subtitle: String,
    val totalValue: String,
    val totalUnit: String,
    val totalLabel: String,
    val axisTickLabels: ImmutableList<String>,
    val stages: ImmutableList<HandshakeStageRow>,
    val nowPercent: Float,
    val footerSlowest: String,
    val footerBudget: String,
)
