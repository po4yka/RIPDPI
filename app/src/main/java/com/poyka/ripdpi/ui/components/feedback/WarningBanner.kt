package com.poyka.ripdpi.ui.components.feedback

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

enum class WarningBannerTone {
    Warning,
    Error,
    Restricted,
}

@Composable
fun WarningBanner(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    tone: WarningBannerTone = WarningBannerTone.Warning,
    icon: ImageVector = RipDpiIcons.Warning,
) {
    val colors = RipDpiThemeTokens.colors
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val borderColor = when (tone) {
        WarningBannerTone.Warning -> colors.cardBorder
        WarningBannerTone.Error -> colors.destructive
        WarningBannerTone.Restricted -> if (isDark) colors.cardBorder else colors.border
    }
    val backgroundColor = if (isDark) colors.background else MaterialTheme.colorScheme.surface

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(backgroundColor, RoundedCornerShape(16.dp))
            .border(1.dp, borderColor, RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 17.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = colors.foreground,
            modifier = Modifier.size(18.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text = title, style = RipDpiThemeTokens.type.bodyEmphasis, color = colors.foreground)
            Text(text = message, style = RipDpiThemeTokens.type.caption, color = colors.mutedForeground)
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun WarningBannerPreview() {
    RipDpiComponentPreview {
        WarningBanner(
            title = "Connection failed",
            message = "Network may be restricting VPN functionality.",
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun WarningBannerDarkPreview() {
    RipDpiComponentPreview(themePreference = "dark") {
        WarningBanner(
            title = "Restricted network",
            message = "VPN functionality may be limited.",
            tone = WarningBannerTone.Restricted,
        )
    }
}
