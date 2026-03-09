package com.poyka.ripdpi.ui.components.feedback

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.theme.RipDpiIconSizes
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiStroke
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

enum class WarningBannerTone {
    Warning,
    Error,
    Info,
    Restricted,
}

@Composable
fun WarningBanner(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    tone: WarningBannerTone = WarningBannerTone.Warning,
    icon: ImageVector? = null,
) {
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type
    val palette = warningBannerPalette(tone)
    val resolvedIcon = icon ?: defaultWarningBannerIcon(tone)

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = palette.container,
        border = BorderStroke(RipDpiStroke.Thin, palette.border),
        contentColor = palette.title,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = spacing.lg, vertical = spacing.lg),
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = resolvedIcon,
                contentDescription = null,
                tint = palette.icon,
                modifier =
                    Modifier
                        .size(RipDpiIconSizes.Default)
                        .padding(top = spacing.xs),
            )
            Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                Text(
                    text = title,
                    style = type.bodyEmphasis,
                    color = palette.title,
                )
                Text(
                    text = message,
                    style = type.secondaryBody,
                    color = palette.message,
                )
            }
        }
    }
}

@Immutable
private data class WarningBannerPalette(
    val container: Color,
    val border: Color,
    val icon: Color,
    val title: Color,
    val message: Color,
)

@Composable
private fun warningBannerPalette(tone: WarningBannerTone): WarningBannerPalette {
    val colors = RipDpiThemeTokens.colors

    return when (tone) {
        WarningBannerTone.Warning -> {
            WarningBannerPalette(
                container = colors.warningContainer,
                border = colors.warning,
                icon = colors.warning,
                title = colors.warningContainerForeground,
                message = colors.warningContainerForeground,
            )
        }

        WarningBannerTone.Error -> {
            WarningBannerPalette(
                container = colors.destructiveContainer,
                border = colors.destructive,
                icon = colors.destructive,
                title = colors.destructiveContainerForeground,
                message = colors.destructiveContainerForeground,
            )
        }

        WarningBannerTone.Info -> {
            WarningBannerPalette(
                container = colors.infoContainer,
                border = colors.info,
                icon = colors.info,
                title = colors.infoContainerForeground,
                message = colors.infoContainerForeground,
            )
        }

        WarningBannerTone.Restricted -> {
            WarningBannerPalette(
                container = colors.restrictedContainer,
                border = colors.restricted,
                icon = colors.restricted,
                title = colors.restrictedContainerForeground,
                message = colors.restrictedContainerForeground,
            )
        }
    }
}

private fun defaultWarningBannerIcon(tone: WarningBannerTone): ImageVector =
    when (tone) {
        WarningBannerTone.Warning -> RipDpiIcons.Warning
        WarningBannerTone.Error -> RipDpiIcons.Error
        WarningBannerTone.Info -> RipDpiIcons.Info
        WarningBannerTone.Restricted -> RipDpiIcons.Lock
    }

@Preview(showBackground = true)
@Composable
private fun WarningBannerPreview() {
    RipDpiComponentPreview {
        Column(verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.md)) {
            WarningBanner(
                title = "Validation warning",
                message = "Double-check the current values before saving the preset.",
                tone = WarningBannerTone.Warning,
            )
            WarningBanner(
                title = "Connection failed",
                message = "The service stopped before the tunnel could be established.",
                tone = WarningBannerTone.Error,
            )
            WarningBanner(
                title = "Manual step required",
                message = "VPN permission must be granted before the service can start.",
                tone = WarningBannerTone.Info,
            )
            WarningBanner(
                title = "Feature unavailable",
                message = "This control only applies when command-line mode is enabled.",
                tone = WarningBannerTone.Restricted,
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun WarningBannerDarkPreview() {
    RipDpiComponentPreview(themePreference = "dark") {
        Column(verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.md)) {
            WarningBanner(
                title = "Validation warning",
                message = "Double-check the current values before saving the preset.",
                tone = WarningBannerTone.Warning,
            )
            WarningBanner(
                title = "Connection failed",
                message = "The service stopped before the tunnel could be established.",
                tone = WarningBannerTone.Error,
            )
            WarningBanner(
                title = "Manual step required",
                message = "VPN permission must be granted before the service can start.",
                tone = WarningBannerTone.Info,
            )
            WarningBanner(
                title = "Feature unavailable",
                message = "This control only applies when command-line mode is enabled.",
                tone = WarningBannerTone.Restricted,
            )
        }
    }
}
