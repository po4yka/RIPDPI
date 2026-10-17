@file:OptIn(androidx.compose.foundation.style.ExperimentalFoundationStyleApi::class)

package com.poyka.ripdpi.ui.theme

import androidx.compose.foundation.style.CommonStyle
import androidx.compose.foundation.style.animate
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color

internal val RipDpiComponentStyles.navigationBar: CommonStyle
    get() = NavigationBarStyle

internal val RipDpiComponentStyles.navigationIndicator: CommonStyle
    get() = NavigationIndicatorStyle

internal val RipDpiComponentStyles.navigationRailBrand: CommonStyle
    get() = NavigationRailBrandStyle

internal val RipDpiComponentStyles.navigationRailItem: CommonStyle
    get() = NavigationRailItemStyle

private val NavigationBarStyle =
    CommonStyle {
        rdsBackground(
            LocalRipDpiSurfaceTokens.currentValue
                .resolve(
                    DefaultRipDpiSurfaceRoleMappings.navigation.bottomBar,
                ).container,
        )
    }

private val NavigationIndicatorStyle =
    CommonStyle {
        rdsBackground(
            LocalRipDpiSurfaceTokens.currentValue
                .resolve(DefaultRipDpiSurfaceRoleMappings.navigation.bottomBarIndicator)
                .container,
        )
    }

private val NavigationRailBrandStyle = CommonStyle { rdsBackground(LocalRipDpiExtendedColors.currentValue.foreground) }

private val NavigationRailItemStyle =
    CommonStyle {
        val colors = LocalRipDpiExtendedColors.currentValue
        rdsBackground(if (state.isSelected) colors.accent else Color.Transparent)
    }

private val BottomNavItemStyle =
    CommonStyle {
        val colors = LocalRipDpiExtendedColors.currentValue
        val motion = LocalRipDpiMotion.currentValue
        animate(motion.quickTween<Float>()) {
            rdsBorder(bottomNavigationFocusBorderColor(state.isFocused, colors.outline), RipDpiStroke.Thick)
        }
        rdsContentScale(if (state.isSelected) motion.selectionScale else 1f)
        rdsContentAlpha(bottomNavigationContentAlpha(state.isPressed))
    }

/** Keep the existing typed item tweens and read their frame values only in attached resolution. */
@Composable
internal fun rememberBottomNavigationItemStyle(
    selectionScale: State<Float>,
    contentAlpha: State<Float>,
): CommonStyle =
    remember(selectionScale, contentAlpha) {
        CommonStyle {
            with(BottomNavItemStyle) { applyStyle() }
            rdsContentScale(selectionScale.value)
            rdsContentAlpha(contentAlpha.value)
        }
    }

internal fun bottomNavigationFocusBorderColor(
    isFocused: Boolean,
    outlineColor: Color,
): Color = if (isFocused) outlineColor else Color.Transparent

internal fun bottomNavigationContentAlpha(isPressed: Boolean): Float = if (isPressed) 0.6f else 1f
