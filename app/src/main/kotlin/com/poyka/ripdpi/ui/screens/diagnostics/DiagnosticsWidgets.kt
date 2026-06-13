package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.DiagnosticsMetricUiModel
import com.poyka.ripdpi.activities.DiagnosticsSparklineUiModel
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.indicators.RipDpiMetricSurface
import com.poyka.ripdpi.ui.components.indicators.RipDpiMetricToneStyle
import com.poyka.ripdpi.ui.components.indicators.ripDpiMetricToneStyle
import com.poyka.ripdpi.ui.components.ripDpiClickable
import com.poyka.ripdpi.ui.debug.TrackRecomposition
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import kotlinx.collections.immutable.ImmutableList
import kotlin.math.roundToInt

private val SparklineChartHeight = 84.dp
private val SparklineChipWidth = 64.dp
private const val SparklineSelectedMarkerRadius = 5f
private const val SparklineSelectedMarkerInnerRadius = 3f
private const val SparklineStrokeWidth = 4f
private const val SparklineDividerStrokeWidth = 1f
private const val MetricCardHorizontalPaddingDp = 14
private const val MetricCardVerticalPaddingDp = 12

@Composable
internal fun CollapsibleSection(
    title: String,
    modifier: Modifier = Modifier,
    badgeCount: Int? = null,
    defaultExpanded: Boolean = false,
    content: @Composable () -> Unit,
) {
    TrackRecomposition("CollapsibleSection")
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val motion = RipDpiThemeTokens.motion
    var expanded by remember { mutableStateOf(defaultExpanded) }
    val expandedLabel = stringResource(R.string.semantic_state_expanded)
    val collapsedLabel = stringResource(R.string.semantic_state_collapsed)
    val expandActionLabel = stringResource(R.string.semantic_action_expand)
    val collapseActionLabel = stringResource(R.string.semantic_action_collapse)
    val rotationAngle by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = motion.stateTween(),
        label = "chevronRotation",
    )

    Column(modifier = modifier) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .stateDescription(expandedLabel, collapsedLabel, expanded)
                    .ripDpiClickable(
                        role = Role.Button,
                        onClickLabel = if (expanded) collapseActionLabel else expandActionLabel,
                        onClick = { expanded = !expanded },
                    ).padding(vertical = spacing.sm),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title.uppercase(),
                    style = RipDpiThemeTokens.type.sectionTitle,
                    color = colors.mutedForeground,
                )
                badgeCount?.let { count ->
                    Surface(
                        color = colors.inputBackground,
                        shape = RipDpiThemeTokens.shapes.full,
                    ) {
                        Text(
                            text = count.toString(),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            style = RipDpiThemeTokens.type.smallLabel,
                            color = colors.mutedForeground,
                        )
                    }
                }
            }
            Icon(
                imageVector = RipDpiIcons.KeyboardArrowDown,
                contentDescription =
                    if (expanded) {
                        stringResource(
                            R.string.cd_collapse,
                        )
                    } else {
                        stringResource(R.string.cd_expand)
                    },
                tint = colors.mutedForeground,
                modifier = Modifier.rotate(rotationAngle),
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = motion.sectionEnterTransition(),
            exit = motion.sectionExitTransition(),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                content()
            }
        }
    }
}

@Composable
internal fun MetricsRow(metrics: ImmutableList<DiagnosticsMetricUiModel>) {
    TrackRecomposition("MetricsRow")
    val spacing = RipDpiThemeTokens.spacing
    if (metrics.isEmpty()) {
        return
    }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
        itemsIndexed(
            items = metrics,
            key = { index, metric -> "${metric.label}-$index" },
            contentType = { _, _ -> "metric" },
        ) { _, metric ->
            TelemetryMetricCard(metric = metric)
        }
    }
}

@Composable
internal fun TelemetryMetricCard(metric: DiagnosticsMetricUiModel) {
    RipDpiMetricSurface(
        tone = metricTone(metric.tone),
        shape = RipDpiThemeTokens.shapes.xl,
    ) { contentColor ->
        Column(
            modifier =
                Modifier
                    .padding(horizontal = MetricCardHorizontalPaddingDp.dp, vertical = MetricCardVerticalPaddingDp.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = metric.label.uppercase(),
                style = RipDpiThemeTokens.type.sectionTitle,
                color = contentColor.copy(alpha = 0.75f),
            )
            Text(
                text = metric.value,
                style = RipDpiThemeTokens.type.monoValue,
                color = contentColor,
            )
        }
    }
}

@Composable
internal fun TelemetrySparkline(trend: DiagnosticsSparklineUiModel) {
    TrackRecomposition("TelemetrySparkline")
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val motion = RipDpiThemeTokens.motion
    val metricStyle = ripDpiMetricToneStyle(metricTone(trend.tone))
    val animatedStrokeColor by animateColorAsState(
        targetValue = metricStyle.content,
        animationSpec = motion.stateTween(),
        label = "telemetrySparklineStroke",
    )
    var previousValues by remember(trend.label) { mutableStateOf(trend.values) }
    var currentValues by remember(trend.label) { mutableStateOf(trend.values) }
    val transitionProgress = remember(trend.label) { Animatable(1f) }

    var selectedIndex by remember(trend.label) { mutableIntStateOf(-1) }

    LaunchedEffect(trend.label, trend.values) {
        selectedIndex = -1
        previousValues = currentValues.ifEmpty { trend.values }
        currentValues = trend.values
        transitionProgress.snapTo(0f)
        transitionProgress.animateTo(
            targetValue = 1f,
            animationSpec = motion.emphasizedTween(),
        )
    }

    val sparklineState = rememberSparklineState(trend = trend, selectedIndex = selectedIndex)

    RipDpiCard {
        SparklineHeader(
            label = trend.label,
            displayValue = sparklineState.displayValue,
            valueColor = metricStyle.content,
            labelColor = colors.foreground,
        )
        SparklineChartRow(
            minValue = sparklineState.minValue,
            maxValue = sparklineState.maxValue,
            spacing = spacing.sm,
            labelColor = colors.mutedForeground,
            values = interpolatedSeries(previousValues, currentValues, transitionProgress.value),
            selectedIndex = selectedIndex,
            onSelectedIndexChange = { index -> selectedIndex = index },
            strokeColor = animatedStrokeColor,
            dividerColor = colors.divider,
            selectionColor = colors.mutedForeground,
            cardColor = colors.card,
            selectedValue = sparklineState.selectedValue,
            chipContainerColor = metricStyle.container,
            chipContentColor = metricStyle.content,
        )
    }
}

@Composable
private fun SparklineHeader(
    label: String,
    displayValue: Float,
    valueColor: Color,
    labelColor: Color,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(
            text = label,
            style = RipDpiThemeTokens.type.bodyEmphasis,
            color = labelColor,
        )
        Text(
            text = formatSparklineValue(displayValue),
            style = RipDpiThemeTokens.type.monoValue,
            color = valueColor,
        )
    }
}

@Composable
private fun SparklineChartRow(
    minValue: Float,
    maxValue: Float,
    spacing: Dp,
    labelColor: Color,
    values: List<Float>,
    selectedIndex: Int,
    onSelectedIndexChange: (Int) -> Unit,
    strokeColor: Color,
    dividerColor: Color,
    selectionColor: Color,
    cardColor: Color,
    selectedValue: Float?,
    chipContainerColor: Color,
    chipContentColor: Color,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(spacing),
    ) {
        SparklineAxisLabels(
            maxValue = maxValue,
            minValue = minValue,
            labelColor = labelColor,
        )
        androidx.compose.foundation.layout.BoxWithConstraints(
            modifier =
                Modifier
                    .weight(1f)
                    .height(SparklineChartHeight),
        ) {
            SparklineCanvas(
                values = values,
                selectedIndex = selectedIndex,
                onSelectedIndexChange = onSelectedIndexChange,
                strokeColor = strokeColor,
                dividerColor = dividerColor,
                selectionColor = selectionColor,
                cardColor = cardColor,
            )
            SparklineSelectionChip(
                selectedIndex = selectedIndex,
                selectedValue = selectedValue,
                pointCount = values.size,
                maxWidth = maxWidth,
                containerColor = chipContainerColor,
                contentColor = chipContentColor,
            )
        }
    }
}

@Composable
private fun SparklineAxisLabels(
    maxValue: Float,
    minValue: Float,
    labelColor: Color,
) {
    Column(
        modifier = Modifier.height(SparklineChartHeight),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = formatSparklineValue(maxValue),
            style = RipDpiThemeTokens.type.monoSmall,
            color = labelColor,
        )
        Text(
            text = formatSparklineValue(minValue),
            style = RipDpiThemeTokens.type.monoSmall,
            color = labelColor,
        )
    }
}

@Composable
private fun SparklineCanvas(
    values: List<Float>,
    selectedIndex: Int,
    onSelectedIndexChange: (Int) -> Unit,
    strokeColor: Color,
    dividerColor: Color,
    selectionColor: Color,
    cardColor: Color,
) {
    Canvas(
        modifier =
            Modifier
                .fillMaxSize()
                .pointerInput(values.size, selectedIndex) {
                    detectTapGestures { offset ->
                        val pointCount = values.size
                        if (pointCount <= 1) return@detectTapGestures
                        val index =
                            ((offset.x / size.width) * (pointCount - 1))
                                .roundToInt()
                                .coerceIn(0, pointCount - 1)
                        onSelectedIndexChange(if (selectedIndex == index) -1 else index)
                    }
                },
    ) {
        if (values.isEmpty()) {
            return@Canvas
        }
        val geometry = SparklineGeometry.from(values, size.width, size.height)
        drawPath(
            path = geometry.path,
            color = strokeColor,
            style = Stroke(width = SparklineStrokeWidth, cap = StrokeCap.Round),
        )
        drawLine(
            color = dividerColor,
            start = Offset(0f, size.height),
            end = Offset(size.width, size.height),
            strokeWidth = SparklineDividerStrokeWidth,
        )
        drawSparklineSelection(
            geometry = geometry,
            selectedIndex = selectedIndex,
            selectionColor = selectionColor,
            strokeColor = strokeColor,
            cardColor = cardColor,
        )
    }
}

@Composable
private fun SparklineSelectionChip(
    selectedIndex: Int,
    selectedValue: Float?,
    pointCount: Int,
    maxWidth: Dp,
    containerColor: Color,
    contentColor: Color,
) {
    if (selectedValue == null || selectedIndex !in 0 until pointCount || pointCount <= 1) {
        return
    }
    val rawOffset = maxWidth * selectedIndex.toFloat() / (pointCount - 1).toFloat()
    val clampedOffset =
        (rawOffset - SparklineChipWidth / 2)
            .coerceIn(0.dp, (maxWidth - SparklineChipWidth).coerceAtLeast(0.dp))
    SparklineValueChip(
        value = formatSparklineValue(selectedValue),
        containerColor = containerColor,
        contentColor = contentColor,
        modifier = Modifier.padding(start = clampedOffset),
    )
}

private fun DrawScope.drawSparklineSelection(
    geometry: SparklineGeometry,
    selectedIndex: Int,
    selectionColor: Color,
    strokeColor: Color,
    cardColor: Color,
) {
    if (selectedIndex !in geometry.values.indices || geometry.values.size <= 1) {
        return
    }
    val x = geometry.xFor(selectedIndex)
    val y = geometry.yFor(selectedIndex)
    drawLine(
        color = selectionColor,
        start = Offset(x, 0f),
        end = Offset(x, size.height),
        strokeWidth = SparklineDividerStrokeWidth,
    )
    drawCircle(
        color = strokeColor,
        radius = SparklineSelectedMarkerRadius,
        center = Offset(x, y),
    )
    drawCircle(
        color = cardColor,
        radius = SparklineSelectedMarkerInnerRadius,
        center = Offset(x, y),
    )
}

private data class SparklineState(
    val minValue: Float,
    val maxValue: Float,
    val displayValue: Float,
    val selectedValue: Float?,
)

private data class SparklineGeometry(
    val values: List<Float>,
    val min: Float,
    val range: Float,
    val width: Float,
    val height: Float,
    val path: Path,
) {
    fun xFor(index: Int): Float =
        if (values.size == 1) {
            0f
        } else {
            width * index.toFloat() / values.lastIndex.toFloat()
        }

    fun yFor(index: Int): Float = height - ((values[index] - min) / range) * height

    companion object {
        fun from(
            values: List<Float>,
            width: Float,
            height: Float,
        ): SparklineGeometry {
            val min = values.minOrNull() ?: 0f
            val max = values.maxOrNull() ?: 0f
            val range = (max - min).takeIf { it > 0f } ?: 1f
            val path =
                Path().apply {
                    values.forEachIndexed { index, _ ->
                        val x =
                            if (values.size == 1) {
                                0f
                            } else {
                                width * index.toFloat() / values.lastIndex.toFloat()
                            }
                        val y = height - ((values[index] - min) / range) * height
                        if (index == 0) {
                            moveTo(x, y)
                        } else {
                            lineTo(x, y)
                        }
                    }
                }
            return SparklineGeometry(
                values = values,
                min = min,
                range = range,
                width = width,
                height = height,
                path = path,
            )
        }
    }
}

private fun rememberSparklineState(
    trend: DiagnosticsSparklineUiModel,
    selectedIndex: Int,
): SparklineState {
    val latestValue = trend.values.lastOrNull() ?: 0f
    val selectedValue = trend.values.getOrNull(selectedIndex)
    return SparklineState(
        minValue = trend.values.minOrNull() ?: 0f,
        maxValue = trend.values.maxOrNull() ?: 0f,
        displayValue = selectedValue ?: latestValue,
        selectedValue = selectedValue,
    )
}

@Composable
private fun SparklineValueChip(
    value: String,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
) {
    RipDpiMetricSurface(
        style =
            RipDpiMetricToneStyle(
                container = containerColor,
                content = contentColor,
            ),
        modifier = modifier,
        shape = RipDpiThemeTokens.shapes.sm,
        tonalElevation = 2.dp,
    ) { chipContentColor ->
        Text(
            text = value,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = RipDpiThemeTokens.type.monoSmall,
            color = chipContentColor,
        )
    }
}

private const val SparklineBytesPerKilobyte = 1_000L
private const val SparklineBytesPerMegabyte = 1_000_000L
private const val SparklineBytesPerGigabyte = 1_000_000_000L

private fun formatSparklineValue(value: Float): String {
    val longValue = value.toLong()
    return when {
        longValue >= SparklineBytesPerGigabyte -> {
            String.format(java.util.Locale.US, "%.1f GB", longValue / SparklineBytesPerGigabyte.toDouble())
        }

        longValue >= SparklineBytesPerMegabyte -> {
            String.format(java.util.Locale.US, "%.1f MB", longValue / SparklineBytesPerMegabyte.toDouble())
        }

        longValue >= SparklineBytesPerKilobyte -> {
            String.format(java.util.Locale.US, "%.1f KB", longValue / SparklineBytesPerKilobyte.toDouble())
        }

        longValue > 0L -> {
            "$longValue B"
        }

        else -> {
            "0"
        }
    }
}

private fun Modifier.stateDescription(
    expandedLabel: String,
    collapsedLabel: String,
    expanded: Boolean,
): Modifier =
    semantics {
        stateDescription = if (expanded) expandedLabel else collapsedLabel
    }
