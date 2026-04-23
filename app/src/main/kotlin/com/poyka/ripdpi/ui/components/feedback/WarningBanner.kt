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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.components.ripDpiClickable
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiBannerStateRole
import com.poyka.ripdpi.ui.theme.RipDpiBannerStateStyle
import com.poyka.ripdpi.ui.theme.RipDpiIconSizes
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiStroke
import com.poyka.ripdpi.ui.theme.RipDpiSurfaceRole
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
    testTag: String? = null,
    tone: WarningBannerTone = WarningBannerTone.Warning,
    icon: ImageVector? = null,
    onClick: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
) {
    val surfaceStyle = RipDpiThemeTokens.surfaces.resolve(RipDpiSurfaceRole.Banner)
    val state = RipDpiThemeTokens.state.banner.resolve(tone.toStateRole())
    val resolvedIcon = icon ?: defaultWarningBannerIcon(tone)
    val surfaceModifier =
        modifier
            .fillMaxWidth()
            .ripDpiTestTag(testTag)
            .semantics { liveRegion = LiveRegionMode.Polite }

    Surface(
        modifier = onClick?.let { surfaceModifier.ripDpiClickable(onClick = it) } ?: surfaceModifier,
        shape = RipDpiThemeTokens.shapes.xl,
        color = state.container,
        border = BorderStroke(RipDpiStroke.Thin, state.border),
        contentColor = state.title,
        shadowElevation = surfaceStyle.shadowElevation,
    ) {
        WarningBannerContent(
            title = title,
            message = message,
            icon = resolvedIcon,
            iconContentDescription = tone.name,
            state = state,
            onDismiss = onDismiss,
        )
    }
}

@Composable
private fun WarningBannerContent(
    title: String,
    message: String,
    icon: ImageVector,
    iconContentDescription: String,
    state: RipDpiBannerStateStyle,
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
                    .size(components.feedback.decorativeBadgeSize)
                    .background(state.iconContainer, RipDpiThemeTokens.shapes.full),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = iconContentDescription,
                tint = state.icon,
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
                color = state.title,
            )
            Text(
                text = message,
                style = type.secondaryBody,
                color = state.message,
            )
        }
        if (onDismiss != null) {
            IconButton(
                onClick = onDismiss,
                modifier =
                    Modifier
                        .size(components.feedback.decorativeBadgeSize)
                        .ripDpiTestTag(RipDpiTestTags.WarningBannerDismiss),
            ) {
                Icon(
                    imageVector = RipDpiIcons.Close,
                    contentDescription = stringResource(R.string.action_dismiss),
                    tint = state.title,
                    modifier = Modifier.size(RipDpiIconSizes.Small),
                )
            }
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

private fun WarningBannerTone.toStateRole(): RipDpiBannerStateRole =
    when (this) {
        WarningBannerTone.Warning -> RipDpiBannerStateRole.Warning
        WarningBannerTone.Error -> RipDpiBannerStateRole.Error
        WarningBannerTone.Info -> RipDpiBannerStateRole.Info
        WarningBannerTone.Restricted -> RipDpiBannerStateRole.Restricted
    }

@Suppress("UnusedPrivateMember")
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

@Suppress("UnusedPrivateMember")
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

@Suppress("UnusedPrivateMember")
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
