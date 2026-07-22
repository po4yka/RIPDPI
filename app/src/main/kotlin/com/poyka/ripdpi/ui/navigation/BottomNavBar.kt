package com.poyka.ripdpi.ui.navigation

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.ui.components.ripDpiSelectable
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIconSizes
import com.poyka.ripdpi.ui.theme.RipDpiStroke
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

@Suppress("LongMethod")
@Composable
fun BottomNavBar(
    selectedRoute: Route?,
    onNavigate: (Route) -> Unit,
    modifier: Modifier = Modifier,
) {
    val components = RipDpiThemeTokens.components
    val layout = RipDpiThemeTokens.layout
    val motion = RipDpiThemeTokens.motion
    val bottomBarSurface =
        RipDpiThemeTokens.surfaces.resolve(RipDpiThemeTokens.surfaceRoles.navigation.bottomBar)
    val indicatorSurface =
        RipDpiThemeTokens.surfaces.resolve(RipDpiThemeTokens.surfaceRoles.navigation.bottomBarIndicator)
    val destinations = Route.topLevel
    val selectedIndex = destinations.indexOfFirst { it == selectedRoute }.takeIf { it >= 0 }
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val labelLineHeight =
        with(density) {
            RipDpiThemeTokens.type.navLabel.lineHeight
                .toDp()
        }
    val labelLineCount = if (density.fontScale > 1f) BottomNavMaximumLabelLines else 1
    val bottomBarHeight =
        maxOf(
            layout.bottomBarHeight,
            components.navigation.bottomNavIndicatorTopOffset * 2 +
                components.navigation.bottomNavIndicatorHeight +
                labelLineHeight * labelLineCount,
        )

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .background(bottomBarSurface.container)
                .navigationBarsPadding(),
    ) {
        HorizontalDivider(
            color = bottomBarSurface.border,
            thickness = RipDpiStroke.Hairline,
        )
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            BoxWithConstraints(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .widthIn(
                            max = layout.contentMaxWidth + layout.horizontalPadding + layout.horizontalPadding,
                        ).height(bottomBarHeight)
                        .ripDpiTestTag(RipDpiTestTags.BottomNavBar)
                        .padding(horizontal = components.navigation.bottomNavHorizontalPadding),
            ) {
                val slotWidth = maxWidth / destinations.size.coerceAtLeast(1)
                val indicatorStartOffset =
                    selectedIndex?.let { index ->
                        slotWidth * index +
                            ((slotWidth - components.navigation.bottomNavIndicatorWidth) / 2)
                    } ?: 0.dp
                val indicatorOffsetPxTarget =
                    with(density) {
                        indicatorStartOffset.toPx() *
                            if (layoutDirection == LayoutDirection.Ltr) 1f else -1f
                    }
                val indicatorOffsetPx by animateFloatAsState(
                    targetValue = indicatorOffsetPxTarget,
                    animationSpec = motion.emphasizedTween(easing = FastOutSlowInEasing),
                    label = "bottomNavIndicatorOffset",
                )
                val indicatorAlpha by animateFloatAsState(
                    targetValue = if (selectedIndex != null) 1f else 0f,
                    animationSpec = motion.stateTween(),
                    label = "bottomNavIndicatorAlpha",
                )
                val indicatorScaleX by animateFloatAsState(
                    targetValue = if (selectedIndex != null) 1f else 0.88f,
                    animationSpec = motion.quickTween(),
                    label = "bottomNavIndicatorScaleX",
                )
                val indicatorTopOffsetPx =
                    with(density) {
                        components.navigation.bottomNavIndicatorTopOffset.toPx()
                    }
                Box(
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Box(
                        modifier =
                            Modifier
                                .size(
                                    width = components.navigation.bottomNavIndicatorWidth,
                                    height = components.navigation.bottomNavIndicatorHeight,
                                ).graphicsLayer {
                                    translationX = indicatorOffsetPx
                                    translationY = indicatorTopOffsetPx
                                    alpha = indicatorAlpha
                                    scaleX = indicatorScaleX
                                }.ripDpiTestTag(RipDpiTestTags.BottomNavIndicator)
                                .background(
                                    color = indicatorSurface.container,
                                    shape = RipDpiThemeTokens.shapes.xxl,
                                ),
                    )
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        destinations.forEach { destination ->
                            BottomNavItem(
                                destination = destination,
                                selected = destination == selectedRoute,
                                onClick = { onNavigate(destination) },
                            )
                        }
                    }
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
    val iconTint by animateColorAsState(
        targetValue = if (selected) colors.foreground else colors.mutedForeground,
        animationSpec = motion.stateTween(),
        label = "bottomNavIconTint",
    )
    val labelColor by animateColorAsState(
        targetValue = if (selected) colors.foreground else colors.mutedForeground,
        animationSpec = motion.stateTween(),
        label = "bottomNavLabelColor",
    )
    val selectionScale by animateFloatAsState(
        targetValue = if (selected) motion.selectionScale else 1f,
        animationSpec = motion.quickTween(easing = FastOutSlowInEasing),
        label = "bottomNavSelectionScale",
    )
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val contentAlpha by animateFloatAsState(
        targetValue = if (isPressed) 0.6f else 1f,
        animationSpec = motion.quickTween(),
        label = "bottomNavContentAlpha",
    )

    Column(
        modifier =
            Modifier
                .fillMaxHeight()
                .weight(1f)
                .ripDpiTestTag(RipDpiTestTags.bottomNav(destination))
                .graphicsLayer {
                    scaleX = selectionScale
                    scaleY = selectionScale
                    alpha = contentAlpha
                }.ripDpiSelectable(
                    selected = selected,
                    role = Role.Tab,
                    interactionSource = interactionSource,
                    showIndication = false,
                    onClick = onClick,
                ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier =
                Modifier
                    .size(
                        width = components.navigation.bottomNavIndicatorWidth,
                        height = components.navigation.bottomNavIndicatorHeight,
                    ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = requireNotNull(destination.icon),
                contentDescription = stringResource(destination.titleRes),
                tint = iconTint,
                modifier = Modifier.size(RipDpiIconSizes.Default),
            )
        }
        Text(
            text = stringResource(destination.titleRes),
            style = type.navLabel,
            color = labelColor,
            maxLines = BottomNavMaximumLabelLines,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

private const val BottomNavMaximumLabelLines = 2

@Preview(showBackground = true)
@Composable
private fun BottomNavBarLightPreview() {
    RipDpiTheme(themePreference = "light") {
        BottomNavBar(
            selectedRoute = Route.Home,
            onNavigate = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun BottomNavBarDarkPreview() {
    RipDpiTheme(themePreference = "dark") {
        BottomNavBar(
            selectedRoute = Route.Settings,
            onNavigate = {},
        )
    }
}
