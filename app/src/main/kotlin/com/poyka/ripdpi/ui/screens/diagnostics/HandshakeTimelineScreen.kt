package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.RipDpiCardVariant
import com.poyka.ripdpi.ui.theme.RipDpiStroke
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import kotlinx.collections.immutable.ImmutableList
import kotlin.math.roundToInt

/**
 * Per-stage Gantt-style timeline of the connection handshake.
 * Matches `docs/design/rds/preview/vpn-handshake-timeline.html` —
 * stage labels and duration above a full-width bar track at fractional
 * position/width, plus a NOW overlay line and a footer with the slowest-stage
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
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            Text(
                text = state.footerSlowest,
                style = type.monoSmall,
                color = colors.mutedForeground,
                modifier = Modifier.width(IntrinsicSize.Max),
            )
            Text(
                text = state.footerBudget,
                style = type.monoSmall,
                color = colors.mutedForeground,
                modifier = Modifier.width(IntrinsicSize.Max),
            )
        }
    }
}

@Composable
private fun HandshakeHeader(state: HandshakeTimelineState) {
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type
    val colors = RipDpiThemeTokens.colors
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Column {
            Text(
                text = state.title,
                style = type.bodyEmphasis,
                color = colors.foreground,
                modifier = Modifier.width(IntrinsicSize.Max),
            )
            Spacer(modifier = Modifier.height(spacing.xs))
            Text(
                text = state.subtitle,
                style = type.monoSmall,
                color = colors.mutedForeground,
                modifier = Modifier.width(IntrinsicSize.Max),
            )
        }
        Column {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = state.totalValue,
                    style = type.monoValue,
                    color = colors.foreground,
                    modifier = Modifier.width(IntrinsicSize.Max),
                )
                Spacer(modifier = Modifier.width(spacing.xs))
                Text(
                    text = state.totalUnit,
                    style = type.monoSmall,
                    color = colors.mutedForeground,
                    modifier = Modifier.width(IntrinsicSize.Max),
                )
            }
            Text(
                text = state.totalLabel.uppercase(),
                style = type.smallLabel,
                color = colors.mutedForeground,
                modifier = Modifier.width(IntrinsicSize.Max),
            )
        }
    }
}

@Composable
private fun HandshakeAxisRow(tickLabels: ImmutableList<String>) {
    val type = RipDpiThemeTokens.type
    val colors = RipDpiThemeTokens.colors
    val gap = with(LocalDensity.current) { RipDpiThemeTokens.spacing.xs.roundToPx() }
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Layout(
            modifier = Modifier.fillMaxWidth(),
            content = {
                tickLabels.forEach { label ->
                    Text(
                        text = label,
                        style = type.monoSmall,
                        color = colors.mutedForeground,
                        modifier = Modifier.width(IntrinsicSize.Max),
                    )
                }
            },
        ) { measurables, constraints ->
            val width = constraints.maxWidth
            val labels = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
            val positions =
                labels.mapIndexed { index, label ->
                    val point = width * index.toFloat() / labels.lastIndex.coerceAtLeast(1)
                    (point - label.width / 2f).roundToInt().coerceIn(0, width - label.width)
                }
            val visible = handshakeVisibleTicks(positions, labels.map { it.width }, gap)
            val rowHeight = labels.maxOfOrNull { it.height } ?: 0
            val stacked =
                visible.size == 2 &&
                    positions[visible.first()] + labels[visible.first()].width + gap > positions[visible.last()]
            layout(width, rowHeight * if (stacked) 2 else 1) {
                visible.forEachIndexed { row, index ->
                    labels[index].place(positions[index], if (stacked) row * rowHeight else 0)
                }
            }
        }
    }
}

/** Keep the source coordinates and both endpoints when narrow text needs fewer ticks. */
private fun handshakeVisibleTicks(
    positions: List<Int>,
    widths: List<Int>,
    gap: Int,
): List<Int> {
    if (positions.size < 2) return positions.indices.toList()
    val last = positions.lastIndex
    return (1..last).firstNotNullOfOrNull { step ->
        val indices = (0..last step step).toList().let { if (it.last() == last) it else it + last }
        indices.takeIf {
            it.zipWithNext().all { (left, right) -> positions[left] + widths[left] + gap <= positions[right] }
        }
    } ?: listOf(0, last)
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
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            Column {
                Text(
                    text = stage.name,
                    style = type.monoValue,
                    color = colors.foreground,
                    modifier = Modifier.width(IntrinsicSize.Max),
                )
                Text(
                    text = stage.sub,
                    style = type.monoSmall,
                    color = colors.mutedForeground,
                    modifier = Modifier.width(IntrinsicSize.Max),
                )
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
                modifier = Modifier.width(IntrinsicSize.Max),
            )
        }
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Box(modifier = Modifier.fillMaxWidth().height(spacing.lg)) {
                HandshakeBarTrack(stage = stage, nowPercent = nowPercent)
            }
        }
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
