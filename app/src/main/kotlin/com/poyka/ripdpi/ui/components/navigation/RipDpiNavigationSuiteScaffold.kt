package com.poyka.ripdpi.ui.components.navigation

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.ShortNavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.WideNavigationRailDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteItem
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import com.poyka.ripdpi.ui.components.RipDpiHapticFeedback
import com.poyka.ripdpi.ui.components.rememberRipDpiHapticPerformer
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIconSizes
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import com.poyka.ripdpi.ui.theme.RipDpiWidthClass
import kotlinx.collections.immutable.ImmutableList

private const val IconOnlyNavigationBarFontScale = 1.5f

// The collapsed rail first breaks a word at 1.2x (pt-BR "Configurações"; ru and de at 1.25x).
internal const val ExpandedRailFontScale = 1.2f

@Immutable
data class RipDpiNavigationItem(
    val label: String,
    val icon: ImageVector,
    val selected: Boolean,
    val testTag: String,
    val onClick: () -> Unit,
)

/**
 * App-shell navigation: a bar on compact windows, a collapsed rail on medium and expanded windows,
 * and an expanded rail on large and extra-large windows or at large font scale. The form follows
 * [RipDpiThemeTokens.layout] so the shell and the layout tokens share one width class.
 */
@Composable
fun RipDpiNavigationSuiteScaffold(
    items: ImmutableList<RipDpiNavigationItem>,
    modifier: Modifier = Modifier,
    showNavigation: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colors = RipDpiThemeTokens.colors
    val surfaces = RipDpiThemeTokens.surfaces
    val roles = RipDpiThemeTokens.surfaceRoles.navigation
    val container = surfaces.resolve(roles.bottomBar).container
    val indicator = surfaces.resolve(roles.bottomBarIndicator).container
    val fontScale = LocalDensity.current.fontScale
    val performHaptic = rememberRipDpiHapticPerformer()
    val suiteType =
        if (showNavigation) {
            ripDpiNavigationSuiteType(RipDpiThemeTokens.layout.widthClass, fontScale)
        } else {
            NavigationSuiteType.None
        }
    // Large fonts cannot fit four bar labels in one row: the bar keeps icons and each icon keeps its label as its name.
    val iconOnly =
        suiteType == NavigationSuiteType.ShortNavigationBarCompact && fontScale >= IconOnlyNavigationBarFontScale
    val itemColors =
        ShortNavigationBarItemDefaults.colors(
            selectedIconColor = colors.foreground,
            selectedTextColorTopIconPosition = colors.foreground,
            selectedTextColorStartIconPosition = colors.foreground,
            selectedIndicatorColor = indicator,
            unselectedIconColor = colors.mutedForeground,
            unselectedTextColor = colors.mutedForeground,
        )
    NavigationSuiteScaffold(
        navigationItems = {
            items.forEach { item ->
                NavigationSuiteItem(
                    selected = item.selected,
                    onClick = {
                        performHaptic(RipDpiHapticFeedback.Selection)
                        item.onClick()
                    },
                    icon = {
                        Icon(
                            imageVector = item.icon,
                            contentDescription = if (iconOnly) item.label else null,
                            modifier = Modifier.size(RipDpiIconSizes.Default),
                        )
                    },
                    label =
                        if (iconOnly) {
                            null
                        } else {
                            {
                                Text(
                                    text = item.label,
                                    style = RipDpiThemeTokens.type.navLabel,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        },
                    modifier = Modifier.ripDpiTestTag(item.testTag),
                    navigationSuiteType = suiteType,
                    colors = itemColors,
                )
            }
        },
        modifier = modifier,
        navigationSuiteType = suiteType,
        navigationSuiteColors =
            NavigationSuiteDefaults.colors(
                shortNavigationBarContainerColor = container,
                shortNavigationBarContentColor = colors.foreground,
                wideNavigationRailColors =
                    WideNavigationRailDefaults.colors(
                        containerColor = container,
                        contentColor = colors.foreground,
                    ),
            ),
        containerColor = colors.background,
        contentColor = colors.foreground,
        content = content,
    )
}

internal fun ripDpiNavigationSuiteType(
    widthClass: RipDpiWidthClass,
    fontScale: Float,
): NavigationSuiteType =
    when (widthClass) {
        RipDpiWidthClass.Compact -> {
            NavigationSuiteType.ShortNavigationBarCompact
        }

        RipDpiWidthClass.Medium, RipDpiWidthClass.Expanded -> {
            if (fontScale >= ExpandedRailFontScale) {
                NavigationSuiteType.WideNavigationRailExpanded
            } else {
                NavigationSuiteType.WideNavigationRailCollapsed
            }
        }

        RipDpiWidthClass.Large, RipDpiWidthClass.ExtraLarge -> {
            NavigationSuiteType.WideNavigationRailExpanded
        }
    }
