package com.poyka.ripdpi.ui.screens.health

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.poyka.ripdpi.R
import com.poyka.ripdpi.services.ConnectionHealthDestinationClass
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.RipDpiCardVariant
import com.poyka.ripdpi.ui.components.chrome.RipDpiEmptyStateCard
import com.poyka.ripdpi.ui.components.indicators.RipDpiProgressBar
import com.poyka.ripdpi.ui.components.scaffold.RipDpiContentScreenScaffold
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import java.text.NumberFormat

private const val AccessibilityMetricFontScale = 1.5f
private const val AccessibilityMetricColumns = 2
private const val MillisPerMinute = 60_000L
private const val LossFractionDigits = 9

@Composable
fun ConnectionHealthRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ConnectionHealthViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    ConnectionHealthScreen(
        uiState = uiState,
        onBack = onBack,
        modifier = modifier,
    )
}

@Composable
fun ConnectionHealthScreen(
    uiState: ConnectionHealthUiState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    RipDpiContentScreenScaffold(
        title = stringResource(R.string.title_connection_health),
        navigationIcon = RipDpiIcons.Back,
        onNavigationClick = onBack,
        modifier = modifier.ripDpiTestTag(RipDpiTestTags.screen(Route.ConnectionHealth)),
    ) {
        ConnectionHealthSummary(uiState)
        if (uiState.latencyDistributions.isNotEmpty()) {
            LatencyDistributionsCard(distributions = uiState.latencyDistributions, source = uiState.latencySource)
        }
        uiState.dnsCounters?.let { counters ->
            DnsCountersCard(counters = counters, source = uiState.dnsSource)
        }
        MeasurementCaption(
            stringResource(
                R.string.measurement_observation_scope,
                uiState.observationWindowMillis / MillisPerMinute,
                uiState.observationLimit,
                uiState.observedAt.takeIf { it > 0L }?.let { measurementTimestamp(it) }
                    ?: stringResource(R.string.diagnostics_field_unknown),
            ),
        )
        uiState.rows.forEach { row ->
            ConnectionHealthRow(row = row)
        }
        if (!uiState.hasData) {
            RipDpiEmptyStateCard(
                title = stringResource(R.string.connection_health_empty_title),
                body = stringResource(R.string.connection_health_empty_body),
            )
        }
    }
}

@Composable
private fun ConnectionHealthSummary(uiState: ConnectionHealthUiState) {
    val loss =
        uiState.qualityLossPercent?.let {
            NumberFormat
                .getNumberInstance(LocalLocale.current.platformLocale)
                .apply {
                    maximumFractionDigits = LossFractionDigits
                }.format(it.toString().toBigDecimal())
        }
    val qualityLine =
        when {
            uiState.qualityLossPercent != null && uiState.qualityRttP50Ms != null -> {
                stringResource(
                    R.string.connection_health_quality_format,
                    loss ?: "—",
                    uiState.qualityRttP50Ms,
                )
            }

            uiState.qualityLossPercent != null -> {
                stringResource(R.string.connection_health_quality_loss_format, loss ?: "—")
            }

            uiState.qualityRttP50Ms != null -> {
                stringResource(R.string.measurement_quality_rtt, uiState.qualityRttP50Ms)
            }

            else -> {
                stringResource(R.string.connection_health_quality_waiting)
            }
        }
    RipDpiCard(variant = RipDpiCardVariant.Tonal) {
        Text(
            text = stringResource(R.string.connection_health_summary_title),
            style = RipDpiThemeTokens.type.sectionTitle,
            color = RipDpiThemeTokens.colors.foreground,
        )
        Text(
            text = qualityLine,
            style = RipDpiThemeTokens.type.body,
            color = RipDpiThemeTokens.colors.mutedForeground,
        )
        if (uiState.qualitySampleCount != null) {
            MeasurementCaption(stringResource(R.string.measurement_quality_meaning))
            MeasurementCaption(
                stringResource(
                    R.string.measurement_quality_samples,
                    uiState.qualitySampleCount,
                    uiState.qualityJitterMs?.toString() ?: "—",
                ),
            )
            MeasurementCaption(
                uiState.qualityWindowStartAtMs?.let {
                    stringResource(R.string.measurement_quality_window, measurementTimestamp(it))
                } ?: stringResource(R.string.measurement_window_unknown),
            )
            uiState.qualityRttP95Ms?.let {
                MeasurementCaption(stringResource(R.string.measurement_quality_p95, it))
            }
            MeasurementSource(uiState.qualitySource)
        }
    }
}

@Composable
private fun ConnectionHealthRow(row: ConnectionHealthRowUiState) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val rate = row.successRatePercent
    RipDpiCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = row.destinationClass.label(),
                    style = RipDpiThemeTokens.type.bodyEmphasis,
                    color = colors.foreground,
                )
                Text(
                    text = row.activeStrategy ?: stringResource(R.string.connection_health_strategy_waiting),
                    style = RipDpiThemeTokens.type.caption,
                    color = colors.mutedForeground,
                )
            }
            Text(
                text =
                    rate?.let { stringResource(R.string.connection_health_rate_format, it) }
                        ?: stringResource(R.string.connection_health_rate_unknown),
                style = RipDpiThemeTokens.type.screenTitleEmphasis,
                color = colors.foreground,
            )
        }
        RipDpiProgressBar(progress = (rate ?: 0) / 100f)
        ConnectionHealthMetrics(
            metrics =
                listOf(
                    stringResource(R.string.connection_health_successes) to row.successCount.toString(),
                    stringResource(R.string.connection_health_failures) to row.failureCount.toString(),
                    stringResource(R.string.connection_health_samples) to row.totalCount.toString(),
                ),
        )
    }
}

@Composable
private fun ConnectionHealthMetrics(
    metrics: List<Pair<String, String>>,
    modifier: Modifier = Modifier,
) {
    val spacing = RipDpiThemeTokens.spacing
    if (LocalDensity.current.fontScale >= AccessibilityMetricFontScale) {
        FlowRow(
            modifier = modifier.fillMaxWidth(),
            maxItemsInEachRow = AccessibilityMetricColumns,
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            metrics.forEach { (label, value) ->
                ConnectionHealthMetric(
                    label = label,
                    value = value,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    } else {
        Row(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            metrics.forEach { (label, value) ->
                ConnectionHealthMetric(
                    label = label,
                    value = value,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun ConnectionHealthMetric(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    val locale = LocalLocale.current.platformLocale
    Column(modifier = modifier.padding(top = RipDpiThemeTokens.spacing.xs)) {
        Text(
            text = value,
            style = RipDpiThemeTokens.type.bodyEmphasis,
            color = RipDpiThemeTokens.colors.foreground,
        )
        Text(
            text = label.uppercase(locale),
            style = RipDpiThemeTokens.type.caption,
            color = RipDpiThemeTokens.colors.mutedForeground,
        )
    }
}

@Composable
private fun ConnectionHealthDestinationClass.label(): String =
    when (this) {
        ConnectionHealthDestinationClass.VK -> {
            stringResource(R.string.connection_health_destination_vk)
        }

        ConnectionHealthDestinationClass.YOUTUBE -> {
            stringResource(R.string.connection_health_destination_youtube)
        }

        ConnectionHealthDestinationClass.TELEGRAM -> {
            stringResource(R.string.connection_health_destination_telegram)
        }

        ConnectionHealthDestinationClass.GENERIC_TLS -> {
            stringResource(R.string.connection_health_destination_generic_tls)
        }
    }

@Composable
private fun LatencyDistributionsCard(
    distributions: List<LatencyDistributionUiState>,
    source: com.poyka.ripdpi.service.telemetry.RuntimeMeasurementSource?,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    RipDpiCard {
        Text(
            text = stringResource(R.string.connection_health_latency_section_title),
            style = RipDpiThemeTokens.type.sectionTitle,
            color = colors.foreground,
        )
        Text(
            text = stringResource(R.string.connection_health_latency_caption),
            style = RipDpiThemeTokens.type.caption,
            color = colors.mutedForeground,
        )
        MeasurementCaption(stringResource(R.string.measurement_percentiles_meaning))
        MeasurementCaption(stringResource(R.string.measurement_runtime_scope))
        MeasurementSource(source)
        distributions.forEach { distribution ->
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = spacing.sm),
                verticalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                Text(
                    text = distribution.kind.label(),
                    style = RipDpiThemeTokens.type.bodyEmphasis,
                    color = colors.foreground,
                )
                PercentileBar(label = "p50", valueMs = distribution.p50Ms, scaleMs = distribution.scaleMs)
                PercentileBar(label = "p95", valueMs = distribution.p95Ms, scaleMs = distribution.scaleMs)
                PercentileBar(label = "p99", valueMs = distribution.p99Ms, scaleMs = distribution.scaleMs)
                Text(
                    text =
                        stringResource(
                            R.string.connection_health_latency_range_format,
                            distribution.minMs,
                            distribution.maxMs,
                            distribution.count,
                        ),
                    style = RipDpiThemeTokens.type.caption,
                    color = colors.mutedForeground,
                )
            }
        }
    }
}

@Composable
private fun PercentileBar(
    label: String,
    valueMs: Long,
    scaleMs: Long,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val fraction = (valueMs.toFloat() / scaleMs.toFloat()).coerceIn(0f, 1f)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = RipDpiThemeTokens.type.monoSmall,
            color = colors.mutedForeground,
        )
        RipDpiProgressBar(
            progress = fraction,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = stringResource(R.string.connection_health_latency_value_ms, valueMs),
            style = RipDpiThemeTokens.type.monoSmall,
            color = colors.foreground,
        )
    }
}

@Composable
private fun DnsCountersCard(
    counters: DnsCountersUiState,
    source: com.poyka.ripdpi.service.telemetry.RuntimeMeasurementSource?,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    RipDpiCard {
        Text(
            text = stringResource(R.string.connection_health_dns_section_title),
            style = RipDpiThemeTokens.type.sectionTitle,
            color = colors.foreground,
        )
        MeasurementCaption(stringResource(R.string.measurement_runtime_scope))
        MeasurementSource(source)
        counters.cacheHitRatePercent?.let { rate ->
            Text(
                text = stringResource(R.string.connection_health_dns_hit_rate_format, rate),
                style = RipDpiThemeTokens.type.caption,
                color = colors.mutedForeground,
            )
        }
        ConnectionHealthMetrics(
            metrics =
                listOf(
                    stringResource(R.string.connection_health_dns_queries) to counters.queriesTotal.toString(),
                    stringResource(R.string.connection_health_dns_cache_hits) to counters.cacheHits.toString(),
                    stringResource(R.string.connection_health_dns_cache_misses) to counters.cacheMisses.toString(),
                    stringResource(R.string.connection_health_dns_failures) to counters.failuresTotal.toString(),
                ),
            modifier = Modifier.padding(top = spacing.xs),
        )
    }
}

@Composable
private fun LatencyDistributionKind.label(): String =
    when (this) {
        LatencyDistributionKind.DnsResolution -> stringResource(R.string.connection_health_latency_dns)
        LatencyDistributionKind.TcpConnect -> stringResource(R.string.connection_health_latency_tcp)
        LatencyDistributionKind.TlsHandshake -> stringResource(R.string.connection_health_latency_tls)
    }

@Preview(
    showBackground = true,
    widthDp = 420,
    heightDp = 900,
)
@Composable
private fun ConnectionHealthScreenPreview() {
    RipDpiTheme {
        ConnectionHealthScreen(uiState = previewConnectionHealthUiState(), onBack = {})
    }
}
