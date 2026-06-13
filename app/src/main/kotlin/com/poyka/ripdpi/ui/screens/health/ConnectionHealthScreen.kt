package com.poyka.ripdpi.ui.screens.health

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.poyka.ripdpi.ui.components.scaffold.RipDpiContentScreenScaffold
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

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
            LatencyDistributionsCard(distributions = uiState.latencyDistributions)
        }
        uiState.dnsCounters?.let { counters ->
            DnsCountersCard(counters = counters)
        }
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
    val qualityLine =
        when {
            uiState.qualityLossPercent != null && uiState.qualityRttP50Ms != null -> {
                stringResource(
                    R.string.connection_health_quality_format,
                    uiState.qualityLossPercent,
                    uiState.qualityRttP50Ms,
                )
            }

            uiState.qualityLossPercent != null -> {
                stringResource(R.string.connection_health_quality_loss_format, uiState.qualityLossPercent)
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
        LinearProgressIndicator(
            progress = { (rate ?: 0) / 100f },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            ConnectionHealthMetric(
                label = stringResource(R.string.connection_health_successes),
                value = row.successCount.toString(),
                modifier = Modifier.weight(1f),
            )
            ConnectionHealthMetric(
                label = stringResource(R.string.connection_health_failures),
                value = row.failureCount.toString(),
                modifier = Modifier.weight(1f),
            )
            ConnectionHealthMetric(
                label = stringResource(R.string.connection_health_samples),
                value = row.totalCount.toString(),
                modifier = Modifier.weight(1f),
            )
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
private fun LatencyDistributionsCard(distributions: List<LatencyDistributionUiState>) {
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
        LinearProgressIndicator(
            progress = { fraction },
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
private fun DnsCountersCard(counters: DnsCountersUiState) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    RipDpiCard {
        Text(
            text = stringResource(R.string.connection_health_dns_section_title),
            style = RipDpiThemeTokens.type.sectionTitle,
            color = colors.foreground,
        )
        counters.cacheHitRatePercent?.let { rate ->
            Text(
                text = stringResource(R.string.connection_health_dns_hit_rate_format, rate),
                style = RipDpiThemeTokens.type.caption,
                color = colors.mutedForeground,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = spacing.xs),
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            ConnectionHealthMetric(
                label = stringResource(R.string.connection_health_dns_queries),
                value = counters.queriesTotal.toString(),
                modifier = Modifier.weight(1f),
            )
            ConnectionHealthMetric(
                label = stringResource(R.string.connection_health_dns_cache_hits),
                value = counters.cacheHits.toString(),
                modifier = Modifier.weight(1f),
            )
            ConnectionHealthMetric(
                label = stringResource(R.string.connection_health_dns_cache_misses),
                value = counters.cacheMisses.toString(),
                modifier = Modifier.weight(1f),
            )
            ConnectionHealthMetric(
                label = stringResource(R.string.connection_health_dns_failures),
                value = counters.failuresTotal.toString(),
                modifier = Modifier.weight(1f),
            )
        }
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

fun previewConnectionHealthUiState(): ConnectionHealthUiState =
    ConnectionHealthUiState(
        rows =
            listOf(
                ConnectionHealthRowUiState(
                    destinationClass = ConnectionHealthDestinationClass.VK,
                    activeStrategy = "tcp:split2+hostfake",
                    successCount = 18,
                    failureCount = 2,
                    attributedCount = 12,
                ),
                ConnectionHealthRowUiState(
                    destinationClass = ConnectionHealthDestinationClass.YOUTUBE,
                    activeStrategy = "quic:sni_split",
                    successCount = 9,
                    failureCount = 6,
                    attributedCount = 7,
                ),
                ConnectionHealthRowUiState(
                    destinationClass = ConnectionHealthDestinationClass.TELEGRAM,
                    activeStrategy = "telegram_ws_cover",
                    successCount = 21,
                    failureCount = 0,
                    attributedCount = 13,
                ),
                ConnectionHealthRowUiState(
                    destinationClass = ConnectionHealthDestinationClass.GENERIC_TLS,
                    activeStrategy = "tcp:record_split",
                    successCount = 11,
                    failureCount = 4,
                    attributedCount = 5,
                ),
            ),
        qualityLossPercent = 8,
        qualityRttP50Ms = 61,
        latencyDistributions =
            listOf(
                LatencyDistributionUiState(
                    kind = LatencyDistributionKind.DnsResolution,
                    p50Ms = 18,
                    p95Ms = 44,
                    p99Ms = 91,
                    minMs = 6,
                    maxMs = 102,
                    count = 312,
                ),
                LatencyDistributionUiState(
                    kind = LatencyDistributionKind.TcpConnect,
                    p50Ms = 52,
                    p95Ms = 140,
                    p99Ms = 220,
                    minMs = 31,
                    maxMs = 240,
                    count = 188,
                ),
                LatencyDistributionUiState(
                    kind = LatencyDistributionKind.TlsHandshake,
                    p50Ms = 96,
                    p95Ms = 260,
                    p99Ms = 410,
                    minMs = 60,
                    maxMs = 430,
                    count = 174,
                ),
            ),
        dnsCounters =
            DnsCountersUiState(
                queriesTotal = 1_204,
                cacheHits = 938,
                cacheMisses = 266,
                failuresTotal = 12,
            ),
        observedAt = 1_000L,
    )
