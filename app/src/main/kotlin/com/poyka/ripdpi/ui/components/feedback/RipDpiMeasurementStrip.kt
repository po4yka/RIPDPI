@file:OptIn(androidx.compose.foundation.style.ExperimentalFoundationStyleApi::class)

package com.poyka.ripdpi.ui.components.feedback

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.style.CommonStyle
import androidx.compose.foundation.style.rememberUpdatedStyleState
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.components.RipDpiControlDensity
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.localizedDigits
import com.poyka.ripdpi.ui.components.ripDpiClickable
import com.poyka.ripdpi.ui.theme.RipDpiIconSizes
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiStroke
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import com.poyka.ripdpi.ui.theme.measurementAccent
import com.poyka.ripdpi.ui.theme.rememberRipDpiStyleResolver
import com.poyka.ripdpi.ui.theme.ripDpiStyleSurface
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/**
 * [Nominal] is deliberately neutral rather than green: it reports that the
 * indicators are in range, which is the resting state, and a success colour
 * would give the resting state more weight than an actual problem. It also has
 * no stripe or icon, so only a real problem draws the eye.
 */
enum class RipDpiMeasurementTone { Nominal, Warning, Critical }

internal fun measurementToneIcon(tone: RipDpiMeasurementTone): ImageVector? =
    when (tone) {
        RipDpiMeasurementTone.Nominal -> null
        RipDpiMeasurementTone.Warning, RipDpiMeasurementTone.Critical -> RipDpiIcons.Warning
    }

data class RipDpiMeasurementMetric(
    val label: String,
    val value: String,
    val delta: String,
    val deltaIsBad: Boolean,
    val action: RipDpiMeasurementAction? = null,
    val modifier: Modifier = Modifier,
)

data class RipDpiMeasurementAction(
    val label: String,
    val onClick: () -> Unit,
)

@OptIn(ExperimentalLayoutApi::class)
@Suppress("LongMethod")
@Composable
fun RipDpiMeasurementStrip(
    title: String,
    body: String,
    metrics: ImmutableList<RipDpiMeasurementMetric>,
    footnote: String?,
    primaryAction: RipDpiMeasurementAction,
    secondaryAction: RipDpiMeasurementAction?,
    modifier: Modifier = Modifier,
    tone: RipDpiMeasurementTone = RipDpiMeasurementTone.Warning,
) {
    val colors = RipDpiThemeTokens.colors
    val shapes = RipDpiThemeTokens.shapes
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type
    val tonalCard = RipDpiThemeTokens.surfaces.resolve(RipDpiThemeTokens.surfaceRoles.cards.tonal)
    val containerColor =
        when (tone) {
            RipDpiMeasurementTone.Nominal -> tonalCard.container
            RipDpiMeasurementTone.Warning -> colors.warningContainer
            RipDpiMeasurementTone.Critical -> colors.destructiveContainer
        }
    val contentColor =
        when (tone) {
            RipDpiMeasurementTone.Nominal -> colors.cardForeground
            RipDpiMeasurementTone.Warning -> colors.warningContainerForeground
            RipDpiMeasurementTone.Critical -> colors.destructiveContainerForeground
        }
    val accentForeground =
        when (tone) {
            RipDpiMeasurementTone.Nominal -> colors.mutedForeground
            RipDpiMeasurementTone.Warning -> colors.warningForeground
            RipDpiMeasurementTone.Critical -> colors.destructiveForeground
        }

    val accentState = rememberUpdatedStyleState(null)
    val accentStyle = RipDpiThemeTokens.styles.measurementAccent(tone)
    val stripeResolver = rememberRipDpiStyleResolver(accentStyle, CommonStyle, accentState)
    val icon = measurementToneIcon(tone)

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = shapes.xl,
        color = containerColor,
        contentColor = contentColor,
        // Higher contrast levels outline tonal cards; a neutral strip stays one of them.
        border =
            tonalCard.border
                .takeIf { tone == RipDpiMeasurementTone.Nominal && it != Color.Transparent }
                ?.let { BorderStroke(RipDpiStroke.Thin, it) },
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            // Measure the content naturally; intrinsic Row height cannot account
            // for wrapped actions and metric values at accessibility font sizes.
            if (icon != null) {
                Box(modifier = Modifier.matchParentSize()) {
                    Box(
                        modifier =
                            Modifier
                                .align(Alignment.CenterStart)
                                .width(spacing.xs)
                                .fillMaxHeight()
                                .ripDpiStyleSurface(stripeResolver),
                    )
                }
            }
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(RipDpiThemeTokens.layout.cardPadding),
                verticalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    icon?.let {
                        Icon(
                            imageVector = it,
                            contentDescription = null,
                            tint = accentForeground,
                            modifier = Modifier.size(RipDpiIconSizes.Default),
                        )
                    }
                    Text(
                        text = title,
                        modifier = Modifier.weight(1f),
                        style = type.bodyEmphasis,
                        color = contentColor,
                    )
                }
                Text(
                    text = body,
                    modifier = Modifier.fillMaxWidth(),
                    style = type.secondaryBody,
                    color = colors.mutedForeground,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(spacing.md),
                    verticalArrangement = Arrangement.spacedBy(spacing.sm),
                    // An actionable readout reserves a taller touch slot; keep its neighbours level with it.
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    metrics.forEach { metric ->
                        RipDpiMeasurementMetricChip(metric = metric)
                    }
                }
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm, Alignment.End),
                    verticalArrangement = Arrangement.spacedBy(spacing.sm),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    footnote?.let {
                        Text(
                            text = it,
                            modifier = Modifier.weight(1f),
                            style = type.caption,
                            color = colors.mutedForeground,
                        )
                    }
                    RipDpiMeasurementActions(primaryAction, secondaryAction)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RipDpiMeasurementActions(
    primaryAction: RipDpiMeasurementAction,
    secondaryAction: RipDpiMeasurementAction?,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.sm, Alignment.End),
        verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.sm),
    ) {
        secondaryAction?.let {
            RipDpiButton(
                text = it.label,
                onClick = it.onClick,
                modifier = Modifier.width(IntrinsicSize.Max),
                variant = RipDpiButtonVariant.Outline,
                density = RipDpiControlDensity.Compact,
            )
        }
        RipDpiButton(
            text = primaryAction.label,
            onClick = primaryAction.onClick,
            modifier = Modifier.width(IntrinsicSize.Max),
            // Outline: a tinted fill reads as disabled here and would outweigh the screen's main action.
            variant = RipDpiButtonVariant.Outline,
            density = RipDpiControlDensity.Compact,
        )
    }
}

/**
 * A measured value as a readout: the label above, the value in large mono digits. Only a metric with an action
 * gets a filled, outlined surface, so the fill marks what can be opened.
 */
@Composable
private fun RipDpiMeasurementMetricChip(metric: RipDpiMeasurementMetric) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type
    val motion = RipDpiThemeTokens.motion
    val action = metric.action
    // Each text hugs its content, so a right-to-left paragraph is not laid out wider than its node.
    val hug = Modifier.width(IntrinsicSize.Max)

    val readout: @Composable () -> Unit = {
        Column {
            Text(metric.label, hug, style = type.smallLabel, color = colors.mutedForeground)
            val value = localizedDigits(metric.value)
            // An actionable readout does not fade: two values in the tree would both be read as its name.
            if (motion.animationsEnabled && action == null) {
                AnimatedContent(
                    targetState = value,
                    transitionSpec = { motion.quickFadeContentTransform() },
                    label = "Measured value",
                ) { shown ->
                    Text(shown, hug, style = type.readoutValue, color = colors.foreground)
                }
            } else {
                Text(value, hug, style = type.readoutValue, color = colors.foreground)
            }
            if (metric.delta.isNotEmpty()) {
                Text(
                    text = metric.delta,
                    modifier = hug,
                    style = type.monoSmall,
                    color = if (metric.deltaIsBad) colors.destructive else colors.success,
                )
            }
        }
    }
    if (action == null) {
        // No surface: its shape would clip the first glyph, which sits on the card's text edge.
        Box(modifier = metric.modifier) { readout() }
    } else {
        Surface(
            // ripDpiClickable reserves the 48dp touch slot around the readout without enlarging it.
            modifier =
                metric.modifier.ripDpiClickable(
                    role = Role.Button,
                    onClickLabel = action.label,
                    onClick = action.onClick,
                ),
            shape = RipDpiThemeTokens.shapes.md,
            color = colors.inputBackground,
            contentColor = colors.foreground,
            border = BorderStroke(RipDpiStroke.Thin, colors.border),
        ) {
            Box(modifier = Modifier.padding(horizontal = spacing.sm, vertical = spacing.xs)) { readout() }
        }
    }
}

private val sampleMetrics =
    persistentListOf(
        RipDpiMeasurementMetric(
            label = "Loss",
            value = "4.1%",
            delta = "+3.2 pp",
            deltaIsBad = true,
        ),
        RipDpiMeasurementMetric(
            label = "RTT p50",
            value = "240 ms",
            delta = "+120",
            deltaIsBad = true,
        ),
        RipDpiMeasurementMetric(
            label = "Jitter",
            value = "18 ms",
            delta = "+7",
            deltaIsBad = true,
        ),
    )

@Preview(showBackground = true)
@Composable
private fun RipDpiMeasurementStripWarningPreview() {
    RipDpiComponentPreview {
        RipDpiMeasurementStrip(
            title = "Connected · quality degraded",
            body = "Latency and loss are above baseline since 12:14.",
            metrics = sampleMetrics,
            footnote = "Since 12:14",
            primaryAction = RipDpiMeasurementAction(label = "Reprobe", onClick = {}),
            secondaryAction = RipDpiMeasurementAction(label = "Dismiss", onClick = {}),
            tone = RipDpiMeasurementTone.Warning,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun RipDpiMeasurementStripCriticalPreview() {
    RipDpiComponentPreview(themePreference = "dark") {
        RipDpiMeasurementStrip(
            title = "Connected · severe degradation",
            body = "Sustained loss and elevated RTT suggest active throttling.",
            metrics = sampleMetrics,
            footnote = "Since 12:14",
            primaryAction = RipDpiMeasurementAction(label = "Reprobe", onClick = {}),
            secondaryAction = null,
            tone = RipDpiMeasurementTone.Critical,
        )
    }
}

@Preview(name = "MeasurementStrip nominal light", showBackground = true)
@Composable
private fun RipDpiMeasurementStripNominalLightPreview() {
    MeasurementStripNominal(theme = "light")
}

@Preview(name = "MeasurementStrip nominal dark", showBackground = true)
@Composable
private fun RipDpiMeasurementStripNominalDarkPreview() {
    MeasurementStripNominal(theme = "dark")
}

@Composable
private fun MeasurementStripNominal(theme: String) {
    RipDpiComponentPreview(themePreference = theme) {
        RipDpiMeasurementStrip(
            title = "Connection measurements",
            body = "Indicators are in range.",
            metrics =
                persistentListOf(
                    RipDpiMeasurementMetric(label = "Loss", value = "0.0%", delta = "", deltaIsBad = false),
                    RipDpiMeasurementMetric(
                        label = "RTT p50",
                        value = "105 ms",
                        delta = "",
                        deltaIsBad = false,
                        action = RipDpiMeasurementAction(label = "Open", onClick = {}),
                    ),
                    RipDpiMeasurementMetric(label = "Traffic total", value = "192 kB", delta = "", deltaIsBad = false),
                ),
            footnote = "2 RTT samples",
            primaryAction = RipDpiMeasurementAction(label = "Re-measure", onClick = {}),
            secondaryAction = null,
            tone = RipDpiMeasurementTone.Nominal,
        )
    }
}
