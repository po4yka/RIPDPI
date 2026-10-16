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
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.ui.components.ripDpiSelectable
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIconSizes
import com.poyka.ripdpi.ui.theme.RipDpiStroke
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

private const val CompactBottomNavFontScale = 1.5f
private val AccessibilityBottomBarExtraHeight = 16.dp

private enum class BottomNavItemLayout { Vertical, Horizontal, Label }

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
    val labels = destinations.map { stringResource(it.titleRes) }
    val labelMeasurer = rememberTextMeasurer()
    val labelStyle = RipDpiThemeTokens.type.navLabel
    val labelWidths =
        labels.map {
            labelMeasurer.measure(AnnotatedString(it), style = labelStyle, softWrap = false).size.width
        }
    val density = LocalDensity.current
    val labelLineHeight = with(density) { labelStyle.lineHeight.toDp() }
    val bottomBarHeight =
        maxOf(
            layout.bottomBarHeight + if (useCompactLabels) AccessibilityBottomBarExtraHeight else 0.dp,
            labelLineHeight + components.navigation.bottomNavIndicatorHeight + RipDpiThemeTokens.spacing.md,
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
                        .widthIn(
                            max = layout.contentMaxWidth + layout.horizontalPadding + layout.horizontalPadding,
                        ).fillMaxWidth()
                        .ripDpiTestTag(RipDpiTestTags.BottomNavBar)
                        .padding(horizontal = components.navigation.bottomNavHorizontalPadding),
            ) {
                val density = LocalDensity.current
                // Large fonts and long locale labels need more width than four slots can give.
                // Keep all destinations visible; do not shrink type or split a word to fit.
                val labelMinWidth =
                    with(density) { labelWidths.maxOrNull()?.toDp() ?: 0.dp } +
                        RipDpiThemeTokens.spacing.xs * 2
                val columns = bottomNavColumnsForWidth(labelMinWidth, maxWidth, destinations.size)
                val horizontalItems =
                    columns == 1 &&
                        labelMinWidth + components.navigation.bottomNavIndicatorWidth <= maxWidth
                if (columns < destinations.size) {
                    Column {
                        destinations.chunked(columns).forEach { row ->
                            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                                row.forEach { destination ->
                                    val label = labels[destinations.indexOf(destination)]
                                    BottomNavItem(
                                        destination = destination,
                                        label = label,
                                        selected = destination == selectedRoute,
                                        onClick = { onNavigate(destination) },
                                        modifier = Modifier.weight(1f).heightIn(min = components.buttons.minHeight),
                                        localIndicator = true,
                                        layout =
                                            if (horizontalItems) {
                                                BottomNavItemLayout.Horizontal
                                            } else {
                                                BottomNavItemLayout.Label
                                            },
                                    )
                                }
                            }
                        }
                    }
                } else {
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
                        modifier = Modifier.fillMaxWidth().height(bottomBarHeight),
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
                                BottomNavItem(
                                    destination = destination,
                                    label = labels[destinations.indexOf(destination)],
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
}

@Composable
private fun BottomNavItem(
    destination: Route,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    localIndicator: Boolean = false,
    layout: BottomNavItemLayout = BottomNavItemLayout.Vertical,
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
    val selectionScale by animateFloatAsState(
        targetValue = if (selected && layout != BottomNavItemLayout.Label) motion.selectionScale else 1f,
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

    val itemModifier =
        modifier
            .fillMaxHeight()
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
                contentDescription = label
            }

    BottomNavItemContent(label, labelColor, selected, layout, itemModifier) {
        BottomNavItemIcon(destination, iconTint, selected, localIndicator)
    }
}

@Composable
private fun BottomNavItemContent(
    label: String,
    labelColor: Color,
    selected: Boolean,
    layout: BottomNavItemLayout,
    modifier: Modifier = Modifier,
    iconContent: @Composable () -> Unit,
) {
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type
    val labelContent: @Composable () -> Unit = {
        Text(
            text = label,
            style = type.navLabel,
            color = labelColor,
            textAlign = TextAlign.Center,
            maxLines = if (layout == BottomNavItemLayout.Label) Int.MAX_VALUE else 1,
            softWrap = layout == BottomNavItemLayout.Label,
            overflow = TextOverflow.Clip,
            modifier =
                if (layout == BottomNavItemLayout.Label) {
                    Modifier.fillMaxWidth().padding(horizontal = spacing.xs, vertical = spacing.sm)
                } else {
                    Modifier.padding(horizontal = spacing.xs).width(IntrinsicSize.Max)
                },
        )
    }
    if (layout == BottomNavItemLayout.Label) {
        Box(modifier, contentAlignment = Alignment.Center) {
            if (selected) {
                Box(
                    Modifier
                        .matchParentSize()
                        .ripDpiTestTag(RipDpiTestTags.BottomNavIndicator)
                        .background(
                            RipDpiThemeTokens.surfaces
                                .resolve(
                                    RipDpiThemeTokens.surfaceRoles.navigation.bottomBarIndicator,
                                ).container,
                            RipDpiThemeTokens.shapes.xxl,
                        ),
                )
            }
            labelContent()
        }
    } else if (layout == BottomNavItemLayout.Horizontal) {
        Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            iconContent()
            labelContent()
        }
    } else {
        Column(
            modifier,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            iconContent()
            labelContent()
        }
    }
}

private fun bottomNavColumnsForWidth(
    labelMinWidth: Dp,
    availableWidth: Dp,
    destinationCount: Int,
): Int =
    when {
        labelMinWidth <= availableWidth / destinationCount -> destinationCount
        labelMinWidth <= availableWidth / 2 -> 2
        else -> 1
    }

@Composable
private fun BottomNavItemIcon(
    destination: Route,
    tint: Color,
    selected: Boolean,
    localIndicator: Boolean,
) {
    val components = RipDpiThemeTokens.components
    Box(
        modifier =
            Modifier
                .size(
                    width = components.navigation.bottomNavIndicatorWidth,
                    height = components.navigation.bottomNavIndicatorHeight,
                ).then(
                    if (localIndicator && selected) {
                        Modifier.ripDpiTestTag(RipDpiTestTags.BottomNavIndicator).background(
                            RipDpiThemeTokens.surfaces
                                .resolve(
                                    RipDpiThemeTokens.surfaceRoles.navigation.bottomBarIndicator,
                                ).container,
                            RipDpiThemeTokens.shapes.xxl,
                        )
                    } else {
                        Modifier
                    },
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
