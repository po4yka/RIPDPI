package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.DiagnosticsContextGroupUiModel
import com.poyka.ripdpi.activities.DiagnosticsEventUiModel
import com.poyka.ripdpi.activities.DiagnosticsMetricUiModel
import com.poyka.ripdpi.activities.DiagnosticsNetworkSnapshotUiModel
import com.poyka.ripdpi.activities.DiagnosticsProbeResultUiModel
import com.poyka.ripdpi.activities.DiagnosticsSessionRowUiModel
import com.poyka.ripdpi.activities.DiagnosticsTone
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.RipDpiCardVariant
import com.poyka.ripdpi.ui.components.cards.SettingsRow
import com.poyka.ripdpi.ui.components.indicators.StatusIndicator
import com.poyka.ripdpi.ui.components.indicators.StatusIndicatorTone
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIconSizes
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import kotlin.math.roundToInt

private val SparklineChartHeight = 84.dp
private val SparklineChipWidth = 64.dp
private const val SparklineSelectedMarkerRadius = 5f
private const val SparklineSelectedMarkerInnerRadius = 3f
private const val SparklineStrokeWidth = 4f
private const val SparklineDividerStrokeWidth = 1f

@Composable
internal fun SnapshotCard(snapshot: DiagnosticsNetworkSnapshotUiModel) {
    RipDpiCard {
        Text(
            text = snapshot.title,
            style = RipDpiThemeTokens.type.sectionTitle,
            color = RipDpiThemeTokens.colors.mutedForeground,
        )
        Text(
            text = snapshot.subtitle,
            style = RipDpiThemeTokens.type.secondaryBody,
            color = RipDpiThemeTokens.colors.foreground,
        )
        snapshot.fields.forEachIndexed { index, field ->
            SettingsRow(
                title = field.label,
                value = field.value,
                monospaceValue = field.value.length > 18,
                showDivider = index != snapshot.fields.lastIndex,
            )
        }
    }
}

@Composable
internal fun ContextGroupCard(group: DiagnosticsContextGroupUiModel) {
    RipDpiCard {
        Text(
            text = group.title,
            style = RipDpiThemeTokens.type.sectionTitle,
            color = RipDpiThemeTokens.colors.mutedForeground,
        )
        group.fields.forEachIndexed { index, field ->
            SettingsRow(
                title = field.label,
                value = field.value,
                monospaceValue = field.value.length > 18,
                showDivider = index != group.fields.lastIndex,
            )
        }
    }
}

@Composable
internal fun MetricsRow(metrics: List<DiagnosticsMetricUiModel>) {
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
    val motion = RipDpiThemeTokens.motion
    val palette = metricPalette(metric.tone)
    val animatedContainer by animateColorAsState(
        targetValue = palette.container,
        animationSpec = tween(durationMillis = motion.duration(motion.stateDurationMillis)),
        label = "telemetryMetricContainer",
    )
    val animatedContent by animateColorAsState(
        targetValue = palette.content,
        animationSpec = tween(durationMillis = motion.duration(motion.stateDurationMillis)),
        label = "telemetryMetricContent",
    )
    Surface(
        color = animatedContainer,
        contentColor = animatedContent,
        shape = MaterialTheme.shapes.large,
    ) {
        Column(
            modifier =
                Modifier
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = metric.label.uppercase(),
                style = RipDpiThemeTokens.type.sectionTitle,
                color = animatedContent.copy(alpha = 0.75f),
            )
            Text(
                text = metric.value,
                style = RipDpiThemeTokens.type.monoValue,
                color = animatedContent,
            )
        }
    }
}

@Composable
internal fun TelemetrySparkline(trend: com.poyka.ripdpi.activities.DiagnosticsSparklineUiModel) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val motion = RipDpiThemeTokens.motion
    val palette = metricPalette(trend.tone)
    val dividerColor = colors.divider
    val animatedStrokeColor by animateColorAsState(
        targetValue = palette.content,
        animationSpec = tween(durationMillis = motion.duration(motion.stateDurationMillis)),
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
            animationSpec = tween(durationMillis = motion.duration(motion.emphasizedDurationMillis)),
        )
    }

    val sparklineState = rememberSparklineState(trend = trend, selectedIndex = selectedIndex)

    RipDpiCard {
        SparklineHeader(
            label = trend.label,
            displayValue = sparklineState.displayValue,
            valueColor = palette.content,
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
            chipContainerColor = palette.container,
            chipContentColor = palette.content,
        )
    }
}

@Composable
private fun SparklineHeader(
    label: String,
    displayValue: Float,
    valueColor: androidx.compose.ui.graphics.Color,
    labelColor: androidx.compose.ui.graphics.Color,
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
    spacing: androidx.compose.ui.unit.Dp,
    labelColor: androidx.compose.ui.graphics.Color,
    values: List<Float>,
    selectedIndex: Int,
    onSelectedIndexChange: (Int) -> Unit,
    strokeColor: androidx.compose.ui.graphics.Color,
    dividerColor: androidx.compose.ui.graphics.Color,
    selectionColor: androidx.compose.ui.graphics.Color,
    cardColor: androidx.compose.ui.graphics.Color,
    selectedValue: Float?,
    chipContainerColor: androidx.compose.ui.graphics.Color,
    chipContentColor: androidx.compose.ui.graphics.Color,
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
    labelColor: androidx.compose.ui.graphics.Color,
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
    strokeColor: androidx.compose.ui.graphics.Color,
    dividerColor: androidx.compose.ui.graphics.Color,
    selectionColor: androidx.compose.ui.graphics.Color,
    cardColor: androidx.compose.ui.graphics.Color,
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
    maxWidth: androidx.compose.ui.unit.Dp,
    containerColor: androidx.compose.ui.graphics.Color,
    contentColor: androidx.compose.ui.graphics.Color,
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

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSparklineSelection(
    geometry: SparklineGeometry,
    selectedIndex: Int,
    selectionColor: androidx.compose.ui.graphics.Color,
    strokeColor: androidx.compose.ui.graphics.Color,
    cardColor: androidx.compose.ui.graphics.Color,
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
    trend: com.poyka.ripdpi.activities.DiagnosticsSparklineUiModel,
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
    containerColor: androidx.compose.ui.graphics.Color,
    contentColor: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = containerColor,
        contentColor = contentColor,
        shape = RipDpiThemeTokens.shapes.sm,
        tonalElevation = 2.dp,
    ) {
        Text(
            text = value,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = RipDpiThemeTokens.type.monoSmall,
            color = contentColor,
        )
    }
}

@Composable
internal fun SessionRow(
    session: DiagnosticsSessionRowUiModel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    RipDpiCard(
        modifier = modifier,
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.xs),
            ) {
                Text(
                    text = session.title,
                    style = RipDpiThemeTokens.type.bodyEmphasis,
                    color = RipDpiThemeTokens.colors.foreground,
                )
                Text(
                    text = session.subtitle,
                    style = RipDpiThemeTokens.type.secondaryBody,
                    color = RipDpiThemeTokens.colors.mutedForeground,
                )
            }
            StatusIndicator(
                label = session.status,
                tone = statusTone(session.tone),
            )
        }
        MetricsRow(metrics = session.metrics)
    }
}

@Composable
internal fun TelegramResultCard(
    probe: DiagnosticsProbeResultUiModel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val details = probe.details.associate { it.label to it.value }
    val verdict = details["verdict"] ?: probe.outcome
    val verdictTone = statusTone(probe.tone)

    RipDpiCard(
        modifier = modifier,
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Telegram",
                style = RipDpiThemeTokens.type.screenTitle,
                color = colors.foreground,
            )
            StatusIndicator(label = verdict, tone = verdictTone)
        }

        TelegramTransferSection(
            label = stringResource(R.string.diagnostics_telegram_download),
            status = details["downloadStatus"] ?: "unknown",
            avgBps = details["downloadAvgBps"],
            peakBps = details["downloadPeakBps"],
            bytes = details["downloadBytes"],
            durationMs = details["downloadDurationMs"],
            error = details["downloadError"],
        )

        TelegramTransferSection(
            label = stringResource(R.string.diagnostics_telegram_upload),
            status = details["uploadStatus"] ?: "unknown",
            avgBps = details["uploadAvgBps"],
            peakBps = details["uploadPeakBps"],
            bytes = details["uploadBytes"],
            durationMs = details["uploadDurationMs"],
            error = details["uploadError"],
        )

        val dcReachable = details["dcReachable"] ?: "0"
        val dcTotal = details["dcTotal"] ?: "0"
        val dcResults = details["dcResults"]?.split("|")?.filter { it.isNotEmpty() } ?: emptyList()

        SettingsRow(
            title = stringResource(R.string.diagnostics_telegram_data_centers),
            value = stringResource(R.string.diagnostics_telegram_reachable_format, dcReachable, dcTotal),
        )
        if (dcResults.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                dcResults.forEach { dc ->
                    val parts = dc.split(":")
                    val label = parts.getOrNull(0) ?: "?"
                    val ok = parts.getOrNull(1) == "ok"
                    val rtt = parts.getOrNull(2) ?: ""
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        StatusIndicator(
                            label = label,
                            tone = if (ok) StatusIndicatorTone.Active else StatusIndicatorTone.Error,
                        )
                        if (rtt.isNotEmpty()) {
                            Text(
                                text = rtt,
                                style = RipDpiThemeTokens.type.monoSmall,
                                color = colors.mutedForeground,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TelegramTransferSection(
    label: String,
    status: String,
    avgBps: String?,
    peakBps: String?,
    bytes: String?,
    durationMs: String?,
    error: String?,
) {
    val colors = RipDpiThemeTokens.colors
    val avgSpeed = formatBps(avgBps?.toLongOrNull() ?: 0)
    val peakSpeed = formatBps(peakBps?.toLongOrNull() ?: 0)
    val totalBytes = formatTransferBytes(bytes?.toLongOrNull() ?: 0)
    val tone =
        when (status) {
            "ok" -> StatusIndicatorTone.Active
            "slow", "stalled" -> StatusIndicatorTone.Warning
            else -> StatusIndicatorTone.Error
        }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = RipDpiThemeTokens.type.bodyEmphasis,
            color = colors.foreground,
        )
        StatusIndicator(label = status, tone = tone)
    }
    SettingsRow(title = stringResource(R.string.diagnostics_telegram_avg_speed), value = avgSpeed)
    SettingsRow(title = stringResource(R.string.diagnostics_telegram_peak_speed), value = peakSpeed)
    SettingsRow(title = stringResource(R.string.diagnostics_telegram_transferred), value = totalBytes)
    if (error != null && error != "none") {
        SettingsRow(title = stringResource(R.string.diagnostics_telegram_error), value = error)
    }
}

private fun formatBps(bps: Long): String =
    when {
        bps >= 1_000_000 -> String.format(java.util.Locale.US, "%.1f Mbps", bps / 1_000_000.0)
        bps >= 1_000 -> String.format(java.util.Locale.US, "%.1f Kbps", bps / 1_000.0)
        else -> "$bps Bps"
    }

private fun formatTransferBytes(bytes: Long): String =
    when {
        bytes >= 1_000_000 -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1_000_000.0)
        bytes >= 1_000 -> String.format(java.util.Locale.US, "%.1f KB", bytes / 1_000.0)
        else -> "$bytes B"
    }

@Composable
internal fun ProbeResultRow(
    probe: DiagnosticsProbeResultUiModel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    RipDpiCard(
        modifier = modifier,
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.xs),
            ) {
                Text(
                    text = probe.target,
                    style = RipDpiThemeTokens.type.bodyEmphasis,
                    color = colors.foreground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = probe.probeType,
                    style = RipDpiThemeTokens.type.secondaryBody,
                    color = colors.mutedForeground,
                )
            }
            StatusIndicator(
                label = probe.outcome,
                tone = statusTone(probe.tone),
            )
        }
        probe.details.take(2).forEach { detail ->
            SettingsRow(
                title = detail.label,
                value = detail.value,
                monospaceValue = true,
            )
        }
    }
}

@Composable
internal fun EventRow(
    event: DiagnosticsEventUiModel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    RipDpiCard(
        modifier = modifier,
        onClick = onClick,
        paddingValues =
            androidx.compose.foundation.layout
                .PaddingValues(RipDpiThemeTokens.layout.cardPadding),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.xs),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    EventBadge(text = event.source, tone = event.tone)
                    EventBadge(text = event.severity, tone = event.tone)
                }
                Text(
                    text = event.message,
                    style = RipDpiThemeTokens.type.body,
                    color = colors.foreground,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = event.createdAtLabel,
                style = RipDpiThemeTokens.type.monoSmall,
                color = colors.mutedForeground,
            )
        }
    }
}

@Composable
internal fun EventBadge(
    text: String,
    tone: DiagnosticsTone,
) {
    val palette = metricPalette(tone)
    Surface(
        color = palette.container,
        contentColor = palette.content,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = RipDpiThemeTokens.type.monoSmall,
            color = palette.content,
        )
    }
}

@Composable
internal fun DiagnosticsPreviewCard(
    title: String,
    body: String,
    metrics: List<DiagnosticsMetricUiModel>,
    archiveStateMessage: String?,
    archiveStateTone: DiagnosticsTone,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val paragraphs = body.split("\n\n").filter { it.isNotBlank() }
    val description = paragraphs.firstOrNull() ?: ""
    val detailParagraphs = paragraphs.drop(1)
    val descriptionLines = description.lines()
    val headerLines = descriptionLines.takeWhile { line -> !isDataLine(line) }
    val dataLines = descriptionLines.drop(headerLines.size)

    RipDpiCard(
        modifier = Modifier.ripDpiTestTag(RipDpiTestTags.DiagnosticsSharePreviewCard),
        variant = RipDpiCardVariant.Elevated,
    ) {
        Text(
            text = title,
            style = RipDpiThemeTokens.type.screenTitle,
            color = colors.foreground,
        )
        if (headerLines.isNotEmpty()) {
            Text(
                text = headerLines.joinToString("\n"),
                style = RipDpiThemeTokens.type.secondaryBody,
                color = colors.mutedForeground,
            )
        }
        if (dataLines.isNotEmpty()) {
            HorizontalDivider(color = colors.divider)
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(colors.inputBackground, RipDpiThemeTokens.shapes.md)
                        .padding(spacing.md),
                verticalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                dataLines.forEach { line ->
                    Text(
                        text = line,
                        style = RipDpiThemeTokens.type.monoSmall,
                        color = colors.mutedForeground,
                    )
                }
            }
        }
        detailParagraphs.forEach { paragraph ->
            Text(
                text = paragraph,
                style = RipDpiThemeTokens.type.secondaryBody,
                color = colors.mutedForeground,
            )
        }
        MetricsRow(metrics = metrics)
        archiveStateMessage?.let { message ->
            StatusIndicator(
                label = message,
                modifier = Modifier.ripDpiTestTag(RipDpiTestTags.DiagnosticsArchiveStateIndicator),
                tone = statusTone(archiveStateTone),
            )
        }
    }
}

@Composable
internal fun EmptyStateCard(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
) {
    RipDpiCard(modifier = modifier) {
        Text(
            text = title,
            style = RipDpiThemeTokens.type.bodyEmphasis,
            color = RipDpiThemeTokens.colors.foreground,
        )
        Text(
            text = body,
            style = RipDpiThemeTokens.type.body,
            color = RipDpiThemeTokens.colors.mutedForeground,
        )
    }
}

@Composable
internal fun ShareActionCard(
    title: String,
    body: String,
    buttonLabel: String,
    onClick: () -> Unit,
    iconTint: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
    variant: RipDpiButtonVariant = RipDpiButtonVariant.Primary,
    enabled: Boolean = true,
) {
    RipDpiCard(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.xs),
            ) {
                Text(
                    text = title,
                    style = RipDpiThemeTokens.type.bodyEmphasis,
                    color = RipDpiThemeTokens.colors.foreground,
                )
                Text(
                    text = body,
                    style = RipDpiThemeTokens.type.secondaryBody,
                    color = RipDpiThemeTokens.colors.mutedForeground,
                )
            }
            androidx.compose.material3.Icon(
                imageVector = RipDpiIcons.Share,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(RipDpiIconSizes.Medium),
            )
        }
        RipDpiButton(
            text = buttonLabel,
            onClick = onClick,
            variant = variant,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private val dataLinePrefixes =
    listOf(
        "Session ",
        "Network ",
        "Context ",
        "Permissions ",
        "Live ",
        "Top warning",
        "Telegram",
    )

private const val SparklineBytesPerKilobyte = 1_000L
private const val SparklineBytesPerMegabyte = 1_000_000L
private const val SparklineBytesPerGigabyte = 1_000_000_000L

private fun isDataLine(line: String): Boolean =
    dataLinePrefixes.any { line.startsWith(it) } || line.contains("probe results")

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
