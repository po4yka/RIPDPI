package com.poyka.ripdpi.ui.screens.connection

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.components.cards.PresetCard
import com.poyka.ripdpi.ui.components.chrome.RipDpiEmptyState
import com.poyka.ripdpi.ui.components.feedback.RipDpiBottomSheet
import com.poyka.ripdpi.ui.components.feedback.RipDpiDialog
import com.poyka.ripdpi.ui.components.feedback.RipDpiDialogAction
import com.poyka.ripdpi.ui.components.feedback.RipDpiDialogVisuals
import com.poyka.ripdpi.ui.components.indicators.RipDpiSpinner
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConnectionProfileChoicesSheet(
    uiState: ConnectionUiState,
    onPresetSelected: (ConnectionPreset) -> Unit,
    profileActions: ConnectionProfileActions,
    onPasteServerLink: () -> Unit,
    onDismiss: () -> Unit,
) {
    // Every choice either changes the active profile or leaves the screen, so each one closes the sheet.
    fun <T> closing(action: (T) -> Unit): (T) -> Unit =
        {
            onDismiss()
            action(it)
        }
    RipDpiBottomSheet(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.config_presets_section),
    ) {
        Column(
            modifier = Modifier.ripDpiTestTag(RipDpiTestTags.ConnectionProfileChoices),
            verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.md),
        ) {
            ConnectionPresetList(uiState, closing(onPresetSelected))
            ConnectionRelayProfileChoices(
                uiState = uiState,
                actions =
                    profileActions.copy(
                        share = closing(profileActions.share),
                        select = closing(profileActions.select),
                        edit = closing(profileActions.edit),
                        create = closing(profileActions.create),
                        scanServer = {
                            onDismiss()
                            profileActions.scanServer()
                        },
                    ),
                onPasteServerLink = {
                    onDismiss()
                    onPasteServerLink()
                },
            )
        }
    }
}

@Composable
private fun ConnectionPresetList(
    uiState: ConnectionUiState,
    onPresetSelected: (ConnectionPreset) -> Unit,
) {
    val spacing = RipDpiThemeTokens.spacing
    var pendingReset by remember { mutableStateOf<ConnectionPreset?>(null) }
    pendingReset?.let { preset ->
        RipDpiDialog(
            onDismissRequest = { pendingReset = null },
            title = stringResource(R.string.config_preset_reset_title),
            dialogTestTag = RipDpiTestTags.ConnectionPresetResetDialog,
            dismissAction =
                RipDpiDialogAction(stringResource(R.string.strategy_tuner_cancel), { pendingReset = null }),
            confirmAction =
                RipDpiDialogAction(
                    label = stringResource(R.string.config_preset_reset_confirm),
                    onClick = {
                        pendingReset = null
                        onPresetSelected(preset)
                    },
                    testTag = RipDpiTestTags.ConnectionPresetResetConfirm,
                    destructive = true,
                ),
            visuals = RipDpiDialogVisuals(message = stringResource(R.string.config_preset_reset_body)),
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
        if (uiState.isLoading) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .ripDpiTestTag(RipDpiTestTags.ConnectionPresetsLoading),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(spacing.md),
            ) {
                RipDpiSpinner()
                RipDpiEmptyState(
                    icon = RipDpiIcons.Config,
                    modifier = Modifier.fillMaxWidth(),
                    title = stringResource(R.string.config_presets_loading_title),
                    body = stringResource(R.string.config_presets_loading_body),
                )
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
                uiState.presets.forEach { preset ->
                    PresetCard(
                        modifier = Modifier.ripDpiTestTag(RipDpiTestTags.connectionPreset(preset.id)),
                        title = stringResource(titleResForPreset(preset.kind)),
                        description = stringResource(descriptionResForPreset(preset.kind)),
                        badgeText =
                            if (preset.isSelected) {
                                stringResource(R.string.config_badge_active)
                            } else {
                                null
                            },
                        selected = preset.isSelected,
                        onClick = {
                            when {
                                preset.kind == ConnectionPresetKind.Custom -> onPresetSelected(preset)
                                !preset.isSelected -> pendingReset = preset
                            }
                        },
                    )
                }
            }
        }
    }
}

internal fun titleResForPreset(kind: ConnectionPresetKind): Int =
    when (kind) {
        ConnectionPresetKind.Recommended -> R.string.config_preset_recommended_title
        ConnectionPresetKind.Custom -> R.string.config_preset_custom_title
    }

private fun descriptionResForPreset(kind: ConnectionPresetKind): Int =
    when (kind) {
        ConnectionPresetKind.Recommended -> R.string.config_preset_recommended_body
        ConnectionPresetKind.Custom -> R.string.config_preset_custom_body
    }
