package com.poyka.ripdpi.ui.components.feedback

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

@Composable
fun RipDpiConfirmDisconnectDialog(
    sessionDuration: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type
    RipDpiDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.vpn_confirm_disconnect_title),
        modifier = modifier,
        dismissAction =
            RipDpiDialogAction(
                label = stringResource(R.string.vpn_confirm_disconnect_stay),
                onClick = onDismiss,
                testTag = RipDpiTestTags.ConnectionActuatorDisconnectStay,
            ),
        confirmAction =
            RipDpiDialogAction(
                label = stringResource(R.string.vpn_confirm_disconnect_disconnect),
                destructive = true,
                onClick = onConfirm,
                testTag = RipDpiTestTags.ConnectionActuatorDisconnectConfirm,
            ),
        visuals =
            RipDpiDialogVisuals(
                message = null,
                tone = RipDpiDialogTone.Destructive,
            ),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Text(
                text =
                    stringResource(
                        R.string.vpn_confirm_disconnect_session_format,
                        sessionDuration,
                    ),
                style = type.bodyEmphasis,
                color = colors.foreground,
            )
            Text(
                text = stringResource(R.string.vpn_confirm_disconnect_body),
                style = type.body,
                color = colors.mutedForeground,
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun RipDpiConfirmDisconnectDialogLightPreview() {
    RipDpiComponentPreview {
        RipDpiConfirmDisconnectDialog(
            sessionDuration = "2h 04m",
            onDismiss = {},
            onConfirm = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun RipDpiConfirmDisconnectDialogDarkPreview() {
    RipDpiComponentPreview(themePreference = "dark") {
        RipDpiConfirmDisconnectDialog(
            sessionDuration = "2h 04m",
            onDismiss = {},
            onConfirm = {},
        )
    }
}
