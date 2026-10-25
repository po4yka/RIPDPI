@file:OptIn(androidx.compose.foundation.style.ExperimentalFoundationStyleApi::class)

package com.poyka.ripdpi.ui.navigation

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.style.CommonStyle
import androidx.compose.foundation.style.rememberUpdatedStyleState
import androidx.compose.foundation.style.styleResolver
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.NonRestartableComposable
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
import com.poyka.ripdpi.ui.components.ripDpiSelectable
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIconSizes
import com.poyka.ripdpi.ui.theme.RipDpiStroke
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import com.poyka.ripdpi.ui.theme.bottomNavigationContentAlpha
import com.poyka.ripdpi.ui.theme.bottomNavigationFocusBorderColor
import com.poyka.ripdpi.ui.theme.navigationBar
import com.poyka.ripdpi.ui.theme.navigationIndicator
import com.poyka.ripdpi.ui.theme.rememberBottomNavigationItemStyle
import com.poyka.ripdpi.ui.theme.rememberRipDpiStyleResolver
import com.poyka.ripdpi.ui.theme.ripDpiScreenSurface
import com.poyka.ripdpi.ui.theme.ripDpiStyleAppearance
import com.poyka.ripdpi.ui.theme.ripDpiStyleContent
import com.poyka.ripdpi.ui.theme.ripDpiStyleSurface

private const val IconOnlyBottomNavFontScale = 1.5f

@Composable
@NonRestartableComposable
fun BottomNavBar(
    selectedRoute: Route?,
    onNavigate: (Route) -> Unit,
    modifier: Modifier = Modifier,
) {
    BottomNavBar(selectedRoute, onNavigate, modifier, visualStyle = CommonStyle)
}

@Suppress("LongMethod")
@Composable
fun BottomNavBar(
    selectedRoute: Route?,
    onNavigate: (Route) -> Unit,
    modifier: Modifier = Modifier,
    visualStyle: CommonStyle = CommonStyle,
) {
    val components = RipDpiThemeTokens.components
    val layout = RipDpiThemeTokens.layout
    val motion = RipDpiThemeTokens.motion
    val bottomBarSurface =
        RipDpiThemeTokens.surfaces.resolve(RipDpiThemeTokens.surfaceRoles.navigation.bottomBar)
    val barStyleState = rememberUpdatedStyleState(null)
    val barStyleResolver =
        rememberRipDpiStyleResolver(RipDpiThemeTokens.styles.navigationBar, visualStyle, barStyleState)
    val destinations = Route.topLevel
    val selectedIndex = destinations.indexOfFirst { it == selectedRoute }.takeIf { it >= 0 }
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    // Large fonts cannot fit four labels in one row. Keep one row of icons and drop the visible
    // labels; each tab keeps its label as the content description.
    val iconOnly = density.fontScale >= IconOnlyBottomNavFontScale
    val labels = destinations.map { stringResource(it.titleRes) }
    val labelStyle = RipDpiThemeTokens.type.navLabel
    val labelLineHeight = with(density) { labelStyle.lineHeight.toDp() }
    val indicatorHeight = components.navigation.bottomNavIndicatorHeight
    val bottomBarHeight =
        if (iconOnly) {
            layout.bottomBarHeight
        } else {
            maxOf(layout.bottomBarHeight, labelLineHeight + indicatorHeight + RipDpiThemeTokens.spacing.md)
        }

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .ripDpiStyleSurface(barStyleResolver)
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
                        .ripDpiTestTag(RipDpiTestTags.BottomNavBar)
                        .padding(horizontal = components.navigation.bottomNavHorizontalPadding),
            ) {
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
                // Without a label the icon is centred in the bar, so the pill follows it there.
                val indicatorTopOffset =
                    if (iconOnly) {
                        (bottomBarHeight - indicatorHeight) / 2
                    } else {
                        components.navigation.bottomNavIndicatorTopOffset
                    }
                val indicatorTopOffsetPx = with(density) { indicatorTopOffset.toPx() }

                Box(
                    modifier = Modifier.fillMaxWidth().height(bottomBarHeight),
                ) {
                    Box(
                        modifier =
                            Modifier
                                .size(
                                    width = components.navigation.bottomNavIndicatorWidth,
                                    height = indicatorHeight,
                                ).graphicsLayer {
                                    translationX =
                                        indicatorOffsetPx *
                                        if (layoutDirection == LayoutDirection.Ltr) 1f else -1f
                                    translationY = indicatorTopOffsetPx
                                    alpha = indicatorAlpha
                                    scaleX = indicatorScaleX
                                }.ripDpiTestTag(RipDpiTestTags.BottomNavIndicator)
                                .ripDpiScreenSurface(
                                    RipDpiThemeTokens.styles.navigationIndicator,
                                    shape = RipDpiThemeTokens.shapes.xxl,
                                ),
                    )
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        destinations.forEachIndexed { index, destination ->
                            BottomNavItem(
                                destination = destination,
                                label = labels[index],
                                showLabel = !iconOnly,
                                modifier = Modifier.weight(1f),
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
private fun BottomNavItem(
    destination: Route,
    label: String,
    showLabel: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val motion = RipDpiThemeTokens.motion
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
    val selectionScale =
        animateFloatAsState(
            targetValue = if (selected) motion.selectionScale else 1f,
            animationSpec = motion.quickTween(easing = FastOutSlowInEasing),
            label = "bottomNavSelectionScale",
        )
    val interactionSource = remember { MutableInteractionSource() }
    val styleState = rememberUpdatedStyleState(interactionSource) { it.isSelected = selected }
    val contentAlpha =
        animateFloatAsState(
            targetValue = bottomNavigationContentAlpha(styleState.isPressed),
            animationSpec = motion.quickTween(),
            label = "bottomNavContentAlpha",
        )

    val itemStyle = rememberBottomNavigationItemStyle(selectionScale, contentAlpha)
    val itemResolver = rememberRipDpiStyleResolver(itemStyle, CommonStyle, styleState)
    val itemModifier =
        modifier
            .fillMaxHeight()
            .styleResolver(itemResolver)
            .ripDpiStyleAppearance(itemResolver, shape = RipDpiThemeTokens.shapes.md)
            .ripDpiTestTag(RipDpiTestTags.bottomNav(destination))
            .ripDpiStyleContent(itemResolver)
            .ripDpiSelectable(
                selected = selected,
                role = Role.Tab,
                interactionSource = interactionSource,
                showIndication = false,
                onClick = onClick,
            ).clearAndSetSemantics {
                contentDescription = label
            }

    Column(
        itemModifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        BottomNavItemIcon(destination, iconTint)
        if (showLabel) {
            Text(
                text = label,
                style = RipDpiThemeTokens.type.navLabel,
                color = labelColor,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(horizontal = RipDpiThemeTokens.spacing.xs),
            )
        }
    }
}

@Composable
private fun BottomNavItemIcon(
    destination: Route,
    tint: Color,
) {
    val components = RipDpiThemeTokens.components
    Box(
        modifier =
            Modifier.size(
                width = components.navigation.bottomNavIndicatorWidth,
                height = components.navigation.bottomNavIndicatorHeight,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = requireNotNull(destination.icon),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(RipDpiIconSizes.Default),
        )
    }
}

internal fun bottomNavFocusBorderColor(
    isFocused: Boolean,
    outlineColor: Color,
): Color = bottomNavigationFocusBorderColor(isFocused, outlineColor)

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
