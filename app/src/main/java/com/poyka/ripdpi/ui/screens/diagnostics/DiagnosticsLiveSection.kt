package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.DiagnosticsHealth
import com.poyka.ripdpi.activities.DiagnosticsLiveUiModel
import com.poyka.ripdpi.activities.DiagnosticsMetricUiModel
import com.poyka.ripdpi.activities.DiagnosticsTone
import com.poyka.ripdpi.activities.DiagnosticsUiState
import com.poyka.ripdpi.ui.components.indicators.StatusIndicator
import com.poyka.ripdpi.ui.components.navigation.SettingsCategoryHeader
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

@Composable
internal fun LiveSection(uiState: DiagnosticsUiState) {
    LiveSectionContent(
        live = uiState.live,
        health = uiState.overview.health,
    )
}

@Composable
private fun LiveSectionContent(
    live: DiagnosticsLiveUiModel,
    health: DiagnosticsHealth,
) {
    val spacing = RipDpiThemeTokens.spacing
    val layout = RipDpiThemeTokens.layout
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding =
            androidx.compose.foundation.layout.PaddingValues(
                horizontal = layout.horizontalPadding,
                vertical = spacing.sm,
            ),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        item {
            LiveHeroCard(
                live = live,
                health = health,
            )
        }
        if (live.metrics.isNotEmpty()) {
            item {
                MetricsRow(metrics = live.metrics)
            }
        }
        if (live.trends.isNotEmpty()) {
            item(key = "live_trends_header") {
                SettingsCategoryHeader(title = stringResource(R.string.diagnostics_trends_section))
            }
            items(live.trends, key = { it.label }) { trend ->
                TelemetrySparkline(trend = trend)
            }
        }
        live.snapshot?.let { snapshot ->
            item { SnapshotCard(snapshot = snapshot) }
        }
        if (live.contextGroups.isNotEmpty()) {
            item(key = "live_context_header") {
                SettingsCategoryHeader(title = stringResource(R.string.diagnostics_context_section))
            }
            items(live.contextGroups, key = { it.title }) { group ->
                ContextGroupCard(group = group)
            }
        }
        if (live.passiveEvents.isNotEmpty()) {
            item(key = "live_passive_events_header") {
                SettingsCategoryHeader(title = stringResource(R.string.diagnostics_passive_events_section))
            }
            items(live.passiveEvents, key = { it.id }) { event ->
                EventRow(event = event, onClick = {})
            }
        }
    }
}

@Composable
internal fun LiveHeroCard(
    live: DiagnosticsLiveUiModel,
    health: DiagnosticsHealth,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val motion = RipDpiThemeTokens.motion
    val palette = liveHeroPalette(health)
    val liveBadgeText = live.networkLabel ?: live.modeLabel ?: "Standby"
    val animatedContainer by animateColorAsState(
        targetValue = palette.container,
        animationSpec = tween(durationMillis = motion.duration(motion.stateDurationMillis)),
        label = "liveHeroContainer",
    )
    val animatedAccent by animateColorAsState(
        targetValue = palette.content,
        animationSpec = tween(durationMillis = motion.duration(motion.stateDurationMillis)),
        label = "liveHeroAccent",
    )
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = animatedContainer,
        contentColor = colors.foreground,
        shape = RipDpiThemeTokens.shapes.xl,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = RipDpiThemeTokens.layout.cardPadding, vertical = spacing.lg),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusIndicator(
                    label = live.statusLabel,
                    tone = health.statusTone(),
                )
                EventBadge(
                    text = liveBadgeText,
                    tone = if (live.networkLabel == null) DiagnosticsTone.Neutral else DiagnosticsTone.Info,
                )
            }
            AnimatedContent(
                targetState = live.headline,
                transitionSpec = {
                    androidx.compose.animation.fadeIn(
                        animationSpec = tween(durationMillis = motion.duration(motion.stateDurationMillis)),
                    ) togetherWith
                        androidx.compose.animation.fadeOut(
                            animationSpec = tween(durationMillis = motion.duration(motion.quickDurationMillis)),
                        )
                },
                label = "liveHeroHeadline",
            ) { headline ->
                Text(
                    text = headline,
                    style = RipDpiThemeTokens.type.screenTitle,
                    color = colors.foreground,
                )
            }
            AnimatedContent(
                targetState = live.body,
                transitionSpec = {
                    androidx.compose.animation.fadeIn(
                        animationSpec = tween(durationMillis = motion.duration(motion.stateDurationMillis)),
                    ) togetherWith
                        androidx.compose.animation.fadeOut(
                            animationSpec = tween(durationMillis = motion.duration(motion.quickDurationMillis)),
                        )
                },
                label = "liveHeroBody",
            ) { body ->
                Text(
                    text = body,
                    style = RipDpiThemeTokens.type.body,
                    color = colors.foreground.copy(alpha = 0.92f),
                )
            }
            if (live.highlights.isNotEmpty()) {
                LiveHighlightsGrid(highlights = live.highlights.take(4))
            }
            HorizontalDivider(color = animatedAccent.copy(alpha = 0.14f))
            Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                AnimatedContent(
                    targetState = live.signalLabel,
                    transitionSpec = {
                        androidx.compose.animation.fadeIn(
                            animationSpec = tween(durationMillis = motion.duration(motion.stateDurationMillis)),
                        ) togetherWith
                            androidx.compose.animation.fadeOut(
                                animationSpec = tween(durationMillis = motion.duration(motion.quickDurationMillis)),
                            )
                    },
                    label = "liveHeroSignalLabel",
                ) { signalLabel ->
                    Text(
                        text = signalLabel,
                        style = RipDpiThemeTokens.type.bodyEmphasis,
                        color = animatedAccent,
                    )
                }
                AnimatedContent(
                    targetState = live.eventSummaryLabel to live.freshnessLabel,
                    transitionSpec = {
                        androidx.compose.animation.fadeIn(
                            animationSpec = tween(durationMillis = motion.duration(motion.stateDurationMillis)),
                        ) togetherWith
                            androidx.compose.animation.fadeOut(
                                animationSpec = tween(durationMillis = motion.duration(motion.quickDurationMillis)),
                            )
                    },
                    label = "liveHeroMeta",
                ) { meta ->
                    Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                        Text(
                            text = meta.first,
                            style = RipDpiThemeTokens.type.secondaryBody,
                            color = colors.foreground.copy(alpha = 0.82f),
                        )
                        Text(
                            text = meta.second,
                            style = RipDpiThemeTokens.type.monoSmall,
                            color = colors.mutedForeground,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun LiveHighlightsGrid(highlights: List<DiagnosticsMetricUiModel>) {
    val spacing = RipDpiThemeTokens.spacing
    val rows = remember(highlights) { highlights.chunked(2) }
    Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                row.forEach { metric ->
                    LiveHighlightCard(
                        metric = metric,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (row.size == 1) {
                    Box(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
internal fun LiveHighlightCard(
    metric: DiagnosticsMetricUiModel,
    modifier: Modifier = Modifier,
) {
    val motion = RipDpiThemeTokens.motion
    val palette = metricPalette(metric.tone)
    val animatedContainer by animateColorAsState(
        targetValue = palette.container.copy(alpha = 0.92f),
        animationSpec = tween(durationMillis = motion.duration(motion.stateDurationMillis)),
        label = "liveHighlightContainer",
    )
    val animatedContent by animateColorAsState(
        targetValue = palette.content,
        animationSpec = tween(durationMillis = motion.duration(motion.stateDurationMillis)),
        label = "liveHighlightContent",
    )
    Surface(
        modifier = modifier,
        color = animatedContainer,
        contentColor = animatedContent,
        shape = RipDpiThemeTokens.shapes.lg,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = metric.label.uppercase(),
                style = RipDpiThemeTokens.type.sectionTitle,
                color = animatedContent.copy(alpha = 0.72f),
            )
            AnimatedContent(
                targetState = metric.value,
                transitionSpec = {
                    androidx.compose.animation.fadeIn(
                        animationSpec = tween(durationMillis = motion.duration(motion.stateDurationMillis)),
                    ) togetherWith
                        androidx.compose.animation.fadeOut(
                            animationSpec = tween(durationMillis = motion.duration(motion.quickDurationMillis)),
                        )
                },
                label = "liveHighlightValue",
            ) { value ->
                Text(
                    text = value,
                    style = RipDpiThemeTokens.type.monoValue,
                    color = animatedContent,
                )
            }
        }
    }
}
