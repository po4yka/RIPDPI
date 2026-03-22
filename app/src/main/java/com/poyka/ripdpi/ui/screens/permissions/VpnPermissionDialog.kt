package com.poyka.ripdpi.ui.screens.permissions

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.ConnectionState
import com.poyka.ripdpi.activities.MainUiState
import com.poyka.ripdpi.permissions.PermissionKind
import com.poyka.ripdpi.ui.components.RipDpiHapticFeedback
import com.poyka.ripdpi.ui.components.feedback.RipDpiDialog
import com.poyka.ripdpi.ui.components.feedback.RipDpiDialogAction
import com.poyka.ripdpi.ui.components.feedback.RipDpiDialogTone
import com.poyka.ripdpi.ui.components.feedback.RipDpiDialogVisuals
import com.poyka.ripdpi.ui.components.feedback.WarningBanner
import com.poyka.ripdpi.ui.components.feedback.WarningBannerTone
import com.poyka.ripdpi.ui.components.rememberRipDpiHapticPerformer
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.theme.RipDpiIcons

@Composable
fun VpnPermissionDialog(
    uiState: MainUiState,
    onDismiss: () -> Unit,
    onContinue: () -> Unit,
) {
    val performHaptic = rememberRipDpiHapticPerformer()
    LaunchedEffect(uiState.connectionState) {
        if (uiState.connectionState == ConnectionState.Connecting ||
            uiState.connectionState == ConnectionState.Connected
        ) {
            performHaptic(RipDpiHapticFeedback.Success)
        }
    }
    RipDpiDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.permissions_vpn_title),
        dismissAction =
            RipDpiDialogAction(
                label = stringResource(R.string.permissions_vpn_not_now),
                onClick = onDismiss,
                testTag = RipDpiTestTags.VpnPermissionDialogDismiss,
            ),
        dialogTestTag = RipDpiTestTags.VpnPermissionDialog,
        confirmAction =
            RipDpiDialogAction(
                label = stringResource(R.string.permissions_vpn_continue),
                onClick = onContinue,
                testTag = RipDpiTestTags.VpnPermissionDialogContinue,
            ),
        visuals =
            RipDpiDialogVisuals(
                message = stringResource(R.string.permissions_vpn_body),
                tone = RipDpiDialogTone.Info,
                icon = RipDpiIcons.Vpn,
            ),
        content = {
            val permissionIssue = uiState.permissionSummary.issue
            if (permissionIssue?.kind == PermissionKind.VpnConsent) {
                WarningBanner(
                    title = permissionIssue.title,
                    message = permissionIssue.message,
                    tone = WarningBannerTone.Error,
                )
            } else if (uiState.connectionState == ConnectionState.Error && uiState.errorMessage != null) {
                WarningBanner(
                    title = stringResource(R.string.permissions_vpn_error_title),
                    message = uiState.errorMessage,
                    tone = WarningBannerTone.Error,
                )
            }
        },
    )
}
