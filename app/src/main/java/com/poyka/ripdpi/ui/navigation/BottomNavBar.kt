package com.poyka.ripdpi.ui.navigation

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.mergeDescendants
import androidx.compose.ui.semantics.semantics
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
    val motion = RipDpiThemeTokens.motion
    val destinations = Route.topLevel
    val selectedIndex = destinations.indexOfFirst { currentRoute == it.route }.takeIf { it >= 0 }

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
            BoxWithConstraints(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .widthIn(
                            max = layout.contentMaxWidth + layout.horizontalPadding + layout.horizontalPadding,
                        ).height(layout.bottomBarHeight)
                        .padding(horizontal = components.bottomNavHorizontalPadding),
            ) {
                val density = LocalDensity.current
                val slotWidth = maxWidth / destinations.size.coerceAtLeast(1)
                val indicatorOffsetPxTarget =
                    selectedIndex?.let { index ->
                        with(density) {
                            (slotWidth * index + ((slotWidth - components.bottomNavIndicatorWidth) / 2)).toPx()
                        }
                    } ?: 0f
                val indicatorOffsetPx by animateFloatAsState(
                    targetValue = indicatorOffsetPxTarget,
                    animationSpec =
                        tween(
                            durationMillis = motion.duration(motion.emphasizedDurationMillis),
                            easing = FastOutSlowInEasing,
                        ),
                    label = "bottomNavIndicatorOffset",
                )
                val indicatorAlpha by animateFloatAsState(
                    targetValue = if (selectedIndex != null) 1f else 0f,
                    animationSpec = tween(durationMillis = motion.duration(motion.stateDurationMillis)),
                    label = "bottomNavIndicatorAlpha",
                )
                val indicatorScaleX by animateFloatAsState(
                    targetValue = if (selectedIndex != null) 1f else 0.88f,
                    animationSpec = tween(durationMillis = motion.duration(motion.quickDurationMillis)),
                    label = "bottomNavIndicatorScaleX",
                )
                val indicatorTopOffsetPx =
                    with(density) {
                        components.bottomNavIndicatorTopOffset.toPx()
                    }

                Box(
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Box(
                        modifier =
                            Modifier
                                .size(
                                    width = components.bottomNavIndicatorWidth,
                                    height = components.bottomNavIndicatorHeight,
                                ).graphicsLayer {
                                    translationX = indicatorOffsetPx
                                    translationY = indicatorTopOffsetPx
                                    alpha = indicatorAlpha
                                    scaleX = indicatorScaleX
                                }.background(
                                    color = colors.inputBackground,
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
                                selected = currentRoute == destination.route,
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
        animationSpec = tween(durationMillis = motion.duration(motion.stateDurationMillis)),
        label = "bottomNavIconTint",
    )
    val labelColor by animateColorAsState(
        targetValue = if (selected) colors.foreground else colors.mutedForeground,
        animationSpec = tween(durationMillis = motion.duration(motion.stateDurationMillis)),
        label = "bottomNavLabelColor",
    )
    val selectionScale by animateFloatAsState(
        targetValue = if (selected) motion.selectionScale else 1f,
        animationSpec =
            tween(
                durationMillis = motion.duration(motion.quickDurationMillis),
                easing = FastOutSlowInEasing,
            ),
        label = "bottomNavSelectionScale",
    )
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val contentAlpha by animateFloatAsState(
        targetValue = if (isPressed) 0.6f else 1f,
        animationSpec = tween(durationMillis = motion.duration(motion.quickDurationMillis)),
        label = "bottomNavContentAlpha",
    )

    Column(
        modifier =
            Modifier
                .fillMaxHeight()
                .weight(1f)
                .semantics(mergeDescendants = true) {}
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
                        width = components.bottomNavIndicatorWidth,
                        height = components.bottomNavIndicatorHeight,
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
