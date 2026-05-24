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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.components.RipDpiHapticFeedback
import com.poyka.ripdpi.ui.components.ripDpiSelectable
import com.poyka.ripdpi.ui.theme.RipDpiIconSizes
import com.poyka.ripdpi.ui.theme.RipDpiStroke
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

/** Width breakpoint at which the bottom-nav switches to a left-anchored nav rail. */
const val NavRailMinWidthDp = 600

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

    Box(modifier = modifier.fillMaxHeight().width(80.dp).background(railSurface.container)) {
        Column(
            modifier = Modifier.fillMaxSize().padding(vertical = RipDpiThemeTokens.spacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.sm),
        ) {
            BrandBadge()
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
            contentDescription = "RIPDPI",
            tint = colors.background,
            modifier = Modifier.size(24.dp),
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
    Column(
        modifier =
            Modifier
                .clip(RoundedCornerShape(RipDpiThemeTokens.spacing.md))
                .background(container)
                .ripDpiSelectable(
                    selected = selected,
                    enabled = true,
                    role = Role.Tab,
                    hapticFeedback = RipDpiHapticFeedback.Selection,
                    onClick = onClick,
                ).padding(horizontal = RipDpiThemeTokens.spacing.md, vertical = RipDpiThemeTokens.spacing.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
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
            text = stringResource(destination.titleRes),
            style = RipDpiThemeTokens.type.navLabel.copy(color = content),
        )
    }
}
