package com.poyka.ripdpi.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.RememberedNetworkStatusTone
import com.poyka.ripdpi.activities.RememberedNetworkUiModel
import com.poyka.ripdpi.activities.RememberedNetworksUiState
import com.poyka.ripdpi.activities.RememberedNetworksViewModel
import com.poyka.ripdpi.ui.components.RipDpiControlDensity
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.feedback.RipDpiDialog
import com.poyka.ripdpi.ui.components.feedback.RipDpiDialogAction
import com.poyka.ripdpi.ui.components.feedback.RipDpiDialogTone
import com.poyka.ripdpi.ui.components.feedback.RipDpiDialogVisuals
import com.poyka.ripdpi.ui.components.feedback.WarningBanner
import com.poyka.ripdpi.ui.components.feedback.WarningBannerTone
import com.poyka.ripdpi.ui.components.scaffold.RipDpiSettingsScaffold
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList

@Composable
fun RememberedNetworksRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: RememberedNetworksViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    RememberedNetworksScreen(
        uiState = uiState,
        onBack = onBack,
        onDeleteEntry = viewModel::deletePolicy,
        onClearAll = viewModel::clearAll,
        modifier = modifier,
    )
}

@Composable
internal fun RememberedNetworksScreen(
    uiState: RememberedNetworksUiState,
    onBack: () -> Unit,
    onDeleteEntry: (RememberedNetworkUiModel) -> Unit,
    onClearAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    var showClearDialog by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<RememberedNetworkUiModel?>(null) }

    if (showClearDialog) {
        RememberedNetworksConfirmDialog(
            title = stringResource(R.string.remembered_networks_clear_confirm_title),
            message = stringResource(R.string.remembered_networks_clear_confirm_body),
            confirmLabel = stringResource(R.string.remembered_networks_clear_confirm_confirm),
            dismissLabel = stringResource(R.string.remembered_networks_clear_confirm_dismiss),
            onDismiss = { showClearDialog = false },
            onConfirm = {
                showClearDialog = false
                onClearAll()
            },
        )
    }

    pendingDelete?.let { entry ->
        RememberedNetworksConfirmDialog(
            title = stringResource(R.string.remembered_networks_delete_confirm_title),
            message = stringResource(R.string.remembered_networks_delete_confirm_body),
            confirmLabel = stringResource(R.string.remembered_networks_delete_confirm_confirm),
            dismissLabel = stringResource(R.string.remembered_networks_delete_confirm_dismiss),
            onDismiss = { pendingDelete = null },
            onConfirm = {
                pendingDelete = null
                onDeleteEntry(entry)
            },
        )
    }

    RipDpiSettingsScaffold(
        modifier =
            modifier
                .ripDpiTestTag(RipDpiTestTags.screen(Route.RememberedNetworks))
                .fillMaxWidth()
                .background(colors.background),
        title = stringResource(R.string.title_remembered_networks),
        navigationIcon = RipDpiIcons.Back,
        onNavigationClick = onBack,
    ) {
        item(key = "remembered_networks_intro") {
            WarningBanner(
                title = stringResource(R.string.remembered_networks_intro_title),
                message = stringResource(R.string.remembered_networks_intro_body),
                tone = WarningBannerTone.Info,
            )
        }

        if (uiState.isEmpty) {
            item(key = "remembered_networks_empty") {
                RememberedNetworksEmptyState()
            }
        } else if (!uiState.loading) {
            item(key = "remembered_networks_header") {
                RememberedNetworksHeader(
                    count = uiState.entries.size,
                    onClearAll = { showClearDialog = true },
                )
            }
            items(
                items = uiState.entries,
                key = { entry -> "remembered_network_${entry.id}" },
            ) { entry ->
                RememberedNetworkCard(
                    entry = entry,
                    onDelete = { pendingDelete = entry },
                )
            }
        }
    }
}

@Composable
private fun RememberedNetworksConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    dismissLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    RipDpiDialog(
        onDismissRequest = onDismiss,
        title = title,
        dismissAction = RipDpiDialogAction(label = dismissLabel, onClick = onDismiss),
        confirmAction = RipDpiDialogAction(label = confirmLabel, onClick = onConfirm),
        visuals = RipDpiDialogVisuals(message = message, tone = RipDpiDialogTone.Destructive),
    )
}

@Composable
private fun RememberedNetworksEmptyState() {
    RipDpiCard(modifier = Modifier.ripDpiTestTag(RipDpiTestTags.RememberedNetworksEmpty)) {
        Column(verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.sm)) {
            Text(
                text = stringResource(R.string.remembered_networks_empty_title),
                style = RipDpiThemeTokens.type.bodyEmphasis,
                color = RipDpiThemeTokens.colors.foreground,
            )
            Text(
                text = stringResource(R.string.remembered_networks_empty_body),
                style = RipDpiThemeTokens.type.caption,
                color = RipDpiThemeTokens.colors.mutedForeground,
            )
        }
    }
}

@Composable
private fun RememberedNetworksHeader(
    count: Int,
    onClearAll: () -> Unit,
) {
    RipDpiCard {
        Column(verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.sm)) {
            Text(
                text = stringResource(R.string.remembered_networks_count_summary, count),
                style = RipDpiThemeTokens.type.bodyEmphasis,
                color = RipDpiThemeTokens.colors.foreground,
            )
            Text(
                text = stringResource(R.string.remembered_networks_header_helper),
                style = RipDpiThemeTokens.type.caption,
                color = RipDpiThemeTokens.colors.mutedForeground,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                RipDpiButton(
                    text = stringResource(R.string.remembered_networks_clear_all_action),
                    onClick = onClearAll,
                    variant = RipDpiButtonVariant.Outline,
                    density = RipDpiControlDensity.Compact,
                    trailingIcon = RipDpiIcons.Delete,
                    modifier = Modifier.ripDpiTestTag(RipDpiTestTags.RememberedNetworksClearAll),
                )
            }
        }
    }
}

@Composable
private fun RememberedNetworkCard(
    entry: RememberedNetworkUiModel,
    onDelete: () -> Unit,
) {
    val colors = RipDpiThemeTokens.colors
    val type = RipDpiThemeTokens.type

    RipDpiCard(modifier = Modifier.ripDpiTestTag(RipDpiTestTags.rememberedNetworkCard(entry.id))) {
        Column(verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.sm)) {
            RememberedNetworkCardHeader(entry)
            RememberedNetworkMetaFlow(entry)
            val strategy = rememberedStrategyLabel(entry)
            if (strategy != null) {
                Text(
                    text = stringResource(R.string.remembered_networks_strategy_label, strategy),
                    style = type.caption,
                    color = colors.mutedForeground,
                )
            }
            RememberedNetworkCardFooter(entry = entry, onDelete = onDelete)
        }
    }
}

@Composable
private fun RememberedNetworkCardHeader(entry: RememberedNetworkUiModel) {
    val colors = RipDpiThemeTokens.colors
    val type = RipDpiThemeTokens.type
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.sm),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.xs),
        ) {
            Text(
                text = rememberedTransportLabel(entry.transportKey),
                style = type.bodyEmphasis,
                color = colors.foreground,
            )
            Text(
                text = stringResource(R.string.remembered_networks_scope_label, entry.scopeKeyShort),
                style = type.monoSmall,
                color = colors.mutedForeground,
            )
        }
        RememberedNetworkStatusChip(entry.statusTone)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RememberedNetworkMetaFlow(entry: RememberedNetworkUiModel) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.sm)) {
        RememberedNetworkMetaText(rememberedModeLabel(entry.modeKey))
        RememberedNetworkMetaText(rememberedValidationLabel(entry.validationKey))
        RememberedNetworkMetaText(
            stringResource(R.string.remembered_networks_dns_servers, entry.dnsServerCount),
        )
        if (entry.privateDnsMode.isNotBlank()) {
            RememberedNetworkMetaText(
                stringResource(R.string.remembered_networks_private_dns_label, entry.privateDnsMode),
            )
        }
    }
}

@Composable
private fun RememberedNetworkCardFooter(
    entry: RememberedNetworkUiModel,
    onDelete: () -> Unit,
) {
    val colors = RipDpiThemeTokens.colors
    val type = RipDpiThemeTokens.type
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text =
                stringResource(
                    R.string.remembered_networks_outcomes,
                    entry.successCount,
                    entry.failureCount,
                ),
            style = type.caption,
            color = colors.mutedForeground,
        )
        Text(
            text = stringResource(R.string.remembered_networks_updated, entry.updatedLabel),
            style = type.caption,
            color = colors.mutedForeground,
        )
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        RipDpiButton(
            text = stringResource(R.string.remembered_networks_delete_action),
            onClick = onDelete,
            variant = RipDpiButtonVariant.Outline,
            density = RipDpiControlDensity.Compact,
            trailingIcon = RipDpiIcons.Delete,
            modifier = Modifier.ripDpiTestTag(RipDpiTestTags.rememberedNetworkDelete(entry.id)),
        )
    }
}

@Composable
private fun RememberedNetworkStatusChip(tone: RememberedNetworkStatusTone) {
    val colors = RipDpiThemeTokens.colors
    val label =
        when (tone) {
            RememberedNetworkStatusTone.Validated -> stringResource(R.string.remembered_networks_status_validated)
            RememberedNetworkStatusTone.Observed -> stringResource(R.string.remembered_networks_status_observed)
            RememberedNetworkStatusTone.Suppressed -> stringResource(R.string.remembered_networks_status_suppressed)
        }
    val tint =
        when (tone) {
            RememberedNetworkStatusTone.Validated -> colors.success
            RememberedNetworkStatusTone.Observed -> colors.mutedForeground
            RememberedNetworkStatusTone.Suppressed -> colors.warning
        }
    Text(
        text = label,
        style = RipDpiThemeTokens.type.smallLabel,
        color = tint,
    )
}

@Composable
private fun RememberedNetworkMetaText(text: String) {
    Text(
        text = text,
        style = RipDpiThemeTokens.type.caption,
        color = RipDpiThemeTokens.colors.mutedForeground,
    )
}

@Composable
private fun rememberedTransportLabel(transportKey: String): String =
    when (transportKey.lowercase()) {
        "wifi" -> stringResource(R.string.remembered_networks_transport_wifi)
        "cellular" -> stringResource(R.string.remembered_networks_transport_cellular)
        "ethernet" -> stringResource(R.string.remembered_networks_transport_ethernet)
        "", "unknown" -> stringResource(R.string.remembered_networks_transport_unknown)
        else -> stringResource(R.string.remembered_networks_transport_other)
    }

@Composable
private fun rememberedValidationLabel(validationKey: String): String =
    when (validationKey.lowercase()) {
        "validated" -> stringResource(R.string.remembered_networks_validation_validated)
        "captive" -> stringResource(R.string.remembered_networks_validation_captive)
        "none" -> stringResource(R.string.remembered_networks_validation_none)
        else -> stringResource(R.string.remembered_networks_validation_unknown)
    }

@Composable
private fun rememberedModeLabel(modeKey: String): String =
    when (modeKey.lowercase()) {
        "vpn" -> stringResource(R.string.remembered_networks_mode_vpn)
        "proxy" -> stringResource(R.string.remembered_networks_mode_proxy)
        else -> stringResource(R.string.remembered_networks_mode_unknown)
    }

@Composable
private fun rememberedStrategyLabel(entry: RememberedNetworkUiModel): String? {
    val parts = listOfNotNull(entry.winningTcpStrategyFamily, entry.winningQuicStrategyFamily)
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

@Preview(showBackground = true)
@Composable
private fun previewRememberedNetworksScreen() {
    RipDpiTheme(themePreference = "light") {
        RememberedNetworksScreen(
            uiState =
                RememberedNetworksUiState(
                    loading = false,
                    entries = rememberedNetworksPreviewEntries().toImmutableList(),
                ),
            onBack = {},
            onDeleteEntry = {},
            onClearAll = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun previewRememberedNetworksScreenDark() {
    RipDpiTheme(themePreference = "dark") {
        RememberedNetworksScreen(
            uiState =
                RememberedNetworksUiState(
                    loading = false,
                    entries = rememberedNetworksPreviewEntries().toImmutableList(),
                ),
            onBack = {},
            onDeleteEntry = {},
            onClearAll = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun previewRememberedNetworksScreenEmpty() {
    RipDpiTheme(themePreference = "light") {
        RememberedNetworksScreen(
            uiState = RememberedNetworksUiState(loading = false, entries = persistentListOf()),
            onBack = {},
            onDeleteEntry = {},
            onClearAll = {},
        )
    }
}

internal fun rememberedNetworksPreviewEntries(): List<RememberedNetworkUiModel> =
    listOf(
        RememberedNetworkUiModel(
            id = 1L,
            fingerprintHash = "9f2c4ab18e7d5c0a3b6f1d2e4c5a6b7c8d9e0f1a2b3c4d5e6f70819a2b3c4d5e",
            modeKey = "vpn",
            statusKey = "validated",
            scopeKeyShort = "9f2c4ab18e7d",
            transportKey = "wifi",
            validationKey = "validated",
            privateDnsMode = "opportunistic",
            dnsServerCount = 2,
            statusTone = RememberedNetworkStatusTone.Validated,
            winningTcpStrategyFamily = "split-tls",
            winningQuicStrategyFamily = "fake-initial",
            successCount = 14,
            failureCount = 1,
            updatedLabel = "May 30, 14:05",
        ),
        RememberedNetworkUiModel(
            id = 2L,
            fingerprintHash = "1a2b3c4d5e6f70819a2b3c4d5e6f70819a2b3c4d5e6f70819a2b3c4d5e6f7081",
            modeKey = "proxy",
            statusKey = "suppressed",
            scopeKeyShort = "1a2b3c4d5e6f",
            transportKey = "cellular",
            validationKey = "none",
            privateDnsMode = "off",
            dnsServerCount = 1,
            statusTone = RememberedNetworkStatusTone.Suppressed,
            winningTcpStrategyFamily = "disorder",
            winningQuicStrategyFamily = null,
            successCount = 3,
            failureCount = 5,
            updatedLabel = "May 28, 09:41",
        ),
    )
