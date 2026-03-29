package com.poyka.ripdpi.ui.components.feedback

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIconSizes
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiStroke
import com.poyka.ripdpi.ui.theme.RipDpiSurfaceRole
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import com.poyka.ripdpi.ui.theme.ripDpiSurfaceStyle

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
    testTag: String? = null,
    tone: WarningBannerTone = WarningBannerTone.Warning,
    icon: ImageVector? = null,
    onClick: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
) {
    val surfaceStyle = ripDpiSurfaceStyle(RipDpiSurfaceRole.Banner)
    val palette = warningBannerPalette(tone)
    val resolvedIcon = icon ?: defaultWarningBannerIcon(tone)
    val surfaceModifier =
        modifier
            .fillMaxWidth()
            .ripDpiTestTag(testTag)
            .semantics { liveRegion = LiveRegionMode.Polite }

    if (onClick != null) {
        Surface(
            onClick = onClick,
            modifier = surfaceModifier,
            shape = RipDpiThemeTokens.shapes.xl,
            color = palette.container,
            border = BorderStroke(RipDpiStroke.Thin, palette.border),
            contentColor = palette.title,
            shadowElevation = surfaceStyle.shadowElevation,
        ) {
            WarningBannerContent(
                title = title,
                message = message,
                icon = resolvedIcon,
                iconContentDescription = tone.name,
                palette = palette,
                onDismiss = onDismiss,
            )
        }
    } else {
        Surface(
            modifier = surfaceModifier,
            shape = RipDpiThemeTokens.shapes.xl,
            color = palette.container,
            border = BorderStroke(RipDpiStroke.Thin, palette.border),
            contentColor = palette.title,
            shadowElevation = surfaceStyle.shadowElevation,
        ) {
            WarningBannerContent(
                title = title,
                message = message,
                icon = resolvedIcon,
                iconContentDescription = tone.name,
                palette = palette,
                onDismiss = onDismiss,
            )
        }
    }
}

@Composable
private fun WarningBannerContent(
    title: String,
    message: String,
    icon: ImageVector,
    iconContentDescription: String,
    palette: WarningBannerPalette,
    onDismiss: (() -> Unit)? = null,
) {
    val components = RipDpiThemeTokens.components
    val layout = RipDpiThemeTokens.layout
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = layout.cardPadding, vertical = spacing.lg),
        horizontalArrangement = Arrangement.spacedBy(spacing.md),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier =
                Modifier
                    .size(components.decorativeBadgeSize)
                    .background(palette.iconContainer, RipDpiThemeTokens.shapes.full),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = iconContentDescription,
                tint = palette.icon,
                modifier = Modifier.size(RipDpiIconSizes.Small),
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
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
        if (onDismiss != null) {
            IconButton(
                onClick = onDismiss,
                modifier =
                    Modifier
                        .size(components.decorativeBadgeSize)
                        .ripDpiTestTag(RipDpiTestTags.WarningBannerDismiss),
            ) {
                Icon(
                    imageVector = RipDpiIcons.Close,
                    contentDescription = stringResource(R.string.action_dismiss),
                    tint = palette.title,
                    modifier = Modifier.size(RipDpiIconSizes.Small),
                )
            }
        }
    }
}

@Immutable
private data class WarningBannerPalette(
    val container: Color,
    val border: Color,
    val iconContainer: Color,
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
                border = colors.warning.copy(alpha = 0.52f),
                iconContainer = colors.warning.copy(alpha = 0.12f),
                icon = colors.warning,
                title = colors.warningContainerForeground,
                message = colors.warningContainerForeground,
            )
        }

        WarningBannerTone.Error -> {
            WarningBannerPalette(
                container = colors.destructiveContainer,
                border = colors.destructive.copy(alpha = 0.52f),
                iconContainer = colors.destructive.copy(alpha = 0.12f),
                icon = colors.destructive,
                title = colors.destructiveContainerForeground,
                message = colors.destructiveContainerForeground,
            )
        }

        WarningBannerTone.Info -> {
            WarningBannerPalette(
                container = colors.infoContainer,
                border = colors.info.copy(alpha = 0.48f),
                iconContainer = colors.info.copy(alpha = 0.12f),
                icon = colors.info,
                title = colors.infoContainerForeground,
                message = colors.infoContainerForeground,
            )
        }

        WarningBannerTone.Restricted -> {
            WarningBannerPalette(
                container = colors.restrictedContainer,
                border = colors.restricted.copy(alpha = 0.52f),
                iconContainer = colors.restricted.copy(alpha = 0.12f),
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
private fun WarningBannerWithDismissPreview() {
    RipDpiComponentPreview {
        Column(verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.md)) {
            WarningBanner(
                title = "Battery optimization",
                message = "Allow RIPDPI to ignore battery optimization.",
                tone = WarningBannerTone.Warning,
                onDismiss = {},
            )
            WarningBanner(
                title = "Background activity",
                message = "Some devices add extra background limits.",
                tone = WarningBannerTone.Info,
                onDismiss = {},
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
