package com.poyka.ripdpi.ui.screens.support

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.support.RestartPolicyAsk
import com.poyka.ripdpi.data.support.RestartPolicyNever
import com.poyka.ripdpi.data.support.RestartPolicyRequired
import com.poyka.ripdpi.data.support.SupportSettingsFieldChange
import com.poyka.ripdpi.ui.components.LifecycleEventEffect
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.chrome.RipDpiPanelHeader
import com.poyka.ripdpi.ui.components.feedback.WarningBanner
import com.poyka.ripdpi.ui.components.feedback.WarningBannerTone
import com.poyka.ripdpi.ui.components.scaffold.RipDpiContentScreenScaffold
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

@Composable
fun SupportSettingsRoute(
    packageJson: String,
    onBack: () -> Unit,
    onApplied: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SupportSettingsViewModel = hiltViewModel(),
) {
    LaunchedEffect(viewModel, packageJson) {
        viewModel.setPackage(packageJson)
    }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LifecycleEventEffect(viewModel.appliedEvents) { onApplied() }

    SupportSettingsScreen(
        uiState = uiState,
        onBack = onBack,
        onApply = viewModel::apply,
        onRetry = { viewModel.setPackage(packageJson) },
        modifier = modifier,
    )
}

@Composable
internal fun SupportSettingsScreen(
    uiState: SupportSettingsUiState,
    onBack: () -> Unit,
    onApply: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    RipDpiContentScreenScaffold(
        title = stringResource(R.string.support_settings_title),
        navigationIcon = RipDpiIcons.Back,
        onNavigationClick = onBack,
        navigationContentDescription = stringResource(R.string.navigation_back),
        modifier = modifier.ripDpiTestTag(RipDpiTestTags.screen(Route.SupportSettings())),
    ) {
        when {
            uiState.loading -> {
                SupportSettingsMessageCard(
                    title = stringResource(R.string.support_settings_loading_title),
                    body = stringResource(R.string.support_settings_loading_body),
                )
            }

            uiState.storageError && uiState.preview == null -> {
                SupportSettingsError(
                    title = stringResource(R.string.strategy_config_import_failed_title),
                    body = stringResource(R.string.config_editor_hydration_failed),
                    actionLabel = stringResource(R.string.strategy_config_restore_retry),
                    onAction = onRetry,
                )
            }

            uiState.invalid || uiState.preview == null -> {
                // A broken link cannot be retried, so the next step is to leave the screen.
                SupportSettingsError(
                    title = stringResource(R.string.support_settings_invalid_title),
                    body = stringResource(R.string.support_settings_invalid_body),
                    actionLabel = stringResource(R.string.support_settings_back_action),
                    onAction = onBack,
                )
            }

            else -> {
                if (uiState.storageError) {
                    WarningBanner(
                        title = stringResource(R.string.strategy_config_import_failed_title),
                        message = stringResource(R.string.asset_provider_persistence_failed_body),
                        tone = WarningBannerTone.Error,
                    )
                }
                SupportSettingsPreviewContent(
                    uiState = uiState,
                    onApply = onApply,
                )
            }
        }
    }
}

@Composable
private fun SupportSettingsPreviewContent(
    uiState: SupportSettingsUiState,
    onApply: () -> Unit,
) {
    val preview = uiState.preview ?: return
    val title = preview.title.ifBlank { stringResource(R.string.support_settings_default_title) }
    val reason = preview.reason.ifBlank { stringResource(R.string.support_settings_default_reason) }
    RipDpiCard {
        RipDpiPanelHeader(title = title, supporting = reason)
    }
    if (preview.hasSensitiveChanges) {
        WarningBanner(
            title = stringResource(R.string.support_settings_sensitive_title),
            message = stringResource(R.string.support_settings_sensitive_body),
            tone = WarningBannerTone.Warning,
        )
    }
    RipDpiCard {
        Column {
            RipDpiPanelHeader(
                title = stringResource(R.string.support_settings_changes_title),
                supporting = restartPolicyText(preview.restartPolicy),
            )
            if (uiState.changes.isEmpty()) {
                Text(
                    text = stringResource(R.string.support_settings_no_changes),
                    style = RipDpiThemeTokens.type.body,
                    color = RipDpiThemeTokens.colors.mutedForeground,
                )
            } else {
                uiState.changes.forEach { change ->
                    SupportSettingsChangeRow(change)
                }
            }
        }
    }
    RipDpiButton(
        text = stringResource(R.string.support_settings_apply_action),
        onClick = onApply,
        enabled = !uiState.applying,
        loading = uiState.applying,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SupportSettingsChangeRow(change: SupportSettingsFieldChange) {
    Text(
        text = change.path,
        style = RipDpiThemeTokens.type.bodyEmphasis,
        color = RipDpiThemeTokens.colors.foreground,
    )
    Text(
        text =
            stringResource(
                R.string.support_settings_change_summary,
                change.previous,
                change.next,
            ),
        style = RipDpiThemeTokens.type.caption,
        color = RipDpiThemeTokens.colors.mutedForeground,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(modifier = Modifier.height(RipDpiThemeTokens.spacing.sm))
}

/** The app's error state: an error banner with one next step, as on the PCAP screens. */
@Composable
private fun SupportSettingsError(
    title: String,
    body: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    WarningBanner(title = title, message = body, tone = WarningBannerTone.Error)
    RipDpiButton(
        text = actionLabel,
        onClick = onAction,
        modifier = Modifier.fillMaxWidth(),
        wrapLabel = true,
    )
}

@Composable
private fun SupportSettingsMessageCard(
    title: String,
    body: String,
) {
    RipDpiCard {
        RipDpiPanelHeader(title = title, supporting = body)
    }
}

@Composable
private fun restartPolicyText(policy: String): String =
    when (policy) {
        RestartPolicyRequired -> stringResource(R.string.support_settings_restart_required)
        RestartPolicyNever -> stringResource(R.string.support_settings_restart_never)
        RestartPolicyAsk -> stringResource(R.string.support_settings_restart_ask)
        else -> stringResource(R.string.support_settings_restart_ask)
    }
