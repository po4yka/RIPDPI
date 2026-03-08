package com.poyka.ripdpi.ui.components.feedback

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

enum class RipDpiDialogTone {
    Default,
    Destructive,
}

@Composable
fun RipDpiDialog(
    onDismissRequest: () -> Unit,
    title: String,
    message: String,
    confirmLabel: String,
    dismissLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    tone: RipDpiDialogTone = RipDpiDialogTone.Default,
    icon: ImageVector = if (tone == RipDpiDialogTone.Destructive) RipDpiIcons.Warning else RipDpiIcons.Offline,
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        RipDpiDialogCard(
            title = title,
            message = message,
            confirmLabel = confirmLabel,
            dismissLabel = dismissLabel,
            onConfirm = onConfirm,
            onDismiss = onDismiss,
            modifier = modifier,
            tone = tone,
            icon = icon,
        )
    }
}

@Composable
fun RipDpiDialogCard(
    title: String,
    message: String,
    confirmLabel: String,
    dismissLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    tone: RipDpiDialogTone = RipDpiDialogTone.Default,
    icon: ImageVector = if (tone == RipDpiDialogTone.Destructive) RipDpiIcons.Warning else RipDpiIcons.Offline,
) {
    val colors = RipDpiThemeTokens.colors
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val surfaceColor = if (isDark) colors.cardBorder else MaterialTheme.colorScheme.surface
    val iconContainer = if (isDark) colors.accent else colors.inputBackground

    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 220.dp)
            .background(surfaceColor, RoundedCornerShape(28.dp))
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            modifier = Modifier
                .size(40.dp)
                .background(iconContainer, CircleShape),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = colors.foreground,
                modifier = Modifier.size(20.dp),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = title, style = RipDpiThemeTokens.type.sheetTitle, color = colors.foreground)
            Text(text = message, style = RipDpiThemeTokens.type.body, color = colors.mutedForeground)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RipDpiButton(
                text = dismissLabel,
                onClick = onDismiss,
                variant = RipDpiButtonVariant.Outline,
            )
            RipDpiButton(
                text = confirmLabel,
                onClick = onConfirm,
                variant = if (tone == RipDpiDialogTone.Destructive) {
                    RipDpiButtonVariant.Destructive
                } else {
                    RipDpiButtonVariant.Primary
                },
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun RipDpiDialogLightPreview() {
    RipDpiComponentPreview {
        RipDpiDialogCard(
            title = "Stop service?",
            message = "Active connections may be interrupted.",
            confirmLabel = "Stop",
            dismissLabel = "Cancel",
            onConfirm = {},
            onDismiss = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun RipDpiDialogDarkPreview() {
    RipDpiComponentPreview(themePreference = "dark") {
        RipDpiDialogCard(
            title = "Reset configuration?",
            message = "All custom flags and modes will be removed.",
            confirmLabel = "Reset",
            dismissLabel = "Cancel",
            onConfirm = {},
            onDismiss = {},
            tone = RipDpiDialogTone.Destructive,
        )
    }
}
