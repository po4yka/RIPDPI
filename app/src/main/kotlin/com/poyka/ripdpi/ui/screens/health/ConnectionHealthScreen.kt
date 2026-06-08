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
        observedAt = 1_000L,
    )
