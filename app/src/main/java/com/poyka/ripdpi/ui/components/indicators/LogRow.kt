package com.poyka.ripdpi.ui.components.indicators

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

enum class LogRowTone {
    Dns,
    Connection,
    Warning,
    Error,
}

@Composable
fun LogRow(
    timestamp: String,
    type: String,
    message: String,
    modifier: Modifier = Modifier,
    tone: LogRowTone = LogRowTone.Connection,
) {
    val colors = RipDpiThemeTokens.colors
    val badgeBackground =
        when (tone) {
            LogRowTone.Dns -> colors.accent
            LogRowTone.Connection -> colors.foreground.copy(alpha = 0.08f)
            LogRowTone.Warning -> colors.warning.copy(alpha = 0.18f)
            LogRowTone.Error -> colors.destructive.copy(alpha = 0.18f)
        }
    val badgeContent =
        when (tone) {
            LogRowTone.Dns -> colors.foreground
            LogRowTone.Connection -> colors.foreground
            LogRowTone.Warning -> colors.foreground
            LogRowTone.Error -> colors.foreground
        }

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = timestamp,
            style = RipDpiThemeTokens.type.monoLog,
            color = colors.mutedForeground,
        )
        Box(
            modifier =
                Modifier
                    .background(badgeBackground, RoundedCornerShape(999.dp))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
        ) {
            Text(
                text = type.uppercase(),
                style = RipDpiThemeTokens.type.smallLabel,
                color = badgeContent,
            )
        }
        Text(
            text = message,
            modifier = Modifier.weight(1f),
            style = RipDpiThemeTokens.type.monoInline,
            color = colors.foreground,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun LogRowPreview() {
    RipDpiComponentPreview(themePreference = "dark") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LogRow(
                timestamp = "02:14:38",
                type = "dns",
                message = "DNS resolver switched to 1.1.1.1",
                tone = LogRowTone.Dns,
            )
            LogRow(
                timestamp = "02:14:42",
                type = "conn",
                message = "VPN service started on 127.0.0.1:1080",
                tone = LogRowTone.Connection,
            )
            LogRow(
                timestamp = "02:14:47",
                type = "warn",
                message = "Fallback DNS is active for this network",
                tone = LogRowTone.Warning,
            )
            LogRow(
                timestamp = "02:14:51",
                type = "error",
                message = "VPN permission was denied by the system",
                tone = LogRowTone.Error,
            )
        }
    }
}
