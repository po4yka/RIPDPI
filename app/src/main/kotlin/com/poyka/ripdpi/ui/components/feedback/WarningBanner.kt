package com.poyka.ripdpi.ui.components.feedback

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.components.ripDpiClickable
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiBannerStateStyle
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
    testTag: String? = null,
    tone: WarningBannerTone = WarningBannerTone.Warning,
    icon: ImageVector? = null,
    // When true (status changes, errors), the banner is a Polite live region and is auto-announced by
    // TalkBack. Set false for static, always-present informational notices so they are read on focus
    // only and not re-announced on every recomposition.
    announce: Boolean = true,
    onClick: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
) {
    val surfaceStyle = RipDpiThemeTokens.surfaces.resolve(RipDpiThemeTokens.surfaceRoles.feedback.banner)
    val state =
        RipDpiThemeTokens.state.banner.resolve(
            RipDpiThemeTokens.stateRoles.banner.fromTone(tone),
        )
    val resolvedIcon = icon ?: defaultWarningBannerIcon(tone)
    val baseModifier =
        modifier
            .fillMaxWidth()
            .ripDpiTestTag(testTag)
    val surfaceModifier =
        if (announce) {
            baseModifier.semantics { liveRegion = LiveRegionMode.Polite }
        } else {
            baseModifier
        }

    Surface(
        modifier =
            onClick?.let {
                surfaceModifier.ripDpiClickable(
                    role = Role.Button,
                    onClick = it,
                )
            } ?: surfaceModifier,
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
    state: RipDpiBannerStateStyle,
    onDismiss: (() -> Unit)? = null,
) {
    val components = RipDpiThemeTokens.components
    val layout = RipDpiThemeTokens.layout
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val wordMinWidth =
        maxOf(
            measurer
                .measure(AnnotatedString(title), style = type.bodyEmphasis.copy(hyphens = Hyphens.None))
                .multiParagraph.minIntrinsicWidth,
            measurer
                .measure(AnnotatedString(message), style = type.secondaryBody.copy(hyphens = Hyphens.None))
                .multiParagraph.minIntrinsicWidth,
        )
    val reservedWidth =
        components.feedback.decorativeBadgeSize + spacing.md +
            if (onDismiss != null) components.buttons.iconButtonSize + spacing.md else 0.dp

    BoxWithConstraints(
        Modifier.fillMaxWidth().padding(horizontal = layout.cardPadding, vertical = spacing.lg),
    ) {
        // Move the controls above the text before they force a word to break.
        if (with(density) { wordMinWidth.toDp() } > maxWidth - reservedWidth) {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    WarningBannerLeadingIcon(icon, state)
                    WarningBannerDismissButton(onDismiss, state)
                }
                WarningBannerText(title, message, state)
            }
        } else {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.md),
                verticalAlignment = Alignment.Top,
            ) {
                WarningBannerLeadingIcon(icon, state)
                WarningBannerText(title, message, state, Modifier.weight(1f))
                WarningBannerDismissButton(onDismiss, state)
            }
        }
    }
}

@Composable
private fun WarningBannerLeadingIcon(
    icon: ImageVector,
    state: RipDpiBannerStateStyle,
) {
    Box(
        modifier =
            Modifier
                .size(RipDpiThemeTokens.components.feedback.decorativeBadgeSize)
                .background(state.iconContainer, RipDpiThemeTokens.shapes.full),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = state.icon, modifier = Modifier.size(RipDpiIconSizes.Large))
    }
}

@Composable
private fun WarningBannerText(
    title: String,
    message: String,
    state: RipDpiBannerStateStyle,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.xs)) {
        Text(title, Modifier.fillMaxWidth(), style = RipDpiThemeTokens.type.bodyEmphasis, color = state.title)
        Text(message, Modifier.fillMaxWidth(), style = RipDpiThemeTokens.type.secondaryBody, color = state.message)
    }
}

@Composable
private fun WarningBannerDismissButton(
    onDismiss: (() -> Unit)?,
    state: RipDpiBannerStateStyle,
) {
    if (onDismiss == null) return
    IconButton(
        onClick = onDismiss,
        modifier =
            Modifier
                .size(RipDpiThemeTokens.components.buttons.iconButtonSize)
                .ripDpiTestTag(RipDpiTestTags.WarningBannerDismiss),
    ) {
        Icon(
            imageVector = RipDpiIcons.Close,
            contentDescription = stringResource(R.string.action_dismiss),
            tint = state.title,
            modifier = Modifier.size(RipDpiIconSizes.Default),
        )
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
