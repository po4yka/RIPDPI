package com.poyka.ripdpi.ui.navigation

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.components.ripDpiSelectable
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIconSizes
import com.poyka.ripdpi.ui.theme.RipDpiStroke
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

private const val CompactBottomNavFontScale = 1.5f
private const val MaximumBottomNavFontScale = 1.8f
private val AccessibilityBottomBarExtraHeight = 16.dp

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
    val fontScale = LocalDensity.current.fontScale
    val layoutDirection = LocalLayoutDirection.current
    val useCompactLabels = fontScale >= CompactBottomNavFontScale
    val useMaximumLabels = fontScale >= MaximumBottomNavFontScale
    val bottomBarHeight =
        layout.bottomBarHeight + if (useCompactLabels) AccessibilityBottomBarExtraHeight else 0.dp

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
                        .widthIn(
                            max = layout.contentMaxWidth + layout.horizontalPadding + layout.horizontalPadding,
                        ).fillMaxWidth()
                        .height(bottomBarHeight)
                        .ripDpiTestTag(RipDpiTestTags.BottomNavBar)
                        .padding(horizontal = components.navigation.bottomNavHorizontalPadding),
            ) {
                val density = LocalDensity.current
                val slotWidth = maxWidth / destinations.size.coerceAtLeast(1)
                val indicatorOffsetPxTarget =
                    selectedIndex?.let { index ->
                        with(density) {
                            (
                                slotWidth * index +
                                    ((slotWidth - components.navigation.bottomNavIndicatorWidth) / 2)
                            ).toPx()
                        }
                    } ?: 0f
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
                                    translationX =
                                        indicatorOffsetPx *
                                        if (layoutDirection == LayoutDirection.Ltr) 1f else -1f
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
                            val fullLabel = stringResource(destination.titleRes)
                            BottomNavItem(
                                destination = destination,
                                label =
                                    stringResource(
                                        if (useCompactLabels) {
                                            if (useMaximumLabels) {
                                                destination.maximumBottomNavTitleRes()
                                            } else {
                                                destination.compactBottomNavTitleRes()
                                            }
                                        } else {
                                            destination.titleRes
                                        },
                                    ),
                                accessibilityLabel = fullLabel,
                                compact = useCompactLabels,
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
    label: String,
    accessibilityLabel: String,
    compact: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = RipDpiThemeTokens.colors
    val components = RipDpiThemeTokens.components
    val motion = RipDpiThemeTokens.motion
    val spacing = RipDpiThemeTokens.spacing
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
    val focusBorderColor = rememberBottomNavFocusBorderColor(interactionSource)
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
                .border(RipDpiStroke.Thick, focusBorderColor, RipDpiThemeTokens.shapes.md)
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
                ).clearAndSetSemantics {
                    contentDescription = accessibilityLabel
                },
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
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(RipDpiIconSizes.Default),
            )
        }
        Text(
            text = label,
            style = if (compact) type.caption else type.navLabel,
            color = labelColor,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Clip,
            modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.xs),
        )
    }
}

@Composable
private fun rememberBottomNavFocusBorderColor(interactionSource: MutableInteractionSource): Color {
    val isFocused by interactionSource.collectIsFocusedAsState()
    val focusBorderColor by animateColorAsState(
        targetValue = bottomNavFocusBorderColor(isFocused, RipDpiThemeTokens.colors.outline),
        animationSpec = RipDpiThemeTokens.motion.quickTween(),
        label = "bottomNavFocusBorder",
    )
    return focusBorderColor
}

internal fun bottomNavFocusBorderColor(
    isFocused: Boolean,
    outlineColor: Color,
): Color = if (isFocused) outlineColor else Color.Transparent

private fun Route.compactBottomNavTitleRes(): Int =
    when (this) {
        Route.Home -> R.string.bottom_nav_home_compact
        Route.Config -> R.string.bottom_nav_config_compact
        Route.Settings -> R.string.bottom_nav_settings_compact
        is Route.Diagnostics -> R.string.bottom_nav_diagnostics_compact
        else -> titleRes
    }

private fun Route.maximumBottomNavTitleRes(): Int =
    when (this) {
        Route.Home -> R.string.bottom_nav_home_maximum
        Route.Config -> R.string.bottom_nav_config_maximum
        Route.Settings -> R.string.bottom_nav_settings_maximum
        is Route.Diagnostics -> R.string.bottom_nav_diagnostics_maximum
        else -> titleRes
    }

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
