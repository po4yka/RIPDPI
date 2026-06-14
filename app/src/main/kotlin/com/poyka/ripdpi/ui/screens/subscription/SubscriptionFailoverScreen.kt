package com.poyka.ripdpi.ui.screens.subscription

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.poyka.ripdpi.R
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
fun SubscriptionFailoverRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SubscriptionFailoverViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    SubscriptionFailoverScreen(
        uiState = uiState,
        onBack = onBack,
        modifier = modifier,
    )
}

@Composable
fun SubscriptionFailoverScreen(
    uiState: SubscriptionFailoverUiState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    RipDpiContentScreenScaffold(
        title = stringResource(R.string.title_subscription_failover),
        navigationIcon = RipDpiIcons.Back,
        onNavigationClick = onBack,
        modifier = modifier.ripDpiTestTag(RipDpiTestTags.screen(Route.SubscriptionFailover)),
    ) {
        SubscriptionFailoverSummary(uiState)
        if (uiState.hasServers) {
            uiState.servers.forEach { server ->
                SubscriptionServerRow(server)
            }
            SubscriptionFailoverTimeline(uiState.events)
        } else {
            RipDpiEmptyStateCard(
                title = stringResource(R.string.subscription_failover_empty_title),
                body = stringResource(R.string.subscription_failover_empty_body),
            )
        }
    }
}

@Composable
private fun SubscriptionFailoverSummary(uiState: SubscriptionFailoverUiState) {
    RipDpiCard(variant = RipDpiCardVariant.Tonal) {
        Text(
            text = stringResource(R.string.subscription_failover_summary_title),
            style = RipDpiThemeTokens.type.sectionTitle,
            color = RipDpiThemeTokens.colors.foreground,
        )
        Text(
            text = uiState.summary,
            style = RipDpiThemeTokens.type.body,
            color = RipDpiThemeTokens.colors.mutedForeground,
        )
        Text(
            text = uiState.activeServerLabel,
            style = RipDpiThemeTokens.type.caption,
            color = RipDpiThemeTokens.colors.mutedForeground,
        )
    }
}

@Composable
private fun SubscriptionServerRow(server: SubscriptionServerUiState) {
    RipDpiCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = server.name,
                    style = RipDpiThemeTokens.type.bodyEmphasis,
                    color = RipDpiThemeTokens.colors.foreground,
                )
                Text(
                    text = "${server.positionLabel} · ${server.endpoint}",
                    style = RipDpiThemeTokens.type.caption,
                    color = RipDpiThemeTokens.colors.mutedForeground,
                )
            }
            Text(
                text = stringResource(server.status.labelRes),
                style = RipDpiThemeTokens.type.bodyEmphasis,
                color = server.status.color(),
            )
        }
        Text(
            text = server.detail,
            style = RipDpiThemeTokens.type.caption,
            color = RipDpiThemeTokens.colors.mutedForeground,
        )
    }
}

@Composable
private fun SubscriptionFailoverTimeline(events: List<SubscriptionFailoverEventUiState>) {
    RipDpiCard {
        Text(
            text = stringResource(R.string.subscription_failover_timeline_title),
            style = RipDpiThemeTokens.type.sectionTitle,
            color = RipDpiThemeTokens.colors.foreground,
        )
        if (events.isEmpty()) {
            Text(
                text = stringResource(R.string.subscription_failover_timeline_empty),
                style = RipDpiThemeTokens.type.body,
                color = RipDpiThemeTokens.colors.mutedForeground,
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.sm)) {
                events.forEach { event ->
                    Text(
                        text = "${event.message} · ${event.timeLabel}",
                        style = RipDpiThemeTokens.type.body,
                        color = RipDpiThemeTokens.colors.foreground,
                    )
                }
            }
        }
    }
}

@Composable
private fun SubscriptionServerStatus.color() =
    when (this) {
        SubscriptionServerStatus.Up -> RipDpiThemeTokens.colors.success
        SubscriptionServerStatus.Checking -> RipDpiThemeTokens.colors.warning
        SubscriptionServerStatus.Down -> RipDpiThemeTokens.colors.destructive
        SubscriptionServerStatus.Unknown -> RipDpiThemeTokens.colors.mutedForeground
    }

@Preview(showBackground = true, widthDp = 420, heightDp = 900)
@Composable
private fun SubscriptionFailoverScreenPreview() {
    RipDpiTheme {
        SubscriptionFailoverScreen(
            uiState = previewSubscriptionFailoverUiState(),
            onBack = {},
        )
    }
}

fun previewSubscriptionFailoverUiState(): SubscriptionFailoverUiState =
    SubscriptionFailoverUiState(
        summary = "server 2/4 up · switched to backup · last check 30s ago",
        lastCheck = "last check 30s ago",
        activeServerLabel = "Server 2: backup.example.net",
        servers =
            listOf(
                SubscriptionServerUiState(
                    id = "primary",
                    name = "primary.example.net",
                    endpoint = "primary.example.net:443",
                    status = SubscriptionServerStatus.Unknown,
                    positionLabel = "server 1/4",
                    detail = "available if the app switches",
                ),
                SubscriptionServerUiState(
                    id = "backup",
                    name = "backup.example.net",
                    endpoint = "backup.example.net:443",
                    status = SubscriptionServerStatus.Up,
                    positionLabel = "server 2/4",
                    detail = "current server",
                ),
                SubscriptionServerUiState(
                    id = "edge",
                    name = "edge.example.net",
                    endpoint = "edge.example.net:443",
                    status = SubscriptionServerStatus.Unknown,
                    positionLabel = "server 3/4",
                    detail = "available if the app switches",
                ),
                SubscriptionServerUiState(
                    id = "last",
                    name = "last.example.net",
                    endpoint = "last.example.net:443",
                    status = SubscriptionServerStatus.Unknown,
                    positionLabel = "server 4/4",
                    detail = "available if the app switches",
                ),
            ),
        events =
            listOf(
                SubscriptionFailoverEventUiState(
                    message = "switched to backup",
                    timeLabel = "14:02",
                ),
                SubscriptionFailoverEventUiState(
                    message = "primary server stopped answering",
                    timeLabel = "14:01",
                ),
            ),
    )
