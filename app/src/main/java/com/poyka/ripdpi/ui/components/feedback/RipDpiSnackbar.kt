package com.poyka.ripdpi.ui.components.feedback

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

enum class RipDpiSnackbarTone {
    Default,
    Warning,
}

@Composable
fun RipDpiSnackbar(
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    tone: RipDpiSnackbarTone = RipDpiSnackbarTone.Default,
) {
    val colors = RipDpiThemeTokens.colors
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val background = if (isDark) colors.card else colors.foreground
    val content = if (isDark) colors.foreground else colors.background

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(background, RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = message,
            modifier = Modifier.weight(1f),
            style = RipDpiThemeTokens.type.body,
            color = content,
        )
        if (actionLabel != null && onAction != null) {
            Text(
                text = actionLabel.uppercase(),
                style = RipDpiThemeTokens.type.smallLabel,
                color = if (tone == RipDpiSnackbarTone.Warning) colors.warning else content,
                modifier = Modifier.clickable(onClick = onAction),
            )
        }
    }
}

@Composable
fun RipDpiSnackbarHost(
    hostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    SnackbarHost(
        hostState = hostState,
        modifier = modifier,
        snackbar = { data ->
            RipDpiSnackbar(
                message = data.visuals.message,
                actionLabel = data.visuals.actionLabel,
                onAction = data::performAction,
            )
        },
    )
}

@Preview(showBackground = true)
@Composable
private fun RipDpiSnackbarPreview() {
    RipDpiComponentPreview {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            RipDpiSnackbar(message = "Logs exported successfully")
            RipDpiSnackbar(
                message = "VPN permission is still required",
                actionLabel = "Open",
                onAction = {},
                tone = RipDpiSnackbarTone.Warning,
            )
        }
    }
}
