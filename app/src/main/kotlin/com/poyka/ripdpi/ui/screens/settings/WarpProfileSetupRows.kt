package com.poyka.ripdpi.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.cards.SettingsRow
import com.poyka.ripdpi.ui.components.feedback.RipDpiDialog
import com.poyka.ripdpi.ui.components.feedback.RipDpiDialogAction
import com.poyka.ripdpi.ui.components.feedback.RipDpiDialogVisuals
import com.poyka.ripdpi.ui.components.indicators.RipDpiSpinner
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

@Composable
internal fun WarpProfileSetupRows(
    state: WarpEnrollmentUiState,
    enabled: Boolean,
    onRegisterProfile: () -> Unit,
    onImportProfile: () -> Unit,
    onCancelSetup: () -> Unit,
) {
    var confirmation by remember { mutableStateOf<WarpSetupOperation?>(null) }
    confirmation?.let { operation ->
        WarpProfileSetupConfirmation(
            operation = operation,
            onDismiss = { confirmation = null },
            onConfirm = {
                confirmation = null
                if (operation == WarpSetupOperation.Registration) onRegisterProfile() else onImportProfile()
            },
        )
    }
    SettingsRow(
        title = stringResource(R.string.warp_create_profile),
        onClick = { confirmation = WarpSetupOperation.Registration },
        enabled = enabled && !state.busy,
        showDivider = true,
        testTag = RipDpiTestTags.WarpCreateProfile,
    )
    SettingsRow(
        title = stringResource(R.string.warp_import_profile),
        onClick = { confirmation = WarpSetupOperation.Import },
        enabled = enabled && !state.busy,
        showDivider = true,
        testTag = RipDpiTestTags.WarpImportProfile,
    )
    if (state.busy) WarpSetupProgress(state, onCancelSetup)
    WarpSetupResult(state)
}

@Composable
private fun WarpProfileSetupConfirmation(
    operation: WarpSetupOperation,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val registering = operation == WarpSetupOperation.Registration
    RipDpiDialog(
        title = stringResource(if (registering) R.string.warp_registration_title else R.string.warp_import_profile),
        onDismissRequest = onDismiss,
        dismissAction = RipDpiDialogAction(stringResource(android.R.string.cancel), onClick = onDismiss),
        confirmAction =
            RipDpiDialogAction(
                label = stringResource(if (registering) R.string.warp_register_action else R.string.warp_choose_file),
                testTag = RipDpiTestTags.WarpSetupConfirm,
                onClick = onConfirm,
            ),
        visuals =
            RipDpiDialogVisuals(
                message =
                    stringResource(
                        if (registering) R.string.warp_registration_consequence else R.string.warp_import_consequence,
                    ),
            ),
        dialogTestTag = RipDpiTestTags.WarpSetupConfirmation,
    )
}

@Composable
private fun WarpSetupResult(state: WarpEnrollmentUiState) {
    val resultResource =
        when {
            state.failure == WarpSetupFailure.Registration -> R.string.warp_registration_failed
            state.failure == WarpSetupFailure.Import -> R.string.warp_import_failed
            state.saved -> R.string.warp_profile_saved
            else -> null
        }
    resultResource?.let { message ->
        val failed = state.failure != null
        val colors = RipDpiThemeTokens.colors
        val resultTag = if (failed) RipDpiTestTags.WarpSetupError else RipDpiTestTags.WarpSetupStatus
        Text(
            text = stringResource(message),
            style = RipDpiThemeTokens.type.body,
            color = if (failed) colors.destructive else colors.success,
            modifier =
                Modifier
                    .padding(RipDpiThemeTokens.layout.cardPadding)
                    .ripDpiTestTag(resultTag)
                    .semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

@Composable
private fun WarpSetupProgress(
    state: WarpEnrollmentUiState,
    onCancelSetup: () -> Unit,
) {
    val message =
        when {
            state.cancellationRequested && state.operation == WarpSetupOperation.Registration -> {
                R.string.warp_registration_cancelling
            }

            state.cancellationRequested -> {
                R.string.warp_import_cancelling
            }

            state.operation == WarpSetupOperation.Registration -> {
                R.string.warp_registering
            }

            else -> {
                R.string.warp_importing
            }
        }
    Column(
        modifier = Modifier.fillMaxWidth().padding(RipDpiThemeTokens.layout.cardPadding),
        verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.md),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.md),
        ) {
            RipDpiSpinner()
            Text(
                text = stringResource(message),
                style = RipDpiThemeTokens.type.body,
                modifier =
                    Modifier
                        .ripDpiTestTag(RipDpiTestTags.WarpSetupStatus)
                        .semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        RipDpiButton(
            text = stringResource(android.R.string.cancel),
            onClick = onCancelSetup,
            variant = RipDpiButtonVariant.Ghost,
            enabled = !state.cancellationRequested,
            modifier = Modifier.ripDpiTestTag(RipDpiTestTags.WarpSetupCancel),
        )
    }
}
