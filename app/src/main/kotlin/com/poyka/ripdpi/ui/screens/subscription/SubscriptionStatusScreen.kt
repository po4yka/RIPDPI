package com.poyka.ripdpi.ui.screens.subscription

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.poyka.ripdpi.R
import com.poyka.ripdpi.subscription.SubscriptionExpiryItemUiState
import com.poyka.ripdpi.subscription.SubscriptionExpiryStatus
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.chrome.RipDpiEmptyStateCard
import com.poyka.ripdpi.ui.components.scaffold.RipDpiContentScreenScaffold
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.security.SecureWindowEffect
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun SubscriptionStatusRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SubscriptionStatusViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    SubscriptionStatusScreen(
        uiState = uiState,
        onBack = onBack,
        onToggleSecrets = viewModel::toggleSecrets,
        onRefresh = viewModel::refresh,
        modifier = modifier,
    )
}

@Composable
internal fun SubscriptionStatusScreen(
    uiState: SubscriptionStatusUiState,
    onBack: () -> Unit,
    onToggleSecrets: () -> Unit,
    onRefresh: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (uiState.secretsRevealed) SecureWindowEffect()
    RipDpiContentScreenScaffold(
        title = stringResource(R.string.subscription_status_title),
        navigationIcon = RipDpiIcons.Back,
        onNavigationClick = onBack,
        modifier = modifier.ripDpiTestTag(RipDpiTestTags.screen(Route.SubscriptionStatus)),
    ) {
        if (uiState.summary.items.isEmpty()) {
            RipDpiEmptyStateCard(
                title = stringResource(R.string.subscription_status_empty_title),
                body = stringResource(R.string.subscription_status_empty_body),
            )
        } else {
            RipDpiButton(
                text =
                    stringResource(
                        if (uiState.secretsRevealed) {
                            R.string.subscription_status_hide_secrets
                        } else {
                            R.string.subscription_status_reveal_secrets
                        },
                    ),
                onClick = onToggleSecrets,
                modifier = Modifier.fillMaxWidth(),
            )
            uiState.summary.items.forEach { item ->
                SubscriptionStatusCard(
                    item = item,
                    refreshing = uiState.refreshingGroupId == item.groupId,
                    onRefresh = { onRefresh(item.groupId) },
                )
            }
        }
    }
}

@Composable
private fun SubscriptionStatusCard(
    item: SubscriptionExpiryItemUiState,
    refreshing: Boolean,
    onRefresh: () -> Unit,
) {
    RipDpiCard {
        Text(item.groupName, style = RipDpiThemeTokens.type.sectionTitle)
        Text(statusText(item), style = RipDpiThemeTokens.type.body)
        Text(
            stringResource(R.string.subscription_status_profiles_format, item.memberCount),
            style = RipDpiThemeTokens.type.body,
        )
        Text(
            stringResource(
                R.string.subscription_status_last_refresh_format,
                formatInstant(item.details.lastUpdatedMillis),
            ),
            style = RipDpiThemeTokens.type.caption,
        )
        Text(
            stringResource(
                R.string.subscription_status_token_expiry_format,
                formatInstant(item.details.tokenExpiryMillis),
            ),
            style = RipDpiThemeTokens.type.caption,
        )
        Text(
            stringResource(
                R.string.subscription_status_credential_expiry_format,
                formatInstant(item.details.credentialExpiryMillis),
            ),
            style = RipDpiThemeTokens.type.caption,
        )
        Text(
            stringResource(
                R.string.subscription_status_refresh_format,
                refreshStatusText(item),
            ),
            style = RipDpiThemeTokens.type.caption,
        )
        Text(
            stringResource(R.string.subscription_status_url_format, item.details.displayLink),
            style = RipDpiThemeTokens.type.caption,
        )
        Text(
            stringResource(R.string.subscription_status_token_format, item.details.displayToken),
            style = RipDpiThemeTokens.type.caption,
        )
        if (
            item.status == SubscriptionExpiryStatus.EXPIRED ||
            item.status == SubscriptionExpiryStatus.INVALIDATED
        ) {
            Text(
                stringResource(R.string.subscription_status_replacement_help),
                color = RipDpiThemeTokens.colors.destructive,
                style = RipDpiThemeTokens.type.body,
            )
        } else if (item.details.refreshable) {
            RipDpiButton(
                text = stringResource(R.string.subscription_status_refresh_action),
                onClick = onRefresh,
                loading = refreshing,
                enabled = !refreshing,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun refreshStatusText(item: SubscriptionExpiryItemUiState): String =
    when {
        item.lastRefreshFailure == null -> stringResource(R.string.subscription_status_refresh_ready)
        item.lastRefreshFailure.isTerminal -> statusText(item)
        else -> stringResource(R.string.subscription_status_refresh_failed)
    }

@Composable
private fun statusText(item: SubscriptionExpiryItemUiState): String =
    when (item.status) {
        SubscriptionExpiryStatus.ACTIVE -> {
            stringResource(R.string.subscription_status_active)
        }

        SubscriptionExpiryStatus.EXPIRING -> {
            stringResource(R.string.subscription_status_expiring_days, item.daysRemaining ?: 0L)
        }

        SubscriptionExpiryStatus.EXPIRED -> {
            stringResource(R.string.subscription_status_expired)
        }

        SubscriptionExpiryStatus.INVALIDATED -> {
            stringResource(R.string.subscription_status_invalidated)
        }

        SubscriptionExpiryStatus.UNKNOWN -> {
            stringResource(R.string.subscription_status_unknown)
        }
    }

private fun formatInstant(epochMillis: Long?): String =
    epochMillis
        ?.takeIf { it > 0L }
        ?.let { value ->
            DateTimeFormatter.ISO_LOCAL_DATE.format(
                Instant.ofEpochMilli(value).atZone(ZoneId.systemDefault()),
            )
        }
        ?: "—"
