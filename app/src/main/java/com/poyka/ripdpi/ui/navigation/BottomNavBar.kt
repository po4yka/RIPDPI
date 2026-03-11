package com.poyka.ripdpi.ui.navigation

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.ui.components.ripDpiSelectable
import com.poyka.ripdpi.ui.theme.RipDpiIconSizes
import com.poyka.ripdpi.ui.theme.RipDpiStroke
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

@Composable
fun BottomNavBar(
    currentRoute: String?,
    onNavigate: (Route) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val components = RipDpiThemeTokens.components
    val layout = RipDpiThemeTokens.layout

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .background(colors.card)
                .navigationBarsPadding(),
    ) {
        HorizontalDivider(
            color = colors.border,
            thickness = RipDpiStroke.Hairline,
        )
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .widthIn(
                            max = layout.contentMaxWidth + layout.horizontalPadding + layout.horizontalPadding,
                        )
                        .height(layout.bottomBarHeight)
                        .padding(horizontal = components.chipVerticalPadding + components.switchThumbPadding),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Route.topLevel.forEach { destination ->
                    BottomNavItem(
                        destination = destination,
                        selected = currentRoute == destination.route,
                        onClick = { onNavigate(destination) },
                    )
                }
            }
        }
    }
}

@Composable
private fun RowScope.BottomNavItem(
    destination: Route,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = RipDpiThemeTokens.colors
    val components = RipDpiThemeTokens.components
    val motion = RipDpiThemeTokens.motion
    val type = RipDpiThemeTokens.type
    val indicatorColor by animateColorAsState(
        targetValue = if (selected) colors.inputBackground else Color.Transparent,
        animationSpec = tween(durationMillis = motion.duration(motion.stateDurationMillis)),
        label = "bottomNavIndicatorColor",
    )
    val iconTint by animateColorAsState(
        targetValue = if (selected) colors.foreground else colors.mutedForeground,
        animationSpec = tween(durationMillis = motion.duration(motion.stateDurationMillis)),
        label = "bottomNavIconTint",
    )
    val labelColor by animateColorAsState(
        targetValue = if (selected) colors.foreground else colors.mutedForeground,
        animationSpec = tween(durationMillis = motion.duration(motion.stateDurationMillis)),
        label = "bottomNavLabelColor",
    )
    val indicatorWidth by animateDpAsState(
        targetValue =
            if (selected) {
                components.bottomNavIndicatorWidth
            } else {
                components.bottomNavIndicatorHeight
            },
        animationSpec = tween(durationMillis = motion.duration(motion.quickDurationMillis)),
        label = "bottomNavIndicatorWidth",
    )
    val selectionScale by animateFloatAsState(
        targetValue = if (selected) motion.selectionScale else 1f,
        animationSpec = tween(
            durationMillis = motion.duration(motion.quickDurationMillis),
            easing = FastOutSlowInEasing,
        ),
        label = "bottomNavSelectionScale",
    )

    Column(
        modifier =
            Modifier
                .fillMaxHeight()
                .weight(1f)
                .padding(vertical = 3.dp)
                .graphicsLayer {
                    scaleX = selectionScale
                    scaleY = selectionScale
                }
                .ripDpiSelectable(
                    selected = selected,
                    role = Role.Tab,
                    onClick = onClick,
                ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier =
                Modifier
                    .size(width = indicatorWidth, height = components.bottomNavIndicatorHeight)
                    .background(
                        color = indicatorColor,
                        shape = RipDpiThemeTokens.shapes.xxl,
                    ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = requireNotNull(destination.icon),
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(RipDpiIconSizes.Default),
            )
        }
        Text(
            text =
                androidx.compose.ui.res.stringResource(destination.titleRes),
            style = type.navLabel,
            color = labelColor,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun BottomNavBarLightPreview() {
    RipDpiTheme(themePreference = "light") {
        BottomNavBar(
            currentRoute = Route.Home.route,
            onNavigate = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun BottomNavBarDarkPreview() {
    RipDpiTheme(themePreference = "dark") {
        BottomNavBar(
            currentRoute = Route.Settings.route,
            onNavigate = {},
        )
    }
}
