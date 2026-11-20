package com.poyka.ripdpi.ui.screens.onboarding

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.components.intro.rememberRipDpiIntroScaffoldMetrics
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

/** An informational step: its header, and under it the guarantee lists or the places that the text names. */
@Composable
internal fun OnboardingInfoPageScene(
    pageModel: OnboardingPage.Informational,
    modifier: Modifier = Modifier,
) {
    val introLayout = rememberRipDpiIntroScaffoldMetrics()
    val header = @Composable { headerModifier: Modifier ->
        OnboardingPageHeader(
            titleRes = pageModel.titleRes,
            subtitleRes = pageModel.descriptionRes,
            modifier = headerModifier,
        )
    }
    val grid = @Composable { gridModifier: Modifier ->
        if (pageModel.kind == InfoPageKind.Privacy) {
            OnboardingGuaranteeGrid(
                privacyLabels =
                    listOf(
                        R.string.onboarding_chip_no_account,
                        R.string.onboarding_chip_no_tracking,
                        R.string.onboarding_chip_no_cloud_sync,
                    ),
                localLabels =
                    listOf(
                        R.string.onboarding_chip_local_vpn,
                        R.string.onboarding_chip_local_proxy,
                        R.string.onboarding_chip_local_config,
                    ),
                modifier = gridModifier,
            )
        } else {
            OnboardingPlaceList(pageModel.places, gridModifier)
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val scrollModifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = introLayout.bodyHorizontalPadding)
                // A scroll child measures with unbounded height; the minimum lets the two landscape panes center
                // in the free space, and the content still scrolls from the top once it outgrows the page.
                .heightIn(min = maxHeight)
        if (maxWidth > maxHeight) {
            // Landscape leaves too little height to stack the header over the lists, so the
            // lists fell below the fold. Two panes keep both in view.
            Row(
                modifier = scrollModifier,
                horizontalArrangement = Arrangement.spacedBy(introLayout.bodyToContentGap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                header(Modifier.weight(1f))
                grid(Modifier.weight(1f))
            }
        } else {
            // Top-anchored like the setup steps: every title sits at one height under the pinned badge.
            Column(
                modifier = scrollModifier,
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(introLayout.bodyToContentGap),
            ) {
                header(Modifier)
                grid(Modifier)
            }
        }
    }
}

/**
 * Passive guarantee grid in two grouped columns — privacy promises on the left, local-engine
 * capabilities on the right — each a small check mark + muted label. Each column carries a quiet
 * micro-header ("Privacy" / "On your device") so the grouping reads. Read-only by design:
 * deliberately NOT bordered pills, so it never reads as an interactive filter-chip row.
 */
@Composable
private fun OnboardingGuaranteeGrid(
    privacyLabels: List<Int>,
    localLabels: List<Int>,
    modifier: Modifier = Modifier,
) {
    val spacing = RipDpiThemeTokens.spacing
    val introLayout = rememberRipDpiIntroScaffoldMetrics()
    val useAccessibilityLayout = LocalDensity.current.fontScale >= AccessibilityOnboardingFontScale
    val gridModifier =
        modifier
            .widthIn(max = introLayout.guaranteeGridMaxWidth)
            .fillMaxWidth()
    if (useAccessibilityLayout) {
        Column(
            modifier = gridModifier,
            verticalArrangement = Arrangement.spacedBy(spacing.lg),
        ) {
            OnboardingGuaranteeColumn(
                headerRes = R.string.onboarding_guarantee_header_privacy,
                labels = privacyLabels,
                modifier = Modifier.fillMaxWidth(),
            )
            OnboardingGuaranteeColumn(
                headerRes = R.string.onboarding_guarantee_header_local,
                labels = localLabels,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    } else {
        // Size each column to its own content (at most half the grid, so long labels wrap) and
        // center the pair. Equal-width columns left the narrower one with a wider right margin.
        BoxWithConstraints(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            val columnModifier =
                Modifier
                    .widthIn(max = (minOf(maxWidth, introLayout.guaranteeGridMaxWidth) - spacing.md) / 2)
                    .width(IntrinsicSize.Max)
            Row(
                horizontalArrangement = Arrangement.spacedBy(spacing.md),
            ) {
                OnboardingGuaranteeColumn(
                    headerRes = R.string.onboarding_guarantee_header_privacy,
                    labels = privacyLabels,
                    modifier = columnModifier,
                )
                OnboardingGuaranteeColumn(
                    headerRes = R.string.onboarding_guarantee_header_local,
                    labels = localLabels,
                    modifier = columnModifier,
                )
            }
        }
    }
}

/**
 * The tabs and the actions that a step names, each with the icon that it has in the app, in the order in which
 * the user reaches them. Passive, like the guarantee lists: it shows where to look and takes no input.
 */
@Composable
private fun OnboardingPlaceList(
    places: List<OnboardingPlace>,
    modifier: Modifier = Modifier,
) {
    val introLayout = rememberRipDpiIntroScaffoldMetrics()
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.widthIn(max = introLayout.guaranteeGridMaxWidth).width(IntrinsicSize.Max),
            verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.md),
        ) {
            places.forEach { place ->
                OnboardingGuaranteeItem(
                    text = stringResource(place.labelRes),
                    icon = place.icon,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun OnboardingGuaranteeColumn(
    @StringRes headerRes: Int,
    labels: List<Int>,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val type = RipDpiThemeTokens.type
    val spacing = RipDpiThemeTokens.spacing
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        Text(
            text = stringResource(headerRes),
            style = type.caption,
            color = colors.mutedForeground,
            modifier = Modifier.fillMaxWidth(),
        )
        labels.forEach { labelRes ->
            OnboardingGuaranteeItem(
                text = stringResource(labelRes),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun OnboardingGuaranteeItem(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = RipDpiIcons.Check,
) {
    val colors = RipDpiThemeTokens.colors
    val type = RipDpiThemeTokens.type
    val spacing = RipDpiThemeTokens.spacing
    val components = RipDpiThemeTokens.components
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = colors.mutedForeground,
            modifier = Modifier.size(components.inputs.chipIconSize),
        )
        Text(
            text = text,
            style = type.body,
            color = colors.mutedForeground,
            // Constrain to the remaining column width so long translated labels wrap, never clip.
            modifier = Modifier.weight(1f),
        )
    }
}
