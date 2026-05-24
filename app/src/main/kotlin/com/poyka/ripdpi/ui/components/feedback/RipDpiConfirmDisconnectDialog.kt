package com.poyka.ripdpi.ui.components.feedback

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

@Composable
fun RipDpiConfirmDisconnectDialog(
    sessionDuration: String,
    onDismiss: () -> Unit,
    onConfirm: (dontAskAgain: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type
    var dontAskAgain by rememberSaveable { mutableStateOf(false) }

    RipDpiDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.vpn_confirm_disconnect_title),
        modifier = modifier,
        dismissAction =
            RipDpiDialogAction(
                label = stringResource(R.string.vpn_confirm_disconnect_stay),
                onClick = onDismiss,
            ),
        confirmAction =
            RipDpiDialogAction(
                label = stringResource(R.string.vpn_confirm_disconnect_disconnect),
                onClick = { onConfirm(dontAskAgain) },
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
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = dontAskAgain,
                onCheckedChange = { dontAskAgain = it },
                colors =
                    CheckboxDefaults.colors(
                        checkedColor = colors.foreground,
                        uncheckedColor = colors.outline,
                        checkmarkColor = colors.background,
                    ),
            )
            Text(
                text = stringResource(R.string.vpn_confirm_disconnect_dont_ask),
                style = type.body,
                color = colors.foreground,
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
