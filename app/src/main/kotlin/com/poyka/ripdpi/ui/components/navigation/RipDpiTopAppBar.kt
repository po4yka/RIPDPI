package com.poyka.ripdpi.ui.components.navigation

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.TwoRowsTopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.components.buttons.RipDpiIconButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiIconButtonStyle
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

private const val TopAppBarStackFontScale = 1.5f

// Material hides the expanded title semantics past this collapse fraction.
private const val CollapsedTitleFraction = 0.5f

/**
 * Screen top app bar on Material 3. The small bar grows to fit a wrapped title; at large font with
 * navigation or actions the title moves to the full-width expanded row of a two-row bar, so it
 * never shares a narrow slot with the controls. Wire [scrollBehavior] to the screen's scroll
 * container with `Modifier.nestedScroll(scrollBehavior.nestedScrollConnection)`.
 */
@Composable
fun RipDpiTopAppBar(
    title: String,
    modifier: Modifier = Modifier,
    navigationIcon: ImageVector? = null,
    onNavigationClick: (() -> Unit)? = null,
    navigationContentDescription: String? = null,
    navigationEnabled: Boolean = true,
    actions: (@Composable RowScope.() -> Unit)? = null,
    scrollBehavior: TopAppBarScrollBehavior? = null,
) {
    val hasNavigation = navigationIcon != null && onNavigationClick != null
    val stackTitle =
        LocalDensity.current.fontScale >= TopAppBarStackFontScale && (hasNavigation || actions != null)
    val titleContent: @Composable () -> Unit = { Text(text = title, modifier = Modifier.semantics { heading() }) }
    val navigationContent: @Composable () -> Unit = {
        if (navigationIcon != null && onNavigationClick != null) {
            RipDpiIconButton(
                icon = navigationIcon,
                contentDescription = navigationContentDescription ?: stringResource(R.string.navigation_back),
                onClick = onNavigationClick,
                style = RipDpiIconButtonStyle.Ghost,
                enabled = navigationEnabled,
            )
        }
    }
    val actionsContent: @Composable RowScope.() -> Unit = actions ?: {}
    val colors = ripDpiTopAppBarColors()

    if (stackTitle) {
        // The collapsed row shares width with the controls, so it shows the title only once the bar
        // has collapsed; otherwise its hidden copy would wrap there and inflate the expanded bar.
        val collapsed by remember(scrollBehavior) {
            derivedStateOf { (scrollBehavior?.state?.collapsedFraction ?: 0f) >= CollapsedTitleFraction }
        }
        TwoRowsTopAppBar(
            title = { expanded -> if (expanded || collapsed) titleContent() },
            modifier = modifier,
            navigationIcon = navigationContent,
            actions = actionsContent,
            colors = colors,
            scrollBehavior = scrollBehavior,
        )
    } else {
        TopAppBar(
            title = titleContent,
            modifier = modifier,
            navigationIcon = navigationContent,
            actions = actionsContent,
            colors = colors,
            scrollBehavior = scrollBehavior,
        )
    }
}

@Composable
private fun ripDpiTopAppBarColors(): TopAppBarColors {
    val colors = RipDpiThemeTokens.colors
    // Scrolled content raises the bar to the same surface as the navigation bar.
    val scrolled = RipDpiThemeTokens.surfaces.resolve(RipDpiThemeTokens.surfaceRoles.navigation.bottomBar).container
    return TopAppBarDefaults.topAppBarColors(
        containerColor = colors.background,
        scrolledContainerColor = scrolled,
        navigationIconContentColor = colors.foreground,
        titleContentColor = colors.foreground,
        actionIconContentColor = colors.foreground,
    )
}

@Preview(showBackground = true)
@Composable
private fun RipDpiTopAppBarPreview() {
    RipDpiComponentPreview {
        RipDpiTopAppBar(
            title = "Logs",
            navigationIcon = RipDpiIcons.Back,
            onNavigationClick = {},
            actions = {
                RipDpiIconButton(
                    icon = RipDpiIcons.Search,
                    contentDescription = "Search",
                    onClick = {},
                )
                RipDpiIconButton(
                    icon = RipDpiIcons.Overflow,
                    contentDescription = "More",
                    onClick = {},
                )
            },
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun RipDpiTopAppBarDarkPreview() {
    RipDpiComponentPreview(themePreference = "dark") {
        RipDpiTopAppBar(
            title = "Mode editor",
            navigationIcon = RipDpiIcons.Back,
            onNavigationClick = {},
        )
    }
}
