package com.poyka.ripdpi.ui.screens.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.HomeAppliedConfigurationUiState
import com.poyka.ripdpi.ui.components.feedback.RipDpiDialog
import com.poyka.ripdpi.ui.components.feedback.RipDpiDialogAction
import com.poyka.ripdpi.ui.components.feedback.RipDpiDialogVisuals
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

@Composable
internal fun HomeReconnectReviewDialog(
    state: HomeAppliedConfigurationUiState,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    RipDpiDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.runtime_config_confirm_title),
        dialogTestTag = RipDpiTestTags.HomeRuntimeReconnectDialog,
        dismissAction = RipDpiDialogAction(stringResource(R.string.config_cancel), onDismiss),
        confirmAction =
            RipDpiDialogAction(
                stringResource(R.string.oom_recovery_action_reconnect),
                onConfirm,
                "home_runtime_confirm",
            ),
        visuals = RipDpiDialogVisuals(message = stringResource(R.string.runtime_config_confirm_body)),
    ) {
        val comparison = state.reconnectComparison
        val unknown = stringResource(R.string.runtime_config_preview_unavailable)
        Column(Modifier.fillMaxWidth().ripDpiTestTag(RipDpiTestTags.HomeRuntimeReconnectCurrent)) {
            ReviewHeading(stringResource(R.string.runtime_config_preview_current))
            ReviewValue(comparison?.currentSummary ?: state.confirmedSummary ?: unknown)
        }
        Column(Modifier.fillMaxWidth().ripDpiTestTag(RipDpiTestTags.HomeRuntimeReconnectSaved)) {
            ReviewHeading(stringResource(R.string.runtime_config_preview_saved))
            ReviewHeading(stringResource(R.string.runtime_config_preview_route))
            ReviewValue(stringResource(R.string.runtime_config_preview_route_unresolved))
            ReviewHeading(stringResource(R.string.runtime_config_preview_dns))
            ReviewValue(comparison?.savedDns ?: unknown)
            ReviewHeading(stringResource(R.string.runtime_config_preview_strategy))
            ReviewValue(comparison?.savedTcpStrategy ?: unknown)
        }
        ReviewValue(stringResource(R.string.runtime_config_preview_note))
    }
}

@Composable
private fun ReviewHeading(value: String) {
    val tokens = RipDpiThemeTokens
    Text(value, Modifier.fillMaxWidth(), color = tokens.colors.foreground, style = tokens.type.bodyEmphasis)
}

@Composable
private fun ReviewValue(value: String) {
    val tokens = RipDpiThemeTokens
    Text(value, Modifier.fillMaxWidth(), color = tokens.colors.mutedForeground, style = tokens.type.secondaryBody)
}
