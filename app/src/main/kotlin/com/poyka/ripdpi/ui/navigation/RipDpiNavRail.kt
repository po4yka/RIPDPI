package com.poyka.ripdpi.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.components.RipDpiHapticFeedback
import com.poyka.ripdpi.ui.components.ripDpiSelectable
import com.poyka.ripdpi.ui.theme.RipDpiIconSizes
import com.poyka.ripdpi.ui.theme.RipDpiStroke
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

/** Width breakpoint at which the bottom-nav switches to a left-anchored nav rail. */
const val NavRailMinWidthDp = 600

private const val NavRailLargeFontScale = 1.25f
private const val NavRailMaximumFontScale = 1.75f

/**
 * Returns true when the current screen width is at the foldable / tablet
 * threshold (>= 600dp) — host code uses this to decide between
 * [BottomNavBar] (compact) and [RipDpiNavRail] (medium/expanded).
 */
@Composable
fun rememberIsWideScreen(): Boolean = LocalConfiguration.current.screenWidthDp >= NavRailMinWidthDp

/**
 * Left-anchored navigation rail for medium / expanded width classes
 * (>= 600dp screen width — foldables, tablets, large landscape phones).
 *
 * Renders the same [Route.topLevel] destinations as [BottomNavBar] but
 * vertically: a brand badge at the top, then a column of item buttons
 * where the selected one gets an accent container.
 *
 * Matches `android-nav-rail.html`.
 */
@Composable
fun RipDpiNavRail(
    selectedRoute: Route?,
    onNavigate: (Route) -> Unit,
    modifier: Modifier = Modifier,
) {
    val surfaces = RipDpiThemeTokens.surfaces
    val railSurface = surfaces.resolve(RipDpiThemeTokens.surfaceRoles.navigation.bottomBar)
    val destinations = Route.topLevel
    val fontScale = LocalDensity.current.fontScale
    val railWidth =
        when {
            fontScale >= NavRailMaximumFontScale -> 168.dp
            fontScale >= NavRailLargeFontScale -> 136.dp
            else -> 104.dp
        }
    val showBrand = fontScale <= 1f
    val verticalPadding =
        if (showBrand) RipDpiThemeTokens.spacing.lg else RipDpiThemeTokens.spacing.sm
    val itemSpacing = if (showBrand) RipDpiThemeTokens.spacing.sm else RipDpiThemeTokens.spacing.xs

    Box(modifier = modifier.fillMaxHeight().width(railWidth).background(railSurface.container)) {
        Column(
            modifier = Modifier.fillMaxSize().padding(vertical = verticalPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(itemSpacing),
        ) {
            if (showBrand) {
                BrandBadge()
            }
            destinations.forEach { destination ->
                NavRailItem(
                    destination = destination,
                    selected = destination == selectedRoute,
                    onClick = { onNavigate(destination) },
                )
            }
        }
        VerticalDivider(
            modifier = Modifier.align(Alignment.CenterEnd),
            color = railSurface.border,
            thickness = RipDpiStroke.Hairline,
        )
    }
}

@Composable
private fun BrandBadge() {
    val colors = RipDpiThemeTokens.colors
    Box(
        modifier =
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(colors.foreground, CircleShape)
                .padding(bottom = RipDpiThemeTokens.spacing.md),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_launcher_foreground_ripdpi_clean),
            contentDescription = stringResource(R.string.app_name),
            tint = colors.background,
            modifier = Modifier.size(RipDpiIconSizes.Medium),
        )
    }
}

@Composable
private fun NavRailItem(
    destination: Route,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = RipDpiThemeTokens.colors
    val container = if (selected) colors.accent else androidx.compose.ui.graphics.Color.Transparent
    val content = if (selected) colors.foreground else colors.mutedForeground
    val label = stringResource(destination.titleRes)
    Column(
        modifier =
            Modifier
                .semantics(mergeDescendants = true) {
                    contentDescription = label
                }.clip(RipDpiThemeTokens.shapes.lg)
                .background(container)
                .ripDpiSelectable(
                    selected = selected,
                    enabled = true,
                    role = Role.Tab,
                    hapticFeedback = RipDpiHapticFeedback.Selection,
                    onClick = onClick,
                ).padding(horizontal = RipDpiThemeTokens.spacing.md, vertical = RipDpiThemeTokens.spacing.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.components.navigation.railItemLabelGap),
    ) {
        destination.icon?.let { icon ->
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = content,
                modifier = Modifier.size(RipDpiIconSizes.Medium),
            )
        }
        Text(
            text = label,
            style = RipDpiThemeTokens.type.navLabel.copy(color = content),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
